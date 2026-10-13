# Architecture decisions

Non-trivial technical decisions. Format: `A##` — a stable anchor for links.
Most of A01–A07 are parts of one performance story: unpacking 2 GB was sped up
from ~50 s to ~7 s (parity with the desktop reference), and on 9 GB it beats the
competitor. Verification is essentially free, CNMT-based and non-fatal — see A12.

- **A01** · zstd is built with `-O3` even in debug
- **A02** · Hardware AES-CTR (ARMv8 crypto) + software fallback
- **A03** · Hardware SHA-256 (ARMv8 crypto)
- **A04** · Async writer (background write + hashing thread)
- **A05** · No-copy input via `fd:N`, output via `/proc/self/fd`
- **A06** · Fallback to a temp copy on direct-read failure
- **A07** · Batch/folder parallelism — three sources for the thread count
- **A08** · Per-app language via `attachBaseContext` + SharedPreferences
- **A09** · Per-type progress throttling (live bar, each number at its own pace)
- **A10** · ThinLTO for the native engine
- **A11** · XCI output mirrors `XciStream` (0x8000 HFS0 alignment, hfs0 at 0xF000)
- **A12** · CNMT-based verification (non-fatal), with a filename fallback and a toggle
- **A13** · Core-aware scheduler for heterogeneous CPUs (big.LITTLE) — DELETED
- **A14** · In-app file picker over the raw filesystem (`MANAGE_EXTERNAL_STORAGE`)
- **A15** · Throughput pass + the write-bound diagnosis (solid mode, 2026-07-24/25)
- **A16** · The native debug log is per job and reference counted

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

## A07: Batch/folder parallelism — three sources for the thread count
**Context:** in multi-file mode files convert in parallel. The shared resource is
**storage write bandwidth** (A15), not CPU. So the best thread count is a property of
*this phone's flash*, not of the app: the knee sat at ~4–8 on the one device it was ever
measured on (SM8735 + UFS), but a budget eMMC can plateau at 2 while UFS 4.0 keeps
scaling. A single constant cannot be right everywhere, which is what this decision
replaces.

**Only the aggregate counts.** With several files unpacking at once, per-file speed is
not an optimisation target — it is just the ceiling divided by N. Per-file matters only
when unpacking a *single* file, where parallelism does not apply at all. This is a
correction of an earlier reading (see History) and it is load-bearing for everything
below.

**Decision — `ThreadMode` (`model/ThreadMode.kt`), persisted in `SettingsRepository`:**
- `MANUAL` — the slider on the thread-count screen (`decompression_threads`, 0 = unset).
- `HALF` — half the cores. **The default.**
- `CALIBRATED` — the value found by the real-file test (`calibrated_threads`, 0 = never
  run), which applies to the next job by itself.

`resolveConcurrency(mode, manualN, calibratedN, cores, queueSize)` is a **pure** function
(unit-tested in `ResolveConcurrencyTest`); any source with nothing to say falls back to
`halfConcurrency(cores) = (cores / 2).coerceAtLeast(1)`, and the result is always clamped
to the queue size. We control only the *number* of threads, never core placement
(placement control was tried and regressed — A13, deleted).

**One fallback for every unset source, deliberately.** A slider left at 0, a test never
run and the default mode all resolve to the same number. The alternative — a different
fallback per mode — makes "unset" mean different things depending on where the user
happens to be standing, which is impossible to explain and easy to mis-measure against.

**Why half rather than `cores − 1`:** on the reference device every count from 2 to 8
landed within ~10 % of each other on aggregate throughput, so the exact value matters far
less than not starving the rest of the phone during a long job. The old `coerceIn(1, 4)`
cap is gone for a different reason: it was justified by protecting *per-file* speed, a
criterion that does not apply in parallel mode. There is no longer a rule that the count
must stop below `cores` — the test sweeps up to `cores` and may legitimately return it.
The one unexplained observation (N=8 at 464 MB/s against 887 at N=4, on a badly worn
flash) is why calibration exists, not a reason to cap the default.

