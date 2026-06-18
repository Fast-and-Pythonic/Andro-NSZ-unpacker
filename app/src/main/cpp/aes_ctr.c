/*
 * AES-128-CTR implementation.
 * Based on FIPS-197 specification.
 *
 * Two code paths:
 *   - Hardware: on ARM64 with the AES crypto extension, uses the
 *     AESE/AESMC intrinsics (vaeseq_u8/vaesmcq_u8), processing 4 CTR
 *     blocks at a time to keep the crypto pipeline busy. ~1-3 GB/s.
 *   - Software: portable scalar FIPS-197 fallback for CPUs without the
 *     extension (and all 32-bit builds). ~50-150 MB/s.
 *
 * The path is chosen once at runtime via getauxval(AT_HWCAP) & HWCAP_AES.
 * Note: -march=armv8-a+crypto only *allows* the compiler to emit AES
 * instructions where we use the intrinsics explicitly; it does NOT turn
 * the scalar software path below into hardware AES on its own.
 */
#include "aes_ctr.h"
#include <string.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

/* Runtime check for the ARMv8 AES crypto extension (cached). */
static int hw_aes_supported(void)
{
    static int cached = -1;
    if (cached < 0) {
        unsigned long hwcap = getauxval(AT_HWCAP);
        cached = (hwcap & HWCAP_AES) ? 1 : 0;
    }
    return cached;
}

/* Expand the 44-word (big-endian) key schedule into 11 NEON round keys. */
static inline void neon_load_round_keys(const uint32_t *ks, uint8x16_t rk[11])
{
    uint8_t tmp[16];
    for (int r = 0; r < 11; r++) {
        for (int c = 0; c < 4; c++) {
            uint32_t w = ks[r * 4 + c];
            tmp[4 * c + 0] = (uint8_t)(w >> 24);
            tmp[4 * c + 1] = (uint8_t)(w >> 16);
            tmp[4 * c + 2] = (uint8_t)(w >>  8);
            tmp[4 * c + 3] = (uint8_t)(w      );
        }
        rk[r] = vld1q_u8(tmp);
    }
}

/* Full AES-128 encryption of one 16-byte block using the crypto extension. */
static inline uint8x16_t neon_aes_encrypt(uint8x16_t s, const uint8x16_t rk[11])
{
    s = vaesmcq_u8(vaeseq_u8(s, rk[0]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[1]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[2]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[3]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[4]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[5]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[6]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[7]));
    s = vaesmcq_u8(vaeseq_u8(s, rk[8]));
    s = vaeseq_u8(s, rk[9]);          /* final round: no MixColumns */
    s = veorq_u8(s, rk[10]);          /* final AddRoundKey */
    return s;
}
#endif /* __aarch64__ */

/* ---------- AES S-box and constants ---------- */

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

/* GF(2^8) multiply by 2 */
static inline uint8_t xtime(uint8_t a)
{
    return (uint8_t)((a << 1) ^ ((a >> 7) ? 0x1b : 0x00));
}

/* ---------- Key expansion ---------- */

static void key_expand(const uint8_t *key, uint32_t *ks)
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

/* ---------- AES-128 encrypt one block ---------- */

static void aes128_encrypt_block(const uint32_t *ks,
                                  const uint8_t *in,
                                  uint8_t *out)
{
    uint8_t s[16];
    memcpy(s, in, 16);

    /* AddRoundKey (round 0) */
    for (int c = 0; c < 4; c++) {
        uint32_t rk = ks[c];
        s[4*c+0] ^= (uint8_t)(rk >> 24);
        s[4*c+1] ^= (uint8_t)(rk >> 16);
        s[4*c+2] ^= (uint8_t)(rk >>  8);
        s[4*c+3] ^= (uint8_t)(rk      );
    }

    for (int round = 1; round <= 10; round++) {
        /* SubBytes */
        for (int i = 0; i < 16; i++) s[i] = SBOX[s[i]];

        /* ShiftRows */
        uint8_t tmp;
        tmp=s[1]; s[1]=s[5]; s[5]=s[9]; s[9]=s[13]; s[13]=tmp;
        tmp=s[2]; s[2]=s[10]; s[10]=tmp;
        tmp=s[6]; s[6]=s[14]; s[14]=tmp;
        tmp=s[15]; s[15]=s[11]; s[11]=s[7]; s[7]=s[3]; s[3]=tmp;

        /* MixColumns (skip last round) */
        if (round < 10) {
            for (int c = 0; c < 4; c++) {
                uint8_t a0=s[4*c+0], a1=s[4*c+1],
                        a2=s[4*c+2], a3=s[4*c+3];
                s[4*c+0] = xtime(a0)^xtime(a1)^a1^a2^a3;
                s[4*c+1] = a0^xtime(a1)^xtime(a2)^a2^a3;
                s[4*c+2] = a0^a1^xtime(a2)^xtime(a3)^a3;
                s[4*c+3] = xtime(a0)^a0^a1^a2^xtime(a3);
            }
        }

        /* AddRoundKey */
        for (int c = 0; c < 4; c++) {
            uint32_t rk = ks[round*4 + c];
            s[4*c+0] ^= (uint8_t)(rk >> 24);
            s[4*c+1] ^= (uint8_t)(rk >> 16);
            s[4*c+2] ^= (uint8_t)(rk >>  8);
            s[4*c+3] ^= (uint8_t)(rk      );
        }
    }
    memcpy(out, s, 16);
}

