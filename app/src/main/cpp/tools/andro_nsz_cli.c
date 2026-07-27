#include "ncz.h"
#include "ncz_decompress.h"
#include "ncz_engine.h"
#include "nsz_debug.h"
#include "nsz_types.h"
#include "sha256.h"

#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>

typedef enum {
   MODE_AUTO,
   MODE_NSZ,
   MODE_NCZ,
   MODE_XCZ
} CliMode;

typedef struct {
   int64_t last_percent;
} ProgressState;

typedef struct {
   int verify;
   int corrupted;
} CliContext;

#define VERIFY_SINK_PATH "/dev/null"

static void print_usage(const char *argv0)
{
   fprintf(stderr,
           "Usage: %s [options] <input.nsz|input.ncz|input.xcz> [output]\n"
           "       %s --verify [options] <input.nsp|input.nsz|input.ncz|input.xci|input.xcz>\n"
           "\n"
           "Options:\n"
           "   --nsz          Force NSZ/NCZ-in-PFS0 to NSP conversion\n"
           "   --ncz          Force single NCZ to NCA conversion\n"
           "   --xcz          Force XCZ to XCI conversion\n"
           "   --verify       Verify only; do not write an output file\n"
           "   --debug-log P  Write engine debug log to P\n"
           "   -h, --help     Show this help\n"
           "\n"
           "Default output:\n"
           "   *.nsz -> *.nsp\n"
           "   *.ncz -> *.nca\n"
           "   *.xcz -> *.xci\n",
           argv0, argv0);
}

static int usage(const char *argv0)
{
   print_usage(argv0);
   return 2;
}

static int ends_with_ci(const char *text, const char *suffix)
{
   size_t text_len = strlen(text);
   size_t suffix_len = strlen(suffix);
   if (text_len < suffix_len) return 0;
   text += text_len - suffix_len;
   for (size_t i = 0; i < suffix_len; i++) {
      if (tolower((unsigned char)text[i]) != tolower((unsigned char)suffix[i])) return 0;
   }
   return 1;
}

static CliMode detect_mode(const char *path)
{
   if (ends_with_ci(path, ".xcz") || ends_with_ci(path, ".xci")) return MODE_XCZ;
   if (ends_with_ci(path, ".ncz")) return MODE_NCZ;
   if (ends_with_ci(path, ".nsz") || ends_with_ci(path, ".nsp")) return MODE_NSZ;
   return MODE_AUTO;
}

static int is_verify_only_input(const char *path)
{
   return ends_with_ci(path, ".xci") || ends_with_ci(path, ".nsp");
}

static const char *mode_name(CliMode mode)
{
   switch (mode) {
      case MODE_NSZ:  return "NSZ";
      case MODE_NCZ:  return "NCZ";
      case MODE_XCZ:  return "XCZ";
      default:        return "AUTO";
   }
}

static char *replace_extension(const char *path, const char *new_ext)
{
   const char *slash = strrchr(path, '/');
   const char *base = slash ? slash + 1 : path;
   const char *dot = strrchr(base, '.');
   size_t stem_len = dot ? (size_t)(dot - path) : strlen(path);
   size_t ext_len = strlen(new_ext);

   char *out = (char *)malloc(stem_len + ext_len + 1);
   if (!out) return NULL;
   memcpy(out, path, stem_len);
   memcpy(out + stem_len, new_ext, ext_len + 1);
   return out;
}

static const char *path_basename(const char *path)
{
   const char *slash = strrchr(path, '/');
   return slash ? slash + 1 : path;
}

static void bytes_to_hex(const uint8_t *bytes, int n, char *out)
{
   static const char H[] = "0123456789abcdef";
   for (int i = 0; i < n; i++) {
      out[i * 2] = H[bytes[i] >> 4];
      out[i * 2 + 1] = H[bytes[i] & 0xF];
   }
   out[n * 2] = '\0';
}

static int has_content_id_stem(const char *path)
{
   const char *base = path_basename(path);
   const char *dot = strrchr(base, '.');
   if (!dot || dot - base != 32) return 0;
   for (const char *p = base; p < dot; p++) {
      if (!isxdigit((unsigned char)*p)) return 0;
   }
   return 1;
}