**Calibration measures whole conversions** (`util/RealFileBenchmark`): a level of N runs N
*complete* unpacks of one user-picked file in parallel, timed end to end, output deleted,
then the next level. The sweep goes 1→cores and then back cores→1, so flash drift cancels
instead of favouring whichever level ran first (**G18**).

Running whole conversions is not just simplicity. The earlier version cut each level short
once a gigabyte of output had appeared and derived speed from progress counters; it had
two defects that biased the *ranking*, not merely the scale — the clock was stopped after
cancelling and deleting the output, and only the first level ever paid for a cold page
cache. Whole runs remove the machinery those defects lived in. Cross-check on device
(2026-07-28): a single-threaded run measured **391 MB/s** against the CLI's 400 (A15).

The engine's progress counters are back in this code path, but **only to drive the
screen** (`RealFileBenchmark.Progress`, one tick per `Constants.BENCH_TICK_INTERVAL_MS`):
a level is minutes long, and a card that moved once per finished level looked hung. The
verdict still comes from wall-clock time and bytes on disk. Keep the two apart — deriving
the verdict from the counters is the exact mistake described above.

**Rejected: adaptive hill-climbing from below.** A controller was built and measured on
device (2026-07-25): `AdaptivePolicy` + `AdaptiveGate`, sampling aggregate MB/s and
probing the count up/down with hysteresis. It was **deleted**. It started low and ramped
up, which cost ~7 % aggregate on a short 4-file batch (674 vs 720 MB/s) because the run
spent much of its life under-parallelized, and it could *shed* threads, which cannot raise
the aggregate. **Do not reintroduce a bottom-up or reactive-to-a-dip controller.**

**Also deleted: searching during a real job.** `AdaptiveWorkerSearch` + `ThreadMode.ADAPTIVE`
(2026-07-26 → 2026-07-27) stepped the count down mid-job, comparing equal-byte blocks. It
was sound in method — it started at the maximum and only stepped down — but needed tens of
GB of output before it could say anything, so on ordinary jobs it only ever logged "not
enough data". The count no longer changes mid-job, which is why the pool is now built at
exactly what it will use. A stored `ADAPTIVE` migrates to `HALF`.

**Also deleted: the synthetic write test.** `util/WriteBenchmark` + `nativeBenchWrite`
measured parallel writes with no NSZ input at all. Two reasons it went: it could not see
the producer side, so it systematically under-counted (it picked 6 where the real-file
test picked 8 on the same device), and it was the kind of benchmark that is easy to get
subtly wrong — it spent a day measuring the page cache instead of the flash (**G20**).

**Consequences:** both start sites go through `util/WorkerPool` (they used to spell out
the same `Semaphore(concurrency)` separately). Per-file progress is still in
`activeFileProgress`.

**Telemetry:** every job writes `nsz_throughput.csv` at 10 Hz (`util/ThroughputRecorder`,
A16b rotation rules); `util/ThroughputAnalysis` scores the calibration levels. The CSV is
now diagnostics only — its `level_tag` column is always empty since nothing tags samples
mid-job. Read [gotchas.md](gotchas.md) **G18** before drawing any conclusion from a
throughput number.

**History:** `(…/2 − 1).coerceIn(1, 3)` → `(…/2).coerceAtLeast(1)` (2026-07-08) →
`(… − 1).coerceAtLeast(1)` (2026-07-24, on a wrong "CPU-bound" reading) → adaptive from
below (2026-07-25, rejected same day) → `(… − 1).coerceIn(1, 4)` (2026-07-25, justified by
per-file speed) → MANUAL/ADAPTIVE/CALIBRATED with `cores − 1` as the fallback (2026-07-26,
after that justification was retracted) → MANUAL/**HALF**/CALIBRATED, one measurement
instead of two, whole-run sweep (2026-07-28).
NB: parallelism is per-file, so it only helps a queue of several files. A single large
solid file is one zstd stream and cannot be parallelized; see A15 for what was tried.
**Load distribution (LPT) — rejected:** dispatching largest-first over the fair
`Semaphore` measured *worse* on-device (~840 → ~700 MB/s): order alone doesn't control
which core takes which file, so a heavy file can strand on a slow core (Q||Cmax). The
core-aware scheduler that tried to fix this (A13) also regressed and was **deleted**
2026-07-24. The shipping path is the plain natural-order `Semaphore`.

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

