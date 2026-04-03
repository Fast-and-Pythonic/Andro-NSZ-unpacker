#pragma once
#include <stdint.h>
#include <stddef.h>

typedef struct {
    uint32_t key_schedule[44];  /* AES-128 expanded key (11 round keys * 4 words) */
    uint8_t  base_counter[16];  /* original counter set by caller */
    uint8_t  ctr[16];           /* current running counter */
} AesCtrCtx;

/* Initialise context with key and initial counter value. */
void aes_ctr_init(AesCtrCtx *ctx, const uint8_t *key, const uint8_t *counter);

/* Reposition the counter for a given byte_offset into the stream.
   base_counter is the section's original crypto_counter.
   byte_offset is the absolute position in the decompressed NCA. */
void aes_ctr_set_offset(AesCtrCtx *ctx,
                        const uint8_t *base_counter,
                        uint64_t byte_offset);

/* Encrypt / decrypt (CTR mode is symmetric). */
void aes_ctr_crypt(AesCtrCtx *ctx,
                   const uint8_t *in,
                   uint8_t *out,
                   size_t len);