static char *default_output_path(const char *input, CliMode mode)
{
   switch (mode) {
      case MODE_XCZ: return replace_extension(input, ".xci");
      case MODE_NCZ: return replace_extension(input, ".nca");
      case MODE_NSZ: return replace_extension(input, ".nsp");
      default:       return NULL;
   }
}

static void status_cb(const char *tag, const char *msg, void *user_data)
{
   CliContext *ctx = (CliContext *)user_data;
   if (ctx && tag && strcmp(tag, "CORRUPTED") == 0) {
      ctx->corrupted = 1;
   }
   fprintf(stderr, "[%s] %s\n", tag ? tag : "STATUS", msg ? msg : "");
}

static void progress_cb(int64_t done, int64_t total, void *user_data)
{
   ProgressState *state = (ProgressState *)user_data;
   if (total <= 0) return;

   int64_t percent = done * 100 / total;
   if (percent == state->last_percent && percent != 100) return;
   state->last_percent = percent;
   fprintf(stderr, "\rProgress: %3lld%%", (long long)percent);
   if (percent >= 100) fputc('\n', stderr);
   fflush(stderr);
}

static int copy_nca_header(FILE *in_fp, FILE *out_fp, Sha256Ctx *sha_ctx)
{
   uint8_t buf[64 * 1024];
   uint64_t remaining = NCA_HEADER_SIZE;

   while (remaining > 0) {
      size_t chunk = remaining < sizeof(buf) ? (size_t)remaining : sizeof(buf);
      if (fread(buf, 1, chunk, in_fp) != chunk) return NCZ_ERR_IO;
      if (sha_ctx) sha256_update(sha_ctx, buf, chunk);
      if (fwrite(buf, 1, chunk, out_fp) != chunk) return NCZ_ERR_IO;
      remaining -= chunk;
   }
   return NCZ_OK;
}

static int convert_single_ncz(const char *input, const char *output,
                              NczProgressCb cb, void *cb_ctx,
                              CliContext *ctx)
{
   FILE *in_fp = fopen(input, "rb");
   if (!in_fp) return NCZ_ERR_OPEN_INPUT;

   FILE *out_fp = fopen(output, "wb");
   if (!out_fp) {
      fclose(in_fp);
      return NCZ_ERR_OPEN_OUTPUT;
   }

   Sha256Ctx sha_ctx;
   sha256_init(&sha_ctx);

   int rc = copy_nca_header(in_fp, out_fp, &sha_ctx);
   if (rc != NCZ_OK) goto done;

   NczHeader hdr;
   if (ncz_parse_header(in_fp, &hdr) != 0) {
      fprintf(stderr, "NCZ parse failed: %s\n", ncz_last_error());
      rc = NCZ_ERR_INVALID_NCZ;
      goto done;
   }

   int64_t body_written = 0;
   rc = ncz_decompress(in_fp, out_fp, &hdr,
                       &sha_ctx,
                       0,   /* start_epoch: CLI never cancels */
                       cb, cb_ctx,
                       NCA_HEADER_SIZE + hdr.decompressed_size,
                       &body_written);
   ncz_free_header(&hdr);

   if (rc == NCZ_OK) {
      uint8_t digest[32];
      char hex[65];
      sha256_final(&sha_ctx, digest);
      bytes_to_hex(digest, 32, hex);
      fprintf(stderr, "[NCA_HASH]    %s\n", hex);
      if (has_content_id_stem(input)) {
         fprintf(stderr,
                 "[HASH_INFO]   %s content hash requires CNMT expected hash\n",
                 input);
      }
   }

done:
   fclose(in_fp);
   fclose(out_fp);
   if (rc != NCZ_OK && rc != NCZ_ERR_CANCELLED &&
         strcmp(output, VERIFY_SINK_PATH) != 0) {
      remove(output);
   }
   return rc;
}