## A12: CNMT-based verification (non-fatal), with a filename fallback and a toggle
**Context:** the engine used to compare only the first 16 bytes of a decompressed NCA's
SHA-256 against the content-id in the filename. But the content-id is only **half** the
NCA hash, so it is **not authoritative**. Reference nsz instead reads the **full** hash
of every content NCA from the CNMT (`FileExistingChecks.ExtractHashes` →
`Cnmt.contentEntries[].hash`) and checks each unpacked NCA's full SHA-256 for set
membership.
**Decision:** port the CNMT approach. Before per-file processing, the engine scans the
**input** container for META NCAs (`*.cnmt.nca`, stored uncompressed but encrypted),
decrypts them and collects the expected hashes into a `CnmtHashSet`
([nca_cnmt.c](../app/src/main/cpp/nca_cnmt.c)). During conversion each NCA's full
SHA-256 (already computed streaming, essentially free) is checked against the set. The
CNMT NCA itself is excluded from the set (it never lists itself). XCZ builds one set
**per HFS0 partition** (the secure partition carries the META).
**Reading the CNMT** reuses existing crypto: AES-XTS header decrypt with `header_key`
(`aes_xts.c`), then AES-128-**ECB** unwrap of the key area with
`key_area_key_application_XX` read **directly** from prod.keys (XX = key generation =
`max(cryptoType, cryptoType2) − 1`; no master-key/KEK derivation, unlike the reference),
then AES-CTR of the PFS0 section (key = key-area entry index 2, counter = section nonce
reversed). `nca_cnmt.c` carries its own one-block AES-ECB core because the primitives in
`aes_xts.c` are `static` (and that file must not be edited).
**Fallback ladder:** CNMT → filename → off. If keys are missing or the META NCA can't be
parsed, the engine falls back to the legacy content-id check and marks it `VERIFIED …
(by name)`. A settings toggle (`should_hash`) can disable hashing entirely — the point
of the toggle, since that is what the SHA cost buys. Default **ON**: CNMT verification is
now authoritative *and* nearly free, so there's no reason to ship it off (this supersedes
the roadmap's earlier "probably off").
**Semantics:** a mismatch stays **non-fatal** — `WARN` "hash mismatch (output kept)", the
file is never deleted. Config is pushed once per batch via
`nativeSetVerification(enabled, header_key, key_area_keys)` and is read-only during
conversion (safe under parallel conversions — see [gotchas.md](gotchas.md)).
**Reporting the verdict (2026-07-25):** the engine emits a machine-readable `CORRUPTED`
tag alongside the human `WARN` in both mismatch branches of `report_hash_result`
([ncz_engine.c](../app/src/main/cpp/ncz_engine.c)). Kotlin derives the per-file
`VerifyStatus` from the inline `VERIFIED`/`CORRUPTED` tags via a per-call `VerifyTracker`
(a `StatusCallback` decorator in [NszConverter.kt](../app/src/main/java/com/androNSZ/NszConverter.kt);
`FolderProcessor.convertDirect` has the same interception). Each conversion has its own
tracker, so parallel files never race.
**The structural `nca_verify_nsp` post-pass was REMOVED (2026-07-25):** it re-read the
*entire* output file, which on write-bound storage (A15) cost ~30 % of a single file's
wall-clock and stole bandwidth from parallel writers. The CNMT check it duplicated is
already streaming, inline and authoritative. `nca_verifier.c`, its JNI entry point and the
Kotlin `external fun` were **deleted** on 2026-07-26. Consequence: XCZ/XCI now reports a
real verdict too (it hashes per HFS0 partition), where before it was always `NOT_CHECKED`.
**Checked against the reference:** nicoboss/nsz has no structural NCA verifier at all —
its verification is entirely CNMT-hash based (`FileExistingChecks.ExtractHashes` →
`NszDecompressor.__decompressContainer`), and `nsz -D` performs no enforced verification,
only advisory `[VERIFIED]`/`[CORRUPTED]` prints. Our inline behaviour matches that; the
structural check was a superset we no longer carry.
**Deferred:** per-core layout optimization (all SHA on 1–2 cores) — [status.md](status.md).

