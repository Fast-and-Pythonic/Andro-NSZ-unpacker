/*
 * CNMT-based verification: extract expected NCA hashes from the META NCA.
 *
 * Flow (mirrors nsz Fs/Nca.py + Fs/Cnmt.py), for each *.cnmt.nca in the input
 * container:
 *   1. AES-128-XTS decrypt the first 0xC00 bytes of the NCA with header_key.
 *   2. Read section table, key area and FS headers from the decrypted header.
 *   3. AES-128-ECB decrypt the key area with key_area_key_application_XX
 *      (XX = key generation); the AES-CTR key is key-area entry index 2.
 *   4. AES-128-CTR decrypt the PFS0 section holding the CNMT.
 *   5. Parse the inner PFS0 and the CNMT to collect content-entry hashes.
 *
 * We read key_area_key_application_XX directly from prod.keys (as dumped by
 * Lockpick-style tools) — it is already the final key-area KEK, so no
 * master-key derivation is needed, unlike the reference's generateKek chain.
 *
 * This module owns its own one-block AES-128 ECB decrypt (the primitives in
 * aes_xts.c are static and must not be modified — see CLAUDE.md).
 */
#include "nca_cnmt.h"
#include "aes_xts.h"
#include "aes_ctr.h"
#include "sha256.h"
#include "nsz_debug.h"

#include <stdlib.h>
#include <string.h>

/* ── Global verification config ─────────────────────────────────────────────
 * Written once before a batch starts, read-only during conversion. */
#define MAX_KAK 32

static struct {
   int     enabled;
   int     have_header_key;
   uint8_t header_key[32];
   int     kak_count;
   uint8_t kak_gen[MAX_KAK];
   uint8_t kak_key[MAX_KAK][16];
} g_cfg = { 1, 0, {0}, 0, {0}, {{0}} };

void nca_verify_config_set(int enabled,
                           const uint8_t *header_key_32,
                           const uint8_t *kak_records,
                           int kak_count)
{
   g_cfg.enabled = enabled;

   if (header_key_32) {
      memcpy(g_cfg.header_key, header_key_32, 32);
      g_cfg.have_header_key = 1;
   } else {
      g_cfg.have_header_key = 0;
   }

   g_cfg.kak_count = 0;
   if (kak_records && kak_count > 0) {
      if (kak_count > MAX_KAK) kak_count = MAX_KAK;
      for (int i = 0; i < kak_count; i++) {
         const uint8_t *rec = kak_records + (size_t)i * 17;
         g_cfg.kak_gen[i] = rec[0];
         memcpy(g_cfg.kak_key[i], rec + 1, 16);
      }
      g_cfg.kak_count = kak_count;
   }
}

int nca_verify_enabled(void) { return g_cfg.enabled; }

int nca_cnmt_keys_available(void)
{
   return g_cfg.have_header_key && g_cfg.kak_count > 0;
}

static const uint8_t *find_kak(uint8_t generation)
{
   for (int i = 0; i < g_cfg.kak_count; i++) {
      if (g_cfg.kak_gen[i] == generation) return g_cfg.kak_key[i];
   }
   return NULL;
}

const char *cnmt_reason(int code)
{
   switch (code) {
      case CNMT_OK:        return "ok";
      case CNMT_DISABLED:  return "disabled";
      case CNMT_NO_KEYS:   return "keys missing";
      case CNMT_NO_META:   return "no CNMT NCA";
      case CNMT_PARSE_ERR: return "parse error";
      default:             return "unknown";
   }
}

/* ── AES-128 ECB single-block decrypt (self-contained) ──────────────────────
 * FIPS-197. Only what is needed to unwrap the 0x40-byte key area. */

