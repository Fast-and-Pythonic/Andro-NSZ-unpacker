#pragma once
#include <stdint.h>
#include <stddef.h>

/* ── Constants ─────────────────────────────────────────────────────────────── */

#define NCA_HEADER_SIZE   0x4000   /* 16 KiB uncompressed NCA header */
#define NCZ_HEADER_OFFSET 0x4000   /* NCZ metadata starts right after NCA hdr */

/* ── Error codes ───────────────────────────────────────────────────────────── */

#define NCZ_OK                  (0)
#define NCZ_ERR_OPEN_INPUT     (-1)
#define NCZ_ERR_OPEN_OUTPUT    (-2)
#define NCZ_ERR_INVALID_PFS0   (-3)
#define NCZ_ERR_INVALID_NCZ    (-4)
#define NCZ_ERR_ZSTD           (-5)
#define NCZ_ERR_IO             (-6)
#define NCZ_ERR_OOM            (-7)
#define NCZ_ERR_CANCELLED      (-8)
#define NCZ_ERR_HASH_MISMATCH  (-9)

/* ── Callback typedefs ─────────────────────────────────────────────────────── */

/* Progress callback — called from conversion thread.
   done_bytes:  bytes of decompressed output written so far.
   total_bytes: estimated total decompressed output size. */
typedef void (*NczProgressCb)(int64_t done_bytes, int64_t total_bytes,
                               void *user_data);

/* Status callback — called for structured log messages.
   tag: "OPEN", "ADDING", "EXISTS", "NCA_HASH", "VERIFIED", "ERROR", "INFO"
   msg: human-readable message text. */
typedef void (*NczStatusCb)(const char *tag, const char *msg, void *user_data);
