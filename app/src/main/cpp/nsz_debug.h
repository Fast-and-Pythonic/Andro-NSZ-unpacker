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
 */

void dbg_open(const char *path);
void dbg_close(void);
void dbg_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void dbg_hex(const char *label, const void *buf, size_t len);

#define DBG(...)        dbg_log(__VA_ARGS__)
#define DBGHEX(l, b, n) dbg_hex((l), (b), (n))
