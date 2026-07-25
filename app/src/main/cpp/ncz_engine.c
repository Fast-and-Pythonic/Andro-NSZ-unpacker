/*
 * NCZ engine — top-level NSZ to NSP conversion orchestrator.
 * Uses section-sequential decompression from ncz_decompress.c.
 */
#include "ncz_engine.h"
#include "pfs0.h"
#include "hfs0.h"
#include "ncz.h"
#include "ncz_decompress.h"
#include "sha256.h"
#include "nca_cnmt.h"
#include "nsz_debug.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>
#if defined(__linux__)
#include <fcntl.h>
#endif

/* Hint sequential access on an input stream so the kernel reads ahead
 * aggressively. Best-effort: a no-op on FUSE-backed fds and non-Linux hosts. */
static void advise_sequential(FILE *fp)
{
#if defined(__linux__)
    if (fp) posix_fadvise(fileno(fp), 0, 0, POSIX_FADV_SEQUENTIAL);
#else
    (void)fp;
#endif
}

/* ---------- cancellation epoch ----------
 * A monotonically increasing counter rather than a single shared flag: each
 * conversion snapshots it at entry and is cancelled once the global epoch moves
 * past that snapshot. This lets several conversions run concurrently without one
 * starting file resetting another's cancel state (the old single-flag bug). */
static atomic_int g_cancel_epoch = ATOMIC_VAR_INIT(0);

void ncz_request_cancel(void) { atomic_fetch_add(&g_cancel_epoch, 1); }
int  ncz_cancelled(int start_epoch) { return atomic_load(&g_cancel_epoch) != start_epoch; }

/* ---------- error strings ---------- */
const char *ncz_error_string(int code)
{
    switch (code) {
        case NCZ_OK:                return "OK";
        case NCZ_ERR_OPEN_INPUT:    return "Cannot open input file";
        case NCZ_ERR_OPEN_OUTPUT:   return "Cannot open output file";
        case NCZ_ERR_INVALID_PFS0:  return "Invalid PFS0 container";
        case NCZ_ERR_INVALID_NCZ:   return "Invalid NCZ header";
        case NCZ_ERR_ZSTD:          return "zStandard decompression error";
        case NCZ_ERR_IO:            return "I/O error";
        case NCZ_ERR_OOM:           return "Out of memory";
        case NCZ_ERR_CANCELLED:     return "Cancelled by user";
        case NCZ_ERR_HASH_MISMATCH: return "NCA hash mismatch";
        default:                    return "Unknown error";
    }
}

/* ---------- I/O buffer size ---------- */
#define IO_BUF_SIZE (4 * 1024 * 1024)

/* Output XCI always places the root HFS0 at 0xF000, with 0x200..0xF000 zeroed —
 * mirroring nsz's XciStream (seek 0xF000 + verbatim 0x200 header). */
#define XCI_ROOT_HFS0_OFFSET 0xF000u

/* Parse an "fd:N" or "/proc/self/fd/N" output path into its fd number, else -1. */
static int output_fd_number(const char *path)
{
    const char *proc_prefix = "/proc/self/fd/";
    if (strncmp(path, "fd:", 3) == 0) return atoi(path + 3);
    if (strncmp(path, proc_prefix, strlen(proc_prefix)) == 0) {
        return atoi(path + strlen(proc_prefix));
    }
    return -1;
}

/*
 * Open an input stream. A path of the form "fd:N" reads an already-open file
 * descriptor directly (via dup + fdopen), bypassing a path re-open. This is
 * required for SAF/FUSE-backed sources whose /proc/self/fd path cannot be
 * re-opened by native code. Any other path is opened normally.
 */
static FILE *open_input_file(const char *path)
{
    if (strncmp(path, "fd:", 3) == 0) {
        int fd = atoi(path + 3);
        int dupfd = dup(fd);
        if (dupfd < 0) return NULL;
        FILE *fp = fdopen(dupfd, "rb");
        if (!fp) { close(dupfd); return NULL; }
        return fp;
    }
    return fopen(path, "rb");
}

/*
 * Open an output stream. Mirrors open_input_file for "fd:N" / "/proc/self/fd/N"
 * targets: dup the fd and write through it (truncating first, since the existing
 * file may be larger than the new output). Any other path is opened normally.
 */
static FILE *open_output_file(const char *path)
{
    int fd = output_fd_number(path);
    if (fd >= 0) {
        int dupfd = dup(fd);
        if (dupfd < 0) return NULL;
        if (ftruncate(dupfd, 0) != 0) {
            DBG("open_output_file: ftruncate failed for fd:%d", fd);
        }
        if (lseek(dupfd, 0, SEEK_SET) < 0) {
            DBG("open_output_file: lseek failed for fd:%d", fd);
        }
        FILE *fp = fdopen(dupfd, "wb");
        if (!fp) { close(dupfd); return NULL; }
        return fp;
    }
    return fopen(path, "wb");
}

