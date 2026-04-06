#pragma once
#include "nsz_types.h"
#include <stdio.h>

#define NCZ_MAGIC_SECTION  "NCZSECTN"
#define NCZ_MAGIC_BLOCK    "NCZBLOCK"

/* ── Section: 64 bytes, matches Python Header.Section ─────────────────────── */
typedef struct {
    int64_t  offset;          /* offset in the decompressed NCA */
    int64_t  size;            /* size of this section */
    int64_t  crypto_type;     /* 0=none, 1=plaintext(fake), 3=AES-CTR, 4=BKTR-CTR */
    int64_t  _padding;
    uint8_t  crypto_key[16];
    uint8_t  crypto_counter[16];
} NczSection;  /* 64 bytes */

/* ── Block header ─────────────────────────────────────────────────────────── */
typedef struct {
    uint8_t   magic[8];          /* "NCZBLOCK" */
    uint8_t   version;
    uint8_t   type;
    uint8_t   unused;
    uint8_t   block_size_exp;    /* block_size = 1 << block_size_exp */
    uint32_t  num_blocks;
    int64_t   decompressed_size;
    uint32_t *compressed_sizes;  /* malloc'd, num_blocks entries */
    uint64_t *block_offsets;     /* malloc'd, cumulative file offsets */
} NczBlockHeader;

/* ── Full parsed NCZ header ───────────────────────────────────────────────── */
typedef struct {
    NczSection    *sections;          /* malloc'd, +1 for potential FakeSection */
    int            section_count;      /* includes FakeSection if inserted */
    int            original_section_count; /* without FakeSection */
    int            has_block_compression;
    int            has_fake_section;   /* 1 if FakeSection was inserted at [0] */
    NczBlockHeader block_header;
    int64_t        decompressed_size;  /* total decompressed NCA body size */
} NczHeader;

/* fp must be positioned at NCZ_HEADER_OFFSET (0x4000) before calling.
   Parses sections, detects block compression, inserts FakeSection if needed.
   Matches Python NszDecompressor.py:136-149. */
int  ncz_parse_header(FILE *fp, NczHeader *out);
void ncz_free_header(NczHeader *h);
const char *ncz_last_error(void);
