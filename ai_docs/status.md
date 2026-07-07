# Status (updated: 2026-07-03)

## Working

- Conversion in all three modes: single file (legacy, no UI), file queue, folder.
  NSZ→NSP (debugged) and XCZ→XCI (**tested on a device 2026-07-01, both modes**,
  after the empty-partition fix — [gotchas.md](gotchas.md) G07).
- Folder mode moved to the "new" pipeline (like the file queue): no-copy `fd:N`
  input, writing the result straight into the destination descriptor, parallelism
  via `FOLDER_CONCURRENCY`, NSP verify, a GUI bar per active file.
- The perf pipeline in `stable`: hardware AES-CTR + SHA-256 with a software
  fallback, no-copy input via `fd:N`, async writer, zstd `-O3`, ThinLTO,
  core-adaptive batch parallelism (`BATCH_CONCURRENCY` 1..3). Result: 2 GB ~7 s.
- NCA SHA-256 verify — **non-fatal** (mismatch → `WARN`, output kept, not deleted).
  The filename check is an approximation, see [architecture.md](architecture.md) A12.
- Output to a chosen folder (SAF tree / file uri) — in all modes, with a fallback
  to Downloads (`NszConverter.createOutputUri`).
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