## A13: Core-aware scheduler for heterogeneous CPUs (big.LITTLE) — DELETED
**Status:** DELETED 2026-07-24. The scheduler (`CoreScheduler.kt`, `CpuTopology.kt`,
`cpu_affinity.c` + `nativeSetThreadAffinity/Clear`, unit tests) was removed from the
tree along with the `smart_distribution` toggle. It had been disabled since 2026-07-08.
**Why it existed / why it failed:** measurement (2026-07-07) showed decompression is
CPU-bound and scales to ~6 cores (A07). Plain largest-first ordering over a fair
`Semaphore` regressed (~840→~700 MB/s) because order alone doesn't control *which* core
takes *which* file (the **Q||Cmax** problem). The scheduler assigned files to cores by
speed (LPT + greedy earliest-completion-time + makespan local search) and pinned the
native decompress to a cluster via `sched_setaffinity`. It also regressed in practice
(static proxies ignore thermal throttling; compressed size is a poor proxy for unpacked
size), so it never shipped. The shipping path is the plain natural-order `Semaphore`
(A07). Re-attempting this would need calibration + guarded work-stealing — not planned.

## A14: In-app file picker over the raw filesystem (`MANAGE_EXTERNAL_STORAGE`)
**Context:** input selection used SAF only (`OpenDocument`/`OpenDocumentTree`,
`content://`), which adds files one at a time and can't freely roam storage. The user
wanted a custom split-screen picker (marked items on top, browser below) that walks the
whole device.
**Decision:** browse the real filesystem with `java.io.File`, gated by the "All files
access" special permission (`MANAGE_EXTERNAL_STORAGE`, granted from a system settings
page — [StoragePermission.kt](../app/src/main/java/com/androNSZ/util/StoragePermission.kt)).
Acceptable because this is a sideloaded homebrew, not a Play-Store app. The reusable
picker ([FilePickerScreen.kt](../app/src/main/java/com/androNSZ/ui/screen/FilePickerScreen.kt))
hands the engine `Uri.fromFile(...)` (`file://`) values.
**Why it's cheap downstream:** the engine already reads `file://` inputs directly
(`NszConverter.convert`: `scheme == "file"` → `uri.path`, the fast path, no fd/temp copy),
and `FolderProcessor` already handles `file://` on both input and output. The only SAF-only
piece was the folder scanner, so a parallel [RawFolderScanner.kt](../app/src/main/java/com/androNSZ/fs/RawFolderScanner.kt)
walks a `File` tree into the same `FolderStructure` shape (`file://` uris) — the rest of
the folder pipeline is untouched.
**Scope (v1):** files-mode marks files only (multi-select), folder-mode marks one folder
only (the pipeline is single-root). A combined files+folders mode, and later dropping the
two old modes, are deferred. Output-folder and prod.keys pickers stay on SAF (write/keys).
**Consequences:** free navigation + multi-select; `file://` inputs skip the fd/temp path.
Trap: `file://` needs explicit handling in the name/size helpers (see
[gotchas.md](gotchas.md) G13). Second trap: the permission gate must re-check on resume
and treat "granted" as insufficient — a grant that arrives while the app runs doesn't
reach the process's storage mount, and the per-app settings page isn't on every skin, so
`StoragePermission` keeps a fallback chain of intents (see [gotchas.md](gotchas.md) G21).

## A15: Throughput pass + the write-bound diagnosis (solid mode, 2026-07-24/25)
**Context:** with several files converting at once, zstd *looked* dominant and the
per-file pipeline wasn't feeding it efficiently. A batch of changes was shipped on that
assumption; then a proper on-device measurement overturned the assumption.

