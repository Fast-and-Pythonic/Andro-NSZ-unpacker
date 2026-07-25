# Status (updated: 2026-07-26)

## Working

- Conversion in all three modes: single file (legacy, no UI), file queue, folder.
  NSZ→NSP (debugged) and XCZ→XCI (**tested on a device 2026-07-01, both modes**,
  after the empty-partition fix — [gotchas.md](gotchas.md) G07).
- Folder mode moved to the "new" pipeline (like the file queue): no-copy `fd:N`
  input, writing the result straight into the destination descriptor, adaptive
  parallelism, inline NSP verify, a GUI bar per active file.
- The perf pipeline in `stable`: hardware AES-CTR + SHA-256 with a software
  fallback, no-copy input via `fd:N`, async writer + page-cache pacing, input
  zstd `-O3`, ThinLTO, parallelism fixed at 4 (A07). Unpacking is
  **write-bound** — see A15 for the measured ceilings.
- NCA SHA-256 verify — **non-fatal** (mismatch → `WARN` + `CORRUPTED` tag, output kept,
  not deleted), computed inline during decompression with no output re-read; the
  filename check is a fallback approximation. See [architecture.md](architecture.md) A12.
- Output to a chosen folder (SAF tree / file uri) — in all modes, with a fallback
  to Downloads (`NszConverter.createOutputUri`).
- **Custom in-app file picker** (split-screen, browses the raw filesystem via
  `MANAGE_EXTERNAL_STORAGE`) replaces the SAF input pickers in both modes: files-mode
  multi-selects files, folder-mode selects one folder (A14). Compiles/installs/launches
  on device; interactive flows tested manually (adb tap injection is blocked on the
  MIUI test device).
- "Save on-screen log" button under the log panel (both modes) → `nsz_screen_log.txt`,
  separate from the engine and folder logs.
- EN/RU localization, in-app language selection (`recreate()`), output folder
  selection (SAF, persistable permission).
- Progress UI: an overall bar + a bar per actively converting file
  (`activeFileProgress`); separate bar/number throttling (A09); a timer to the right
  of the contextual "Unpacking…/Unpacked" status.

## Fragile points

- **Progress units.** The batch overall bar mixed a compressed denominator with an
  uncompressed numerator (fixed, [gotchas.md](gotchas.md) G01). When editing progress,
  keep the numerator and denominator in the same units.
- **No-copy I/O.** The `fd:N` / `/proc/self/fd` path depends on provider behavior; it
  relies on the temp-copy fallback (G03). Don't remove the retry.
- **XCZ→XCI: full XCI has no byte reference.** Trimmed XCI is tested on a device
  (2026-07-01, both modes, real .xcz), the layout is aligned to 0x8000/0xF000 (A11).
  Still unverified is **full** XCI (key area at 0x0, header at 0x1000): the reference
  doesn't round-trip it, there's no byte reference — needs a real sample before release
  promises (see [gotchas.md](gotchas.md) G06).
- **Verification is CNMT-based, with a filename fallback (A12).** The engine reads the
  full expected NCA hashes from the input's CNMT and checks each unpacked NCA against
  them. Only when keys are missing / the META NCA can't be parsed does it fall back to
  the (non-authoritative) filename content-id check, marked `VERIFIED … (by name)`. A
  mismatch stays non-fatal in both modes — never return `NCZ_ERR_HASH_MISMATCH` here.
  The per-file UI verdict rides on the `VERIFIED`/`CORRUPTED` status tags (there is no
  output re-read pass any more) — renaming those tags silently breaks the file cards.
- **Unpacking is write-bound, and the ceiling drifts** (A15, G17). More workers do **not**
  raise total throughput past ~4; they only slice per-file speed. Before "optimizing
  parallelism", re-read the measured table — and benchmark fresh vs sustained separately,
  since the flash slows ~2× once its SLC cache is exhausted.
- **`async_writer`'s `sync_file_range`/`FADV_DONTNEED` pacing is load-bearing.** It looks
  like a removable "experiment" but disabling it collapses parallel writes ~2.2×.
- **Writing folder results straight into a SAF descriptor.** Folder mode writes output
  via `/proc/self/fd` into an arbitrary folder (not just Downloads). On rare firmwares
  FUSE failures are possible — input has a temp fallback, output does not.
