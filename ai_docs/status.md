# Status (updated: 2026-07-01)

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
- **Filename verification is not authoritative.** The content-id in the name is half the
  NCA hash; the correct check is against the CNMT (A12). A mismatch is currently
  non-fatal (doesn't delete the output) but also not a guarantee. Don't return
  `NCZ_ERR_HASH_MISMATCH` on this path.
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
  1. an enable/disable verification setting — probably **off** by default (low error
     probability; other nsz developers often disable it);
  2. proper CNMT-based verification (full NCA hash vs `Cnmt.contentEntries[].hash`), as
     in the reference;
  3. per-core layout optimization: cores are currently paired (1 decompress + 1 verify);
     the idea — put all SHA-256 verification on 1–2 cores, ~6 of 8 on decompression, 1
     for system/GUI.

## Decision log

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
