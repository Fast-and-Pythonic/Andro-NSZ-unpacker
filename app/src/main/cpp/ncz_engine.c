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
    FILE *out_fp = open_output_file(output_path);
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
            int verify_nca = is_nca_file(f->name) && is_content_id_named(f->name);
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

                EMIT("HASH_INFO", "   %s content hash requires CNMT expected hash", f->name);
            }
            continue;
        }

        /* ── NCZ decompression ── */
        DBG("file[%d] '%s' — NCZ  compressed=%llu  decompressed=%llu",
            i, f->name,
            (unsigned long long)f->size,
            (unsigned long long)new_sizes[i]);

        int verify_nca = is_content_id_named(f->name);
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

            EMIT("HASH_INFO", "   %s content hash requires CNMT expected hash", f->name);
        }
    }

    fclose(in_fp);
    fclose(out_fp);
    free(new_sizes);

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
                            int64_t *done_bytes,
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
        /* Non-NCZ file: copy verbatim. Verify only hash-named .nca files. */
        int verify_nca = is_nca_file(name) && is_content_id_named(name);
        if (verify_nca) sha256_init(&sha_ctx);

        ret = copy_bytes(in_fp, out_fp, f_size, verify_nca ? &sha_ctx : NULL);
        *done_bytes += (int64_t)f_size;
        if (progress_cb) progress_cb(*done_bytes, total_output_bytes, cb_ctx);

        if (ret == NCZ_OK && verify_nca) {
            uint8_t digest[32];
            char hex[65];
            sha256_final(&sha_ctx, digest);
            bytes_to_hex(digest, 32, hex);
            EMIT("NCA_HASH", "   %s", hex);

            EMIT("HASH_INFO", "   %s content hash requires CNMT expected hash", name);
        }
        return ret;
    }

    /* ── NCZ → NCA decompression ── */
    int verify_nca = is_content_id_named(name);
    if (verify_nca) sha256_init(&sha_ctx);

    /* Copy NCA header verbatim (first 0x4000 bytes) */
    ret = copy_bytes(in_fp, out_fp, NCA_HEADER_SIZE, verify_nca ? &sha_ctx : NULL);
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
                         verify_nca ? &sha_ctx : NULL,
                         &g_cancel,
                         progress_cb, cb_ctx,
                         total_output_bytes,
                         &body_written);

    ncz_free_header(&hdr);
    *done_bytes += (int64_t)new_size;

    if (ret == NCZ_OK && verify_nca) {
        uint8_t digest[32];
        char hex[65];
        sha256_final(&sha_ctx, digest);
        bytes_to_hex(digest, 32, hex);
        EMIT("NCA_HASH", "   %s", hex);

        EMIT("HASH_INFO", "   %s content hash requires CNMT expected hash", name);
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
    atomic_store(&g_cancel, 0);

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
    int            root_count      = 0;
    int            ret             = NCZ_OK;
    Hfs0Container  root;

    /* 1. Open input. fd:N is supported so SAF/FUSE sources need no temp copy. */
    in_fp = open_input_file(input_path);
    if (!in_fp) return NCZ_ERR_OPEN_INPUT;
    setvbuf(in_fp, NULL, _IOFBF, IO_BUF_SIZE);

    /* 2. Read & validate the 0x200 XCI header */
    uint8_t xci_header[0x200];
    if (fread(xci_header, 1, 0x200, in_fp) != 0x200) {
        DBG("Failed to read XCI header");
        ret = NCZ_ERR_IO; goto done;
    }
    if (memcmp(xci_header + 0x100, "HEAD", 4) != 0) {
        DBG("Invalid XCI magic at 0x100 (expected 'HEAD')");
        ret = NCZ_ERR_INVALID_PFS0; goto done;  /* reuse code for invalid container */
    }

    /* HFS0 (root partition) offset lives at 0x130 in the header */
    uint64_t hfs0_offset;
    memcpy(&hfs0_offset, xci_header + 0x130, sizeof(hfs0_offset));
    DBG("XCI hfs0_offset = 0x%llX", (unsigned long long)hfs0_offset);
    if (hfs0_offset < 0x200 || hfs0_offset > 0x1000000) {
        DBG("Invalid HFS0 offset: 0x%llX", (unsigned long long)hfs0_offset);
        ret = NCZ_ERR_INVALID_PFS0; goto done;
    }

    /* 3. Parse the ROOT HFS0. Its entries are the XCI sub-partitions
     *    (update / normal / secure / logo) — NOT the NCA files. The actual
     *    NCA/NCZ files live one level deeper, inside each sub-partition's own
     *    HFS0. (Reference: nsz NszDecompressor.__decompressXcz, which iterates
     *    container.hfs0 and rebuilds a nested HFS0 per partition.) */
    if (hfs0_parse_at(in_fp, hfs0_offset, &root) != 0) {
        DBG("root hfs0_parse failed: %s", hfs0_last_error());
        EMIT("ERROR", "   Root HFS0 parse failed: %s", hfs0_last_error());
        ret = NCZ_ERR_INVALID_PFS0; goto done;
    }
    root_count = root.file_count;
    EMIT("FOLDER", "   XCI root partitions: %d", root_count);

    inners          = calloc((size_t)root_count, sizeof(Hfs0Container));
    inner_new_sizes = calloc((size_t)root_count, sizeof(uint64_t *));
    part_new_sizes  = calloc((size_t)root_count, sizeof(uint64_t));
    if (!inners || !inner_new_sizes || !part_new_sizes) { ret = NCZ_ERR_OOM; goto done; }

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
    }

    /* 5. Open output */
    out_fp = open_output_file(output_path);
    if (!out_fp) { ret = NCZ_ERR_OPEN_OUTPUT; goto done; }
    setvbuf(out_fp, NULL, _IOFBF, IO_BUF_SIZE);

    /* 6. XCI header verbatim (nsz copies the original 0x200 header unchanged) */
    if (fwrite(xci_header, 1, 0x200, out_fp) != 0x200) { ret = NCZ_ERR_IO; goto done; }

    /* 7. Copy XCI metadata (0x200 .. hfs0_offset) verbatim */
    if (fseeko(in_fp, 0x200, SEEK_SET) != 0) { ret = NCZ_ERR_IO; goto done; }
    uint64_t metadata_size = hfs0_offset - 0x200;
    if (metadata_size > 0) {
        DBG("Copying XCI metadata: 0x200..0x%llX (%llu bytes)",
            (unsigned long long)hfs0_offset, (unsigned long long)metadata_size);
        int cr = copy_bytes(in_fp, out_fp, metadata_size, NULL);
        if (cr != NCZ_OK) { ret = cr; goto done; }
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