- **Parity with the Python reference.** `aes_*`, `sha256`, `ncz_decompress`, the
  container parsers, and the JNI signatures are debugged and match nsz. Change only on
  request.
- **Indentation on blank lines.** Auto-formatters strip it, breaking the code style
  (3 spaces, keeping indentation). Check after formatting.
- **Dead legacy code.** `MainViewModel.startConversion()`, `pickFile()`,
  `selectedUri/selectedName` remain after removing `LegacySingleFileUI`, but nothing
  calls them anymore. Candidates for removal (as a separate task).

## Deferred

- **Combined picker mode** (mark files + folders together in one pass) and then
  dropping the two separate modes — the picker is built mode-scoped for now (A14).
- **Multi-folder selection** in folder mode — the folder pipeline is single-root.
- **Picker row-spacing tuning control** — the gear menu's two live spacing fields are a
  temporary aid; once good values are found, hardcode them and remove the fields/menu.
- **Block-level parallelism in C** — branch `block-parallel-wip` (commit `aa7cf73`).
  Currently BROKEN, not in `stable`. `stable` uses file-level batch parallelism. Reason
  deferred: instability; the perf target is already met by other means.
- **Verification roadmap** (see [architecture.md](architecture.md) A12):
  1. ~~enable/disable verification setting~~ — **done** (2026-07-03), default **ON**
     (CNMT verify is authoritative and nearly free, so the earlier "probably off" no
     longer applies);
  2. ~~proper CNMT-based verification (full NCA hash vs `Cnmt.contentEntries[].hash`)~~ —
     **done** (2026-07-03), [nca_cnmt.c](../app/src/main/cpp/nca_cnmt.c);
  3. per-core layout optimization: cores are currently paired (1 decompress + 1 verify);
     the idea — put all SHA-256 verification on 1–2 cores, ~6 of 8 on decompression, 1
     for system/GUI. **Still deferred.**

## Decision log

- 2026-07-26 — **logs: one file = one run, plus one previous**
  ([architecture.md](architecture.md) A16b, [gotchas.md](gotchas.md) G07). Three fixes in one
  pass. (1) **Accumulation:** `nsz_folder_debug.log` opened in append mode and was never
  truncated — it grew without bound and stacked unlabelled banners from every past run. All
  three file sinks now rotate on start (`<name>.prev.<ext>`, older dropped) via the new
  `util/LogFiles.kt`, so at most two generations exist. (2) **Identity:** every header now
  carries `Run : #N` from `SettingsRepository.nextRunId()` (`@Synchronized` + `commit()`,
  because a folder scan and the conversion after it allocate back-to-back); the same number
  in the native and the Kotlin log means the same run, and the screen snapshot stamps
  `lastRunId()` without allocating. This exists because a stale log was once read as the
  current one after a GUI test silently failed to start. (3) **Self-description:** the shared
  banner lists all four sinks and the rotation rule, so a log explains the scheme without
  reading code. JNI: `nativeSetDebugLog(path, banner)` / `dbg_open(path, banner)` gained a
  nullable banner argument (the native header is written by C on open, so Kotlin could not
  prepend it otherwise); the CLI passes `NULL`. Also fixed: `scanFolderInto` closed its
  `FolderLogWriter` only on the error path, so a successful scan leaked the writer and the
  conversion held the same file open a second time. New `LogFilesTest` (5 tests).