int main(int argc, char **argv)
{
   CliMode mode = MODE_AUTO;
   CliContext cli = {0, 0};
   const char *debug_log = NULL;
   const char *input = NULL;
   const char *output_arg = NULL;

   for (int i = 1; i < argc; i++) {
      if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
         print_usage(argv[0]);
         return 0;
      } else if (strcmp(argv[i], "--nsz") == 0) {
         mode = MODE_NSZ;
      } else if (strcmp(argv[i], "--ncz") == 0) {
         mode = MODE_NCZ;
      } else if (strcmp(argv[i], "--xcz") == 0) {
         mode = MODE_XCZ;
      } else if (strcmp(argv[i], "--verify") == 0) {
         cli.verify = 1;
      } else if (strcmp(argv[i], "--debug-log") == 0) {
         if (++i >= argc) return usage(argv[0]);
         debug_log = argv[i];
      } else if (!input) {
         input = argv[i];
      } else if (!output_arg) {
         output_arg = argv[i];
      } else {
         return usage(argv[0]);
      }
   }

   if (!input) return usage(argv[0]);

   if (mode == MODE_AUTO) mode = detect_mode(input);
   if (mode == MODE_AUTO) {
      fprintf(stderr, "Cannot detect input type. Use --nsz, --ncz, or --xcz.\n");
      return 2;
   }
   if (!cli.verify && is_verify_only_input(input)) {
      fprintf(stderr, "Input is already decompressed. Use --verify for .nsp/.xci files.\n");
      return 2;
   }

   if (cli.verify && output_arg) {
      fprintf(stderr, "--verify does not write an output file.\n");
      return usage(argv[0]);
   }

   char *generated_output = NULL;
   const char *output = cli.verify ? VERIFY_SINK_PATH : output_arg;
   if (!output && !cli.verify) {
      generated_output = default_output_path(input, mode);
      if (!generated_output) {
         fprintf(stderr, "Failed to allocate output path.\n");
         return 2;
      }
      output = generated_output;
   }

   if (debug_log) dbg_open(debug_log, NULL);

   fprintf(stderr, "Mode:   %s\n", mode_name(mode));
   fprintf(stderr, "Verify: %s\n", cli.verify ? "yes" : "no");
   fprintf(stderr, "Input:  %s\n", input);
   if (cli.verify) {
      fprintf(stderr, "Output: none\n");
   } else {
      fprintf(stderr, "Output: %s\n", output);
   }

   ProgressState progress = {-1};
   int rc;
   struct timespec t0, t1;
   clock_gettime(CLOCK_MONOTONIC, &t0);
   if (mode == MODE_XCZ) {
      rc = ncz_convert_xcz_to_xci(input, output, progress_cb, &progress, status_cb, &cli);
   } else if (mode == MODE_NCZ) {
      rc = convert_single_ncz(input, output, progress_cb, &progress, &cli);
   } else {
      rc = ncz_convert_nsz_to_nsp(input, output, progress_cb, &progress, status_cb, &cli);
   }
   clock_gettime(CLOCK_MONOTONIC, &t1);

   /* Benchmark line: wall-clock elapsed + throughput over the decompressed
    * (output) size. For /dev/null runs the size is unknown here — pair with a
    * file run for the byte count. */
   {
      double elapsed = (double)(t1.tv_sec - t0.tv_sec)
                     + (double)(t1.tv_nsec - t0.tv_nsec) / 1e9;
      long long out_bytes = -1;
      if (output && strcmp(output, VERIFY_SINK_PATH) != 0) {
         struct stat st;
         if (stat(output, &st) == 0) out_bytes = (long long)st.st_size;
      }
      if (out_bytes >= 0)
         fprintf(stderr, "BENCH elapsed=%.3fs bytes=%lld MBps=%.1f\n",
                 elapsed, out_bytes, (double)out_bytes / 1048576.0 / elapsed);
      else
         fprintf(stderr, "BENCH elapsed=%.3fs bytes=? MBps=?\n", elapsed);
   }

   if (debug_log) dbg_close();

   if (rc == NCZ_OK && cli.verify && cli.corrupted) {
      fprintf(stderr, "\nVerify failed: hash mismatches detected.\n");
      rc = NCZ_ERR_HASH_MISMATCH;
   }

   if (rc != NCZ_OK) {
      fprintf(stderr, "\nFailed: %s (%d)\n", ncz_error_string(rc), rc);
      free(generated_output);
      return 1;
   }

   free(generated_output);
   fprintf(stderr, "Done.\n");
   return 0;
}
