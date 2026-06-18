/*
 * NCZ decompression — matches Python NszDecompressor.py __decompressNcz() exactly.
 *
 * Key algorithm (Python lines 175-210):
 *   firstSection = True
 *   for s in sections:
 *       i = s.offset
 *       useCrypto = s.cryptoType in (3, 4)
 *       end = s.offset + s.size
 *       if firstSection:
 *           firstSection = False
 *           uncompressedSize = UNCOMPRESSABLE_HEADER_SIZE - sections[0].offset
 *           if uncompressedSize > 0: i += uncompressedSize
 *       while i < end:
 *           if useCrypto: crypto.seek(i)
 *           chunkSz = min(0x10000, end - i)
 *           inputChunk = decompressor.read(chunkSz)
 *           if useCrypto: inputChunk = crypto.encrypt(inputChunk)
 *           write(inputChunk)
 *           hash.update(inputChunk)
 *           i += len(inputChunk)
 *
 * Fixes vs NSZExpress v1:
 *   1. Crypto only for types 3, 4 (NOT any non-zero type)
 *   2. Sequential section iteration (NOT per-offset lookup)
 *   3. FakeSection gap = plaintext pass-through
 *   4. firstSection skip for already-written NCA header bytes
 *   5. Block plaintext detection: compressed < decompressedBlockSize (NOT ==)
 *   6. Last block remainder handling
 */
#include "ncz_decompress.h"
#include "aes_ctr.h"
#include "async_writer.h"
#include "nsz_debug.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <zstd.h>

/* ── Block decompression reader ──────────────────────────────────────────── */
/* Mirrors Python BlockDecompressorReader.py */

typedef struct {
    FILE             *fp;
    const NczBlockHeader *bh;
    uint32_t          block_size;      /* 1 << block_size_exp */
    int64_t           position;        /* current position in decompressed stream */
    uint8_t          *current_block;   /* cached decompressed block data */
    int               current_block_id;
    uint32_t          current_block_len;
    uint8_t          *comp_buf;        /* scratch buffer for compressed data */
} BlockReader;

static int block_reader_init(BlockReader *br, FILE *fp, const NczBlockHeader *bh)
{
    br->fp = fp;
    br->bh = bh;
    br->block_size = 1u << bh->block_size_exp;
    br->position = 0;
    br->current_block_id = -1;
    br->current_block_len = 0;

    br->current_block = malloc(br->block_size);
    br->comp_buf      = malloc(br->block_size);
    if (!br->current_block || !br->comp_buf) {
        free(br->current_block);
        free(br->comp_buf);
        return NCZ_ERR_OOM;
    }
    return NCZ_OK;
}

static void block_reader_free(BlockReader *br)
{
    free(br->current_block);
    free(br->comp_buf);
    br->current_block = NULL;
    br->comp_buf = NULL;
}

/* Decompress a single block. Matches Python BlockDecompressorReader.__decompressBlock */
static int block_reader_decompress_block(BlockReader *br, int block_id)
{
    if (br->current_block_id == block_id)
        return NCZ_OK;

    if (block_id >= (int)br->bh->num_blocks)
        return NCZ_ERR_IO; /* EOF */

    /* Determine decompressed size for this block.
     * Python: decompressedBlockSize = self.BlockSize
     *         if blockID >= len(CompressedBlockOffsetList) - 1:
     *             remainder = decompressedSize % blockSize
     *             if remainder > 0: decompressedBlockSize = remainder */
    uint32_t d_size = br->block_size;
    if (block_id >= (int)br->bh->num_blocks - 1) {
        int64_t remainder = br->bh->decompressed_size % br->block_size;
        if (remainder > 0)
            d_size = (uint32_t)remainder;
    }

    uint32_t c_size = br->bh->compressed_sizes[block_id];

    fseeko(br->fp, (off_t)br->bh->block_offsets[block_id], SEEK_SET);

    /* Python: if CompressedBlockSizeList[blockID] < decompressedBlockSize:
     *             decompress
     *         else:
     *             read plaintext */
    if (c_size < d_size) {
        /* Compressed block */
        if (fread(br->comp_buf, 1, c_size, br->fp) != c_size)
            return NCZ_ERR_IO;
        size_t result = ZSTD_decompress(br->current_block, d_size,
                                         br->comp_buf, c_size);
        if (ZSTD_isError(result)) {
            DBG("block_reader: zstd error on block %d — %s",
                block_id, ZSTD_getErrorName(result));
            return NCZ_ERR_ZSTD;
        }
    } else {
        /* Plaintext (uncompressed) block */
        if (fread(br->current_block, 1, d_size, br->fp) != d_size)
            return NCZ_ERR_IO;
    }

    br->current_block_id  = block_id;
    br->current_block_len = d_size;
    return NCZ_OK;
}

