#pragma once
#include <stdint.h>
#include <stddef.h>

/* Compute SHA-256 digest of data[0..len-1]. Output: digest_out[32]. */
void sha256(const uint8_t *data, size_t len, uint8_t *digest_out);

/* Streaming SHA-256 API */
typedef struct {
   uint32_t h[8];
   uint8_t  buf[64];
   uint32_t buf_len;
   uint64_t total_len;
} Sha256Ctx;

void sha256_init(Sha256Ctx *ctx);
void sha256_update(Sha256Ctx *ctx, const uint8_t *data, size_t len);
void sha256_final(Sha256Ctx *ctx, uint8_t *digest_out);
