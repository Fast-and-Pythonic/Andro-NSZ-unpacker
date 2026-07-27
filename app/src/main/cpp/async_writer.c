/* sync_file_range() is a GNU/bionic extension gated behind _GNU_SOURCE, which
 * must be defined before any system header (incl. those pulled in below). */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "async_writer.h"

#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#if defined(__linux__)
#include <fcntl.h>
#endif

/*
 * Page-cache relief interval. With several files writing in parallel, tens of
 * MB/s each of dirty pages can pile up and trigger writeback stalls / kswapd
 * pressure. Every AW_DROP_INTERVAL bytes the writer kicks off async writeback
 * for the just-written region and drops from the page cache the region it kicked
 * off a round earlier (never the most recent one, which may not be on disk yet).
 * All best-effort: FUSE-backed outputs return EINVAL, which we ignore.
 *
 * NB: **load-bearing, do not remove.** It shipped labelled "experimental, probably
 * revert"; measurement then showed parallel writes *collapse* without it (451 vs
 * 1013 MB/s at 4 writers — architecture.md A15). The original worry, that DONTNEED
 * would penalise a verification pass re-reading the output, is moot: that pass is
 * gone (A12).
 *
 * NB: SYNC_FILE_RANGE_WRITE only *queues* writeback; it never waits, so data is not
 * on storage when the last fwrite returns. That is what this path wants — but any
 * benchmark built on it must force the data out before stopping its clock, or it
 * times the page cache instead of the flash (measured: 1.8 GB/s against 897 MB/s
 * durable). A synthetic write bench that got this wrong lived here until 2026-07-27;
 * the surviving test measures whole real conversions, where the kernel's dirty-page
 * throttling forces the average back down to the true rate.
 */
#define AW_DROP_INTERVAL (64 * 1024 * 1024)

struct AsyncWriter {
    FILE      *out;
    Sha256Ctx *sha;          /* nullable */
    size_t     buf_size;
    int        n;

    uint8_t  **bufs;         /* n buffers of buf_size */

    /* job queue (ring of buffer indices + lengths) */
    int       *job_idx;
    size_t    *job_len;
    int        job_head, job_tail, job_count;

    /* free list (stack of buffer indices) */
    int       *free_idx;
    int        free_count;

    pthread_mutex_t mtx;
    pthread_cond_t  can_produce;   /* a free buffer became available */
    pthread_cond_t  can_consume;   /* a job became available (or stop) */

    int        stop;               /* producer signalled end of input */
    int        io_error;
    pthread_t  thread;
    int        thread_ok;

    /* Page-cache relief (writer thread only; see AW_DROP_INTERVAL). */
    int        out_fd;             /* fileno(out), or -1 if unavailable */
    long long  io_written;         /* total bytes fwritten so far */
    long long  io_synced;          /* bytes already handed to writeback */
    long long  io_dropped;         /* bytes already dropped from the cache */
};

/* Kick off writeback for freshly written data and drop the cache behind it.
 * Compile with -DAW_NO_CACHE_RELIEF to disable (benchmark A/B for Step 6). */
static void aw_relieve_cache(AsyncWriter *aw)
{
#if defined(__linux__) && !defined(AW_NO_CACHE_RELIEF)
    if (aw->out_fd < 0) return;
    while (aw->io_written - aw->io_synced >= AW_DROP_INTERVAL) {
        sync_file_range(aw->out_fd, (off_t)aw->io_synced, AW_DROP_INTERVAL,
                        SYNC_FILE_RANGE_WRITE);
        if (aw->io_synced > aw->io_dropped) {
            posix_fadvise(aw->out_fd, (off_t)aw->io_dropped,
                          (off_t)(aw->io_synced - aw->io_dropped),
                          POSIX_FADV_DONTNEED);
            aw->io_dropped = aw->io_synced;
        }
        aw->io_synced += AW_DROP_INTERVAL;
    }
#else
    (void)aw;
#endif
}

/* Map a buffer pointer back to its index (n is small). */
static int aw_index_of(AsyncWriter *aw, const uint8_t *buf)
{
    for (int i = 0; i < aw->n; i++)
        if (aw->bufs[i] == buf) return i;
    return -1;
}

static void *aw_thread_main(void *arg)
{
    AsyncWriter *aw = (AsyncWriter *)arg;

    for (;;) {
        pthread_mutex_lock(&aw->mtx);
        while (aw->job_count == 0 && !aw->stop)
            pthread_cond_wait(&aw->can_consume, &aw->mtx);

        if (aw->job_count == 0 && aw->stop) {
            pthread_mutex_unlock(&aw->mtx);
            break;
        }

        int    idx = aw->job_idx[aw->job_head];
        size_t len = aw->job_len[aw->job_head];
        aw->job_head = (aw->job_head + 1) % aw->n;
        aw->job_count--;
        pthread_mutex_unlock(&aw->mtx);

        /* Perform I/O outside the lock. */
        if (!aw->io_error && len > 0) {
            if (fwrite(aw->bufs[idx], 1, len, aw->out) != len) {
                aw->io_error = 1;
            } else {
                if (aw->sha) sha256_update(aw->sha, aw->bufs[idx], len);
                aw->io_written += (long long)len;
                aw_relieve_cache(aw);
            }
        }

        pthread_mutex_lock(&aw->mtx);
        aw->free_idx[aw->free_count++] = idx;
        pthread_cond_signal(&aw->can_produce);
        pthread_mutex_unlock(&aw->mtx);
    }
    return NULL;
}