/* ---------- helpers ---------- */

/* Returns 1 if filename looks like a Nintendo content id: 32 hex chars before extension. */
static int is_content_id_named(const char *name)
{
    const char *dot = strrchr(name, '.');
    if (!dot || (size_t)(dot - name) != 32) return 0;
    for (const char *p = name; p < dot; p++) {
        char c = *p;
        int is_hex = (c >= '0' && c <= '9') ||
                     (c >= 'a' && c <= 'f') ||
                     (c >= 'A' && c <= 'F');
        if (!is_hex) return 0;
    }
    return 1;
}

/* Returns 1 if filename ends with .nca or .ncz */
static int is_nca_file(const char *name)
{
    const char *ext = strrchr(name, '.');
    if (!ext) return 0;
    return (strcmp(ext, ".nca") == 0 || strcmp(ext, ".ncz") == 0);
}

/* An output path we must not remove() on failure: the caller owns the fd, and
 * /dev/null is a verify-only sink. */
static int is_discard_output(const char *path)
{
    return path && (strcmp(path, "/dev/null") == 0 || output_fd_number(path) >= 0);
}

static void bytes_to_hex(const uint8_t *bytes, int n, char *out)
{
    static const char H[] = "0123456789abcdef";
    for (int i = 0; i < n; i++) {
        out[i*2    ] = H[bytes[i] >> 4];
        out[i*2 + 1] = H[bytes[i] & 0xF];
    }
    out[n*2] = '\0';
}

/* Returns 1 if filename ends with .cnmt.nca / .cnmt.ncz (the META NCA). */
static int is_cnmt_named(const char *name)
{
    size_t n = strlen(name);
    if (n < 9) return 0;
    return strcasecmp(name + n - 9, ".cnmt.nca") == 0 ||
           strcasecmp(name + n - 9, ".cnmt.ncz") == 0;
}

/*
 * Whether this file's SHA-256 should be computed for verification.
 * - verification off        → never (this is what makes the toggle free);
 * - CNMT hash set available  → every NCA except the META NCA itself;
 * - fallback (no set)        → only content-id-named files (legacy check).
 */
static int should_hash(const char *name, const CnmtHashSet *set)
{
    if (!nca_verify_enabled()) return 0;
    if (set && set->count > 0) {
        return is_nca_file(name) && !is_cnmt_named(name);
    }
    return is_content_id_named(name);
}

/*
 * Report the result of an NCA hash. With a CNMT set the digest is checked for
 * membership (authoritative); without one it falls back to comparing against
 * the filename content-id (partial — marked "(by name)"). A mismatch is
 * non-fatal in both cases: WARN, output kept.
 */
static void report_hash_result(const char *name, const uint8_t digest[32],
                               const CnmtHashSet *set,
                               NczStatusCb status_cb, void *status_ctx)
{
    char hex[65];
    bytes_to_hex(digest, 32, hex);

#define EMIT(tag, ...) \
    do { \
        if (status_cb) { \
            char _sb_[512]; \
            snprintf(_sb_, sizeof(_sb_), __VA_ARGS__); \
            status_cb(tag, _sb_, status_ctx); \
        } \
    } while (0)

    EMIT("NCA_HASH", "   %s", hex);

    if (set && set->count > 0) {
        if (cnmt_hashset_contains(set, digest)) {
            EMIT("VERIFIED", "   %s", name);
        } else {
            DBG("CNMT HASH MISMATCH '%s': %s not in set", name, hex);
            EMIT("WARN", "   hash mismatch (output kept): %s", name);
            /* Machine-readable mismatch tag: the Kotlin/CLI layer keys the
             * per-file verify status off VERIFIED/CORRUPTED, so no separate
             * output re-read pass is needed. Non-fatal (output kept). */
            EMIT("CORRUPTED", "   %s", name);
        }
        return;
    }

    /* Fallback: compare against the content-id in the filename. */
    char base[33] = {0};
    const char *dot = strrchr(name, '.');
    if (dot) {
        size_t blen = (size_t)(dot - name);
        if (blen > 32) blen = 32;
        memcpy(base, name, blen);
    }
    if (strncasecmp(hex, base, 32) == 0) {
        EMIT("VERIFIED", "   %s (by name)", name);
    } else {
        DBG("HASH MISMATCH '%s': expected=%s got=%s", name, base, hex);
        EMIT("WARN", "   hash mismatch (output kept): %s", name);
        EMIT("CORRUPTED", "   %s", name);
    }

#undef EMIT
}

