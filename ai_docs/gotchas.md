# Gotchas

Non-trivial traps. Format `G##` — a stable anchor. Add after debugging > 30 min
with a non-obvious cause.

## G01: The overall batch progress fills up before all files are unpacked
**Symptom:** in queue mode the overall bar reaches 100% long before the last files
are actually unpacked.
**Root cause:** the denominator was computed once from the **compressed** input sizes,
while "done" accumulated toward the **decompressed** output sizes (`fileTotals[i]` is
replaced with the uncompressed total as soon as a file's first progress arrives).
Different units → the "done" sum reaches the denominator too early.
**Fix:** in `MainViewModel.emitOverall()` the denominator is recomputed dynamically
from `fileTotals.sum()` (same units as the numerator). After the loop — a final emit
at 100% (throttling otherwise leaves the bar a hair below).
**How to spot:** the bar is at 100% while the status still says "Unpacking…" and files
in the queue aren't `Completed`. Folder mode doesn't suffer this: there "done" is tied
to file completion by input sizes (`structure.totalSize`).

## G02: The debug build unpacks many times slower than release
**Symptom:** conversion in the debug APK is painfully slow, in release it's fast.
**Root cause:** zstd as a CMake dependency inherits `CMAKE_BUILD_TYPE=Debug` → `-O0`
+ `-DDEBUGLEVEL=1` (internal asserts). Decompression is the hot path.
**Fix:** in `CMakeLists.txt` `libzstd_static` is force-built with
`-O3 -DNDEBUG -UDEBUGLEVEL -DDEBUGLEVEL=0` (see [architecture.md](architecture.md)
A01). Don't remove this when editing CMake.
**How to spot:** the profile shows time in zstd functions; a release build of the same
revision is many times faster.

## G03: Direct fd input reading fails on FUSE storage
**Symptom:** conversion of some files fails immediately at PFS0/NCZ parsing even though
the file is valid; the same files from other sources work.
**Root cause:** FUSE-backed scoped storage hands out a descriptor whose
`/proc/self/fd/<n>` path native cannot re-open. So the input is passed as `"fd:N"`
(`dup()+fdopen()`), but even that sometimes fails at parsing.
**Fix:** `NszConverter` has a fallback: on `ERR_OPEN_INPUT/INVALID_PFS0/INVALID_NCZ/IO`
with an active `inputPfd`, the input is copied to cache and conversion is retried (see
[architecture.md](architecture.md) A05–A06). Don't remove this retry.
**How to spot:** the status log line "Direct read failed (code …), copying to cache and
retrying", after which the file converts successfully.

## G04: The app language can't be read from DataStore
**Symptom:** storing the language in DataStore makes the locale apply with a delay / not
apply to the first UI frame.
**Root cause:** `MainActivity.attachBaseContext()` runs **synchronously before** Compose
init and before coroutines are available, while DataStore is async.
**Fix:** `language` is stored in **SharedPreferences** (synchronous read `getLanguage()`),
the other settings in DataStore. Language change → `recreate()`. See
[architecture.md](architecture.md) A08.
**How to spot:** the language "jumps" or applies only after the Activity is recreated.

## G05: PFS0 alignment must be reproduced from the source, not guessed
**Symptom:** the unpacked NSP matches reference `nsz` byte-for-byte for most files, but
for some it comes out ~32 KB smaller (SHA-256 mismatch), even though all the inner NCAs
are identical.
**Root cause:** official NSPs align the first file's data to `0x8000` by inserting a
leading gap between the string table and the first file. Reference `nsz` by default
(`fixPadding=False`) **preserves the original gap** = `entries[0].offset` from the input.
`pfs0_write_header` instead computed the gap with a heuristic "align only if the first
entry is `.tik`/`.cert`". When an aligned container started with `.cnmt.nca`, the gap was
lost → a denser, shorter file.
**Fix:** in [pfs0.c](../app/src/main/cpp/pfs0.c) `data_gap` is taken as
`files[0].data_offset - data_area_offset` (= the original `entries[0].offset`). Renaming
`.ncz`→`.nca` doesn't change name lengths, so the header/data-area size is identical to
the input and the gap transfers directly. This matches the `NszDecompressor.py` default
(`getFirstFileOffset()`). Don't bring back the `is_meta` heuristic.
**How to spot:** the output is exactly `0x8000 − header_size` smaller than the reference;
PFS0 parse shows `entries[0].offset == 0` where the source `.nsz` had it non-zero. The
NCA bodies still match (NCA name = SHA-256 of the content).

## G06: HFS0/XCI — 0x8000 alignment and non-roundtripping of full XCI
**Symptom / traps** when bringing XCZ→XCI to reference parity:
1. An HFS0 partition header is **not** aligned to 0x20 by the string table (like PFS0).
   Instead the partition's first file data is placed on the **0x8000** boundary, and the
   gap is encoded in `entry.offset` (`0x8000 − header_size`) + padded with zeros.
   `string_table_size` in the HFS0 header is the **raw** length, no padding. This is
   dictated by `nsz.Fs.Hfs0.Hfs0Stream` (`headerSize = 0x8000`, `len(stringTable)`).
2. The `hashed_region_size` and `sha256_hash` fields in HFS0 entries are **always zero**
   (both here and in the reference; this is not a bug, don't "fix" it).
3. **A full XCI (key area at 0x0, header at 0x1000) does NOT round-trip in the reference:**
   `XciStream` always stores only the first 0x200 and puts hfs0 at 0xF000, so an `.xcz`
   compressed from a full XCI cannot be correctly unpacked by reference nsz itself. There
   is **no** byte reference for full XCI. We detect it (`Xci.isFullXci()`: no `HEAD` at
   0x100 → header at 0x1000) and read it correctly, but the output has nothing to compare
   against — **needs a real sample** before making release promises.
**Fix/where:** [hfs0.c](../app/src/main/cpp/hfs0.c) `hfs0_write_header`,
[ncz_engine.c](../app/src/main/cpp/ncz_engine.c) `ncz_convert_xcz_to_xci`. Details —
[architecture.md](architecture.md) A11.
**How to spot:** the XCI output size doesn't match the reference → check that the partition
header is 0x8000 and `0x200..0xF000` is zeroed. For full XCI — the log `treating as full XCI`.

## G07: XCZ→XCI fails on an XCI with an empty partition (`file_count == 0`)
**Symptom:** XCZ→XCI aborts at partition parsing: log `Partition 'update' parse failed:
hfs0_parse: file_count 0 out of range`, then `Invalid PFS0 container`, and the finished
output is deleted.
**Root cause:** XCI gamecards routinely contain an **empty** `update` partition
(`file_count == 0`). `hfs0_parse` rejected `file_count == 0` as out of range. Also
`calloc(0, …)` may return NULL — just dropping the check isn't enough, you must skip
calloc/fread when the count is 0.
**Fix:** [hfs0.c](../app/src/main/cpp/hfs0.c) `hfs0_parse_stream` — allow 0, allocate and
read entries only when `file_count > 0` (the rest of the parse: the loop runs 0 times,
`free(NULL)` is safe). Came from PR #6 (manx98).
**Diagnosis (for next time):** the file log `nsz_debug.log` is useless here — `dbg_open`
opens it with `"w"` (rewritten on every run), and the desktop copy goes stale. What helped
was the "Save on-screen log" button (`nsz_screen_log.txt`) and `adb logcat`. If the native
log looks "empty/old" — look at the on-screen log and logcat, not the file.

## G08: `Flow.catch` in batch mode masks a failure as "Done"
**Symptom:** in "files" mode a failed XCZ→XCI showed a green "Done", with "Processed 1 of
1" below and no error text. In "folder" mode — correct.
**Root cause:** in `startBatchConversion` the flow had a `.catch { … Failed }`.
`Flow.catch` catches the exception and **completes the flow normally**, so the code after
`collect` unconditionally set `FileStatus.Completed`, overwriting `Failed`.
**Fix:** [MainViewModel.kt](../app/src/main/java/com/androNSZ/viewmodel/MainViewModel.kt)
`startBatchConversion` — remove `.catch`, let the exception reach the surrounding
`try/catch` (which sets `Failed` and never reaches `Completed`); the final summary also
adds a failure line. Folder mode isn't affected — there statuses go through
`FolderProcessor`.
**How to spot:** a file in the queue shows "Done" even though the log has an `ERROR` for it.
