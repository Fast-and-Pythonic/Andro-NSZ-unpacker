#pragma once
#include <stddef.h>

/*
 * nsz_debug — file-based debug log for AndroNSZ.
 *
 * Usage:
 *   dbg_open("/path/to/debug.log");
 *   DBG("some value: %d", x);
 *   DBGHEX("key", buf, 16);
 *   dbg_close();
 *
 * Output goes to the log file AND to Android logcat (tag AndroNSZ).
 *
 * dbg_open/dbg_close are **reference counted** and the log is truncated only by
 * the outermost open, so concurrent conversions sharing one log file cannot
 * truncate or close it under each other. Open it once per job, not per file.
 * dbg_open(NULL) force-closes regardless of the refcount.
 */

void dbg_open(const char *path);
void dbg_close(void);
void dbg_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void dbg_hex(const char *label, const void *buf, size_t len);

#define DBG(...)        dbg_log(__VA_ARGS__)
#define DBGHEX(l, b, n) dbg_hex((l), (b), (n))
