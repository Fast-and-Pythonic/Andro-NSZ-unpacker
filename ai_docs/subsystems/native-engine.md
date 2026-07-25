# Subsystem: Native engine (libAndroNSZ)

A C11 decompression engine. Built via CMake (`app/src/main/cpp/CMakeLists.txt`)
together with static zstd. Called from Kotlin via JNI. The logic mirrors the
Python reference nicoboss/nsz.

> Do **not** change the cryptography, decompression, container parsers, or JNI
> signatures without an explicit request — see [../conventions.md](../conventions.md).

## Modules

| File | Purpose |
|------|---------|
| `jni_bridge.c` | JNI entry points; string/callback marshaling; attaching the thread to the JVM |
| `ncz_engine.c` | Orchestrator: `ncz_convert_nsz_to_nsp`, `ncz_convert_xcz_to_xci`, cancel, `ncz_error_string` |
| `ncz.c` | NCZ header parser: sections (`NczSection`), blocks (`NczBlockHeader`), FakeSection (the gap between the NCA header and the first section) |
| `ncz_decompress.c` | Decompression: `BlockReader` (block-wise zstd with a cache) and `SolidReader` (streaming `ZSTD_DStream`). Solid input is read in `NCZ_IN_BUF_SIZE` (1 MiB) freads (a user-space prefetch thread was measured and rejected — A15); the write loop uses `NCZ_CHUNK_SIZE` (256 KiB) chunks + a `NCZ_WRITER_BUFFERS` async pool. AES-CTR only for crypto_type 3/4; FakeSection (type 1) — plaintext. Feeds SHA-256. See [../architecture.md](../architecture.md) A15 |
| `async_writer.c` | Background write+hashing thread (see [../architecture.md](../architecture.md) A04) |
| `pfs0.c` | PFS0 container (NSP/NSZ). Entry — 24 bytes. `pfs0_parse`, `pfs0_write_header` (.ncz→.nca, size recompute) |
| `hfs0.c` | HFS0 container (XCI/XCZ). Entry — 64 bytes (SHA-256/`hashed_region_size` zeroed, as in the reference). Partition header aligned to `0x8000` (gap in `entry.offset` + zeros, raw strtab); `hfs0_parse_at(fp, offset)` — streaming parse of a nested partition; `hfs0_computed_header_size()` → `0x8000`. See [../architecture.md](../architecture.md) A11 |
| `aes_ctr.c` | AES-128-CTR for NCA sections. Counter: `nonce[0:8] \|\| (offset>>4)` big-endian. Hardware + software (A02) |
| `aes_xts.c` | AES-128-XTS for decrypting the NCA header (0x200 sectors, IEEE 1619) |
| `sha256.c` | SHA-256: one-shot `sha256()` and streaming (`init/update/final`). Hardware + software (A03) |
| `nca_verifier.c` | `nca_verify_nsp()`: AES-XTS of the header, "NCA3" magic check, SHA-256 of sections |
| `nca_cnmt.c` | CNMT verification: extract full expected NCA hashes from the input's META NCA (`CnmtHashSet`); global config `nca_verify_config_set`. See [../architecture.md](../architecture.md) A12 |
| `nsz_debug.c` | `dbg_open/close/log/hex` — log to file + logcat, millisecond timestamps |
| `nsz_types.h` | Error codes, callback types, constants (`NCA_HEADER_SIZE=0x4000`) |

## Dependency graph (C)

```
jni_bridge.c
 └─ ncz_engine.c
     ├─ pfs0.c / hfs0.c
     ├─ ncz.c
     └─ ncz_decompress.c
         ├─ aes_ctr.c
         ├─ sha256.c
         └─ async_writer.c

nca_verifier.c      (separate entry point via JNI)
 ├─ pfs0.c
 ├─ aes_xts.c
 └─ sha256.c

nca_cnmt.c          (called by ncz_engine.c; config set via JNI)
 ├─ aes_xts.c        (NCA header)
 ├─ aes_ctr.c        (PFS0 section)
 ├─ sha256.c
 └─ (own AES-128-ECB core for the key-area unwrap)

nsz_debug.c         (used everywhere)
```

## Conversion flows

**`ncz_convert_nsz_to_nsp()`** (NSZ→NSP):
1. Parse the input PFS0.
2. Pre-scan NCZ files → decompressed sizes.
2b. Extract expected NCA hashes from the input's CNMT into a `CnmtHashSet`
   (`cnmt_extract_hashes_pfs0`), unless verification is off or keys are missing.
3. Write the new PFS0 header with updated sizes.
4. Per file: NCZ → decompress, otherwise → copy.
5. SHA-256 check: `should_hash` decides whether to hash; `report_hash_result` checks
   set membership (CNMT) or, as a fallback, the filename hex prefix (`… (by name)`).
   **Non-fatal** — a mismatch warns (`WARN`) and keeps the output; see
   [../architecture.md](../architecture.md) A12.
6. On a real error — remove the partial output (but not for `/dev/null` / fd sinks).