- 2026-07-26 — **cleanup: dead verifier deleted, native debug log fixed**
  ([architecture.md](architecture.md) A12/A16). (1) `nca_verifier.c`/`.h`, the
  `nativeVerifyNsp` JNI entry point and its Kotlin `external fun` are **deleted**. Checked
  against the reference first: nicoboss/nsz has **no** structural NCA verifier — its
  verification is entirely CNMT-hash based and `nsz -D` enforces nothing, so our inline
  check already matches it and the structural pass was a superset with no caller.
  (2) The native debug log was opened **per file** on one fixed path with `fopen(..., "w")`:
  every file of a parallel batch truncated it and the first to finish closed it for all the
  others; folder/combined mode never opened it at all. Now opened **once per job**
  (`NszConverter.openJobDebugLog/closeJobDebugLog`, called next to `nativeSetVerification`
  in `startBatchConversion` and `runFolderStyleConversion`), closed via `invokeOnCompletion`
  (batch) and the existing `finally` under `NonCancellable` (folder — which also stops a
  cancelled scope from skipping temp cleanup). `dbg_open`/`dbg_close` are now **refcounted**,
  so no future per-file open can truncate or close another conversion's log; the elapsed-ms
  read of `s_start_time` moved under the mutex that writes it.
  Verified on device: a 4-file parallel batch produced one 224 k-line log with all four
  conversions and ~2.8 k lines written *after* the first file finished (the old cut-off
  point); folder mode produced a 280 k-line log with all 5 conversions where it previously
  produced none. Builds + tests green.
- 2026-07-25 — **write-bound diagnosis; two features built, measured and rejected**
  ([architecture.md](architecture.md) A15/A07/A12, [gotchas.md](gotchas.md) G17). An adb CLI
  benchmark overturned the "CPU-bound" model: decompression scales to ~2375 MB/s
  (→ `/dev/null`) while **writing** caps at ~1000–1080 MB/s and decays to ~450–500 under
  sustained load (UFS SLC exhaustion, not thermal — CPU stayed ~50 °C). FUSE ≈ raw UFS, so the
  output path is innocent. The `cores − 1` step from 2026-07-24 was therefore a regression
  (per-file cut ~2×, no aggregate gain).
  **Kept:** (1) `nativeVerifyNsp` post-pass **removed** — it re-read the entire output; the
  per-file `VerifyStatus` now comes from the engine's inline `VERIFIED`/new `CORRUPTED` tags via
  `NszConverter.VerifyTracker` (XCZ gets a real verdict for the first time; `headerKey` plumbing
  dropped from `convert`/`convertXcz`/`convertDirect`). (2) Parallelism fixed at
  `min(cores − 1, 4)`. (3) Step-6 fadvise/`sync_file_range` reclassified **load-bearing**
  (2.2× on parallel writes — disabling it collapses them); 256 KiB chunk confirmed (1 MiB is
  worse for writes).
  **Rejected after measuring** (both worked correctly, both bought nothing — details and the
  numbers in A07/A15, kept as "don't retry this" notes): an *adaptive* worker-count controller
  (`AdaptivePolicy`/`AdaptiveGate`, hill-climb on aggregate MB/s) — its premise is false, since
  fewer workers never raise aggregate at any wear level, and its ramp cost ~7 %; and a native
  *input prefetch* pthread in `SolidReader` — reads are never the bottleneck and kernel
  readahead already covers them.
  **End state, measured in the app** (4 files × 2.7 GB out, fresh flash): aggregate
  **967 MB/s**, per-file **242–254 MB/s**, all files `Checked`, 10.7 GB in 11 s — vs per-file
  ~115–140 at the 7-worker starting point. Builds + 8 unit tests green; verified on device
  (POCO/HyperOS, Android 16, SM8735).
- 2026-07-24 — **parallel-decompression throughput pass** ([architecture.md](architecture.md)
  A15) on branch `dev-parallel-files-2`. (1) `AUTO_CONCURRENCY` → `cores − 1` and the
  Settings slider's verify-off gate removed (`resolveConcurrency(override)`); slider now
  always shown. (2) Native solid path: `NCZ_CHUNK_SIZE` 64 KiB→256 KiB, `NCZ_WRITER_BUFFERS`
  12, `NCZ_IN_BUF_SIZE` 1 MiB, input+output `FILE*` set to `_IONBF` (kills the stdio
  double-copy), `copy_bytes` mallocs only what it copies. (3) Experimental page-cache
  hygiene in `async_writer.c` (`sync_file_range`+`FADV_DONTNEED` every 64 MiB) +
  `FADV_SEQUENTIAL` on input — measure-gated, note the verify-reread trade-off. (4)
  Per-conversion cancel **epoch** (`g_cancel_epoch`) fixes the parallel un-cancel bug;
  `ncz_decompress`/`copy_bytes`/`xcz_process_file` now take `int start_epoch`. (5) **Deleted**
  the disabled core-aware scheduler (A13): `CoreScheduler.kt`, `CpuTopology.kt`,
  `cpu_affinity.c/.h`, `nativeSetThreadAffinity/Clear`, `smart_distribution` pref + strings,
  and their unit tests. Builds (both ABIs) + Kotlin + unit tests green; on-device speed
  benchmark pending (user). **Deferred:** plan Step 7 (release the permit before
  verify/finalize) and the shared-debug-log-per-batch cleanup.
