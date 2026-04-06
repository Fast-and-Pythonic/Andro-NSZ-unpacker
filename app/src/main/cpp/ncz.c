/*
 * NCZ header parser.
 * Matches Python nsz/Header.py + NszDecompressor.py:136-149.
 * Key fix vs NSZExpress: inserts FakeSection when sections[0].offset > 0x4000.
 */
#include "ncz.h"
#include "nsz_debug.h"
#include <stdlib.h>
#include <string.h>

static char s_err[512];

const char *ncz_last_error(void) { return s_err; }

int ncz_parse_header(FILE *fp, NczHeader *out)
{
    memset(out, 0, sizeof(*out));

    long fp_start = (long)ftello(fp);
    DBG("ncz_parse_header: start fp_pos=0x%lX", fp_start);

    /* Read and verify magic "NCZSECTN" */
    uint8_t magic[8];
    if (fread(magic, 1, 8, fp) != 8) {
        snprintf(s_err, sizeof(s_err), "ncz_parse_header: cannot read magic");
        return -1;
    }
    if (memcmp(magic, NCZ_MAGIC_SECTION, 8) != 0) {
        snprintf(s_err, sizeof(s_err),
                 "ncz_parse_header: bad magic at 0x%llX (got %02X %02X %02X %02X %02X %02X %02X %02X)",
                 (unsigned long long)fp_start,
                 magic[0], magic[1], magic[2], magic[3],
                 magic[4], magic[5], magic[6], magic[7]);
        DBGHEX("  bad magic (got)", magic, 8);
        return -2;
    }

    /* Read section count */
    int64_t section_count;
    if (fread(&section_count, sizeof(int64_t), 1, fp) != 1) {
        snprintf(s_err, sizeof(s_err), "ncz_parse_header: cannot read section_count");
        return -3;
    }
    DBG("ncz_parse_header: section_count=%lld", (long long)section_count);

    if (section_count < 0 || section_count > 1000000) {
        snprintf(s_err, sizeof(s_err),
                 "ncz_parse_header: section_count %lld out of range",
                 (long long)section_count);
        return -4;
    }

    out->original_section_count = (int)section_count;
    out->sections = calloc((size_t)section_count + 1u, sizeof(NczSection));
    if (!out->sections) {
        snprintf(s_err, sizeof(s_err), "ncz_parse_header: OOM sections");
        return -4;
    }

    /* Read sections (64 bytes each) */
    /* Leave room at [0] for potential FakeSection — read into [0..n-1] first */
    for (int i = 0; i < (int)section_count; i++) {
        NczSection *s = &out->sections[i];
        if (fread(s, sizeof(NczSection), 1, fp) != 1) {
            snprintf(s_err, sizeof(s_err),
                     "ncz_parse_header: failed to read section %d", i);
            ncz_free_header(out);
            return -5;
        }
        DBG("ncz_parse_header:   section[%d] offset=0x%llX size=0x%llX crypto_type=%lld",
            i, (unsigned long long)s->offset,
            (unsigned long long)s->size,
            (long long)s->crypto_type);
        DBGHEX("    crypto_key",     s->crypto_key,     16);
        DBGHEX("    crypto_counter", s->crypto_counter, 16);
    }

    out->section_count = (int)section_count;

    /*
     * FakeSection insertion — matches Python NszDecompressor.py:140-142:
     *   if sections[0].offset - UNCOMPRESSABLE_HEADER_SIZE > 0:
     *       fakeSection = FakeSection(UNCOMPRESSABLE_HEADER_SIZE,
     *                                 sections[0].offset - UNCOMPRESSABLE_HEADER_SIZE)
     *       sections.insert(0, fakeSection)
     *
     * FakeSection has cryptoType=1 (plaintext, no encryption applied).
     */
    if (out->section_count > 0 &&
        out->sections[0].offset > (int64_t)NCA_HEADER_SIZE) {

        int64_t gap = out->sections[0].offset - (int64_t)NCA_HEADER_SIZE;
        DBG("ncz_parse_header: inserting FakeSection — gap=%lld bytes at 0x%X",
            (long long)gap, NCA_HEADER_SIZE);

        /* Shift all sections right by 1 */
        memmove(&out->sections[1], &out->sections[0],
                (size_t)out->section_count * sizeof(NczSection));

        /* Insert FakeSection at [0] */
        memset(&out->sections[0], 0, sizeof(NczSection));
        out->sections[0].offset      = (int64_t)NCA_HEADER_SIZE;
        out->sections[0].size        = gap;
        out->sections[0].crypto_type = 1;  /* plaintext */

        out->section_count++;
        out->has_fake_section = 1;
    }

    /* Compute total decompressed body size (sum of original sections) */
    int64_t nca_body_size = 0;
    for (int i = 0; i < out->original_section_count; i++) {
        /* Use original sections (skip fake if present) */
        int idx = out->has_fake_section ? (i + 1) : i;
        nca_body_size += out->sections[idx].size;
    }
    out->decompressed_size = nca_body_size;

    /* Peek for NCZBLOCK magic */
    uint8_t peek[8];
    size_t n = fread(peek, 1, 8, fp);
    DBG("ncz_parse_header: peek after sections (%zu bytes read)", n);

    if (n == 8 && memcmp(peek, NCZ_MAGIC_BLOCK, 8) == 0) {
        DBG("ncz_parse_header: NCZBLOCK detected — block compression");
        out->has_block_compression = 1;
        NczBlockHeader *bh = &out->block_header;
        memcpy(bh->magic, peek, 8);

        struct {
            uint8_t  version;
            uint8_t  type;
            uint8_t  unused;
            uint8_t  block_size_exp;
            uint32_t num_blocks;
            int64_t  decompressed_size;
        } bh_fixed;
        if (fread(&bh_fixed, sizeof(bh_fixed), 1, fp) != 1) {
            snprintf(s_err, sizeof(s_err), "ncz_parse_header: failed to read NCZBLOCK");
            ncz_free_header(out);
            return -5;
        }
        bh->version           = bh_fixed.version;
        bh->type              = bh_fixed.type;
        bh->unused            = bh_fixed.unused;
        bh->block_size_exp    = bh_fixed.block_size_exp;
        bh->num_blocks        = bh_fixed.num_blocks;
        bh->decompressed_size = bh_fixed.decompressed_size;

        /* Override decompressed_size from block header (more authoritative) */
        out->decompressed_size = bh_fixed.decompressed_size;

        DBG("ncz_parse_header: NCZBLOCK  version=%u  type=%u  "
            "block_size_exp=%u (block=%u B)  num_blocks=%u  decompressed=%lld B",
            bh->version, bh->type,
            bh->block_size_exp, 1u << bh->block_size_exp,
            bh->num_blocks,
            (long long)bh->decompressed_size);

        if (bh->block_size_exp < 14 || bh->block_size_exp > 32) {
            snprintf(s_err, sizeof(s_err),
                     "ncz_parse_header: block_size_exp %u out of range [14..32]",
                     bh->block_size_exp);
            ncz_free_header(out);
            return -6;
        }

        if (bh->num_blocks == 0) {
            snprintf(s_err, sizeof(s_err), "ncz_parse_header: num_blocks == 0");
            ncz_free_header(out);
            return -6;
        }

        bh->compressed_sizes = malloc(bh->num_blocks * sizeof(uint32_t));
        bh->block_offsets    = malloc(bh->num_blocks * sizeof(uint64_t));
        if (!bh->compressed_sizes || !bh->block_offsets) {
            free(bh->compressed_sizes);
            free(bh->block_offsets);
            bh->compressed_sizes = NULL;
            bh->block_offsets = NULL;
            snprintf(s_err, sizeof(s_err), "ncz_parse_header: OOM block arrays");
            ncz_free_header(out);
            return -7;
        }
        if (fread(bh->compressed_sizes, sizeof(uint32_t), bh->num_blocks, fp)
                != bh->num_blocks) {
            snprintf(s_err, sizeof(s_err),
                     "ncz_parse_header: failed to read compressed_sizes");
            ncz_free_header(out);
            return -8;
        }

        /* Compute cumulative block offsets */
        uint64_t base = (uint64_t)ftello(fp);
        uint64_t cur  = base;
        for (uint32_t i = 0; i < bh->num_blocks; i++) {
            bh->block_offsets[i] = cur;
            cur += bh->compressed_sizes[i];
        }

        uint32_t log_n = bh->num_blocks < 4 ? bh->num_blocks : 4;
        for (uint32_t i = 0; i < log_n; i++) {
            DBG("ncz_parse_header:   block[%u] comp_size=%u  file_offset=0x%llX",
                i, bh->compressed_sizes[i],
                (unsigned long long)bh->block_offsets[i]);
        }
        if (bh->num_blocks > 4)
            DBG("ncz_parse_header:   ... (%u blocks total)", bh->num_blocks);
    } else {
        /* Solid mode — seek back */
        DBG("ncz_parse_header: no NCZBLOCK — solid compression");
        out->has_block_compression = 0;
        if (n > 0) {
            fseeko(fp, -(long)n, SEEK_CUR);
        }
    }

    DBG("ncz_parse_header: OK  sections=%d (fake=%d)  decompressed=%lld B",
        out->section_count, out->has_fake_section,
        (long long)out->decompressed_size);
    return 0;
}

void ncz_free_header(NczHeader *h)
{
    if (h->has_block_compression) {
        free(h->block_header.compressed_sizes);
        free(h->block_header.block_offsets);
        h->block_header.compressed_sizes = NULL;
        h->block_header.block_offsets    = NULL;
    }
    free(h->sections);
    h->sections = NULL;
    h->section_count = 0;
    h->original_section_count = 0;
    h->has_fake_section = 0;
    h->has_block_compression = 0;
}
