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

__attribute__((visibility("default"))) __attribute__((used))
void ncz_reset_cancel(void);
