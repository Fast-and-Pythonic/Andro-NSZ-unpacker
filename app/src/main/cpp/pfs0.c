#include "pfs0.h"
#include "nsz_debug.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>

static __thread char s_err[512];  /* per-thread: safe under parallel conversions */

/* Open input, supporting the "fd:N" pseudo-path (dup + fdopen of an already
 * open descriptor) used for SAF/FUSE sources. Mirrors ncz_engine.c. */
static FILE *pfs0_open_input(const char *path)
{
    if (strncmp(path, "fd:", 3) == 0) {
        int fd = atoi(path + 3);
        int dupfd = dup(fd);
        if (dupfd < 0) return NULL;
        FILE *fp = fdopen(dupfd, "rb");
        if (!fp) { close(dupfd); return NULL; }
        return fp;
    }
    return fopen(path, "rb");
}

static uint32_t pfs0_align_0x10(uint32_t n)
{
    uint32_t rem = n % 0x10u;
    return rem == 0 ? 0 : (0x10u - rem);
}

const char *pfs0_last_error(void) { return s_err; }

int pfs0_parse(const char *input_path, Pfs0Container *out)
{
    DBG("pfs0_parse: opening '%s'", input_path);

    FILE *fp = pfs0_open_input(input_path);
    if (!fp) {
        snprintf(s_err, sizeof(s_err), "pfs0_parse: cannot open '%s': %s",
                 input_path, strerror(errno));
        DBG("pfs0_parse: ERROR — %s", s_err);
        return -1;
    }
    setvbuf(fp, NULL, _IOFBF, 4 * 1024 * 1024);

    Pfs0Header hdr;
    if (fread(&hdr, sizeof(hdr), 1, fp) != 1) {
        snprintf(s_err, sizeof(s_err), "pfs0_parse: failed to read header");
        DBG("pfs0_parse: ERROR — %s", s_err);
        fclose(fp);
        return -2;
    }

    DBG("pfs0_parse: magic=0x%08X (expected 0x%08X), file_count=%u, str_table_size=%u",
        hdr.magic, PFS0_MAGIC, hdr.file_count, hdr.string_table_size);

    if (hdr.magic != PFS0_MAGIC) {
        snprintf(s_err, sizeof(s_err),
                 "pfs0_parse: bad magic 0x%08X (expected 0x%08X)",
                 hdr.magic, PFS0_MAGIC);
        fclose(fp);
        return -3;
    }

    if (hdr.file_count == 0 || hdr.file_count > PFS0_MAX_FILES) {
        snprintf(s_err, sizeof(s_err),
                 "pfs0_parse: file_count %u out of range", hdr.file_count);
        fclose(fp);
        return -4;
    }

    Pfs0FileEntry *entries = calloc(hdr.file_count, sizeof(Pfs0FileEntry));
    if (!entries) {
        snprintf(s_err, sizeof(s_err), "pfs0_parse: OOM entries");
        fclose(fp);
        return -5;
    }
    if (fread(entries, sizeof(Pfs0FileEntry), hdr.file_count, fp)
            != hdr.file_count) {
        snprintf(s_err, sizeof(s_err), "pfs0_parse: failed to read entries");
        free(entries);
        fclose(fp);
        return -6;
    }

    char *strtab = calloc(1, hdr.string_table_size + 1);
    if (!strtab) {
        snprintf(s_err, sizeof(s_err), "pfs0_parse: OOM string table");
        free(entries);
        fclose(fp);
        return -7;
    }
    if (fread(strtab, 1, hdr.string_table_size, fp) != hdr.string_table_size) {
        snprintf(s_err, sizeof(s_err), "pfs0_parse: failed to read string table");
        free(strtab);
        free(entries);
        fclose(fp);
        return -8;
    }

    uint64_t data_area = 0x10
                       + (uint64_t)hdr.file_count * sizeof(Pfs0FileEntry)
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

        DBG("pfs0_parse:   [%u] '%s'  data_offset=0x%llX  size=%llu  is_ncz=%d",
            i, out->files[i].name,
            (unsigned long long)out->files[i].data_offset,
            (unsigned long long)out->files[i].size,
            out->files[i].is_ncz);
    }

    DBG("pfs0_parse: OK — %d files, data_area_offset=0x%llX",
        out->file_count, (unsigned long long)out->data_area_offset);

    free(strtab);
    free(entries);
    fclose(fp);
    return 0;
}

int pfs0_write_header(FILE *out_fp, Pfs0Container *container,
                      const uint64_t *new_file_sizes)
{
    int fc = container->file_count;

    char strtab[PFS0_MAX_FILES * 256];
    uint32_t str_offsets[PFS0_MAX_FILES];
    uint32_t strtab_size_non_padded = 0;

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

    uint32_t header_size_non_padded = 0x10u
                                    + (uint32_t)fc * (uint32_t)sizeof(Pfs0FileEntry)
                                    + strtab_size_non_padded;
    uint32_t strtab_padding = pfs0_align_0x10(header_size_non_padded);
    uint32_t strtab_size = strtab_size_non_padded + strtab_padding;

    memset(strtab + strtab_size_non_padded, 0, strtab_padding);

    /* Reproduce the source container's leading gap so the rebuilt NSP matches
       byte-for-byte. The input preserves the first file's offset relative to the
       data area (entries[0].offset); the .ncz->.nca rename keeps name lengths, so
       header and data-area sizes are identical and the gap transfers directly.
       Mirrors nsz's default (non-fixPadding) behavior in NszDecompressor.py. */
    uint64_t data_gap = (fc > 0)
        ? container->files[0].data_offset - container->data_area_offset
        : 0;

    Pfs0Header hdr;
    hdr.magic             = PFS0_MAGIC;
    hdr.file_count        = (uint32_t)fc;
    hdr.string_table_size = strtab_size;
    hdr._pad              = 0;

    if (fwrite(&hdr, sizeof(hdr), 1, out_fp) != 1) {
        snprintf(s_err, sizeof(s_err), "pfs0_write_header: write hdr failed");
        return -1;
    }

    uint64_t cur_offset = data_gap;  /* entry offsets skip the alignment gap */
    for (int i = 0; i < fc; i++) {
        Pfs0FileEntry e;
        e.offset        = cur_offset;
        e.size          = new_file_sizes[i];
        e.string_offset = str_offsets[i];
        e._pad          = 0;
        if (fwrite(&e, sizeof(e), 1, out_fp) != 1) {
            snprintf(s_err, sizeof(s_err),
                     "pfs0_write_header: write entry %d failed", i);
            return -2;
        }
        cur_offset += new_file_sizes[i];
    }

    if (fwrite(strtab, 1, strtab_size, out_fp) != strtab_size) {
        snprintf(s_err, sizeof(s_err), "pfs0_write_header: write strtab failed");
        return -3;
    }

    /* Write the leading gap zeros so the first file lands at its original offset */
    if (data_gap > 0) {
        uint8_t zero_buf[4096];
        memset(zero_buf, 0, sizeof(zero_buf));
        uint64_t remaining = data_gap;
        while (remaining > 0) {
            uint32_t chunk = remaining < sizeof(zero_buf)
                           ? (uint32_t)remaining : (uint32_t)sizeof(zero_buf);
            if (fwrite(zero_buf, 1, chunk, out_fp) != chunk) {
                snprintf(s_err, sizeof(s_err), "pfs0_write_header: write gap failed");
                return -4;
            }
            remaining -= chunk;
        }
    }

    return 0;
}