- 2026-07-08 — **custom in-app file picker** ([architecture.md](architecture.md) A14).
  Replaced the SAF input pickers with a split-screen picker that browses the raw
  filesystem (`java.io.File`) under `MANAGE_EXTERNAL_STORAGE`. Reason: SAF adds files one
  at a time and can't roam storage freely; the raw path also feeds the engine `file://`
  directly (fast path). Reused what already accepts `file://` (engine `convert`,
  `FolderProcessor`); only the folder scanner was duplicated (`RawFolderScanner`). New:
  `FilePickerScreen`, `PickerMode`, `StoragePermission`, `Screen.FilePicker`,
  `MainViewModel.selectFolderFromFile`. Fixed `file://` name/size helpers
  ([gotchas.md](gotchas.md) G13). Scope kept minimal (files-mode = files, folder-mode =
  one folder); combined mode deferred. New JNI: none.
- 2026-07-08 — **release prep on `dev`** (three changes):
  1. **Accent color:** new `AccentMode.DEFAULT` (fixed brand accent `#a6c8ff`,
     `Color.kt` `DefaultAccent`), listed **first** and now the out-of-box default
     (was `SYSTEM`/Material You) in `SettingsRepository.getAccentMode`, the ViewModel
     initial state, and the `AndroNSZTheme` param. Users can still pick System/Manual;
     `DEFAULT` shows no manual controls (gated on `== CUSTOM`).
  2. **Smart core distribution disabled + hidden** (A13): `smartDistribution` forced
     `false` and not loaded from prefs; the Settings `Card` and its wiring removed.
     Scheduler code kept (deferred experiment).
  3. **Use all cores:** `AUTO_CONCURRENCY` = `(availableProcessors()/2).coerceAtLeast(1)`
     (was `(…/2 − 1).coerceIn(1,3)`). 8 cores → 4 parallel files → all 8 cores busy
     (2 threads/file). No cores reserved for system/GUI. See [architecture.md](architecture.md) A07.
- 2026-07-07 — smart load distribution, step 2: **core-aware scheduler**
  ([architecture.md](architecture.md) A13). Step 1's plain LPT measured *worse*
  (~840→~700 MB/s) because order alone doesn't control which core takes which file.
  New: `CpuTopology` (sysfs speed + cluster masks), `CoreScheduler` (LPT + greedy ECT +
  makespan local search, static, "protect makespan" — no work-stealing), and native
  `cpu_affinity.c` + `nativeSetThreadAffinity/Clear` pinning the decompress to a cluster
  on the IO thread before `nativeConvert`. Engages only when the CPU is heterogeneous
  and affinity works (EPERM → baseline); homogeneous/no-affinity → natural-order
  semaphore. `convert*`/`convertDirect` gained `affinityMask`; `processFolder` gained
  `smartDistribution` (replaces `largestFirst`). Unit tests: `CoreSchedulerTest`,
  `CpuTopologyTest`. **Deferred:** speed calibration by measurement + guarded
  work-stealing (hybrid); then SHA-256 per-core layout (A12 §3). New JNI methods are
  additive; crypto/decompress untouched.
- 2026-07-07 — smart load distribution, step 1: **LPT (largest-first)**
  ([architecture.md](architecture.md) A07). Measurement confirmed decompression is
  CPU-bound (1 worker ~250–360 MB/s → ~800 MB/s aggregate at ~6 cores), so the old
  "write ceiling" assumption is wrong for ≤6 cores. Both batch and folder now create
  their conversion coroutines in descending-size order; the fair `Semaphore` hands
  permits to the largest files first, so a heavy file never trails on a slow core.
  Gated by a **separate Settings toggle** `smart_distribution` (default ON; kept
  toggleable for A/B measurement). `FolderProcessor.processFolder` gained a
  `largestFirst` param. Kotlin-only — no native changes. **Next steps** (deferred):
  (a) explicit big/little core affinity — new native `cpu_affinity.c` +
  `nativeSetThreadAffinity` (`sched_setaffinity` on the IO thread before
  `nativeConvert`) + a Kotlin `CpuTopology` reader (sysfs `cpu_capacity` /
  `cpuinfo_max_freq`), largest files → big cluster, EPERM → silent fallback to plain
  LPT, under the same toggle; (b) smart SHA-256 core layout (A12 §3).