### The measurement (2026-07-25) — unpacking is WRITE-bound, not CPU-bound
Method: the host CLI ([tools/andro_nsz_cli.c](../app/src/main/cpp/tools/andro_nsz_cli.c))
cross-compiled for arm64 and run over adb on a SM8735 device (8 heterogeneous cores,
UFS storage), one 2.88 GB-output NSZ, N processes in parallel. See
[gotchas.md](gotchas.md) G17 for the methodology and its traps.

| Target | N=1 | N=2 | N=4 | N=6 | N=8 |
|---|---|---|---|---|---|
| → `/dev/null` (no write) | 611 | 1105 | 1905 | 2158 | **2375** |
| → raw UFS (`/data/local/tmp`) | 400 | 695 | 1013 | 1079 | — |
| → FUSE (`/sdcard`) | 467 | 737 | 1053 | 1008 | — |
| → raw UFS, **Step-6 disabled** | — | 548 | 451 | 477 | — |

⚠️ **This table is one device** (SM8735 + UFS), and the write rows are one wear state.
Nothing in it generalises to other storage: the knee's *position* is exactly what differs
between an eMMC budget phone and UFS 4.0. Treat it as the reference measurement that the
methodology was validated against, not as the app's tuning. The app now measures the
device instead — A07, and the real-file test in `util/RealFileBenchmark`.

**Cross-check, 2026-07-28 (same device).** The in-app test, rewritten to time whole
conversions, measured **391 MB/s** at one thread against the 400 in the N=1 row above —
the two agree within 2 %, which is what says the in-app timing is sound. The same run
peaked at ~1150–1190 MB/s from 4 threads up, again on internal storage.

Conclusions, each of which contradicted a prior belief:
1. **Decompression is not the ceiling.** It scales to ~2375 MB/s; writing caps at
   ~1000–1080 MB/s (N≈4 knee) and **degrades to ~450–500 MB/s under sustained load** —
   flash SLC-cache exhaustion, *not* thermal (CPU stayed ~50 °C, and per-core `cpufreq`
   showed no throttling). CPU busy during a write run (72 %) < during `/dev/null` (79 %):
   cores stall on I/O.
2. **FUSE/MediaStore is innocent** (FUSE ≈ raw UFS). The app's output path is not the problem.
3. **The Step-6 page-cache hygiene below is essential, not harmful** — disabling it makes
   parallel writes *collapse* (451 vs 1013 at N=4). The "measure-gated, probably revert"
   note it shipped with was wrong; it is now load-bearing.
4. **Chunk size barely matters for decompression** (64 K ≈ 256 K ≈ 512 K ≈ 1 M, 362–380 MB/s
   single-threaded) but **1 MiB writes are worse than 256 KiB** (491 vs 887 at N=4). 256 KiB
   is the right pick, for the write side.
5. **A single solid file is producer-bound, not write-bound** (one zstd stream, ~600 MB/s to
   /dev/null vs 400–467 with a write, while the write path is free). Attacking that with an
   input prefetcher didn't work (see the rejected item below); a solid frame can't be
   parallelized, so single-file speed is now bounded by one zstd stream.

### End state, measured in the app (4 files × 2.7 GB out, 4 workers, fresh flash)
Aggregate **967 MB/s**, per-file **242–254 MB/s**, every file `Checked`, 10.7 GB in 11 s.
Compare the starting point of this whole effort (7 workers): per-file ~115–140 MB/s with no
better aggregate. Per-file roughly doubled and the aggregate now sits at the storage write
ceiling measured by the CLI (~1013 MB/s at N=4). On a *worn* flash the same run gives
~680–740 MB/s — that spread is the flash, not the code, so always note the wear state
alongside a number (G17).

**Decisions:**
- **Fixed parallelism of 4** — *superseded 2026-07-26.* It was justified by per-file speed,
  a criterion that does not apply when several files unpack at once, and the cap was
  measured on a single device. The worker count is now chosen per device: see A07.
- **Verify post-pass removed** (the biggest single-file win: it re-read the whole output) —
  see A12.