/* ---------- CTR helpers ---------- */

static inline void ctr_inc(uint8_t *ctr)
{
    for (int i = 15; i >= 0; i--) {
        if (++ctr[i]) break;
    }
}

/* ---------- Public API ---------- */

void aes_ctr_init(AesCtrCtx *ctx, const uint8_t *key, const uint8_t *counter)
{
    key_expand(key, ctx->key_schedule);
    memcpy(ctx->base_counter, counter, 16);
    memcpy(ctx->ctr, counter, 16);
}

/*
 * NSZ AES-CTR counter layout (big-endian):
 *   Top 8 bytes: crypto_counter[0..7] (nonce/prefix)
 *   Bottom 8 bytes: counter[8..15] XOR (byte_offset / 16), big-endian.
 *
 * Matches Python: Counter.new(64, prefix=nonce[0:8], initial_value=(offset >> 4))
 */
void aes_ctr_set_offset(AesCtrCtx *ctx,
                        const uint8_t *base_counter,
                        uint64_t byte_offset)
{
    uint64_t sector = byte_offset >> 4;

    memcpy(ctx->ctr, base_counter, 16);
    memcpy(ctx->base_counter, base_counter, 16);

    ctx->ctr[ 8] ^= (uint8_t)(sector >> 56);
    ctx->ctr[ 9] ^= (uint8_t)(sector >> 48);
    ctx->ctr[10] ^= (uint8_t)(sector >> 40);
    ctx->ctr[11] ^= (uint8_t)(sector >> 32);
    ctx->ctr[12] ^= (uint8_t)(sector >> 24);
    ctx->ctr[13] ^= (uint8_t)(sector >> 16);
    ctx->ctr[14] ^= (uint8_t)(sector >>  8);
    ctx->ctr[15] ^= (uint8_t)(sector      );
}

/* Portable scalar CTR (FIPS-197 software AES). */
static void aes_ctr_crypt_sw(AesCtrCtx *ctx,
                             const uint8_t *in,
                             uint8_t *out,
                             size_t len)
{
    uint8_t keystream[16];
    size_t i = 0;

    while (i < len) {
        aes128_encrypt_block(ctx->key_schedule, ctx->ctr, keystream);
        ctr_inc(ctx->ctr);

        size_t block_bytes = len - i;
        if (block_bytes > 16) block_bytes = 16;
        for (size_t j = 0; j < block_bytes; j++) {
            out[i+j] = in[i+j] ^ keystream[j];
        }
        i += block_bytes;
    }
}

#if defined(__aarch64__)
/* Hardware CTR using the ARMv8 AES extension, 4 blocks interleaved. */
static void aes_ctr_crypt_hw(AesCtrCtx *ctx,
                             const uint8_t *in,
                             uint8_t *out,
                             size_t len)
{
    uint8x16_t rk[11];
    neon_load_round_keys(ctx->key_schedule, rk);

    size_t i = 0;

    /* 4 blocks (64 bytes) at a time — keeps the AES pipeline saturated. */
    while (i + 64 <= len) {
        uint8x16_t c0 = vld1q_u8(ctx->ctr); ctr_inc(ctx->ctr);
        uint8x16_t c1 = vld1q_u8(ctx->ctr); ctr_inc(ctx->ctr);
        uint8x16_t c2 = vld1q_u8(ctx->ctr); ctr_inc(ctx->ctr);
        uint8x16_t c3 = vld1q_u8(ctx->ctr); ctr_inc(ctx->ctr);

        c0 = neon_aes_encrypt(c0, rk);
        c1 = neon_aes_encrypt(c1, rk);
        c2 = neon_aes_encrypt(c2, rk);
        c3 = neon_aes_encrypt(c3, rk);

        vst1q_u8(out + i,      veorq_u8(vld1q_u8(in + i),      c0));
        vst1q_u8(out + i + 16, veorq_u8(vld1q_u8(in + i + 16), c1));
        vst1q_u8(out + i + 32, veorq_u8(vld1q_u8(in + i + 32), c2));
        vst1q_u8(out + i + 48, veorq_u8(vld1q_u8(in + i + 48), c3));
        i += 64;
    }

    /* Remaining whole blocks. */
    while (i + 16 <= len) {
        uint8x16_t c0 = vld1q_u8(ctx->ctr); ctr_inc(ctx->ctr);
        c0 = neon_aes_encrypt(c0, rk);
        vst1q_u8(out + i, veorq_u8(vld1q_u8(in + i), c0));
        i += 16;
    }

    /* Final partial block. */
    if (i < len) {
        uint8_t ks[16];
        uint8x16_t c0 = vld1q_u8(ctx->ctr); ctr_inc(ctx->ctr);
        c0 = neon_aes_encrypt(c0, rk);
        vst1q_u8(ks, c0);
        for (size_t j = 0; i < len; i++, j++) {
            out[i] = in[i] ^ ks[j];
        }
    }
}
#endif /* __aarch64__ */

void aes_ctr_crypt(AesCtrCtx *ctx,
                   const uint8_t *in,
                   uint8_t *out,
                   size_t len)
{
#if defined(__aarch64__)
    if (hw_aes_supported()) {
        aes_ctr_crypt_hw(ctx, in, out, len);
        return;
    }
#endif
    aes_ctr_crypt_sw(ctx, in, out, len);
}
