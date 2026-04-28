#include "hfs0.h"
#include "nsz_debug.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static char s_err[512];

static uint32_t hfs0_align_0x20(uint32_t n)
{
    uint32_t rem = n % 0x20u;
    return rem == 0 ? 0x20u : (0x20u - rem);
}

const char *hfs0_last_error(void) { return s_err; }

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

    Hfs0Header hdr;
    if (fread(&hdr, sizeof(hdr), 1, fp) != 1) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: failed to read header");
        DBG("hfs0_parse: ERROR — %s", s_err);
        fclose(fp);
        return -2;
    }

    DBG("hfs0_parse: magic=0x%08X (expected 0x%08X), file_count=%u, str_table_size=%u",
        hdr.magic, HFS0_MAGIC, hdr.file_count, hdr.string_table_size);

    if (hdr.magic != HFS0_MAGIC) {
        snprintf(s_err, sizeof(s_err),
                 "hfs0_parse: bad magic 0x%08X (expected 0x%08X)",
                 hdr.magic, HFS0_MAGIC);
        fclose(fp);
        return -3;
    }

    if (hdr.file_count == 0 || hdr.file_count > HFS0_MAX_FILES) {
        snprintf(s_err, sizeof(s_err),
                 "hfs0_parse: file_count %u out of range", hdr.file_count);
        fclose(fp);
        return -4;
    }

    /* HFS0 file entries are 64 bytes (vs PFS0's 24 bytes) */
    Hfs0FileEntry *entries = calloc(hdr.file_count, sizeof(Hfs0FileEntry));
    if (!entries) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: OOM entries");
        fclose(fp);
        return -5;
    }
    if (fread(entries, sizeof(Hfs0FileEntry), hdr.file_count, fp)
            != hdr.file_count) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: failed to read entries");
        free(entries);
        fclose(fp);
        return -6;
    }

    char *strtab = calloc(1, hdr.string_table_size + 1);
    if (!strtab) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: OOM string table");
        free(entries);
        fclose(fp);
        return -7;
    }
    if (fread(strtab, 1, hdr.string_table_size, fp) != hdr.string_table_size) {
        snprintf(s_err, sizeof(s_err), "hfs0_parse: failed to read string table");
        free(strtab);
        free(entries);
        fclose(fp);
        return -8;
    }

    /* HFS0 data area offset: 16-byte header + (file_count * 64) + string_table_size */
    uint64_t data_area = 0x10
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
    fclose(fp);
    return 0;
}

int hfs0_write_header(FILE *out_fp, Hfs0Container *container,
                      const uint64_t *new_file_sizes)
{
    int fc = container->file_count;

    char strtab[HFS0_MAX_FILES * 256];
    uint32_t str_offsets[HFS0_MAX_FILES];
    uint32_t strtab_size_non_padded = 0;

    /* Build string table and rename .ncz → .nca */
    for (int i = 0; i < fc; i++) {
        str_offsets[i] = strtab_size_non_padded;
        char name[256];
        strncpy(name, container->files[i].name, sizeof(name) - 1);
        name[sizeof(name) - 1] = '\0';
        size_t nlen = strlen(name);
        if (nlen >= 4 && strcmp(name + nlen - 4, ".ncz") == 0) {
            name[nlen - 1] = 'a'; /* .ncz -> .nca */
        }
        size_t slen = strlen(name) + 1;
        memcpy(strtab + strtab_size_non_padded, name, slen);
        strtab_size_non_padded += (uint32_t)slen;
    }

    /* Calculate header size: 16 + (file_count * 64) + string_table */
    uint32_t header_size_non_padded = 0x10u
                                    + (uint32_t)fc * (uint32_t)sizeof(Hfs0FileEntry)
                                    + strtab_size_non_padded;
    uint32_t strtab_padding = hfs0_align_0x20(header_size_non_padded);
    uint32_t strtab_size = strtab_size_non_padded + strtab_padding;

    memset(strtab + strtab_size_non_padded, 0, strtab_padding);

    /* Write HFS0 header (16 bytes) */
    Hfs0Header hdr;
    hdr.magic             = HFS0_MAGIC;
    hdr.file_count        = (uint32_t)fc;
    hdr.string_table_size = strtab_size;
    hdr._pad              = 0;

    if (fwrite(&hdr, sizeof(hdr), 1, out_fp) != 1) {
        snprintf(s_err, sizeof(s_err), "hfs0_write_header: write hdr failed");
        return -1;
    }

    /* Write HFS0 file entries (64 bytes each)
     * NOTE: hashed_region_size and sha256_hash are set to zero
     * (matching Python nsz reference implementation behavior)
     */
    uint64_t cur_offset = 0;
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

    /* Write string table (with padding) */
    if (fwrite(strtab, 1, strtab_size, out_fp) != strtab_size) {
        snprintf(s_err, sizeof(s_err), "hfs0_write_header: write strtab failed");
        return -3;
    }

    return 0;
}
