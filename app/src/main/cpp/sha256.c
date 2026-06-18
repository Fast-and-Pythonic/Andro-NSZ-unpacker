/*
 * SHA-256 implementation (FIPS 180-4).
 *
 * Uses the ARMv8 SHA2 crypto extension (sha256h/sha256h2/sha256su0/su1)
 * when the CPU supports it, falling back to a compact public-domain scalar
 * implementation otherwise. The extension turns ~150-300 MB/s software
 * hashing into 1+ GB/s, which matters because every output byte is hashed
 * for NCA verification on the conversion hot path.
 */
#include "sha256.h"
#include <string.h>

static const uint32_t K[64] = {
   0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,
   0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
   0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,
   0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
   0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,
   0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
   0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,
   0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
   0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,
   0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
   0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,
   0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
   0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,
   0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
   0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
   0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define ROTR32(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z)   (((x)&(y))^(~(x)&(z)))
#define MAJ(x,y,z)  (((x)&(y))^((x)&(z))^((y)&(z)))
#define EP0(x)  (ROTR32(x,2)^ROTR32(x,13)^ROTR32(x,22))
#define EP1(x)  (ROTR32(x,6)^ROTR32(x,11)^ROTR32(x,25))
#define SIG0(x) (ROTR32(x,7)^ROTR32(x,18)^((x)>>3))
#define SIG1(x) (ROTR32(x,17)^ROTR32(x,19)^((x)>>10))

static void sha256_compress_sw(uint32_t h[8], const uint8_t blk[64])
{
   uint32_t w[64];
   for (int i = 0; i < 16; i++) {
      w[i] = ((uint32_t)blk[i*4  ] << 24)
            |((uint32_t)blk[i*4+1] << 16)
            |((uint32_t)blk[i*4+2] <<  8)
            |((uint32_t)blk[i*4+3]);
   }
   for (int i = 16; i < 64; i++) {
      w[i] = SIG1(w[i-2]) + w[i-7] + SIG0(w[i-15]) + w[i-16];
   }

   uint32_t a=h[0],b=h[1],c=h[2],d=h[3],
            e=h[4],f=h[5],g=h[6],hh=h[7];

   for (int i = 0; i < 64; i++) {
      uint32_t t1 = hh + EP1(e) + CH(e,f,g) + K[i] + w[i];
      uint32_t t2 = EP0(a) + MAJ(a,b,c);
      hh=g; g=f; f=e; e=d+t1;
      d=c;  c=b; b=a; a=t1+t2;
   }

   h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d;
   h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
}

#if defined(__aarch64__)
#include <arm_neon.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

/* Runtime check for the ARMv8 SHA2 crypto extension (cached). */
static int hw_sha2_supported(void)
{
   static int cached = -1;
   if (cached < 0) {
      unsigned long hwcap = getauxval(AT_HWCAP);
      cached = (hwcap & HWCAP_SHA2) ? 1 : 0;
   }
   return cached;
}

/* One 64-byte block via the SHA2 extension (canonical 16-quad schedule). */
static void sha256_compress_hw(uint32_t h[8], const uint8_t blk[64])
{
   uint32x4_t STATE0 = vld1q_u32(&h[0]);
   uint32x4_t STATE1 = vld1q_u32(&h[4]);
   uint32x4_t ABEF_SAVE = STATE0, CDGH_SAVE = STATE1;

   uint32x4_t MSG0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk +  0)));
   uint32x4_t MSG1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk + 16)));
   uint32x4_t MSG2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk + 32)));
   uint32x4_t MSG3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk + 48)));
   uint32x4_t TMP0, TMP1, TMP2;

   TMP0 = vaddq_u32(MSG0, vld1q_u32(&K[0]));

   /* Rounds 0-3 */
   MSG0 = vsha256su0q_u32(MSG0, MSG1);
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG1, vld1q_u32(&K[4]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
   MSG0 = vsha256su1q_u32(MSG0, MSG2, MSG3);

   /* Rounds 4-7 */
   MSG1 = vsha256su0q_u32(MSG1, MSG2);
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG2, vld1q_u32(&K[8]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);
   MSG1 = vsha256su1q_u32(MSG1, MSG3, MSG0);

   /* Rounds 8-11 */
   MSG2 = vsha256su0q_u32(MSG2, MSG3);
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG3, vld1q_u32(&K[12]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
   MSG2 = vsha256su1q_u32(MSG2, MSG0, MSG1);

   /* Rounds 12-15 */
   MSG3 = vsha256su0q_u32(MSG3, MSG0);
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG0, vld1q_u32(&K[16]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);
   MSG3 = vsha256su1q_u32(MSG3, MSG1, MSG2);

   /* Rounds 16-19 */
   MSG0 = vsha256su0q_u32(MSG0, MSG1);
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG1, vld1q_u32(&K[20]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
   MSG0 = vsha256su1q_u32(MSG0, MSG2, MSG3);

   /* Rounds 20-23 */
   MSG1 = vsha256su0q_u32(MSG1, MSG2);
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG2, vld1q_u32(&K[24]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);
   MSG1 = vsha256su1q_u32(MSG1, MSG3, MSG0);

   /* Rounds 24-27 */
   MSG2 = vsha256su0q_u32(MSG2, MSG3);
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG3, vld1q_u32(&K[28]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
   MSG2 = vsha256su1q_u32(MSG2, MSG0, MSG1);

   /* Rounds 28-31 */
   MSG3 = vsha256su0q_u32(MSG3, MSG0);
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG0, vld1q_u32(&K[32]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);
   MSG3 = vsha256su1q_u32(MSG3, MSG1, MSG2);

   /* Rounds 32-35 */
   MSG0 = vsha256su0q_u32(MSG0, MSG1);
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG1, vld1q_u32(&K[36]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
   MSG0 = vsha256su1q_u32(MSG0, MSG2, MSG3);

   /* Rounds 36-39 */
   MSG1 = vsha256su0q_u32(MSG1, MSG2);
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG2, vld1q_u32(&K[40]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);
   MSG1 = vsha256su1q_u32(MSG1, MSG3, MSG0);

   /* Rounds 40-43 */
   MSG2 = vsha256su0q_u32(MSG2, MSG3);
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG3, vld1q_u32(&K[44]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
   MSG2 = vsha256su1q_u32(MSG2, MSG0, MSG1);

   /* Rounds 44-47 */
   MSG3 = vsha256su0q_u32(MSG3, MSG0);
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG0, vld1q_u32(&K[48]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);
   MSG3 = vsha256su1q_u32(MSG3, MSG1, MSG2);

   /* Rounds 48-51 */
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG1, vld1q_u32(&K[52]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);

   /* Rounds 52-55 */
   TMP2 = STATE0;
   TMP0 = vaddq_u32(MSG2, vld1q_u32(&K[56]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);

   /* Rounds 56-59 */
   TMP2 = STATE0;
   TMP1 = vaddq_u32(MSG3, vld1q_u32(&K[60]));
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);

   /* Rounds 60-63 */
   TMP2 = STATE0;
   STATE0 = vsha256hq_u32(STATE0, STATE1, TMP1);
   STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP1);

   STATE0 = vaddq_u32(STATE0, ABEF_SAVE);
   STATE1 = vaddq_u32(STATE1, CDGH_SAVE);

   vst1q_u32(&h[0], STATE0);
   vst1q_u32(&h[4], STATE1);
}
#endif /* __aarch64__ */

static inline void sha256_compress(uint32_t h[8], const uint8_t blk[64])
{
#if defined(__aarch64__)
   if (hw_sha2_supported()) {
      sha256_compress_hw(h, blk);
      return;
   }
#endif
   sha256_compress_sw(h, blk);
}

void sha256(const uint8_t *data, size_t len, uint8_t *digest_out)
{
   uint32_t h[8] = {
      0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
      0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
   };

   size_t pos = 0;
   while (pos + 64 <= len) {
      sha256_compress(h, data + pos);
      pos += 64;
   }

   uint8_t buf[64];
   size_t rem = len - pos;
   memcpy(buf, data + pos, rem);
   buf[rem] = 0x80;
   memset(buf + rem + 1, 0, 63 - rem);

   if (rem >= 56) {
      sha256_compress(h, buf);
      memset(buf, 0, 56);
   }

   uint64_t bit_len = (uint64_t)len * 8;
   buf[56] = (uint8_t)(bit_len >> 56);
   buf[57] = (uint8_t)(bit_len >> 48);
   buf[58] = (uint8_t)(bit_len >> 40);
   buf[59] = (uint8_t)(bit_len >> 32);
   buf[60] = (uint8_t)(bit_len >> 24);
   buf[61] = (uint8_t)(bit_len >> 16);
   buf[62] = (uint8_t)(bit_len >>  8);
   buf[63] = (uint8_t)(bit_len      );
   sha256_compress(h, buf);

   for (int i = 0; i < 8; i++) {
      digest_out[i*4+0] = (uint8_t)(h[i] >> 24);
      digest_out[i*4+1] = (uint8_t)(h[i] >> 16);
      digest_out[i*4+2] = (uint8_t)(h[i] >>  8);
      digest_out[i*4+3] = (uint8_t)(h[i]      );
   }
}

/* ── Streaming API ─────────────────────────────────────────────── */

void sha256_init(Sha256Ctx *ctx)
{
   ctx->h[0] = 0x6a09e667; ctx->h[1] = 0xbb67ae85;
   ctx->h[2] = 0x3c6ef372; ctx->h[3] = 0xa54ff53a;
   ctx->h[4] = 0x510e527f; ctx->h[5] = 0x9b05688c;
   ctx->h[6] = 0x1f83d9ab; ctx->h[7] = 0x5be0cd19;
   ctx->buf_len   = 0;
   ctx->total_len = 0;
}

void sha256_update(Sha256Ctx *ctx, const uint8_t *data, size_t len)
{
   ctx->total_len += (uint64_t)len;
   size_t pos = 0;

   if (ctx->buf_len > 0 && len > 0) {
      size_t need = 64u - ctx->buf_len;
      size_t take = len < need ? len : need;
      memcpy(ctx->buf + ctx->buf_len, data, take);
      ctx->buf_len += (uint32_t)take;
      pos           += take;
      if (ctx->buf_len == 64) {
         sha256_compress(ctx->h, ctx->buf);
         ctx->buf_len = 0;
      }
   }

   while (pos + 64 <= len) {
      sha256_compress(ctx->h, data + pos);
      pos += 64;
   }

   size_t rem = len - pos;
   if (rem > 0) {
      memcpy(ctx->buf, data + pos, rem);
      ctx->buf_len = (uint32_t)rem;
   }
}

void sha256_final(Sha256Ctx *ctx, uint8_t *digest_out)
{
   uint8_t tmp[64];
   memcpy(tmp, ctx->buf, ctx->buf_len);
   tmp[ctx->buf_len] = 0x80;
   if (ctx->buf_len + 1 < 64)
      memset(tmp + ctx->buf_len + 1, 0, 63u - ctx->buf_len);

   if (ctx->buf_len >= 56) {
      sha256_compress(ctx->h, tmp);
      memset(tmp, 0, 56);
   }

   uint64_t bit_len = ctx->total_len * 8u;
   tmp[56] = (uint8_t)(bit_len >> 56);
   tmp[57] = (uint8_t)(bit_len >> 48);
   tmp[58] = (uint8_t)(bit_len >> 40);
   tmp[59] = (uint8_t)(bit_len >> 32);
   tmp[60] = (uint8_t)(bit_len >> 24);
   tmp[61] = (uint8_t)(bit_len >> 16);
   tmp[62] = (uint8_t)(bit_len >>  8);
   tmp[63] = (uint8_t)(bit_len      );
   sha256_compress(ctx->h, tmp);

   for (int i = 0; i < 8; i++) {
      digest_out[i*4+0] = (uint8_t)(ctx->h[i] >> 24);
      digest_out[i*4+1] = (uint8_t)(ctx->h[i] >> 16);
      digest_out[i*4+2] = (uint8_t)(ctx->h[i] >>  8);
      digest_out[i*4+3] = (uint8_t)(ctx->h[i]      );
   }
}