/* Copy helper with optional SHA-256 feed */
static int copy_bytes(FILE *in, FILE *out, uint64_t size, Sha256Ctx *sha_ctx,
                      int start_epoch)
{
    /* Only allocate as much as we actually copy — callers pass small sizes
     * (0x4000 NCA headers) far more often than a full IO_BUF_SIZE run. */
    size_t buf_size = size < IO_BUF_SIZE ? (size_t)size : IO_BUF_SIZE;
    if (buf_size == 0) return NCZ_OK;
    uint8_t *buf = malloc(buf_size);
    if (!buf) return NCZ_ERR_OOM;

    uint64_t remaining = size;
    while (remaining > 0) {
        if (ncz_cancelled(start_epoch)) { free(buf); return NCZ_ERR_CANCELLED; }
        size_t chunk = remaining < buf_size ? (size_t)remaining : buf_size;
        if (fread(buf, 1, chunk, in) != chunk) { free(buf); return NCZ_ERR_IO; }
        if (sha_ctx) sha256_update(sha_ctx, buf, chunk);
        if (fwrite(buf, 1, chunk, out) != chunk) { free(buf); return NCZ_ERR_IO; }
        remaining -= chunk;
    }
    free(buf);
    return NCZ_OK;
}

/* ---------- Main conversion ---------- */
int ncz_convert_nsz_to_nsp(const char *input_path,
                            const char *output_path,
                            NczProgressCb progress_cb,
                            void *cb_ctx,
                            NczStatusCb status_cb,
                            void *status_ctx)
{
    /* Snapshot the cancel epoch; this conversion aborts once it moves on. */
    int start_epoch = atomic_load(&g_cancel_epoch);

#define EMIT(tag, ...) \
    do { \
        if (status_cb) { \
            char _sb_[512]; \
            snprintf(_sb_, sizeof(_sb_), __VA_ARGS__); \
            status_cb(tag, _sb_, status_ctx); \
        } \
    } while (0)

    DBG("=== ncz_convert_nsz_to_nsp ===");
    DBG("  input  = '%s'", input_path);
    DBG("  output = '%s'", output_path);
    EMIT("PATH", "source: %s", input_path);
    EMIT("PATH", "target: %s", output_path);

    /* 1. Parse PFS0 */
    Pfs0Container container;
    int rc = pfs0_parse(input_path, &container);
    if (rc != 0) {
        DBG("pfs0_parse failed: rc=%d  last_error='%s'", rc, pfs0_last_error());
        return NCZ_ERR_INVALID_PFS0;
    }
    DBG("pfs0_parse: OK — %d files", container.file_count);

    for (int i = 0; i < container.file_count; i++) {
        Pfs0File *f = &container.files[i];
        EMIT("OPEN", "     %-50s 0x%llX bytes at 0x%llX",
             f->name,
             (unsigned long long)f->size,
             (unsigned long long)f->data_offset);
    }

    /* 2. Pre-scan NCZ headers for decompressed sizes */
    FILE *in_fp = open_input_file(input_path);
    if (!in_fp) return NCZ_ERR_OPEN_INPUT;
    /* Unbuffered: the solid reader already reads the compressed stream in large
     * (NCZ_IN_BUF_SIZE) freads straight into its own buffer, so a 4 MiB stdio
     * buffer on top would just copy every byte a second time. Header/CNMT parsing
     * does a handful of small reads — a few extra syscalls per file, negligible. */
    setvbuf(in_fp, NULL, _IONBF, 0);
    advise_sequential(in_fp);

    uint64_t *new_sizes = calloc(container.file_count, sizeof(uint64_t));
    if (!new_sizes) { fclose(in_fp); return NCZ_ERR_OOM; }

    int64_t total_output_bytes = 0;

    for (int i = 0; i < container.file_count; i++) {
        Pfs0File *f = &container.files[i];
        if (f->is_ncz) {
            fseeko(in_fp, (off_t)(f->data_offset + NCZ_HEADER_OFFSET), SEEK_SET);
            NczHeader hdr;
            if (ncz_parse_header(in_fp, &hdr) != 0) {
                DBG("pre-scan [%d] ncz_parse_header FAILED: %s", i, ncz_last_error());
                EMIT("ERROR", "   NCZ parse failed for %s at 0x%llX: %s",
                     f->name,
                     (unsigned long long)(f->data_offset + NCZ_HEADER_OFFSET),
                     ncz_last_error());
                free(new_sizes); fclose(in_fp);
                return NCZ_ERR_INVALID_NCZ;
            }
            new_sizes[i] = (uint64_t)(NCA_HEADER_SIZE + hdr.decompressed_size);
            DBG("pre-scan [%d] '%s' dec_size=%lld  new_size=%llu",
                i, f->name, (long long)hdr.decompressed_size,
                (unsigned long long)new_sizes[i]);
            ncz_free_header(&hdr);
        } else {
            new_sizes[i] = f->size;
            DBG("pre-scan [%d] '%s' passthrough  size=%llu",
                i, f->name, (unsigned long long)f->size);
        }
        total_output_bytes += (int64_t)new_sizes[i];
    }

    /* 2b. Extract expected NCA hashes from the CNMT (reference verification).
     *     On any failure we fall back to the legacy filename check; hooks in
     *     step 5 read cnmt_set.count to decide the mode. */
    CnmtHashSet cnmt_set = {0};
    if (!nca_verify_enabled()) {
        EMIT("VERIFY", "   verification disabled in settings");
    } else if (!nca_cnmt_keys_available()) {
        EMIT("WARN", "   keys for CNMT missing — partial verification by filename");
    } else {
        int cnmt_rc = cnmt_extract_hashes_pfs0(in_fp, &container, &cnmt_set);
        if (cnmt_rc == CNMT_OK) {
            EMIT("VERIFY", "   CNMT verification: %d expected hashes", cnmt_set.count);
        } else {
            EMIT("WARN", "   CNMT unavailable (%s) — partial verification by filename",
                 cnmt_reason(cnmt_rc));
        }
    }

    /* 3. Open output */
    FILE *out_fp = open_output_file(output_path);
    if (!out_fp) {
        free(new_sizes); fclose(in_fp); cnmt_hashset_free(&cnmt_set);
        return NCZ_ERR_OPEN_OUTPUT;
    }
    /* Unbuffered: the async writer submits large (NCZ_CHUNK_SIZE) chunks and
     * copy_bytes writes in <=IO_BUF_SIZE pieces, so a 4 MiB stdio buffer would
     * only memcpy every output byte again before the kernel write. */
    setvbuf(out_fp, NULL, _IONBF, 0);

    /* 4. Write PFS0 header */
    if (pfs0_write_header(out_fp, &container, new_sizes) != 0) {
        free(new_sizes); fclose(in_fp); fclose(out_fp); cnmt_hashset_free(&cnmt_set);
        return NCZ_ERR_IO;
    }

    /* 5. Process each file */
    int64_t done_bytes = 0;
    int     ret        = NCZ_OK;

    for (int i = 0; i < container.file_count && ret == NCZ_OK; i++) {
        Pfs0File *f = &container.files[i];
        fseeko(in_fp, (off_t)f->data_offset, SEEK_SET);

        EMIT("EXISTS", "     %s", f->name);

        if (!f->is_ncz) {
            /* Non-NCZ file: copy verbatim. Verify hashable NCAs (see should_hash). */
            int hash_it = should_hash(f->name, &cnmt_set);
            Sha256Ctx sha_ctx;
            if (hash_it) sha256_init(&sha_ctx);

            ret = copy_bytes(in_fp, out_fp, f->size, hash_it ? &sha_ctx : NULL, start_epoch);
            done_bytes += (int64_t)f->size;
            if (progress_cb) progress_cb(done_bytes, total_output_bytes, cb_ctx);

            if (ret == NCZ_OK && hash_it) {
                uint8_t digest[32];
                sha256_final(&sha_ctx, digest);
                report_hash_result(f->name, digest, &cnmt_set, status_cb, status_ctx);
            }
            continue;
        }

        /* ── NCZ decompression ── */
        DBG("file[%d] '%s' — NCZ  compressed=%llu  decompressed=%llu",
            i, f->name,
            (unsigned long long)f->size,
            (unsigned long long)new_sizes[i]);

        int hash_it = should_hash(f->name, &cnmt_set);
        Sha256Ctx sha_ctx;
        if (hash_it) sha256_init(&sha_ctx);

        /* Copy NCA header verbatim (first 0x4000 bytes) */
        ret = copy_bytes(in_fp, out_fp, NCA_HEADER_SIZE, hash_it ? &sha_ctx : NULL, start_epoch);
        if (ret != NCZ_OK) break;

        /* Parse NCZ header at offset 0x4000 */
        NczHeader hdr;
        if (ncz_parse_header(in_fp, &hdr) != 0) {
            DBG("file[%d] NCZ header parse FAILED: %s", i, ncz_last_error());
            EMIT("ERROR", "   NCZ parse failed for %s at 0x%llX: %s",
                 f->name,
                 (unsigned long long)(f->data_offset + NCZ_HEADER_OFFSET),
                 ncz_last_error());
            ret = NCZ_ERR_INVALID_NCZ;
            break;
        }

        /* Decompress using section-sequential algorithm */
        int64_t body_written = 0;
        ret = ncz_decompress(in_fp, out_fp, &hdr,
                             hash_it ? &sha_ctx : NULL,
                             start_epoch,
                             progress_cb, cb_ctx,
                             total_output_bytes,
                             &body_written);

        ncz_free_header(&hdr);
        done_bytes += (int64_t)new_sizes[i];

        if (ret == NCZ_OK && hash_it) {
            uint8_t digest[32];
            sha256_final(&sha_ctx, digest);
            report_hash_result(f->name, digest, &cnmt_set, status_cb, status_ctx);
        }
    }

    fclose(in_fp);
    fclose(out_fp);
    free(new_sizes);
    cnmt_hashset_free(&cnmt_set);

    if (ret != NCZ_OK && ret != NCZ_ERR_CANCELLED) {
        DBG("conversion FAILED (ret=%d '%s') — removing partial output",
            ret, ncz_error_string(ret));
        if (!is_discard_output(output_path)) remove(output_path);
    } else {
        DBG("conversion %s", ret == NCZ_OK ? "SUCCESS" : "CANCELLED");
    }

#undef EMIT
    return ret;
}