static const uint8_t ISBOX[256] = {
   0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb,
   0x7c,0xe3,0x39,0x82,0x9b,0x2f,0xff,0x87,0x34,0x8e,0x43,0x44,0xc4,0xde,0xe9,0xcb,
   0x54,0x7b,0x94,0x32,0xa6,0xc2,0x23,0x3d,0xee,0x4c,0x95,0x0b,0x42,0xfa,0xc3,0x4e,
   0x08,0x2e,0xa1,0x66,0x28,0xd9,0x24,0xb2,0x76,0x5b,0xa2,0x49,0x6d,0x8b,0xd1,0x25,
   0x72,0xf8,0xf6,0x64,0x86,0x68,0x98,0x16,0xd4,0xa4,0x5c,0xcc,0x5d,0x65,0xb6,0x92,
   0x6c,0x70,0x48,0x50,0xfd,0xed,0xb9,0xda,0x5e,0x15,0x46,0x57,0xa7,0x8d,0x9d,0x84,
   0x90,0xd8,0xab,0x00,0x8c,0xbc,0xd3,0x0a,0xf7,0xe4,0x58,0x05,0xb8,0xb3,0x45,0x06,
   0xd0,0x2c,0x1e,0x8f,0xca,0x3f,0x0f,0x02,0xc1,0xaf,0xbd,0x03,0x01,0x13,0x8a,0x6b,
   0x3a,0x91,0x11,0x41,0x4f,0x67,0xdc,0xea,0x97,0xf2,0xcf,0xce,0xf0,0xb4,0xe6,0x73,
   0x96,0xac,0x74,0x22,0xe7,0xad,0x35,0x85,0xe2,0xf9,0x37,0xe8,0x1c,0x75,0xdf,0x6e,
   0x47,0xf1,0x1a,0x71,0x1d,0x29,0xc5,0x89,0x6f,0xb7,0x62,0x0e,0xaa,0x18,0xbe,0x1b,
   0xfc,0x56,0x3e,0x4b,0xc6,0xd2,0x79,0x20,0x9a,0xdb,0xc0,0xfe,0x78,0xcd,0x5a,0xf4,
   0x1f,0xdd,0xa8,0x33,0x88,0x07,0xc7,0x31,0xb1,0x12,0x10,0x59,0x27,0x80,0xec,0x5f,
   0x60,0x51,0x7f,0xa9,0x19,0xb5,0x4a,0x0d,0x2d,0xe5,0x7a,0x9f,0x93,0xc9,0x9c,0xef,
   0xa0,0xe0,0x3b,0x4d,0xae,0x2a,0xf5,0xb0,0xc8,0xeb,0xbb,0x3c,0x83,0x53,0x99,0x61,
   0x17,0x2b,0x04,0x7e,0xba,0x77,0xd6,0x26,0xe1,0x69,0x14,0x63,0x55,0x21,0x0c,0x7d
};

/* Forward S-box — only the values used by the key schedule are needed. */
static const uint8_t SBOX[256] = {
   0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
   0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
   0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
   0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
   0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
   0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
   0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
   0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
   0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
   0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
   0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
   0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
   0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
   0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
   0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
   0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
};

static const uint8_t RCON[11] = {
   0x00,0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36
};

static void ecb_key_expand(const uint8_t *key, uint32_t *ks)
{
   for (int i = 0; i < 4; i++) {
      ks[i] = ((uint32_t)key[4*i]   << 24)
            | ((uint32_t)key[4*i+1] << 16)
            | ((uint32_t)key[4*i+2] <<  8)
            | ((uint32_t)key[4*i+3]);
   }
   for (int i = 4; i < 44; i++) {
      uint32_t tmp = ks[i-1];
      if (i % 4 == 0) {
         tmp = ((uint32_t)SBOX[(tmp >> 16) & 0xff] << 24)
             | ((uint32_t)SBOX[(tmp >>  8) & 0xff] << 16)
             | ((uint32_t)SBOX[(tmp      ) & 0xff] <<  8)
             | ((uint32_t)SBOX[(tmp >> 24) & 0xff]);
         tmp ^= (uint32_t)RCON[i/4] << 24;
      }
      ks[i] = ks[i-4] ^ tmp;
   }
}

static inline uint8_t gmul(uint8_t a, uint8_t b)
{
   uint8_t p = 0;
   for (int i = 0; i < 8; i++) {
      if (b & 1) p ^= a;
      uint8_t hi = a & 0x80;
      a = (uint8_t)(a << 1);
      if (hi) a ^= 0x1b;
      b >>= 1;
   }
   return p;
}

