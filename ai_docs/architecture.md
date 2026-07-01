# Architecture decisions

Non-trivial technical decisions. Format: `A##` — a stable anchor for links.
Most of A01–A07 are parts of one performance story: unpacking 2 GB was sped up
from ~50 s to ~7 s (parity with the desktop reference), and on 9 GB it beats the
competitor. Verification is essentially free (and now non-fatal — see A12).

## A01: zstd is built with `-O3` even in debug
**Context:** the zstd dependency in debug inherits `CMAKE_BUILD_TYPE=Debug` → `-O0`
and `-DDEBUGLEVEL=1` (internal asserts). Decompression is the dominant cost of the
hot path, and `-O0` tanks it.
**Decision:** in `CMakeLists.txt`, force `-O3 -DNDEBUG -UDEBUGLEVEL -DDEBUGLEVEL=0`
for the `libzstd_static` target, regardless of build type.
**Consequences:** the debug APK unpacks at release speed. This turned out to be the
decisive win (see also [gotchas.md](gotchas.md) G02).

## A02: Hardware AES-CTR (ARMv8 crypto) + software fallback
**Context:** AES-CTR decrypts NCA sections (crypto_type 3/4) on every block.
**Alternatives:** scalar FIPS-197 only (slow); hardware only (crashes on CPUs
without the extension).
**Decision:** `aes_ctr.c` has an intrinsics path (`vaeseq_u8/vaesmcq_u8`, processing
4 CTR blocks at a time) and a portable scalar fallback. ARM64 is built with
`-march=armv8-a+crypto`.
**Consequences:** on modern phones crypto is nearly free; on old ones it runs slower
but correctly.

## A03: Hardware SHA-256 (ARMv8 crypto)
**Context:** SHA-256 is needed both for verification and for matching filenames by
hex prefix; it is computed on the fly over the output stream.
**Decision:** `sha256.c` uses `vsha256hq_u32` etc. intrinsics with the software API
as a fallback.
**Consequences:** verifying a finished NSP costs almost no time, so it runs whenever
header_key is available.

## A04: Async writer (background write + hashing thread)
**Context:** the producer (decompress+decrypt) and the disk compete for time.
**Decision:** `async_writer.c` — a background thread that receives ready chunks via a
buffer queue; it `fwrite`s them and feeds the SHA-256 context while the producer
prepares the next chunk. Writes are strictly in submit order → output is sequential
and SHA sees bytes in order. API: `aw_start/aw_get_buffer/aw_submit/aw_finish`.
**Consequences:** CPU work and I/O overlap. The price is double buffering of memory.

## A05: No-copy input via `fd:N`, output via `/proc/self/fd`
**Context:** SAF/scoped storage gives a `Uri`, but native needs a path/descriptor.
Copying a multi-gigabyte input to a temp file is expensive.
**Decision:** input — `context.contentResolver.openFileDescriptor(uri,"r")`; if the fd
is seekable (`statSize >= 0`), native gets the string `"fd:N"` and does
`dup()+fdopen()`. Output — MediaStore Downloads + `"/proc/self/fd/<fd>"`.
**Consequences:** no extra temp copy. But some FUSE providers hand out a descriptor
native cannot re-open → a fallback is needed (see A06 / G03).

## A06: Fallback to a temp copy on direct-read failure
**Context:** direct fd reading sometimes fails while parsing PFS0 on FUSE storage.
**Decision:** if `nativeConvert` returned `ERR_OPEN_INPUT/INVALID_PFS0/INVALID_NCZ/
IO` with an active `inputPfd`, `NszConverter` copies the input to cache
(`resolveToFilePath`) and retries. The fast attempt fails immediately at parsing, so
the retry is almost free.
**Consequences:** reliability across all providers at the cost of a rare retry.

## A07: Core-adaptive batch parallelism (queue mode)
**Context:** in multi-file mode files can be converted in parallel.
**Decision:** `BATCH_CONCURRENCY = (availableProcessors()/2 - 1)`, clamped to `1..3`
(8 cores → 3, 6 → 2, ≤4 → 1). Files are launched via a `Semaphore`, native work runs
on `Dispatchers.IO`, state is written on Main.
**Consequences:** loads several cores. The cap of 3 — we hit the storage write ceiling.
Per-file progress is in `activeFileProgress` (keyed by index).

## A08: Per-app language via `attachBaseContext` + SharedPreferences
**Context:** changing the language in-app without changing the system one.
**Decision:** `MainActivity.attachBaseContext()` synchronously reads the language and
wraps the context with the right `Locale` before UI inflation. That's why `language`
is stored in **SharedPreferences** (synchronous), not DataStore — coroutines aren't
available there yet. Changing the language calls `Activity.recreate()`.
**Consequences:** instant language application; this one setting lives apart from the
others (which are in the DataStore flow).