/* ========================================================================== */
/* XCZ → XCI conversion (XCI cartridge format with HFS0 container)           */
/* ========================================================================== */

/*
 * Process a single container file whose data starts at in_fp's current position:
 * NCZ → NCA decompression, or a verbatim copy for everything else, with the same
 * optional hash verification as ncz_convert_nsz_to_nsp. [new_size] is the file's
 * decompressed output size (used for progress accounting on the NCZ path).
 * Returns NCZ_OK or an NCZ_ERR_* code.
 */
static int xcz_process_file(FILE *in_fp, FILE *out_fp,
                            const char *name, uint64_t f_size, int is_ncz,
                            uint64_t new_size, int64_t total_output_bytes,
                            int64_t *done_bytes, const CnmtHashSet *set,
                            int start_epoch,
                            NczProgressCb progress_cb, void *cb_ctx,
                            NczStatusCb status_cb, void *status_ctx)
{
#define EMIT(tag, ...) \
    do { \
        if (status_cb) { \
            char _sb_[512]; \
            snprintf(_sb_, sizeof(_sb_), __VA_ARGS__); \
            status_cb(tag, _sb_, status_ctx); \
        } \
    } while (0)

    int ret = NCZ_OK;
    Sha256Ctx sha_ctx;

    if (!is_ncz) {
        /* Non-NCZ file: copy verbatim. Verify hashable NCAs (see should_hash). */
        int hash_it = should_hash(name, set);
        if (hash_it) sha256_init(&sha_ctx);

        ret = copy_bytes(in_fp, out_fp, f_size, hash_it ? &sha_ctx : NULL, start_epoch);
        *done_bytes += (int64_t)f_size;
        if (progress_cb) progress_cb(*done_bytes, total_output_bytes, cb_ctx);

        if (ret == NCZ_OK && hash_it) {
            uint8_t digest[32];
            sha256_final(&sha_ctx, digest);
            report_hash_result(name, digest, set, status_cb, status_ctx);
        }
        return ret;
    }

    /* ── NCZ → NCA decompression ── */
    int hash_it = should_hash(name, set);
    if (hash_it) sha256_init(&sha_ctx);

    /* Copy NCA header verbatim (first 0x4000 bytes) */
    ret = copy_bytes(in_fp, out_fp, NCA_HEADER_SIZE, hash_it ? &sha_ctx : NULL, start_epoch);
    if (ret != NCZ_OK) return ret;

    /* Parse NCZ header at offset 0x4000 */
    NczHeader hdr;
    if (ncz_parse_header(in_fp, &hdr) != 0) {
        DBG("NCZ header parse FAILED for '%s': %s", name, ncz_last_error());
        EMIT("ERROR", "   NCZ parse failed for %s: %s", name, ncz_last_error());
        return NCZ_ERR_INVALID_NCZ;
    }

    /* Decompress using section-sequential algorithm */
    int64_t body_written = 0;
    ret = ncz_decompress(in_fp, out_fp, &hdr,
                         hash_it ? &sha_ctx : NULL,
                         start_epoch,
                         progress_cb, cb_ctx,
                         total_output_bytes,
                         &body_written);

    ncz_free_header(&hdr);
    *done_bytes += (int64_t)new_size;

    if (ret == NCZ_OK && hash_it) {
        uint8_t digest[32];
        sha256_final(&sha_ctx, digest);
        report_hash_result(name, digest, set, status_cb, status_ctx);
    }
    return ret;

#undef EMIT
}

