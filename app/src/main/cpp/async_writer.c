#include "async_writer.h"

#include <stdlib.h>
#include <string.h>
#include <pthread.h>

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
};

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
            } else if (aw->sha) {
                sha256_update(aw->sha, aw->bufs[idx], len);
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
