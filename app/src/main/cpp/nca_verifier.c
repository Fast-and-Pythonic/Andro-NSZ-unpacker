#include "nca_verifier.h"
#include "pfs0.h"
#include "aes_xts.h"
#include "sha256.h"
#include "nsz_debug.h"

#include <stdio.h>
#include <string.h>
#include <stdint.h>

#define XTS_SECTOR_SIZE   0x200u
#define NCA_HDR_SECTORS   6u
#define NCA_HDR_BYTES     (NCA_HDR_SECTORS * XTS_SECTOR_SIZE)

#define NCA_MAGIC_OFF     0x200u
#define NCA_SEC_ENTRY_OFF 0x240u
#define NCA_SEC_HASH_OFF  0x280u
#define NCA_SEC_HDR_OFF   0x400u
#define NCA_SEC_HDR_SIZE  XTS_SECTOR_SIZE

typedef struct {
   uint32_t media_start;
   uint32_t media_end;
   uint32_t reserved1;
   uint32_t reserved2;
} NcaSectionEntry;

int nca_verify_nsp(const char    *nsp_path,
                   const uint8_t *header_key_32,
                   char          *err_out,
                   int            err_len)
{
   const uint8_t *key1 = header_key_32;
   const uint8_t *key2 = header_key_32 + 16;

   Pfs0Container container;
   if (pfs0_parse(nsp_path, &container) != 0) {
      snprintf(err_out, (size_t)err_len, "verify: cannot parse NSP container");
      return -1;
   }

   FILE *fp = fopen(nsp_path, "rb");
   if (!fp) {
      snprintf(err_out, (size_t)err_len, "verify: cannot open NSP file");
      return -2;
   }
   setvbuf(fp, NULL, _IOFBF, 64 * 1024);

   int ncas_checked = 0;
   int result = 0;

   for (int fi = 0; fi < container.file_count && result == 0; fi++) {
      Pfs0File *f = &container.files[fi];

      const char *ext = strrchr(f->name, '.');
      if (!ext || (strcmp(ext, ".nca") != 0 && strcmp(ext, ".ncz") != 0)) {
         DBG("nca_verify: skipping '%s'", f->name);
         continue;
      }

      DBG("nca_verify: verifying '%s'", f->name);

      if (f->size < NCA_HDR_BYTES) {
         snprintf(err_out, (size_t)err_len,
                  "verify: '%s' too small for NCA header", f->name);
         result = -3;
         break;
      }

      uint8_t enc[NCA_HDR_BYTES];
      fseeko(fp, (off_t)f->data_offset, SEEK_SET);
      if (fread(enc, 1, NCA_HDR_BYTES, fp) != NCA_HDR_BYTES) {
         snprintf(err_out, (size_t)err_len,
                  "verify: I/O error reading '%s'", f->name);
         result = -4;
         break;
      }

      uint8_t dec[NCA_HDR_BYTES];
      aes_xts_decrypt(enc, dec, NCA_HDR_SECTORS, XTS_SECTOR_SIZE, 0, key1, key2);

      const char *magic = (const char *)(dec + NCA_MAGIC_OFF);
      if (memcmp(magic, "NCA3", 4) != 0 &&
          memcmp(magic, "NCA2", 4) != 0 &&
          memcmp(magic, "NCA0", 4) != 0) {
         snprintf(err_out, (size_t)err_len,
                  "verify: bad NCA magic in '%s' — wrong header_key?", f->name);
         result = -5;
         break;
      }
      DBG("nca_verify: '%s' magic OK (%.4s)", f->name, magic);

      for (int si = 0; si < 4 && result == 0; si++) {
         const NcaSectionEntry *entry =
            (const NcaSectionEntry *)(dec + NCA_SEC_ENTRY_OFF + si * 16);

         if (entry->media_start == 0 && entry->media_end == 0) continue;

         const uint8_t *sec_hdr      = dec + NCA_SEC_HDR_OFF + si * NCA_SEC_HDR_SIZE;
         const uint8_t *expected_hash = dec + NCA_SEC_HASH_OFF + si * 32;

         uint8_t computed[32];
         sha256(sec_hdr, NCA_SEC_HDR_SIZE, computed);

         if (memcmp(computed, expected_hash, 32) != 0) {
            snprintf(err_out, (size_t)err_len,
                     "verify: section %d hash mismatch in '%s'", si, f->name);
            result = -6;
         } else {
            DBG("nca_verify:   section[%d] hash OK", si);
         }
      }

      ncas_checked++;
   }

   fclose(fp);

   if (result == 0 && ncas_checked == 0) {
      snprintf(err_out, (size_t)err_len, "verify: no NCA files found");
      return -7;
   }

   if (result == 0)
      DBG("nca_verify: all %d NCA(s) passed", ncas_checked);

   return result;
}