- The verify gate on the concurrency slider was removed — see A07.
- **Rejected: user-space input prefetch thread.** A `Prefetcher` pthread (ring of 3 × 1 MiB,
  mutex + condvars) was implemented to overlap the compressed-input `fread` with
  decompression, aiming at the single-file case. Measured on device with `-DNCZ_NO_PREFETCH`
  as the A/B control, pinned to one core, 3 paired rounds warm **and** cold (page cache
  evicted with a 6 GB ballast read between runs): **no gain either way** — warm 341.6 vs
  344.7 MB/s (prefetch marginally *slower*, sync overhead), cold 337/310 vs 340/312. Output
  SHA-256 was byte-identical, so it worked; it just bought nothing, and was removed.
  Reason: decompression emits ~340 MB/s of output ≈ 250 MB/s of compressed input while the
  storage delivers 600+ MB/s — reads are never the bottleneck — and the kernel's readahead
  (hinted by `POSIX_FADV_SEQUENTIAL`) already overlaps them. Don't re-add without a
  measurement showing reads actually stalling the producer.
- **Bigger solid chunks:** `NCZ_CHUNK_SIZE` 64 KiB → **256 KiB** (≥ `ZSTD_DStreamOutSize()`,
  so zstd flushes full blocks; 4× fewer mutex/condvar and `aes_ctr_set_offset` ops). Async
  pool `NCZ_WRITER_BUFFERS = 12` (3 MiB/file). Output is byte-identical to the reference's
  0x10000 (AES reseeds per chunk from the absolute offset; chunks clamp to section ends).
- **No stdio double-copy:** input and output `FILE*` are now `_IONBF` (were 4 MiB `_IOFBF`).
  The solid reader reads the compressed stream in 1 MiB (`NCZ_IN_BUF_SIZE`) freads straight
  into its own buffer; the async writer submits 256 KiB writes. Removes a full memcpy of
  every byte in and out, and ~8 MiB/file of stdio buffers. `copy_bytes` now mallocs
  `min(size, IO_BUF_SIZE)` (was always 4 MiB, for 0x4000 header copies).
- **Page-cache hygiene — LOAD-BEARING, do not remove:** `posix_fadvise(SEQUENTIAL)` on the
  input; the async writer does `sync_file_range(WRITE)` + `posix_fadvise(DONTNEED)` every
  `AW_DROP_INTERVAL` (64 MiB) to bound dirty pages under N parallel writers. Best-effort
  (FUSE returns EINVAL, ignored). Measured **2.2× on parallel writes** (see point 3 above) —
  it shipped labelled "experimental, probably revert" and turned out to be one of the most
  valuable changes. The old worry that DONTNEED would penalise the verify re-read is moot:
  that re-read is gone (A12).