/* Read `length` bytes from current position. Matches Python BlockDecompressorReader.read */
static int block_reader_read(BlockReader *br, uint8_t *out, size_t length, size_t *bytes_read)
{
    size_t total = 0;
    while (total < length) {
        uint32_t block_offset = (uint32_t)(br->position % br->block_size);
        int      block_id     = (int)(br->position / br->block_size);

        if (block_id >= (int)br->bh->num_blocks) {
            break; /* EOF */
        }

        int rc = block_reader_decompress_block(br, block_id);
        if (rc != NCZ_OK) return rc;

        /* How many bytes available in current block from current offset */
        uint32_t avail = br->current_block_len - block_offset;
        size_t   take  = length - total;
        if (take > avail) take = avail;

        memcpy(out + total, br->current_block + block_offset, take);
        total        += take;
        br->position += (int64_t)take;
    }

    *bytes_read = total;
    return NCZ_OK;
}

/* ── Solid decompression reader ──────────────────────────────────────────── */

typedef struct {
    ZSTD_DStream  *dstream;
    FILE          *fp;
    uint8_t       *in_buf;
    size_t         in_buf_size;
    ZSTD_inBuffer  zin;
    int            eof;
} SolidReader;

static int solid_reader_init(SolidReader *sr, FILE *fp)
{
    sr->fp = fp;
    sr->in_buf_size = ZSTD_DStreamInSize();
    sr->in_buf = malloc(sr->in_buf_size);
    if (!sr->in_buf) return NCZ_ERR_OOM;

    sr->dstream = ZSTD_createDStream();
    if (!sr->dstream) { free(sr->in_buf); return NCZ_ERR_OOM; }
    ZSTD_initDStream(sr->dstream);

    sr->zin.src  = sr->in_buf;
    sr->zin.size = 0;
    sr->zin.pos  = 0;
    sr->eof = 0;

    return NCZ_OK;
}

static void solid_reader_free(SolidReader *sr)
{
    if (sr->dstream) ZSTD_freeDStream(sr->dstream);
    free(sr->in_buf);
    sr->dstream = NULL;
    sr->in_buf = NULL;
}

/* Read exactly `length` decompressed bytes (or less if stream ends) */
static int solid_reader_read(SolidReader *sr, uint8_t *out, size_t length, size_t *bytes_read)
{
    ZSTD_outBuffer zout = { out, length, 0 };

    while (zout.pos < zout.size) {
        /* Refill input if needed */
        if (sr->zin.pos >= sr->zin.size && !sr->eof) {
            size_t n = fread(sr->in_buf, 1, sr->in_buf_size, sr->fp);
            if (n == 0) { sr->eof = 1; break; }
            sr->zin.src  = sr->in_buf;
            sr->zin.size = n;
            sr->zin.pos  = 0;
        }

        if (sr->eof && sr->zin.pos >= sr->zin.size) break;

        size_t ret = ZSTD_decompressStream(sr->dstream, &zout, &sr->zin);
        if (ZSTD_isError(ret)) {
            DBG("solid_reader: zstd error — %s", ZSTD_getErrorName(ret));
            return NCZ_ERR_ZSTD;
        }
        if (ret == 0) { sr->eof = 1; break; } /* frame complete */
    }

    *bytes_read = zout.pos;
    return NCZ_OK;
}

/* ── Main decompression function ─────────────────────────────────────────── */

