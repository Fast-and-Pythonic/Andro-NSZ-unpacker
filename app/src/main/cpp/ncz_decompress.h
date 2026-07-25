#pragma once
#include "ncz.h"
#include "nsz_types.h"
#include "sha256.h"
#include <stdio.h>

/*
 * NCZ decompression engine — mirrors Python NszDecompressor.py:175-210.
 *
 * Key design: iterates sections sequentially (not per-offset lookup),
 * applies AES-CTR only for crypto_type 3 and 4 (not type 1).
 *
 * Supports both solid (streaming zstd) and block (per-block zstd) modes.
 */

/* Decompress a single NCZ file.
 *
 * in_fp:    input file, positioned right after NCZ header (at compressed data start)
 * out_fp:   output file, positioned where decompressed body should be written
 * hdr:      parsed NCZ header (with sections, optional block header, FakeSection)
 * sha_ctx:  if non-NULL, all output bytes are fed into this hash context
 * start_epoch: cancel-epoch snapshot from the caller; the loop aborts once
 *           ncz_cancelled(start_epoch) becomes true (see ncz_engine.h)
 * cb:       progress callback (may be NULL)
 * cb_ctx:   opaque user data for callback
 * total_est: total estimated output size (for progress reporting)
 *
 * Returns NCZ_OK on success, or an NCZ_ERR_* code.
 */
int ncz_decompress(FILE *in_fp,
                   FILE *out_fp,
                   const NczHeader *hdr,
                   Sha256Ctx *sha_ctx,
                   int start_epoch,
                   NczProgressCb cb,
                   void *cb_ctx,
                   int64_t total_est,
                   int64_t *bytes_done_out);
