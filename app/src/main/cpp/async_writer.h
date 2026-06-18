#pragma once
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include "sha256.h"

/*
 * Async output writer: a background thread that fwrites (and optionally
 * SHA-256 hashes) buffers handed to it, so the producer thread can keep
 * decompressing/decrypting the next chunk while disk I/O happens in parallel.
 *
 * Usage:
 *   AsyncWriter *aw = aw_start(out_fp, sha_ctx, buf_size, num_buffers);
 *   for each chunk:
 *       uint8_t *b = aw_get_buffer(aw);   // NULL => writer hit an I/O error
 *       ... fill b with up to buf_size bytes ...
 *       aw_submit(aw, b, len);            // hands ownership to the writer
 *   int rc = aw_finish(aw);               // drains queue, joins, frees
 *
 * All writes happen in submission order, so the output stays sequential and
 * the SHA-256 context sees the bytes in order.
 */
typedef struct AsyncWriter AsyncWriter;

/* Returns NULL on allocation/thread-start failure. */
AsyncWriter *aw_start(FILE *out_fp, Sha256Ctx *sha_ctx,
                      size_t buf_size, int num_buffers);

/* Blocks until a free buffer is available. Returns NULL if the writer has
 * already failed with an I/O error. */
uint8_t *aw_get_buffer(AsyncWriter *aw);

/* Enqueue a filled buffer (len <= buf_size) for writing+hashing. */
void aw_submit(AsyncWriter *aw, uint8_t *buf, size_t len);

/* Return a buffer obtained from aw_get_buffer without writing it. */
void aw_return_unused(AsyncWriter *aw, uint8_t *buf);

/* True if the writer thread has encountered an I/O error. */
int aw_failed(AsyncWriter *aw);

/* Signal end-of-input, drain the queue, join the thread and free everything.
 * Returns 0 on success, non-zero if any write failed. */
int aw_finish(AsyncWriter *aw);
