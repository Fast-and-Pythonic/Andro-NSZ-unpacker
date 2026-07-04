#pragma once
/*
 * CNMT-based verification support.
 *
 * The CNMT (Content Meta) record inside a META NCA (*.cnmt.nca) stores the
 * full 32-byte SHA-256 of every content NCA in the package. Extracting those
 * hashes from the INPUT container before conversion lets the engine verify
 * each decompressed NCA against its authoritative hash instead of the
 * filename content-id (which is only the first half of the hash).
 *
 * Reference: nsz FileExistingChecks.ExtractHashes + Fs/Nca.py + Fs/Cnmt.py.
 */
#include <stdint.h>
#include <stdio.h>
#include "pfs0.h"
#include "hfs0.h"

/* Result / reason codes */
#define CNMT_OK          0
#define CNMT_DISABLED  (-1)   /* verification switched off                    */
#define CNMT_NO_KEYS   (-2)   /* header_key or needed key_area_key missing    */
#define CNMT_NO_META   (-3)   /* no *.cnmt.nca found in the container         */
#define CNMT_PARSE_ERR (-4)   /* bad magic / unsupported crypto / corrupt     */

/*
 * Global verification config. Written once (from JNI or the host CLI) before
 * conversions start; read-only while a batch is running, so it is safe under
 * concurrent per-file conversions. Defaults: enabled, no keys — which makes
 * the engine fall back to the legacy filename check.
 *
 * header_key_32: 32 bytes (XTS key1 || key2), or NULL to clear.
 * kak_records:   kak_count records of 17 bytes each: [generation u8][key 16B]
 *                (key_area_key_application_XX), or NULL to clear.
 */
void nca_verify_config_set(int enabled,
                           const uint8_t *header_key_32,
                           const uint8_t *kak_records,
                           int kak_count);

int nca_verify_enabled(void);

/* 1 when header_key and at least one key_area_key are loaded. */
int nca_cnmt_keys_available(void);

/* Set of expected full NCA SHA-256 hashes collected from CNMT records. */
typedef struct {
   uint8_t (*hashes)[32];
   int      count;
} CnmtHashSet;

/*
 * Scan a parsed container for META NCAs, decrypt them and collect all CNMT
 * content-entry hashes into [out] (union across multiple META NCAs).
 * [in_fp] position is clobbered. Returns CNMT_OK when at least one hash was
 * collected, else a reason code (caller falls back to the filename check).
 */
int cnmt_extract_hashes_pfs0(FILE *in_fp, const Pfs0Container *c, CnmtHashSet *out);
int cnmt_extract_hashes_hfs0(FILE *in_fp, const Hfs0Container *c, CnmtHashSet *out);

int  cnmt_hashset_contains(const CnmtHashSet *set, const uint8_t digest[32]);
void cnmt_hashset_free(CnmtHashSet *set);

/* Short human-readable reason for a CNMT_* code (for status messages). */
const char *cnmt_reason(int code);
