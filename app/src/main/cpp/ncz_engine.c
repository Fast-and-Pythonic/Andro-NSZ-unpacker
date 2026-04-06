/*
 * NCZ engine — top-level NSZ to NSP conversion orchestrator.
 * Uses section-sequential decompression from ncz_decompress.c.
 */
#include "ncz_engine.h"
#include "pfs0.h"
#include "ncz.h"
#include "ncz_decompress.h"
#include "sha256.h"
#include "nsz_debug.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>

/* ---------- cancellation flag ---------- */
static volatile atomic_int g_cancel = ATOMIC_VAR_INIT(0);

void ncz_request_cancel(void) { atomic_store(&g_cancel, 1); }
void ncz_reset_cancel(void)   { atomic_store(&g_cancel, 0); }

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

/* ---------- helpers ---------- */

/* Returns 1 if filename looks like a hash-named NCA: 32 hex chars before extension */
static int is_hash_named(const char *name)
{
    const char *dot = strrchr(name, '.');
    return dot && (size_t)(dot - name) == 32;
}

/* Returns 1 if filename ends with .nca or .ncz */
static int is_nca_file(const char *name)
{
    const char *ext = strrchr(name, '.');
    if (!ext) return 0;
    return (strcmp(ext, ".nca") == 0 || strcmp(ext, ".ncz") == 0);
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

/* Copy helper with optional SHA-256 feed */
static int copy_bytes(FILE *in, FILE *out, uint64_t size, Sha256Ctx *sha_ctx)
{
    uint8_t *buf = malloc(IO_BUF_SIZE);
    if (!buf) return NCZ_ERR_OOM;

    uint64_t remaining = size;
    while (remaining > 0) {
        if (atomic_load(&g_cancel)) { free(buf); return NCZ_ERR_CANCELLED; }
        size_t chunk = remaining < IO_BUF_SIZE ? (size_t)remaining : IO_BUF_SIZE;
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
    atomic_store(&g_cancel, 0);

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
    FILE *in_fp = fopen(input_path, "rb");
    if (!in_fp) return NCZ_ERR_OPEN_INPUT;
    setvbuf(in_fp, NULL, _IOFBF, IO_BUF_SIZE);

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

    /* 3. Open output */
    FILE *out_fp = fopen(output_path, "wb");
    if (!out_fp) {
        free(new_sizes); fclose(in_fp);
        return NCZ_ERR_OPEN_OUTPUT;
    }
    setvbuf(out_fp, NULL, _IOFBF, IO_BUF_SIZE);

    /* 4. Write PFS0 header */
    if (pfs0_write_header(out_fp, &container, new_sizes) != 0) {
        free(new_sizes); fclose(in_fp); fclose(out_fp);
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
            /* Non-NCZ file: copy verbatim */
            /*
             * Hash verification: only for .nca files that are hash-named.
             * Python reference: verifyFile = nspf._path.endswith('.nca')
             *                   and not nspf._path.endswith('.cnmt.nca')
             * We also skip non-NCA files (certs, tickets) to avoid the v1 bug.
             */
            int verify_nca = is_nca_file(f->name) && is_hash_named(f->name);
            Sha256Ctx sha_ctx;
            if (verify_nca) sha256_init(&sha_ctx);

            ret = copy_bytes(in_fp, out_fp, f->size, verify_nca ? &sha_ctx : NULL);
            done_bytes += (int64_t)f->size;
            if (progress_cb) progress_cb(done_bytes, total_output_bytes, cb_ctx);

            if (ret == NCZ_OK && verify_nca) {
                uint8_t digest[32];
                char hex[65];
                sha256_final(&sha_ctx, digest);
                bytes_to_hex(digest, 32, hex);

                EMIT("NCA_HASH", "   %s", hex);

                char base[33] = {0};
                const char *dot = strrchr(f->name, '.');
                if (dot) {
                    size_t blen = (size_t)(dot - f->name);
                    if (blen > 32) blen = 32;
                    memcpy(base, f->name, blen);
                }

                if (strncasecmp(hex, base, 32) == 0) {
                    EMIT("VERIFIED", "   %s", f->name);
                } else {
                    DBG("file[%d] HASH MISMATCH: expected=%s got=%s", i, base, hex);
                    EMIT("ERROR", "   hash mismatch: %s", f->name);
                    ret = NCZ_ERR_HASH_MISMATCH;
                }
            }
            continue;
        }

        /* ── NCZ decompression ── */
        DBG("file[%d] '%s' — NCZ  compressed=%llu  decompressed=%llu",
            i, f->name,
            (unsigned long long)f->size,
            (unsigned long long)new_sizes[i]);

        int verify_nca = is_hash_named(f->name);
        Sha256Ctx sha_ctx;
        if (verify_nca) sha256_init(&sha_ctx);

        /* Copy NCA header verbatim (first 0x4000 bytes) */
        ret = copy_bytes(in_fp, out_fp, NCA_HEADER_SIZE, verify_nca ? &sha_ctx : NULL);
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
                             verify_nca ? &sha_ctx : NULL,
                             &g_cancel,
                             progress_cb, cb_ctx,
                             total_output_bytes,
                             &body_written);

        ncz_free_header(&hdr);
        done_bytes += (int64_t)new_sizes[i];

        if (ret == NCZ_OK && verify_nca) {
            uint8_t digest[32];
            char hex[65];
            sha256_final(&sha_ctx, digest);
            bytes_to_hex(digest, 32, hex);

            EMIT("NCA_HASH", "   %s", hex);

            char base[33] = {0};
            const char *dot = strrchr(f->name, '.');
            if (dot) {
                size_t blen = (size_t)(dot - f->name);
                if (blen > 32) blen = 32;
                memcpy(base, f->name, blen);
            }

            if (strncasecmp(hex, base, 32) == 0) {
                EMIT("VERIFIED", "   %s", f->name);
            } else {
                DBG("file[%d] HASH MISMATCH: expected=%s got=%s", i, base, hex);
                EMIT("ERROR", "   hash mismatch: %s", f->name);
                ret = NCZ_ERR_HASH_MISMATCH;
            }
        }
    }

    fclose(in_fp);
    fclose(out_fp);
    free(new_sizes);

    if (ret != NCZ_OK && ret != NCZ_ERR_CANCELLED) {
        DBG("conversion FAILED (ret=%d '%s') — removing partial output",
            ret, ncz_error_string(ret));
        remove(output_path);
    } else {
        DBG("conversion %s", ret == NCZ_OK ? "SUCCESS" : "CANCELLED");
    }

#undef EMIT
    return ret;
}
