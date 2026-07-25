#include "nsz_debug.h"
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
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
/*
 * Open/close are reference counted. The log has a single fixed path, so when it
 * was opened per converted file every file of a parallel batch truncated it and
 * the first file to finish closed it for all the others — they then reached only
 * logcat. Callers now open it once per job, and the refcount makes a stray nested
 * open harmless: it can neither truncate nor prematurely close a log another
 * conversion is still writing to.
 */
static int              s_refs       = 0;
static char             s_path[1024] = {0};
/* Guards the shared log file so concurrent conversions can't race on s_fp. */
static pthread_mutex_t  s_mtx        = PTHREAD_MUTEX_INITIALIZER;

/* ── open / close ─────────────────────────────────────────────────── */

/* Closes the log regardless of the refcount. Caller must hold s_mtx. */
static void dbg_close_locked(void)
{
    if (!s_fp) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long ms = (now.tv_sec  - s_start_time.tv_sec)  * 1000
            + (now.tv_nsec - s_start_time.tv_nsec) / 1000000;
    fprintf(s_fp, "\n[%6ld ms] === End of log ===\n", ms);
    fflush(s_fp);
    fclose(s_fp);
    s_fp      = NULL;
    s_refs    = 0;
    s_path[0] = '\0';
}

void dbg_open(const char *path)
{
    pthread_mutex_lock(&s_mtx);

    /* dbg_open(NULL) keeps its historical meaning: shut the session down
     * whatever the refcount — this is what nativeSetDebugLog(null) maps to. */
    if (!path || !path[0]) {
        dbg_close_locked();
        pthread_mutex_unlock(&s_mtx);
        return;
    }

    if (s_fp) {
        /* Already open: take a reference and leave the file alone. */
        s_refs++;
        if (strcmp(s_path, path) != 0) {
            fprintf(s_fp, "[note] nested dbg_open('%s') ignored; '%s' stays open\n",
                    path, s_path);
            fflush(s_fp);
        }
        pthread_mutex_unlock(&s_mtx);
        return;
    }

    s_fp = fopen(path, "w");
    if (!s_fp) {
        pthread_mutex_unlock(&s_mtx);
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "dbg_open: cannot create log at '%s'", path);
        return;
    }

    s_refs = 1;
    snprintf(s_path, sizeof s_path, "%s", path);
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
    if (s_refs > 1) {
        /* Another holder is still logging — just drop this reference. */
        s_refs--;
        pthread_mutex_unlock(&s_mtx);
        return;
    }
    dbg_close_locked();
    pthread_mutex_unlock(&s_mtx);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "debug log closed");
}

/* ── dbg_log ──────────────────────────────────────────────────────── */

void dbg_log(const char *fmt, ...)
{
    va_list ap_log;
    va_start(ap_log, fmt);
    __android_log_vprint(ANDROID_LOG_DEBUG, LOG_TAG, fmt, ap_log);
    va_end(ap_log);

    /* s_start_time is read under the same lock that writes it in dbg_open. */
    pthread_mutex_lock(&s_mtx);
    if (s_fp) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long ms = (now.tv_sec  - s_start_time.tv_sec)  * 1000
                + (now.tv_nsec - s_start_time.tv_nsec) / 1000000;
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

    /* s_start_time is read under the same lock that writes it in dbg_open. */
    pthread_mutex_lock(&s_mtx);
    if (s_fp) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long ms = (now.tv_sec  - s_start_time.tv_sec)  * 1000
                + (now.tv_nsec - s_start_time.tv_nsec) / 1000000;
        fprintf(s_fp, "[%6ld ms] %s (%zu bytes): %s%s\n",
                ms, label, len, hex, len > 32 ? "..." : "");
        fflush(s_fp);
    }
    pthread_mutex_unlock(&s_mtx);
}