static void ecb_decrypt_block(const uint32_t *ks, const uint8_t *in, uint8_t *out)
{
   uint8_t s[16];
   memcpy(s, in, 16);

   for (int c = 0; c < 4; c++) {
      uint32_t rk = ks[10*4 + c];
      s[4*c+0] ^= (uint8_t)(rk >> 24);
      s[4*c+1] ^= (uint8_t)(rk >> 16);
      s[4*c+2] ^= (uint8_t)(rk >>  8);
      s[4*c+3] ^= (uint8_t)(rk      );
   }

   for (int round = 9; round >= 1; round--) {
      uint8_t tmp;
      tmp=s[13]; s[13]=s[9]; s[9]=s[5];  s[5]=s[1];  s[1]=tmp;
      tmp=s[2];  s[2]=s[10]; s[10]=tmp;
      tmp=s[6];  s[6]=s[14]; s[14]=tmp;
      tmp=s[3];  s[3]=s[7];  s[7]=s[11]; s[11]=s[15]; s[15]=tmp;

      for (int i = 0; i < 16; i++) s[i] = ISBOX[s[i]];

      for (int c = 0; c < 4; c++) {
         uint32_t rk = ks[round*4 + c];
         s[4*c+0] ^= (uint8_t)(rk >> 24);
         s[4*c+1] ^= (uint8_t)(rk >> 16);
         s[4*c+2] ^= (uint8_t)(rk >>  8);
         s[4*c+3] ^= (uint8_t)(rk      );
      }

      for (int c = 0; c < 4; c++) {
         uint8_t a0=s[4*c+0], a1=s[4*c+1], a2=s[4*c+2], a3=s[4*c+3];
         s[4*c+0] = gmul(0x0e,a0)^gmul(0x0b,a1)^gmul(0x0d,a2)^gmul(0x09,a3);
         s[4*c+1] = gmul(0x09,a0)^gmul(0x0e,a1)^gmul(0x0b,a2)^gmul(0x0d,a3);
         s[4*c+2] = gmul(0x0d,a0)^gmul(0x09,a1)^gmul(0x0e,a2)^gmul(0x0b,a3);
         s[4*c+3] = gmul(0x0b,a0)^gmul(0x0d,a1)^gmul(0x09,a2)^gmul(0x0e,a3);
      }
   }

   uint8_t tmp;
   tmp=s[13]; s[13]=s[9]; s[9]=s[5];  s[5]=s[1];  s[1]=tmp;
   tmp=s[2];  s[2]=s[10]; s[10]=tmp;
   tmp=s[6];  s[6]=s[14]; s[14]=tmp;
   tmp=s[3];  s[3]=s[7];  s[7]=s[11]; s[11]=s[15]; s[15]=tmp;

   for (int i = 0; i < 16; i++) s[i] = ISBOX[s[i]];

   for (int c = 0; c < 4; c++) {
      uint32_t rk = ks[c];
      s[4*c+0] ^= (uint8_t)(rk >> 24);
      s[4*c+1] ^= (uint8_t)(rk >> 16);
      s[4*c+2] ^= (uint8_t)(rk >>  8);
      s[4*c+3] ^= (uint8_t)(rk      );
   }

   memcpy(out, s, 16);
}

/* ── little-endian readers ──────────────────────────────────────────────── */

static uint64_t rd_u64(const uint8_t *p)
{
   uint64_t v = 0;
   for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
   return v;
}

