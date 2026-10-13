# Status

Snapshot of 2026-09-12. State only — the reasons live in `architecture.md` and
`gotchas.md`, the history in `journal.md`.

## Working

- **All three modes**: single file (legacy, no UI), file queue, folder. NSZ→NSP
  debugged; XCZ→XCI tested on device 2026-07-01 in both modes, after the
  empty-partition fix (G07).
- **Folder mode is on the same pipeline as the file queue**: no-copy `fd:N` input,
  output written straight into the destination descriptor, parallel unpacking, inline
  NSP verify, a bar per active file.
- **The performance pipeline in `stable`** (A15): hardware AES-CTR and SHA-256 with a
  software fallback, no-copy input, async writer with page-cache pacing, zstd `-O3`,
  ThinLTO. The thread count is chosen per device (A07) — half the cores by default, or
  measured by the real-file test — and lives on its own settings screen.
- **NCA verification is CNMT-based and non-fatal** (A12): computed inline during
  decompression with no output re-read; a mismatch is a `WARN` plus a `CORRUPTED` tag,
  and the output is kept.
- **Custom in-app file picker** (A14), browsing the raw filesystem under
  `MANAGE_EXTERNAL_STORAGE`, replaces the SAF input pickers in both modes. Interactive
  flows tested by hand — adb tap injection is blocked on the MIUI test device.
- Output to a chosen folder in all modes with a Downloads fallback; "Save on-screen
  log"; EN/RU with in-app language switching; an overall bar plus a bar per active
  file, throttled separately (A09).

## Fragile points

- **Unpacking is write-bound, and the ceiling drifts** (A15, G17). The flash halves in
  speed once its SLC cache is spent, and the knee's position is device-specific. The
  table in A15 is one phone in one wear state, not the app's tuning.
- **Only the aggregate matters in parallel mode** (A07). Per-file speed is the ceiling
  divided by N, and is a target only when unpacking a single file. A cap of four workers
  was once justified by per-file speed and had to be retracted.
- **Measuring thread counts is biased by order** (G18), and a write benchmark that does
  not force durability measures the page cache (G20) — where the I/O it forces can itself
  depend on the parameter under test, corrupting the *ranking* and not merely the scale.
  Sweep each way, require a ≥5 % margin, and check that reversing the schedule does not
  change the verdict.
- **The real-file test writes 70–145 GB** on an 8-core phone. That is deliberate — it is
  what makes the numbers trustworthy — but it wears the flash hard, so do not run it
  casually or benchmark anything else straight afterwards.
- **`async_writer`'s `sync_file_range`/`FADV_DONTNEED` pacing is load-bearing.** It reads
  like a removable experiment; disabling it collapses parallel writes ~2.2×.
- **The `VERIFIED`/`CORRUPTED` tags carry the per-file UI verdict.** There is no output
  re-read pass any more, so renaming one silently breaks the file cards — and a mismatch
  must never return `NCZ_ERR_HASH_MISMATCH` (A12).
- **No-copy I/O depends on provider behaviour** and leans on the temp-copy fallback
  (G03); do not remove the retry. Folder *output* has no such fallback — it writes
  through `/proc/self/fd` into an arbitrary folder, and FUSE failures are possible on
  rare firmwares.
- **Full XCI has no byte reference** (G06, A11). Trimmed XCI is device-tested; full XCI
  — key area at 0x0, header at 0x1000 — is not, because the reference does not
  round-trip it. It needs a real sample before any release promise.
- **Progress numerator and denominator must stay in the same units** (G01).
- **Parity with the Python reference.** `aes_*`, `sha256`, `ncz_decompress`, the
  container parsers and the JNI signatures match nsz and are debugged. Change only on
  request.
- **Auto-formatters strip indentation on blank lines**, breaking the 3-space style.
- **Dead legacy code**: `MainViewModel.startConversion()`, `pickFile()` and
  `selectedUri`/`selectedName` have had no caller since `LegacySingleFileUI` went.

## Deferred

- **Combined picker mode** — marking files and folders in one pass, and then dropping
  the two separate modes. The picker is mode-scoped for now (A14).
- **Multi-folder selection**: the folder pipeline is single-root.
- **A synthetic NSZ for the thread-count test**, so it needs no file from the user. The
  build ships only the zstd *decompressor*, so this wants a compressor plus key material.
  Since the synthetic write test was deleted (A07) it is the only route left to a
  zero-input calibration — worth revisiting if "pick a 1–2 GB file" proves a barrier.
- **Picker row-spacing controls**: the gear menu's two live fields are a temporary aid.
  Once good values are found, hardcode them and remove the fields.
- **Block-level parallelism in C** — branch `block-parallel-wip`, commit `aa7cf73`.
  Currently broken and not in `stable`; deferred for instability, and the performance
  target is met by other means.
- **Per-core verification layout** (A12, step 3): cores are paired one decompress plus
  one verify; the idea is to put SHA-256 on one or two cores and about six of eight on
  decompression.
