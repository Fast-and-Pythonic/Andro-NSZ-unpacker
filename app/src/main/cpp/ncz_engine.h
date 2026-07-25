#pragma once
#include "nsz_types.h"

__attribute__((visibility("default"))) __attribute__((used))
int ncz_convert_nsz_to_nsp(
    const char   *input_path,
    const char   *output_path,
    NczProgressCb progress_cb,
    void         *cb_ctx,
    NczStatusCb   status_cb,
    void         *status_ctx
);

__attribute__((visibility("default"))) __attribute__((used))
int ncz_convert_xcz_to_xci(
    const char   *input_path,
    const char   *output_path,
    NczProgressCb progress_cb,
    void         *cb_ctx,
    NczStatusCb   status_cb,
    void         *status_ctx
);

__attribute__((visibility("default"))) __attribute__((used))
const char *ncz_error_string(int error_code);

__attribute__((visibility("default"))) __attribute__((used))
void ncz_request_cancel(void);

/* Cancellation uses an epoch counter instead of a single shared flag, so that
 * concurrent conversions don't interfere: ncz_request_cancel() bumps the epoch,
 * cancelling every conversion whose snapshot no longer matches. Each conversion
 * snapshots the epoch at entry (no reset needed) and polls ncz_cancelled(). */
int ncz_cancelled(int start_epoch);