static uint32_t rd_u32(const uint8_t *p)
{
   return (uint32_t)p[0] | ((uint32_t)p[1] << 8)
        | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint16_t rd_u16(const uint8_t *p)
{
   return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

/* ── hash set ───────────────────────────────────────────────────────────── */

static int hashset_add(CnmtHashSet *set, const uint8_t hash[32])
{
   if (cnmt_hashset_contains(set, hash)) return 0;   /* union: skip dupes */
   uint8_t (*grown)[32] = realloc(set->hashes, (size_t)(set->count + 1) * 32);
   if (!grown) return -1;
   set->hashes = grown;
   memcpy(set->hashes[set->count], hash, 32);
   set->count++;
   return 0;
}

int cnmt_hashset_contains(const CnmtHashSet *set, const uint8_t digest[32])
{
   for (int i = 0; i < set->count; i++) {
      if (memcmp(set->hashes[i], digest, 32) == 0) return 1;
   }
   return 0;
}

void cnmt_hashset_free(CnmtHashSet *set)
{
   free(set->hashes);
   set->hashes = NULL;
   set->count  = 0;
}

/* ── CNMT parse ─────────────────────────────────────────────────────────── */

#define CNMT_CONTENT_STRIDE 0x38
#define CNMT_MAX_ENTRIES    0x1000

static int parse_cnmt(const uint8_t *buf, size_t len, CnmtHashSet *set)
{
   if (len < 0x20) return CNMT_PARSE_ERR;

   uint16_t header_off  = rd_u16(buf + 0x0E);   /* extended header size */
   uint16_t entry_count = rd_u16(buf + 0x10);
   if (entry_count > CNMT_MAX_ENTRIES) return CNMT_PARSE_ERR;

   size_t base = (size_t)0x20 + header_off;
   for (int i = 0; i < entry_count; i++) {
      size_t off = base + (size_t)i * CNMT_CONTENT_STRIDE;
      if (off + 32 > len) return CNMT_PARSE_ERR;
      if (hashset_add(set, buf + off) != 0) return CNMT_PARSE_ERR;
   }
   return CNMT_OK;
}

/* Scan a decrypted in-memory PFS0 buffer for *.cnmt files and parse them. */
static int parse_inner_pfs0(const uint8_t *buf, size_t len, CnmtHashSet *set)
{
   if (len < 0x10 || rd_u32(buf) != PFS0_MAGIC) return CNMT_PARSE_ERR;

   uint32_t file_count = rd_u32(buf + 0x04);
   uint32_t str_size   = rd_u32(buf + 0x08);
   if (file_count == 0 || file_count > PFS0_MAX_FILES) return CNMT_PARSE_ERR;

   size_t entries_off = 0x10;
   size_t str_off     = entries_off + (size_t)file_count * 0x18;
   size_t data_off    = str_off + str_size;
   if (data_off > len) return CNMT_PARSE_ERR;

   int found = 0;
   for (uint32_t i = 0; i < file_count; i++) {
      const uint8_t *e = buf + entries_off + (size_t)i * 0x18;
      uint64_t f_off  = rd_u64(e);
      uint64_t f_size = rd_u64(e + 0x08);
      uint32_t name_off = rd_u32(e + 0x10);

      if (str_off + name_off >= len) continue;
      const char *name = (const char *)(buf + str_off + name_off);
      size_t name_max  = len - (str_off + name_off);
      size_t name_len  = strnlen(name, name_max);
      if (name_len < 5 || memcmp(name + name_len - 5, ".cnmt", 5) != 0) continue;

      if (data_off + f_off + f_size > len) return CNMT_PARSE_ERR;
      int rc = parse_cnmt(buf + data_off + f_off, (size_t)f_size, set);
      if (rc != CNMT_OK) return rc;
      found = 1;
   }
   return found ? CNMT_OK : CNMT_NO_META;
}

/* Extract hashes from a single NCA at absolute offset [abs_off] in [fp].
 * Returns CNMT_OK (hashes appended), or a reason code. Non-META NCAs return
 * CNMT_NO_META so the caller can keep scanning. */
static int extract_from_nca(FILE *fp, uint64_t abs_off, uint64_t nca_size,
                            CnmtHashSet *set)
{
   if (nca_size < 0xC00) return CNMT_PARSE_ERR;

   uint8_t enc[0xC00], dec[0xC00];
   if (fseeko(fp, (off_t)abs_off, SEEK_SET) != 0) return CNMT_PARSE_ERR;
   if (fread(enc, 1, 0xC00, fp) != 0xC00) return CNMT_PARSE_ERR;

   aes_xts_decrypt(enc, dec, 6, 0x200, 0, g_cfg.header_key, g_cfg.header_key + 16);

   if (memcmp(dec + 0x200, "NCA3", 4) != 0 && memcmp(dec + 0x200, "NCA2", 4) != 0) {
      return CNMT_PARSE_ERR;   /* bad magic — wrong header_key or NCA0 */
   }
   if (dec[0x205] != 0x01) return CNMT_NO_META;   /* contentType != META */

   /* META NCAs are key-area encrypted (rightsId zero). Guard the title-key
    * case we cannot handle. */
   int rights_set = 0;
   for (int i = 0; i < 16; i++) if (dec[0x230 + i]) { rights_set = 1; break; }
   if (rights_set) return CNMT_PARSE_ERR;

   if (dec[0x207] != 0x00) return CNMT_NO_KEYS;   /* keyIndex != application */

   int crypto1 = dec[0x206];
   int crypto2 = dec[0x220];
   int keygen  = (crypto1 > crypto2 ? crypto1 : crypto2) - 1;
   if (keygen < 0) keygen = 0;

   const uint8_t *kak = find_kak((uint8_t)keygen);
   if (!kak) return CNMT_NO_KEYS;

   /* Decrypt the 0x40-byte key area (4 blocks) with the KAK. */
   uint32_t ks[44];
   ecb_key_expand(kak, ks);
   uint8_t key_area[0x40];
   for (int b = 0; b < 4; b++) {
      ecb_decrypt_block(ks, dec + 0x300 + b * 16, key_area + b * 16);
   }
   const uint8_t *ctr_key = key_area + 0x20;   /* entry index 2 */

   /* Walk the 4 sections; the CNMT lives in a PFS0 section. */
   for (int si = 0; si < 4; si++) {
      const uint8_t *sec = dec + 0x240 + si * 0x10;
      uint32_t media_start = rd_u32(sec);
      uint32_t media_end   = rd_u32(sec + 4);
      if (media_start == 0 && media_end == 0) continue;

      const uint8_t *fsh = dec + 0x400 + si * 0x200;
      if (fsh[0x03] != 0x02) continue;          /* fs_type != PFS0 */
      int crypto = fsh[0x04];                   /* 1 = none, 3 = CTR */
      if (crypto != 1 && crypto != 3) continue;

      uint64_t section_off = (uint64_t)media_start * 0x200;
      uint64_t pfs0_off    = rd_u64(fsh + 0x40);
      uint64_t pfs0_size   = rd_u64(fsh + 0x48);
      if (pfs0_size == 0 || pfs0_size > 16u * 1024u * 1024u) continue;

      uint64_t data_abs = abs_off + section_off + pfs0_off;
      uint8_t *pbuf = malloc((size_t)pfs0_size);
      if (!pbuf) return CNMT_PARSE_ERR;

      if (fseeko(fp, (off_t)data_abs, SEEK_SET) != 0 ||
          fread(pbuf, 1, (size_t)pfs0_size, fp) != (size_t)pfs0_size) {
         free(pbuf);
         return CNMT_PARSE_ERR;
      }

      if (crypto == 3) {
         /* base counter = { nonce[0x147..0x140] reversed, 0x00 * 8 } */
         uint8_t base_ctr[16] = {0};
         for (int i = 0; i < 8; i++) base_ctr[i] = fsh[0x147 - i];
         AesCtrCtx ctx;
         aes_ctr_init(&ctx, ctr_key, base_ctr);
         aes_ctr_set_offset(&ctx, base_ctr, section_off + pfs0_off);
         aes_ctr_crypt(&ctx, pbuf, pbuf, (size_t)pfs0_size);
      }

      int rc = parse_inner_pfs0(pbuf, (size_t)pfs0_size, set);
      free(pbuf);
      if (rc == CNMT_OK) return CNMT_OK;
      if (rc == CNMT_PARSE_ERR) return rc;
      /* CNMT_NO_META: this PFS0 had no *.cnmt — keep scanning sections. */
   }

   return CNMT_NO_META;
}

/* Returns 1 if the filename ends with ".cnmt.nca". */
static int is_cnmt_nca(const char *name)
{
   size_t n = strlen(name);
   return n >= 9 && strcmp(name + n - 9, ".cnmt.nca") == 0;
}

int cnmt_extract_hashes_pfs0(FILE *in_fp, const Pfs0Container *c, CnmtHashSet *out)
{
   if (!g_cfg.enabled) return CNMT_DISABLED;
   if (!nca_cnmt_keys_available()) return CNMT_NO_KEYS;

   int last = CNMT_NO_META;
   for (int i = 0; i < c->file_count; i++) {
      const Pfs0File *f = &c->files[i];
      if (!is_cnmt_nca(f->name)) continue;
      int rc = extract_from_nca(in_fp, f->data_offset, f->size, out);
      if (rc == CNMT_OK) last = CNMT_OK;
      else if (last != CNMT_OK) last = rc;
   }
   if (out->count > 0) return CNMT_OK;
   return last;
}

int cnmt_extract_hashes_hfs0(FILE *in_fp, const Hfs0Container *c, CnmtHashSet *out)
{
   if (!g_cfg.enabled) return CNMT_DISABLED;
   if (!nca_cnmt_keys_available()) return CNMT_NO_KEYS;

   int last = CNMT_NO_META;
   for (int i = 0; i < c->file_count; i++) {
      const Hfs0File *f = &c->files[i];
      if (!is_cnmt_nca(f->name)) continue;
      int rc = extract_from_nca(in_fp, f->data_offset, f->size, out);
      if (rc == CNMT_OK) last = CNMT_OK;
      else if (last != CNMT_OK) last = rc;
   }
   if (out->count > 0) return CNMT_OK;
   return last;
}