## A09: Per-type progress throttling (live bar, each number at its own pace)
**Context:** frequent updates are needed for a smooth bar, but they jitter the numbers
(percent/speed/size), which become hard to read. There used to be one shared "number"
constant, but percent and size were actually derived from live `done`/`total` and
flickered at the bar's rate — only speed was really throttled.
**Decision:** four independent constants in `Constants.kt` — `PROGRESS_BAR_…` (smooth
animation, follows the live byte counter), `PROGRESS_PERCENT_…`, `PROGRESS_SPEED_…`,
`PROGRESS_SIZE_…` (defaults 100 / 250 / 250 / 250 ms). The logic lives in
`util/ProgressThrottler.kt` (one instance per progress stream): `sample(done,total)`
returns `null` until the bar interval elapses, otherwise a `ConversionProgress` where
the live `doneBytes/totalBytes` drive the bar while the `display*` fields
(`displayPercent`, `displayDoneBytes/TotalBytes`, `speedMBps`) are each "frozen" at
their own interval. `ConversionProgress.display*` default from the live values, so
non-throttling callers (folder current-file, final pin) don't break. Used at four
emit sites: `NszConverter` (fd and temp paths), `MainViewModel` (batch overall),
`FolderProcessor`. The UI (`SingleFilesUI`, `FolderModeUI`) draws the bar from the
live `.percent` and the text from `display*`.
**Consequences:** a smooth bar + calm, per-type tunable numbers. To change one
metric's pace — edit one constant. Old downside: throttling can leave the overall bar
a hair below 100% at the end, so batch has a final "top-up" emit (see
[gotchas.md](gotchas.md) G01).

## A10: ThinLTO for the native engine
**Context:** the engine is split into ~11 translation units; without LTO the compiler
won't inline hot helpers across file boundaries (the AES step into the decompression
loop, `sha256_update` into the verifier).
**Decision:** `-flto=thin` on both compile and link. ThinLTO is parallel/incremental,
so the build cost is small.
**Consequences:** a modest win (the hot path is already in hardware crypto and zstd),
low risk. LTO must be passed to the linker too, or the bitcode objects won't be
codegen'd.

## A11: XCI output mirrors `XciStream` (0x8000 HFS0 alignment, hfs0 at 0xF000)
**Context:** the XCZ→XCI path must produce the byte-for-byte same `.xci` as reference
nsz (as already achieved for NSP). We used to write compact HFS0 headers and copy the
`0x200..hfs0_offset` region, which made the container layout differ from the reference.
**Decision:** reproduce `nsz.Fs.Xci.XciStream` / `Hfs0Stream` exactly:
- Each HFS0 partition (root and nested) reserves a fixed header
  `HFS0_PARTITION_HEADER = 0x8000`; the first file's data starts on that boundary. The
  gap is encoded in the entry offsets (`entry.offset = 0x8000 − header_size`) and
  padded with zeros. The string table is **raw, without 0x20 padding**
  (`string_table_size` = raw length). See [hfs0.c](../app/src/main/cpp/hfs0.c)
  `hfs0_write_header`, `hfs0_computed_header_size` (→ 0x8000).
- In [ncz_engine.c](../app/src/main/cpp/ncz_engine.c) the output: the first `0x200` of
  the input verbatim, then **zeros** up to `XCI_ROOT_HFS0_OFFSET = 0xF000`, then the
  root HFS0 at 0xF000. That's exactly what `XciStream` does (seek 0xF000, the region is
  a hole/zeros). Zeros are written explicitly (not via seek) for non-seekable output fds.
- SHA256/`hashed_region_size` in HFS0 entries stay zero (as before — matches the
  reference).
**Consequences:** `.xci` matches the reference for ordinary (trimmed) XCI. The
`0x200..0xF000` region (gamecard cert) is zeroed — but in an nsz-produced `.xcz` it is
already zero (the compressor uses the same `XciStream`), so nothing is lost. Full XCI
see [gotchas.md](gotchas.md) G06.

## A12: SHA-256 verification — non-fatal, filename check as an approximation
**Context:** the engine compares the first 16 bytes of the decompressed NCA's SHA-256
against the content-id in the filename. But the content-id is only **half** the NCA
hash; reference nsz compares the **full** hash against the ones expected from the CNMT
(`FileExistingChecks.ExtractHashes` → `Cnmt.contentEntries[].hash`), and uses the
filename only as a fallback for a standalone `.ncz`. So a filename mismatch is **not
authoritative**.
**Decision:** a mismatch is no longer fatal. Instead of `NCZ_ERR_HASH_MISMATCH` (which
deleted the finished output via `remove`), the engine emits `WARN` "hash mismatch
(output kept)" and keeps the file. `is_content_id_named` now checks the 32 chars are
hex. See [ncz_engine.c](../app/src/main/cpp/ncz_engine.c) (4 verification blocks).
**Consequences:** the footgun is gone (a false mismatch used to delete a good file).
Real CNMT-based verification, an enable/disable setting, and per-core optimization are
deferred ([status.md](status.md) Deferred). Came in with the PR #6 integration.