AsyncWriter *aw_start(FILE *out_fp, Sha256Ctx *sha_ctx,
                      size_t buf_size, int num_buffers)
{
    if (num_buffers < 2) num_buffers = 2;

    AsyncWriter *aw = calloc(1, sizeof(*aw));
    if (!aw) return NULL;

    aw->out      = out_fp;
    aw->sha      = sha_ctx;
    aw->buf_size = buf_size;
    aw->n        = num_buffers;
    aw->out_fd   = out_fp ? fileno(out_fp) : -1;

    aw->bufs     = calloc(num_buffers, sizeof(uint8_t *));
    aw->job_idx  = calloc(num_buffers, sizeof(int));
    aw->job_len  = calloc(num_buffers, sizeof(size_t));
    aw->free_idx = calloc(num_buffers, sizeof(int));
    if (!aw->bufs || !aw->job_idx || !aw->job_len || !aw->free_idx)
        goto fail;

    for (int i = 0; i < num_buffers; i++) {
        aw->bufs[i] = malloc(buf_size);
        if (!aw->bufs[i]) goto fail;
        aw->free_idx[i] = i;
    }
    aw->free_count = num_buffers;

    pthread_mutex_init(&aw->mtx, NULL);
    pthread_cond_init(&aw->can_produce, NULL);
    pthread_cond_init(&aw->can_consume, NULL);

    if (pthread_create(&aw->thread, NULL, aw_thread_main, aw) != 0) {
        pthread_mutex_destroy(&aw->mtx);
        pthread_cond_destroy(&aw->can_produce);
        pthread_cond_destroy(&aw->can_consume);
        goto fail;
    }
    aw->thread_ok = 1;
    return aw;

fail:
    if (aw->bufs) {
        for (int i = 0; i < num_buffers; i++) free(aw->bufs[i]);
        free(aw->bufs);
    }
    free(aw->job_idx);
    free(aw->job_len);
    free(aw->free_idx);
    free(aw);
    return NULL;
}

uint8_t *aw_get_buffer(AsyncWriter *aw)
{
    pthread_mutex_lock(&aw->mtx);
    while (aw->free_count == 0 && !aw->io_error)
        pthread_cond_wait(&aw->can_produce, &aw->mtx);

    if (aw->io_error) {
        pthread_mutex_unlock(&aw->mtx);
        return NULL;
    }

    int idx = aw->free_idx[--aw->free_count];
    pthread_mutex_unlock(&aw->mtx);
    return aw->bufs[idx];
}

void aw_submit(AsyncWriter *aw, uint8_t *buf, size_t len)
{
    int idx = aw_index_of(aw, buf);
    if (idx < 0) return;

    pthread_mutex_lock(&aw->mtx);
    aw->job_idx[aw->job_tail] = idx;
    aw->job_len[aw->job_tail] = len;
    aw->job_tail = (aw->job_tail + 1) % aw->n;
    aw->job_count++;
    pthread_cond_signal(&aw->can_consume);
    pthread_mutex_unlock(&aw->mtx);
}

void aw_return_unused(AsyncWriter *aw, uint8_t *buf)
{
    int idx = aw_index_of(aw, buf);
    if (idx < 0) return;

    pthread_mutex_lock(&aw->mtx);
    aw->free_idx[aw->free_count++] = idx;
    pthread_cond_signal(&aw->can_produce);
    pthread_mutex_unlock(&aw->mtx);
}

int aw_failed(AsyncWriter *aw)
{
    pthread_mutex_lock(&aw->mtx);
    int e = aw->io_error;
    pthread_mutex_unlock(&aw->mtx);
    return e;
}

int aw_finish(AsyncWriter *aw)
{
    if (!aw) return 0;

    if (aw->thread_ok) {
        pthread_mutex_lock(&aw->mtx);
        aw->stop = 1;
        pthread_cond_broadcast(&aw->can_consume);
        pthread_mutex_unlock(&aw->mtx);
        pthread_join(aw->thread, NULL);

        pthread_mutex_destroy(&aw->mtx);
        pthread_cond_destroy(&aw->can_produce);
        pthread_cond_destroy(&aw->can_consume);
    }

    int err = aw->io_error;

    for (int i = 0; i < aw->n; i++) free(aw->bufs[i]);
    free(aw->bufs);
    free(aw->job_idx);
    free(aw->job_len);
    free(aw->free_idx);
    free(aw);
    return err;
}