- 2026-07-07 — configurable decompression parallelism (measurement experiment,
  [architecture.md](architecture.md) A07). `BATCH_CONCURRENCY`/`FOLDER_CONCURRENCY`
  replaced by `MainViewModel.resolveConcurrency(verificationEnabled, override)`; a
  Settings slider (`decompression_threads`, 0 = auto) can raise the worker count up to
  the core count, **only when verification is off**. `FolderProcessor.processFolder`
  now takes a `concurrency` param. Goal: test the A12 §3 premise (bottleneck = storage
  write, not cores) before investing in `block-parallel-wip` or the "SHA on dedicated
  cores" layout. Native code, crypto and `nativeSetVerification` untouched. Parallelism
  is per-file — the slider only scales a queue/folder of several files.
- 2026-07-03 — CNMT-based verification + a settings toggle (A12). New native module
  `nca_cnmt.c` extracts full expected NCA hashes from the input's META NCA (XTS header →
  ECB key-area unwrap with `key_area_key_application_XX` from prod.keys → CTR PFS0 →
  CNMT); the 4 engine verify blocks now check set membership, falling back to the
  filename check (`(by name)`) when keys/META are unavailable. New JNI
  `nativeSetVerification`; `KeysParser.parseKeyAreaKeys`; a `verification_enabled`
  setting (default ON) with a `Switch` in Settings. Mismatch stays non-fatal.
- 2026-07-03 — folder-mode verify made reliable + non-fatal (G10): close the write
  descriptor before reopening a fresh read-only fd for verify (a FUSE read/write race
  under parallel conversions caused false `cannot parse NSP container`), and treat a
  verify failure as a `WARN` that keeps the output, matching single-file mode.
- 2026-07-01 — integrated PR #6 (manx98, first external contribution): empty
  HFS0-partition fix (`file_count == 0`, G07 — the cause of the XCZ→XCI failure);
  `is_content_id_named` (hex validation); output to `fd:N`/`/proc/self/fd`; output to a
  chosen folder; host CLI + host build. Integrated manually (not a PR merge) with
  authorship preserved; PR closed.
- 2026-07-01 — verification made non-fatal (A12): mismatch → `WARN`, output not deleted.
  Reason: the filename content-id ≠ the full NCA hash, so the check isn't authoritative.
- 2026-07-01 — batch-mode error-status fix (G08: `.catch` masked a failure) + the "Save
  on-screen log" button.
- 2026-06-22 — folder mode rewritten to the "new" pipeline (no-copy `fd:N`, writing into
  the destination descriptor without a temp-output+copy, `FOLDER_CONCURRENCY`
  parallelism, NSP verify, per-file bars in the UI). `FolderProgressUpdate` now carries
  `activeFiles: List<ActiveFolderFile>`.
- 2026-06-22 — XCZ→XCI implemented for real: `ncz_convert_xcz_to_xci` rewritten for a
  nested HFS0; added `hfs0_parse_at` / `hfs0_computed_header_size`; `.xcz`→`convertXcz`
  dispatch in both modes; `convertXcz` moved to `fd:N`. Not tested on a real file (at the
  time).
- 2026-06-22 — the batch overall bar moved to a dynamic denominator + a final 100% emit —
  a fix for premature filling (G01).
- 2026-06-22 — removed the `LegacySingleFileUI` screen and the `ElapsedTimeRow` component;
  the `None` branch in `ConversionScreen` is unreachable and left empty for an exhaustive
  `when`.
- Perf story (hw crypto, no-copy I/O, async writer, zstd `-O3`, batch parallelism,
  ThinLTO) — see [architecture.md](architecture.md) A01–A10.