int ncz_convert_xcz_to_xci(const char *input_path,
                            const char *output_path,
                            NczProgressCb progress_cb,
                            void *cb_ctx,
                            NczStatusCb status_cb,
                            void *status_ctx)
{
    /* Snapshot the cancel epoch; this conversion aborts once it moves on. */
    int start_epoch = atomic_load(&g_cancel_epoch);

#define EMIT(tag, ...) \
    do { \
        if (status_cb) { \
            char _sb_[512]; \
            snprintf(_sb_, sizeof(_sb_), __VA_ARGS__); \
            status_cb(tag, _sb_, status_ctx); \
        } \
    } while (0)

    DBG("=== ncz_convert_xcz_to_xci ===");
    DBG("  input  = '%s'", input_path);
    DBG("  output = '%s'", output_path);
    EMIT("PATH", "source: %s", input_path);
    EMIT("PATH", "target: %s", output_path);

    FILE          *in_fp           = NULL;
    FILE          *out_fp          = NULL;
    Hfs0Container *inners          = NULL;   /* one inner HFS0 per sub-partition  */
    uint64_t     **inner_new_sizes = NULL;   /* [partition][file] output sizes    */
    uint64_t      *part_new_sizes  = NULL;   /* [partition] total partition size  */
    CnmtHashSet   *part_sets       = NULL;   /* [partition] expected NCA hashes   */
    int            root_count      = 0;
    int            ret             = NCZ_OK;
    Hfs0Container  root;

    /* 1. Open input. fd:N is supported so SAF/FUSE sources need no temp copy. */
    in_fp = open_input_file(input_path);
    if (!in_fp) return NCZ_ERR_OPEN_INPUT;
    /* Unbuffered: the solid reader already reads the compressed stream in large
     * (NCZ_IN_BUF_SIZE) freads straight into its own buffer, so a 4 MiB stdio
     * buffer on top would just copy every byte a second time. Header/CNMT parsing
     * does a handful of small reads — a few extra syscalls per file, negligible. */
    setvbuf(in_fp, NULL, _IONBF, 0);
    advise_sequential(in_fp);

    /* 2. Read the first 0x200 and locate the XCI header.
     *    Trimmed XCI: the header (magic "HEAD" at +0x100) is this first block.
     *    Full XCI: 0x0..0x1000 is a key area; the real header is at 0x1000.
     *    Mirrors nsz Xci.isFullXci() (headerOffset = 0x1000). The output always
     *    copies the first 0x200 verbatim (as XciStream does), so we keep it. */
    uint8_t xci_header[0x200];
    if (fread(xci_header, 1, 0x200, in_fp) != 0x200) {
        DBG("Failed to read XCI header");
        ret = NCZ_ERR_IO; goto done;
    }

    uint64_t header_base;
    uint8_t  hdr_buf[0x200];
    const uint8_t *head;
    if (memcmp(xci_header + 0x100, "HEAD", 4) == 0) {
        header_base = 0;
        head = xci_header;
    } else {
        header_base = 0x1000;
        DBG("No 'HEAD' at 0x100 — treating as full XCI, header at 0x1000");
        if (fseeko(in_fp, (off_t)header_base, SEEK_SET) != 0) { ret = NCZ_ERR_IO; goto done; }
        if (fread(hdr_buf, 1, 0x200, in_fp) != 0x200) { ret = NCZ_ERR_IO; goto done; }
        if (memcmp(hdr_buf + 0x100, "HEAD", 4) != 0) {
            DBG("Invalid XCI: no 'HEAD' magic at 0x100 or 0x1100");
            ret = NCZ_ERR_INVALID_PFS0; goto done;
        }
        head = hdr_buf;
    }

    /* HFS0 (root partition) offset lives at +0x130 in the header. The root HFS0
     * in the INPUT is at header_base + hfs0_offset. */
    uint64_t hfs0_offset;
    memcpy(&hfs0_offset, head + 0x130, sizeof(hfs0_offset));
    if (hfs0_offset < 0x200 || hfs0_offset > 0x1000000) {
        DBG("Invalid HFS0 offset: 0x%llX", (unsigned long long)hfs0_offset);
        ret = NCZ_ERR_INVALID_PFS0; goto done;
    }
    uint64_t root_hfs0_abs = header_base + hfs0_offset;
    DBG("XCI header_base=0x%llX hfs0_offset=0x%llX root_hfs0_abs=0x%llX",
        (unsigned long long)header_base, (unsigned long long)hfs0_offset,
        (unsigned long long)root_hfs0_abs);

    /* 3. Parse the ROOT HFS0. Its entries are the XCI sub-partitions
     *    (update / normal / secure / logo) — NOT the NCA files. The actual
     *    NCA/NCZ files live one level deeper, inside each sub-partition's own
     *    HFS0. (Reference: nsz NszDecompressor.__decompressXcz, which iterates
     *    container.hfs0 and rebuilds a nested HFS0 per partition.) */
    if (hfs0_parse_at(in_fp, root_hfs0_abs, &root) != 0) {
        DBG("root hfs0_parse failed: %s", hfs0_last_error());
        EMIT("ERROR", "   Root HFS0 parse failed: %s", hfs0_last_error());
        ret = NCZ_ERR_INVALID_PFS0; goto done;
    }
    root_count = root.file_count;
    EMIT("FOLDER", "   XCI root partitions: %d", root_count);

    inners          = calloc((size_t)root_count, sizeof(Hfs0Container));
    inner_new_sizes = calloc((size_t)root_count, sizeof(uint64_t *));
    part_new_sizes  = calloc((size_t)root_count, sizeof(uint64_t));
    part_sets       = calloc((size_t)root_count, sizeof(CnmtHashSet));
    if (!inners || !inner_new_sizes || !part_new_sizes || !part_sets) {
        ret = NCZ_ERR_OOM; goto done;
    }

    /* One CNMT verification note for the whole XCI (per-partition sets below). */
    if (!nca_verify_enabled()) {
        EMIT("VERIFY", "   verification disabled in settings");
    } else if (!nca_cnmt_keys_available()) {
        EMIT("WARN", "   keys for CNMT missing — partial verification by filename");
    }

    /* 4. Pre-scan: for each sub-partition, parse its inner HFS0, compute every
     *    file's decompressed size, and the partition's new total size. */
    int64_t total_output_bytes = 0;
    for (int p = 0; p < root_count; p++) {
        Hfs0File *pf = &root.files[p];
        EMIT("OPEN", "     %-12s 0x%llX bytes at 0x%llX",
             pf->name, (unsigned long long)pf->size,
             (unsigned long long)pf->data_offset);

        if (hfs0_parse_at(in_fp, pf->data_offset, &inners[p]) != 0) {
            DBG("partition[%d] '%s' inner hfs0_parse failed: %s",
                p, pf->name, hfs0_last_error());
            EMIT("ERROR", "   Partition '%s' parse failed: %s", pf->name, hfs0_last_error());
            ret = NCZ_ERR_INVALID_PFS0; goto done;
        }
        Hfs0Container *inner = &inners[p];
        inner_new_sizes[p] = calloc((size_t)inner->file_count, sizeof(uint64_t));
        if (!inner_new_sizes[p]) { ret = NCZ_ERR_OOM; goto done; }

        uint64_t part_data = 0;
        for (int i = 0; i < inner->file_count; i++) {
            Hfs0File *f = &inner->files[i];
            if (f->is_ncz) {
                fseeko(in_fp, (off_t)(f->data_offset + NCZ_HEADER_OFFSET), SEEK_SET);
                NczHeader hdr;
                if (ncz_parse_header(in_fp, &hdr) != 0) {
                    DBG("pre-scan p%d[%d] ncz_parse_header FAILED: %s", p, i, ncz_last_error());
                    EMIT("ERROR", "   NCZ parse failed for %s: %s", f->name, ncz_last_error());
                    ret = NCZ_ERR_INVALID_NCZ; goto done;
                }
                inner_new_sizes[p][i] = (uint64_t)(NCA_HEADER_SIZE + hdr.decompressed_size);
                ncz_free_header(&hdr);
            } else {
                inner_new_sizes[p][i] = f->size;
            }
            part_data += inner_new_sizes[p][i];
        }
        part_new_sizes[p] = hfs0_computed_header_size(inner) + part_data;
        total_output_bytes += (int64_t)part_new_sizes[p];
        DBG("partition[%d] '%s' new_size=%llu (%d files)",
            p, pf->name, (unsigned long long)part_new_sizes[p], inner->file_count);

        /* Extract expected NCA hashes from this partition's CNMT (the secure
         * partition carries the META NCA; others usually have none → fallback). */
        if (nca_verify_enabled() && nca_cnmt_keys_available()) {
            int cnmt_rc = cnmt_extract_hashes_hfs0(in_fp, inner, &part_sets[p]);
            if (cnmt_rc == CNMT_OK) {
                EMIT("VERIFY", "   CNMT verification (%s): %d expected hashes",
                     pf->name, part_sets[p].count);
            }
        }
    }

    /* 5. Open output */
    out_fp = open_output_file(output_path);
    if (!out_fp) { ret = NCZ_ERR_OPEN_OUTPUT; goto done; }
    /* Unbuffered: the async writer submits large (NCZ_CHUNK_SIZE) chunks and
     * copy_bytes writes in <=IO_BUF_SIZE pieces, so a 4 MiB stdio buffer would
     * only memcpy every output byte again before the kernel write. */
    setvbuf(out_fp, NULL, _IONBF, 0);

    /* 6. XCI header verbatim (nsz copies the original 0x200 header unchanged) */
    if (fwrite(xci_header, 1, 0x200, out_fp) != 0x200) { ret = NCZ_ERR_IO; goto done; }

    /* 7. Zero-fill 0x200 .. 0xF000. nsz's XciStream seeks to 0xF000 and leaves
     *    this region as a hole (reads back as zeros); for nsz-produced .xcz the
     *    source bytes here are already zero (the compressor uses the same
     *    XciStream). Writing zeros explicitly is safe for non-seekable output fds
     *    and matches the reference byte-for-byte regardless of the input's
     *    hfs0_offset. The root HFS0 then always starts at 0xF000. */
    {
        uint8_t zero_buf[4096];
        memset(zero_buf, 0, sizeof(zero_buf));
        uint64_t remaining = (uint64_t)XCI_ROOT_HFS0_OFFSET - 0x200;
        DBG("Zero-filling 0x200..0x%X (%llu bytes)",
            XCI_ROOT_HFS0_OFFSET, (unsigned long long)remaining);
        while (remaining > 0) {
            uint32_t chunk = remaining < sizeof(zero_buf)
                           ? (uint32_t)remaining : (uint32_t)sizeof(zero_buf);
            if (fwrite(zero_buf, 1, chunk, out_fp) != chunk) { ret = NCZ_ERR_IO; goto done; }
            remaining -= chunk;
        }
    }

    /* 8. Write the rebuilt ROOT HFS0 header (sub-partitions with new sizes) */
    if (hfs0_write_header(out_fp, &root, part_new_sizes) != 0) { ret = NCZ_ERR_IO; goto done; }

    /* 9. For each sub-partition: write its inner HFS0 header, then its files.
     *    Partitions and files are written contiguously so the offsets declared
     *    in the headers above match the actual byte positions. */
    int64_t done_bytes = 0;
    for (int p = 0; p < root_count && ret == NCZ_OK; p++) {
        Hfs0Container *inner = &inners[p];
        EMIT("FOLDER", "   partition %s — %d files", root.files[p].name, inner->file_count);

        if (hfs0_write_header(out_fp, inner, inner_new_sizes[p]) != 0) {
            ret = NCZ_ERR_IO; break;
        }

        for (int i = 0; i < inner->file_count && ret == NCZ_OK; i++) {
            Hfs0File *f = &inner->files[i];
            if (fseeko(in_fp, (off_t)f->data_offset, SEEK_SET) != 0) { ret = NCZ_ERR_IO; break; }
            EMIT("EXISTS", "     %s", f->name);
            ret = xcz_process_file(in_fp, out_fp, f->name, f->size, f->is_ncz,
                                   inner_new_sizes[p][i], total_output_bytes, &done_bytes,
                                   &part_sets[p], start_epoch,
                                   progress_cb, cb_ctx, status_cb, status_ctx);
        }
    }

done:
    if (in_fp)  fclose(in_fp);
    if (out_fp) fclose(out_fp);
    if (inner_new_sizes) {
        for (int p = 0; p < root_count; p++) free(inner_new_sizes[p]);
        free(inner_new_sizes);
    }
    if (part_sets) {
        for (int p = 0; p < root_count; p++) cnmt_hashset_free(&part_sets[p]);
        free(part_sets);
    }
    free(inners);
    free(part_new_sizes);

    if (out_fp && ret != NCZ_OK && ret != NCZ_ERR_CANCELLED) {
        DBG("conversion FAILED (ret=%d '%s') — removing partial output",
            ret, ncz_error_string(ret));
        if (!is_discard_output(output_path)) remove(output_path);
    } else {
        DBG("conversion %s",
            ret == NCZ_OK ? "SUCCESS" : (ret == NCZ_ERR_CANCELLED ? "CANCELLED" : "FAILED"));
    }

#undef EMIT
    return ret;
}
