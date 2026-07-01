#include "hfs0.h"
#include "nsz_debug.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static __thread char s_err[512];  /* per-thread: safe under parallel conversions */

const char *hfs0_last_error(void) { return s_err; }

/* Core parser: reads an HFS0 partition starting at [base] inside the open stream
 * [fp]. File offsets in [out] are stored absolute (base + header + entry offset). */
static int hfs0_parse_stream(FILE *fp, uint64_t base, Hfs0Container *out)
{
    if (fseeko(fp, (off_t)base, SEEK_SET) != 0) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: seek to 0x%llX failed",
                 (unsigned long long)base);
        return -1;
    }

    Hfs0Header hdr;
    if (fread(&hdr, sizeof(hdr), 1, fp) != 1) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: failed to read header");
        DBG("hfs0_parse: ERROR — %s", s_err);
        return -2;
    }

    DBG("hfs0_parse: base=0x%llX magic=0x%08X (expected 0x%08X), file_count=%u, str_table_size=%u",
        (unsigned long long)base, hdr.magic, HFS0_MAGIC, hdr.file_count, hdr.string_table_size);

    if (hdr.magic != HFS0_MAGIC) {
        snprintf(s_err, sizeof(s_err),
                 "hfs0_parse: bad magic 0x%08X (expected 0x%08X) at 0x%llX",
                 hdr.magic, HFS0_MAGIC, (unsigned long long)base);
        return -3;
    }

    if (hdr.file_count == 0 || hdr.file_count > HFS0_MAX_FILES) {
        snprintf(s_err, sizeof(s_err),
                 "hfs0_parse: file_count %u out of range", hdr.file_count);
        return -4;
    }

    /* HFS0 file entries are 64 bytes (vs PFS0's 24 bytes) */
    Hfs0FileEntry *entries = calloc(hdr.file_count, sizeof(Hfs0FileEntry));
    if (!entries) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: OOM entries");
        return -5;
    }
    if (fread(entries, sizeof(Hfs0FileEntry), hdr.file_count, fp)
            != hdr.file_count) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: failed to read entries");
        free(entries);
        return -6;
    }

    char *strtab = calloc(1, hdr.string_table_size + 1);
    if (!strtab) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: OOM string table");
        free(entries);
        return -7;
    }
    if (fread(strtab, 1, hdr.string_table_size, fp) != hdr.string_table_size) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: failed to read string table");
        free(strtab);
        free(entries);
        return -8;
    }

    /* HFS0 data area: 16-byte header + (file_count * 64) + string_table_size,
     * relative to the partition start [base]. */
    uint64_t data_area = base
                       + 0x10
                       + (uint64_t)hdr.file_count * sizeof(Hfs0FileEntry)
                       + hdr.string_table_size;

    out->file_count       = (int)hdr.file_count;
    out->data_area_offset = data_area;

    for (uint32_t i = 0; i < hdr.file_count; i++) {
        const char *name = strtab + entries[i].string_offset;
        strncpy(out->files[i].name, name, sizeof(out->files[i].name) - 1);
        out->files[i].name[sizeof(out->files[i].name) - 1] = '\0';
        out->files[i].data_offset = data_area + entries[i].offset;
        out->files[i].size        = entries[i].size;

        size_t nlen = strlen(out->files[i].name);
        out->files[i].is_ncz = (nlen >= 4 &&
            strcmp(out->files[i].name + nlen - 4, ".ncz") == 0) ? 1 : 0;

        DBG("hfs0_parse:   [%u] '%s'  data_offset=0x%llX  size=%llu  is_ncz=%d",
            i, out->files[i].name,
            (unsigned long long)out->files[i].data_offset,
            (unsigned long long)out->files[i].size,
            out->files[i].is_ncz);
    }

    DBG("hfs0_parse: OK — %d files, data_area_offset=0x%llX",
        out->file_count, (unsigned long long)out->data_area_offset);

    free(strtab);
    free(entries);
    return 0;
}

int hfs0_parse(const char *input_path, Hfs0Container *out)
{
    DBG("hfs0_parse: opening '%s'", input_path);

    FILE *fp = fopen(input_path, "rb");
    if (!fp) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: cannot open '%s': %s",
                 input_path, strerror(errno));
        DBG("hfs0_parse: ERROR — %s", s_err);
        return -1;
    }
    setvbuf(fp, NULL, _IOFBF, 4 * 1024 * 1024);

    int rc = hfs0_parse_stream(fp, 0, out);
    fclose(fp);
    return rc;
}

int hfs0_parse_at(FILE *fp, uint64_t hfs0_abs_offset, Hfs0Container *out)
{
    return hfs0_parse_stream(fp, hfs0_abs_offset, out);
}