- **Per-conversion cancel:** the old single global `g_cancel` (reset to 0 at every
  conversion's entry) let a newly-started parallel file un-cancel the others. Replaced with
  a monotonic **epoch counter** (`g_cancel_epoch`): each conversion snapshots it at entry
  and polls `ncz_cancelled(start_epoch)`; `ncz_request_cancel()` bumps it, cancelling all
  in-flight files (the existing UX). `ncz_decompress()` / `copy_bytes()` / `xcz_process_file()`
  take `int start_epoch` instead of a flag pointer; JNI surface unchanged.
**Done in the follow-up (2026-07-26):** the dead structural verifier was deleted (A12) and
the native debug log is now opened **once per job** and reference counted (A16).
**Deferred / known-but-not-done:**
- Releasing the gate slot before per-file MediaStore finalize (the old "Step 7"): with the
  cap at 4 and the verify re-read gone, the remaining tail work is small. Only worth it if a
  benchmark shows inter-file gaps.
- Block-mode (non-solid) NCZ is still untouched by all of the above.

## A16: The native debug log is per job and reference counted
**Context:** `nsz_debug.c` keeps one global `FILE*` opened with `"w"`, and Kotlin used to
call `nativeSetDebugLog`/`nativeCloseDebugLog` **per converted file** on one fixed path
(`<externalFilesDir>/nsz_debug.log`). With up to 4 files in parallel that meant every
starting file truncated the shared log and the first file to finish closed it for all the
others — they silently continued to logcat only. Folder/combined mode was worse: it never
opened the log at all (`FolderProcessor.convertDirect` calls native directly), so those
modes produced no native log whatsoever.
**Decision:**
- Kotlin opens the log **once per job**, next to the other once-per-job call
  (`nativeSetVerification`): `NszConverter.openJobDebugLog(context)` in
  `MainViewModel.startBatchConversion` and in `runFolderStyleConversion` (which serves both
  folder and combined). Closing: `job.invokeOnCompletion` for the batch — it covers success,
  failure and cancellation without wrapping the ~130-line body in `try/finally` — and the
  existing `finally` for folder mode, under `NonCancellable` so a cancelled scope still
  closes the log and cleans temp files.
- `dbg_open`/`dbg_close` are **reference counted** (`nsz_debug.c`): the outermost open
  truncates and writes the header, a nested open just takes a reference (never truncates,
  never reopens — a differing path is logged and ignored), and only the last close writes
  the footer and `fclose`s. `dbg_open(NULL)` still force-closes. This makes the bug class
  unrepeatable: no future per-file open can truncate or close another conversion's log.
- Kept `"w"` (truncate once per job, so the file is exactly "the last run").
- Also fixed in passing: `s_start_time` was read outside the mutex in `dbg_log`/`dbg_hex`;
  the elapsed-ms computation now happens under the same lock that writes it.
**Consequences:** a 4-file parallel batch produced a single 224 k-line log containing all
four conversions, with ~2.8 k lines written *after* the first file finished (previously the
cut-off point); folder mode now yields a full log (280 k lines, 5 conversions) where it had
none.

### A16b: one file = one run, plus one previous (2026-07-26)
**Context:** three further problems remained. `nsz_folder_debug.log` opened in **append**
mode and was never truncated, so it grew without bound and stacked unlabelled banners from
every past run. No log carried any identity, so a log left over from an earlier run was
indistinguishable from the current one — during an adb session I did read a stale log as if
it were fresh. And the four sinks' differing semantics had to be re-derived from the code
every time.
**Decision:** a single invariant, implemented once in
[LogFiles.kt](../app/src/main/java/com/androNSZ/util/LogFiles.kt) and reused by all three
file sinks: **one file = exactly one run**. Starting a run rotates the current file to
`<name>.prev.<ext>` (dropping the older `.prev`) and writes the new one from scratch, so at
most two generations ever exist and nothing accumulates.
- **Run numbers.** `SettingsRepository.nextRunId()` (SharedPreferences, `@Synchronized` +
  `commit()` — a folder scan and the conversion after it ask back-to-back, and a lost update
  would hand out the same number) stamps every header. The *same* run number in
  `nsz_debug.log` and `nsz_folder_debug.log` means the same run. The screen snapshot uses
  `lastRunId()` — it belongs to the run already on screen and must not allocate a new one.
- **Self-describing headers.** `LogFiles.banner()` writes run number, timestamp, mode
  (queue / folder / combined / folder-scan / screen-snapshot), app version, and a short
  "how logging works here" block naming every sink — so a log explains the scheme
  without reading any code. `nsz_throughput.csv` (A07 telemetry) is a fifth sink under the
  same rules; it takes the banner through `LogFiles.commentedBanner()`, which prefixes each
  line with `#` so the file stays machine-readable, and its first uncommented line is the
  column header.
- **JNI:** the native header is written by C on open, before Kotlin could append anything,
  so `nativeSetDebugLog(path, banner)` / `dbg_open(path, banner)` gained a nullable banner
  argument (the only JNI signature change here; the CLI passes `NULL`). Rotation happens on
  the Kotlin side *before* the open, because the engine truncates.
- Also fixed: `scanFolderInto` created a `FolderLogWriter` and closed it **only on the error
  path**, so a successful scan leaked its writer and the conversion then held the same file
  open a second time, with two independent `Mutex`es. It now closes on every path.
**Consequences:** logs stay bounded at two generations each, and `Run #` makes a stale log
obvious at a glance. logcat is untouched (never rotated, still receives every native line).