int ncz_decompress(FILE *in_fp,
                   FILE *out_fp,
                   const NczHeader *hdr,
                   Sha256Ctx *sha_ctx,
                   volatile atomic_int *cancel,
                   NczProgressCb cb,
                   void *cb_ctx,
                   int64_t total_est,
                   int64_t *bytes_done_out)
{
    int ret = NCZ_OK;
    int64_t bytes_done = 0;

    /* Init reader (solid or block) */
    BlockReader block_reader;
    SolidReader solid_reader;
    int use_block = hdr->has_block_compression;

    if (use_block) {
        ret = block_reader_init(&block_reader, in_fp, &hdr->block_header);
        if (ret != NCZ_OK) return ret;
    } else {
        ret = solid_reader_init(&solid_reader, in_fp);
        if (ret != NCZ_OK) return ret;
    }

    /* Chunk buffer for reading decompressed data (used when there is no
     * output stream; otherwise the async writer owns the buffers). */
    uint8_t *chunk_buf = malloc(0x10000);
    if (!chunk_buf) {
        if (use_block) block_reader_free(&block_reader);
        else solid_reader_free(&solid_reader);
        return NCZ_ERR_OOM;
    }

    /* Async writer overlaps disk writes (and SHA-256) with the next chunk's
     * decompress + AES on this thread. */
    AsyncWriter *aw = NULL;
    if (out_fp) {
        aw = aw_start(out_fp, sha_ctx, 0x10000, 32);
        if (!aw) {
            free(chunk_buf);
            if (use_block) block_reader_free(&block_reader);
            else solid_reader_free(&solid_reader);
            return NCZ_ERR_OOM;
        }
    }

    /* Throttle progress callbacks */
    struct timespec last_cb_time;
    clock_gettime(CLOCK_MONOTONIC, &last_cb_time);

    /*
     * Section-sequential iteration — exact mirror of Python:
     *   firstSection = True
     *   for s in sections:
     *       ...
     */
    int first_section = 1;

    DBG("ncz_decompress: start  sections=%d  mode=%s",
        hdr->section_count, use_block ? "BLOCK" : "SOLID");

    for (int si = 0; si < hdr->section_count && ret == NCZ_OK; si++) {
        const NczSection *s = &hdr->sections[si];
        int64_t i   = s->offset;
        int64_t end = s->offset + s->size;

        /* Python: useCrypto = s.cryptoType in (3, 4) */
        int use_crypto = (s->crypto_type == 3 || s->crypto_type == 4);

        AesCtrCtx aes;
        if (use_crypto) {
            aes_ctr_init(&aes, s->crypto_key, s->crypto_counter);
        }

        /*
         * Python firstSection logic (NszDecompressor.py:182-186):
         *   if firstSection:
         *       firstSection = False
         *       uncompressedSize = UNCOMPRESSABLE_HEADER_SIZE - sections[0].offset
         *       if uncompressedSize > 0: i += uncompressedSize
         *
         * This skips the bytes already written as the NCA header (0x4000 bytes).
         * sections[0].offset is the offset of the FIRST section in the array
         * (which may be FakeSection starting at 0x4000, or the first real section).
         */
        if (first_section) {
            first_section = 0;
            int64_t uncompressed_size = (int64_t)NCA_HEADER_SIZE - hdr->sections[0].offset;
            if (uncompressed_size > 0) {
                i += uncompressed_size;
                DBG("ncz_decompress:   section[%d] firstSection skip=%lld bytes",
                    si, (long long)uncompressed_size);
            }
        }

        DBG("ncz_decompress:   section[%d] offset=0x%llX size=0x%llX crypto=%lld useCrypto=%d start_i=0x%llX",
            si, (unsigned long long)s->offset, (unsigned long long)s->size,
            (long long)s->crypto_type, use_crypto, (unsigned long long)i);

        while (i < end && ret == NCZ_OK) {
            if (cancel && atomic_load(cancel)) { ret = NCZ_ERR_CANCELLED; break; }

            /* Python: crypto.seek(i) — set CTR counter to absolute NCA offset */
            if (use_crypto) {
                aes_ctr_set_offset(&aes, s->crypto_counter, (uint64_t)i);
            }

            /* Python: chunkSz = 0x10000 if end - i > 0x10000 else end - i */
            int64_t remain = end - i;
            size_t chunk_sz = remain > 0x10000 ? 0x10000 : (size_t)remain;

            /* Acquire a buffer: an async-writer buffer when writing, else the
             * local scratch buffer. */
            uint8_t *buf;
            if (aw) {
                buf = aw_get_buffer(aw);
                if (!buf) { ret = NCZ_ERR_IO; break; }  /* writer hit I/O error */
            } else {
                buf = chunk_buf;
            }

            /* Read decompressed data from appropriate reader */
            size_t got = 0;
            if (use_block) {
                ret = block_reader_read(&block_reader, buf, chunk_sz, &got);
            } else {
                ret = solid_reader_read(&solid_reader, buf, chunk_sz, &got);
            }
            if (ret != NCZ_OK) { if (aw) aw_return_unused(aw, buf); break; }

            /* Python: if not len(inputChunk): break */
            if (got == 0) { if (aw) aw_return_unused(aw, buf); break; }

            /* Python: if useCrypto: inputChunk = crypto.encrypt(inputChunk) */
            if (use_crypto) {
                aes_ctr_crypt(&aes, buf, buf, got);
            }

            /* Hand off to the async writer (writes + hashes in the background),
             * or hash inline when there is no output stream. */
            if (aw) {
                aw_submit(aw, buf, got);
            } else if (sha_ctx) {
                sha256_update(sha_ctx, buf, got);
            }

            i          += (int64_t)got;
            bytes_done += (int64_t)got;

            /* Throttled progress callback (~20/sec) */
            if (cb) {
                struct timespec now;
                clock_gettime(CLOCK_MONOTONIC, &now);
                long ms = (now.tv_sec - last_cb_time.tv_sec) * 1000
                        + (now.tv_nsec - last_cb_time.tv_nsec) / 1000000;
                if (ms >= 50) {
                    cb(bytes_done + (int64_t)NCA_HEADER_SIZE, total_est, cb_ctx);
                    last_cb_time = now;
                }
            }
        }
    }

    /* Drain and join the writer (also flushes remaining buffers + hashing). */
    if (aw) {
        if (aw_finish(aw) != 0 && ret == NCZ_OK)
            ret = NCZ_ERR_IO;
    }

    free(chunk_buf);
    if (use_block) block_reader_free(&block_reader);
    else solid_reader_free(&solid_reader);

    if (bytes_done_out) *bytes_done_out = bytes_done;

    DBG("ncz_decompress: done  bytes=%lld  ret=%d", (long long)bytes_done, ret);
    return ret;
}