uint64_t hfs0_computed_header_size(const Hfs0Container *container)
{
    /* Each partition reserves a fixed 0x8000-byte header region (matches nsz's
     * Hfs0Stream). The actual header is smaller; the remainder is a zero gap.
     * file_count is unused here but kept in the signature for clarity/callers. */
    (void)container;
    return (uint64_t)HFS0_PARTITION_HEADER;
}

int hfs0_write_header(FILE *out_fp, Hfs0Container *container,
                      const uint64_t *new_file_sizes)
{
    int fc = container->file_count;

    char strtab[HFS0_MAX_FILES * 256];
    uint32_t str_offsets[HFS0_MAX_FILES];
    uint32_t strtab_size = 0;   /* raw, NOT padded to 0x20 (matches nsz Hfs0Stream) */

    /* Build string table and rename .ncz → .nca */
    for (int i = 0; i < fc; i++) {
        str_offsets[i] = strtab_size;
        char name[256];
        strncpy(name, container->files[i].name, sizeof(name) - 1);
        name[sizeof(name) - 1] = '\0';
        size_t nlen = strlen(name);
        if (nlen >= 4 && strcmp(name + nlen - 4, ".ncz") == 0) {
            name[nlen - 1] = 'a'; /* .ncz -> .nca */
        }
        size_t slen = strlen(name) + 1;
        memcpy(strtab + strtab_size, name, slen);
        strtab_size += (uint32_t)slen;
    }

    /* Actual header size with the raw (unpadded) string table. File data is
     * aligned to HFS0_PARTITION_HEADER (0x8000) via a leading gap encoded in the
     * entry offsets — exactly like nsz's Hfs0Stream (headerSize = 0x8000). */
    uint32_t header_size = 0x10u
                         + (uint32_t)fc * (uint32_t)sizeof(Hfs0FileEntry)
                         + strtab_size;
    if (header_size > HFS0_PARTITION_HEADER) {
        snprintf(s_err, sizeof(s_err),
                 "hfs0_write_header: header 0x%X exceeds 0x%X (too many files)",
                 header_size, HFS0_PARTITION_HEADER);
        return -1;
    }
    uint64_t data_gap = (uint64_t)HFS0_PARTITION_HEADER - header_size;

    /* Write HFS0 header (16 bytes). string_table_size is the RAW length. */
    Hfs0Header hdr;
    hdr.magic             = HFS0_MAGIC;
    hdr.file_count        = (uint32_t)fc;
    hdr.string_table_size = strtab_size;
    hdr._pad              = 0;

    if (fwrite(&hdr, sizeof(hdr), 1, out_fp) != 1) {
        snprintf(s_err, sizeof(s_err), "hfs0_write_header: write hdr failed");
        return -1;
    }

    /* Write HFS0 file entries (64 bytes each). Entry offsets are relative to the
     * data area (end of raw header) and start after the alignment gap, so the
     * first file lands at absolute 0x8000.
     * NOTE: hashed_region_size and sha256_hash stay zero (nsz reference behavior).
     */
    uint64_t cur_offset = data_gap;
    for (int i = 0; i < fc; i++) {
        Hfs0FileEntry e;
        memset(&e, 0, sizeof(e));  /* Zero entire structure first */
        e.offset             = cur_offset;
        e.size               = new_file_sizes[i];
        e.string_offset      = str_offsets[i];
        e.hashed_region_size = 0;  /* Always 0 (nsz reference behavior) */
        e._pad1              = 0;
        /* e.sha256_hash already zeroed by memset */

        if (fwrite(&e, sizeof(e), 1, out_fp) != 1) {
            snprintf(s_err, sizeof(s_err),
                     "hfs0_write_header: write entry %d failed", i);
            return -2;
        }
        cur_offset += new_file_sizes[i];
    }

    /* Write the raw string table (no padding) */
    if (fwrite(strtab, 1, strtab_size, out_fp) != strtab_size) {
        snprintf(s_err, sizeof(s_err), "hfs0_write_header: write strtab failed");
        return -3;
    }

    /* Pad with zeros up to 0x8000 so the first file is aligned */
    if (data_gap > 0) {
        uint8_t zero_buf[4096];
        memset(zero_buf, 0, sizeof(zero_buf));
        uint64_t remaining = data_gap;
        while (remaining > 0) {
            uint32_t chunk = remaining < sizeof(zero_buf)
                           ? (uint32_t)remaining : (uint32_t)sizeof(zero_buf);
            if (fwrite(zero_buf, 1, chunk, out_fp) != chunk) {
                snprintf(s_err, sizeof(s_err), "hfs0_write_header: write gap failed");
                return -4;
            }
            remaining -= chunk;
        }
    }

    return 0;
}