**`ncz_convert_xcz_to_xci()`** (XCZ→XCI): an XCI is a **nested** HFS0 (root →
update/normal/secure/logo sub-partitions → NCA/NCZ files), mirroring
`NszDecompressor.__decompressXcz` + `Xci.XciStream` (see [../architecture.md](../architecture.md) A11).
1. Input via `open_input_file` (`fd:N` support); read the first 0x200. If there's no
   "HEAD" at 0x100 → full XCI: header at 0x1000 (`Xci.isFullXci`). The root in the input
   is at `header_base + hfs0_offset` (from +0x130).
2. Parse the root HFS0 (`hfs0_parse_at`).
3. Per sub-partition: parse the nested HFS0, pre-scan NCZ → file sizes, compute the new
   partition size (`hfs0_computed_header_size`=0x8000 + sum of files).
4. Output: the first 0x200 of the input verbatim, **zeros** up to
   `XCI_ROOT_HFS0_OFFSET=0xF000`, the root HFS0 at 0xF000 (with new partition sizes). The
   layout is an exact copy of `XciStream`.
5. Per sub-partition: write the nested HFS0 header (aligned to 0x8000), then the files
   (NCZ → decompress via `ncz_decompress`, otherwise → copy), NCA SHA-256 check against
   that partition's own `CnmtHashSet` (the secure partition carries the META NCA;
   others fall back to the filename check) — non-fatal, A12.
6. On error — remove the partial output. (XCI hashes/`hfs0HeaderHash` are not recomputed —
   as in the reference, which copies the header verbatim. ⚠️ The reference doesn't
   round-trip full XCI — no byte reference, needs a real sample; see [../gotchas.md](../gotchas.md) G06.)

## JNI interface

Signatures — in `NszConverter.kt` (`native*`) ↔ `jni_bridge.c`.

| Kotlin method | Description |
|---------------|-------------|
| `nativeConvert(input, output, progressCb, statusCb): Int` | NSZ → NSP |
| `nativeConvertXcz(input, output, progressCb, statusCb): Int` | XCZ → XCI |
| `nativeVerifyNsp(nspPath, headerKey): String?` | ~~NCA verification in an NSP (structural: section-header hashes)~~ — **dead code since 2026-07-25**: it re-read the whole output; verification is now inline (A12). Kept only until a cleanup pass removes it with `nca_verifier.c` |
| `nativeSetVerification(enabled, headerKey, keyAreaKeys)` | Set CNMT verification config once before a batch (global, read-only during conversion — [../gotchas.md](../gotchas.md) G11) |
| `nativeSetDebugLog(path)` / `nativeCloseDebugLog()` | Debug log |
| `nativeCancel()` | Cancellation request (bumps the global cancel epoch — cancels every in-flight conversion; see A15) |
| `nativeErrorString(code): String` | Error code → text |

`input` accepts a plain path, `"file://"`, or `"fd:N"` (no-copy, see
[../architecture.md](../architecture.md) A05). `output` — `/proc/self/fd/<fd>`.

**Callbacks:**
- `ProgressCallback.onProgress(done, total)` — bytes of decompressed output.
- `StatusCallback.onStatus(tag, msg)` — structured messages. Tags (native):
  `OPEN`, `EXISTS`, `HEAD`, `VERIFY`, `NCA_HASH`, `VERIFIED`, `CORRUPTED`, `WARN`, `PATH`,
  `OK`, `SUCCESS`, `CANCELLED`, `ERROR`. Kotlin additionally uses `FILE_START`, `FOLDER`,
  `NSZ`, `INFO`. (`VERIFY` = a verification-mode summary line, e.g. "CNMT verification:
  N expected hashes".)
- **`VERIFIED` / `CORRUPTED` are the verification contract.** They are emitted per NCA by
  `report_hash_result` and are how the Kotlin layer derives each file's `VerifyStatus`
  (there is no post-conversion re-read pass any more — [../architecture.md](../architecture.md)
  A12). `CORRUPTED` accompanies the human-readable `WARN` on a mismatch and is **non-fatal**
  (output kept). Don't rename or drop these tags without updating `NszConverter.VerifyTracker`
  and `FolderProcessor.convertDirect`; the CLI's `--verify` exit status also keys off `CORRUPTED`.

## Error codes (`nsz_types.h`)

| Code | Constant | Description |
|------|----------|-------------|
| 0 | `NCZ_OK` | Success |
| -1 | `NCZ_ERR_OPEN_INPUT` | Input didn't open |
| -2 | `NCZ_ERR_OPEN_OUTPUT` | Output didn't open |
| -3 | `NCZ_ERR_INVALID_PFS0` | Invalid PFS0 |
| -4 | `NCZ_ERR_INVALID_NCZ` | Invalid NCZ header |
| -5 | `NCZ_ERR_ZSTD` | zstd error |
| -6 | `NCZ_ERR_IO` | I/O error |
| -7 | `NCZ_ERR_OOM` | Out of memory |
| -8 | `NCZ_ERR_CANCELLED` | Cancelled by the user |
| -9 | `NCZ_ERR_HASH_MISMATCH` | SHA-256 mismatch (no longer returned by the content-id check — A12) |

The NSZ/NCZ format itself — in [../references/nsz-format.md](../references/nsz-format.md).
