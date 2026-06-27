#include "nsz_debug.h"
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>
#if defined(__ANDROID__)
#include <android/log.h>
#else
#define ANDROID_LOG_WARN  0
#define ANDROID_LOG_INFO  0
#define ANDROID_LOG_DEBUG 0

static int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
   (void)prio;
   fprintf(stderr, "%s: ", tag);
   va_list ap;
   va_start(ap, fmt);
   int written = vfprintf(stderr, fmt, ap);
   va_end(ap);
   fputc('\n', stderr);
   return written;
}

static int __android_log_vprint(int prio, const char *tag, const char *fmt, va_list ap)
{
   (void)prio;
   fprintf(stderr, "%s: ", tag);
   int written = vfprintf(stderr, fmt, ap);
   fputc('\n', stderr);
   return written;
}
#endif

#define LOG_TAG "AndroNSZ"

static FILE            *s_fp         = NULL;
static struct timespec  s_start_time = {0, 0};
/* Guards the shared log file so concurrent conversions can't race on s_fp. */
static pthread_mutex_t  s_mtx        = PTHREAD_MUTEX_INITIALIZER;

/* ── open / close ─────────────────────────────────────────────────── */

void dbg_open(const char *path)
{
    pthread_mutex_lock(&s_mtx);
    if (s_fp) {
        fprintf(s_fp, "\n=== session closed (new session started) ===\n");
        fclose(s_fp);
        s_fp = NULL;
    }
    if (!path || !path[0]) { pthread_mutex_unlock(&s_mtx); return; }

    s_fp = fopen(path, "w");
    if (!s_fp) {
        pthread_mutex_unlock(&s_mtx);
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "dbg_open: cannot create log at '%s'", path);
        return;
    }

    clock_gettime(CLOCK_MONOTONIC, &s_start_time);

    fprintf(s_fp,
            "=== AndroNSZ Debug Log ===\n"
            "Log path : %s\n"
            "Format   : [elapsed ms] message\n\n",
            path);
    fflush(s_fp);
    pthread_mutex_unlock(&s_mtx);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "debug log opened: %s", path);
}

void dbg_close(void)
{
    pthread_mutex_lock(&s_mtx);
    if (!s_fp) { pthread_mutex_unlock(&s_mtx); return; }
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long ms = (now.tv_sec  - s_start_time.tv_sec)  * 1000
            + (now.tv_nsec - s_start_time.tv_nsec) / 1000000;
    fprintf(s_fp, "\n[%6ld ms] === End of log ===\n", ms);
    fflush(s_fp);
    fclose(s_fp);
    s_fp = NULL;
    pthread_mutex_unlock(&s_mtx);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "debug log closed");
}

/* ── dbg_log ──────────────────────────────────────────────────────── */

void dbg_log(const char *fmt, ...)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long ms = (now.tv_sec  - s_start_time.tv_sec)  * 1000
            + (now.tv_nsec - s_start_time.tv_nsec) / 1000000;

    va_list ap_log;
    va_start(ap_log, fmt);
    __android_log_vprint(ANDROID_LOG_DEBUG, LOG_TAG, fmt, ap_log);
    va_end(ap_log);

    pthread_mutex_lock(&s_mtx);
    if (s_fp) {
        fprintf(s_fp, "[%6ld ms] ", ms);
        va_list ap;
        va_start(ap, fmt);
        vfprintf(s_fp, fmt, ap);
        va_end(ap);
        fprintf(s_fp, "\n");
        fflush(s_fp);
    }
    pthread_mutex_unlock(&s_mtx);
}

/* ── dbg_hex ──────────────────────────────────────────────────────── */

void dbg_hex(const char *label, const void *buf, size_t len)
{
    if (!buf || len == 0) return;

    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long ms = (now.tv_sec  - s_start_time.tv_sec)  * 1000
            + (now.tv_nsec - s_start_time.tv_nsec) / 1000000;

    const uint8_t *b    = (const uint8_t *)buf;
    size_t         show = len > 32 ? 32 : len;

    char hex[97];
    size_t pos = 0;
    for (size_t i = 0; i < show; i++) {
        hex[pos++] = "0123456789ABCDEF"[b[i] >> 4];
        hex[pos++] = "0123456789ABCDEF"[b[i] & 0xF];
        if ((i & 3) == 3 && i + 1 < show) hex[pos++] = ' ';
    }
    hex[pos] = '\0';
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
                        "%s (%zu B): %s%s", label, len,
                        hex, len > 32 ? "..." : "");

    pthread_mutex_lock(&s_mtx);
    if (s_fp) {
        fprintf(s_fp, "[%6ld ms] %s (%zu bytes): %s%s\n",
                ms, label, len, hex, len > 32 ? "..." : "");
        fflush(s_fp);
    }
    pthread_mutex_unlock(&s_mtx);
}
