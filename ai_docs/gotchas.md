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
**Diagnosis (for next time):** every log file now starts with a header carrying `Run : #N`
(A16b) — **check it before trusting a log**. One file = one run; the previous run is kept as
`*.prev.*` and anything older is gone. The same `Run #` in `nsz_debug.log` and
`nsz_folder_debug.log` means the same run. This exists because a stale log was once read as
if it were the current one after a GUI test silently failed to start. `adb logcat` remains
the quickest cross-check and receives every `DBG` even when no log file is open.

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

## G09: Spurious `Unresolved reference` errors from a dropped Kotlin compile daemon
**Symptom:** `:app:compileDebugKotlin` fails with `Unresolved reference` for
known-good, **unchanged** imported top-level functions (seen here: `getUriSize`,
`resolveToFilePath`, `countAllFiles`) — typically only in the file you just edited,
while class imports from the same packages resolve fine. In the log, next to the
errors: `e: Daemon compilation failed: Could not connect to Kotlin compile daemon`.
**Root cause:** the Kotlin compile daemon dropped its connection mid-build, and the
incremental analyzer emitted a bogus unresolved-reference cascade on package-level
**function** imports. The symbols exist and compile — this is not a code error.
**Fix:** clean rebuild to reset incremental state — Android Studio: Build → Clean
Project, then Rebuild Project; if it persists, File → Invalidate Caches / Restart.
A fresh CLI build of the same tree compiles successfully (see
[conventions.md](conventions.md) "Build and checks").
**How to spot:** the "missing" symbols are used elsewhere without error and you
didn't touch them; only function imports are flagged, class imports are fine; the
"Could not connect to Kotlin compile daemon" line is present. Contrast: a *real*
error names a symbol you actually changed or removed.

## G10: Folder mode — verify falsely fails with "cannot parse NSP container"
**Status:** **moot** — the post-conversion output re-read was removed (A12) and its code
(`nca_verifier.c` + `nativeVerifyNsp`) deleted on 2026-07-26, so there is no reopen left to
race. Kept because the underlying trap (reopening a FUSE-backed file whose write handle is
still open) applies to *any* future code that re-reads a just-written output. The write pfd
is still closed before finalize.
**Symptom:** parallel folder NSZ→NSP marks nearly every file failed with
`Verification failed: verify: cannot parse NSP container (code: -9)` and deletes the
output; the same files in single-file mode pass.
**Root cause:** the post-conversion verify (`nativeVerifyNsp` → `pfs0_parse`)
reopened the just-written output via a fresh `fopen("/proc/self/fd/N")` **while the
write descriptor was still open**. Under folder mode's parallel conversions this
races on FUSE-backed scoped storage — the fresh read handle can observe uncommitted
data, so the PFS0 header read fails. Sequential single-file mode doesn't hit it. It
was compounded by folder mode treating a verify failure as fatal (throw + delete).
**Fix:** [FolderProcessor.kt](../app/src/main/java/com/androNSZ/fs/FolderProcessor.kt)
`convertDirect` — close the write pfd **first** (forces the provider to flush),
reopen a fresh read-only descriptor from the output Uri for verify, and make verify
**non-fatal** (WARN, keep output; mirrors single-file mode). Related input-side trap:
**G03**. Verification model — [status.md](status.md).
**How to spot:** the inner NCAs all report `[VERIFIED]`/`[NCA_HASH]` (content is
fine), yet the final NSP re-parse fails — and only under parallelism.

## G11: CNMT verification config is global — set it before conversions start
**Symptom:** verification silently uses the wrong mode (falls back to the filename
check, or hashes when the toggle is off), or a data race if it were changed mid-batch.
**Root cause:** `nca_verify_config_set` in
[nca_cnmt.c](../app/src/main/cpp/nca_cnmt.c) stores the enable flag, `header_key` and
`key_area_key_application_*` in a **process-global** struct, read (never written) by all
concurrent per-file conversions under `BATCH_CONCURRENCY`. It is populated once from
`nativeSetVerification`, which the ViewModel calls **before** launching a batch/folder
job. Calling a conversion without setting it first leaves the native default (enabled,
no keys → filename fallback) — safe, but not CNMT verification. Changing it mid-batch
would be an unsynchronised write.
**Fix / contract:** always call `NszConverter.nativeSetVerification(enabled, headerKey,
keyAreaKeys)` once per job, before the first `convert`/`convertXcz` (see
[MainViewModel.kt](../app/src/main/java/com/androNSZ/viewmodel/MainViewModel.kt)
`startBatchConversion` / `startFolderConversion`). Never re-set it while files are
converting. The single settings source makes a differing mid-batch value unreachable in
practice.
**Edge cases that fall back to the filename check (`VERIFIED … (by name)`), by design:**
META NCAs with a non-zero rights-ID (title-key crypto — doesn't occur in practice) and
NCA2 META headers (ancient; the sequential 6-sector XTS decrypt mis-reads their FS
headers). Both are guarded → fallback, never a crash.

## G12: Gradle test worker can't connect — `BindException` on `:testDebugUnitTest`
**Symptom:** `:app:testDebugUnitTest` dies with `Test process encountered an unexpected
problem … finished with non-zero exit value 1`; with `--info`, `ConnectException: Could
not connect to server … BindException: Cannot assign requested address`. Compile/build
tasks succeed — only the forked test JVM fails.
**Root cause:** same localhost IPv4/IPv6 mismatch as the Gradle daemon (G09-adjacent /
[conventions.md](conventions.md) "Daemon won't connect"), but the **test executor is a
separate forked JVM** that does **not** inherit `GRADLE_OPTS`, so
`-Djava.net.preferIPv4Stack=true` set there never reaches it.
**Fix:** export `_JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"` (inherited by every
JVM, including forked workers) alongside the usual `JAVA_HOME`/`GRADLE_OPTS`:
```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"
$env:GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
$env:_JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"
.\gradlew.bat :app:testDebugUnitTest --offline
```
Android Studio's bundled runner handles this itself; the workaround is only for headless
CLI test runs.

## G13: `file://` inputs silently mis-name the output / zero the progress bar
**Symptom:** files chosen through the in-app picker (raw `file://` uris, A14) unpack to
an output called `input.nsp`, and in a multi-file queue the overall progress bar denominator
is 0 (bar shows "0/0" / never fills).
**Root cause:** the name/size helpers query `ContentResolver`, which returns **nothing** for
a `file://` uri. `queryFileName` ([FileUtils.kt](../app/src/main/java/com/androNSZ/util/FileUtils.kt))
then falls through to its `"input.nsz"` default (→ output `input.nsp`), and `getUriSize`
([FormatUtils.kt](../app/src/main/java/com/androNSZ/util/FormatUtils.kt)) returns `0`, so
`startBatchConversion`'s `fileTotals.sum()` denominator is 0.
**Fix:** both helpers short-circuit `scheme == "file"` — `queryFileName` → `File(uri.path).name`,
`getUriSize` → `File(uri.path).length()`. (`resolveDisplayName` already returned
`lastPathSegment` = the file name for `file://`, so it needed no change.)
**How to spot:** the output NSP is named `input.nsp` regardless of the source, or the batch
overall bar reads "0/0" while single-file conversions still work.

## G14: Judge Compose scroll smoothness only on a non-debuggable build
**Symptom:** a Compose list (seen here: the file picker's `LazyColumn`,
[FilePickerScreen.kt](../app/src/main/java/com/androNSZ/ui/screen/FilePickerScreen.kt))
scrolls janky / low fps even after the per-row work is already cheap. Optimizing the
code further barely moves the needle.
**Root cause:** the `debug` build is `debuggable=true` and has no baseline profile, so ART
runs Compose's hot paths in a slow, non-AOT mode — scrolling looks far worse than it ever
does in an optimized build. This is a build-type artifact, not a code bug (same spirit as
**G02**, where debug decompression is many times slower). It's easy to chase phantom
code fixes for jank that simply isn't there in release.
**Fix / how to measure:** there's a dedicated `benchmark` build type in
[app/build.gradle.kts](../app/build.gradle.kts) — release-optimized (R8 + shrink) but
debug-signed and `isDebuggable=false`, so it installs straight onto a device. Judge scroll
performance there: `.\gradlew.bat :app:installBenchmark`. Do **not** conclude a Compose UI
is slow from `assembleDebug` alone. (Genuine per-frame code costs still matter and were
fixed here too — the picker pre-resolves each row's `java.io.File` metadata off the main
thread instead of calling `isDirectory`/`length()` during composition; those are blocking
stat syscalls. But that fix was only visible once tested on `benchmark`.)
**How to spot:** the same revision scrolls smoothly on `benchmark` and janky on `debug`;
no state read changes during the fling (existing rows aren't recomposing), yet debug still
stutters.

## G15: GitHub API returns HTTP 403 without a `User-Agent` header
**Symptom:** the in-app update check always lands in the "check failed" branch; the
request to `api.github.com/repos/.../releases/latest` comes back `403` even though the
same URL opens fine in a browser and the repo/releases are public.
**Root cause:** GitHub's REST API **rejects any request without a `User-Agent`**. A raw
`HttpURLConnection` sends none by default, so every call 403s regardless of network or
auth. (Unauthenticated calls are also rate-limited to 60/hour per IP — fine for an
occasional, once-a-day-throttled check, but not for polling.)
**Fix:** [UpdateChecker.kt](../app/src/main/java/com/androNSZ/util/UpdateChecker.kt) sets
`User-Agent` (and `Accept: application/vnd.github+json`) on both the release query and
the APK download. Don't remove them. The update feature deliberately uses the JDK's
`HttpURLConnection` + `org.json` (no OkHttp/Ktor dependency).
**How to spot:** the failure dialog shows "HTTP 403"; adding the `User-Agent` header
makes the exact same request succeed.

## G16: Sideloaded APK install needs the permission + FileProvider + a per-app grant
**Symptom:** tapping "Update" downloads the APK but the system installer never opens, or
throws (`SecurityException` / "parse error" / nothing happens).
**Root cause:** three separate requirements for launching the package installer from a
sideloaded update, any one of which silently breaks it:
1. `REQUEST_INSTALL_PACKAGES` must be declared in the manifest.
2. Modern Android rejects a raw `file://` uri handed to the installer — it must be a
   `content://` uri from a `FileProvider` (declared in the manifest, backed by
   `res/xml/file_paths.xml`), launched with `FLAG_GRANT_READ_URI_PERMISSION`.
3. Even with both, on API 26+ the user must have granted **this app** the "install
   unknown apps" permission (`canRequestPackageInstalls()`), which isn't a runtime
   dialog — you have to send them to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
**Fix:** [ApkInstaller.kt](../app/src/main/java/com/androNSZ/util/ApkInstaller.kt) checks
`canRequestPackageInstalls()` first (routing to settings if needed), then serves the APK
via `FileProvider.getUriForFile(...,"${'$'}{packageName}.fileprovider", apk)` with the
read-grant flag. Provider + permission live in
[AndroidManifest.xml](../app/src/main/AndroidManifest.xml); the shared cache path is in
`res/xml/file_paths.xml`.
**How to spot:** the download completes (the cached `AndroNSZ_<v>.apk` exists) but the
installer doesn't appear — check, in order, the manifest permission, the FileProvider
authority match, and the "install unknown apps" toggle for the app.

## G17: Unpacking speed is bounded by a *drifting* flash write ceiling — benchmark accordingly
**Symptom:** raising the worker count doesn't raise total throughput; instead each file
gets slower (aggregate pinned at roughly the same MB/s while per-file ≈ ceiling ÷ N). Or:
two runs of the identical benchmark disagree by 2× for no visible reason.
**Root cause:** unpacking is **write-bound**, not CPU-bound (see
[architecture.md](architecture.md) A15). Worse, the ceiling is not a constant: a fresh UFS
absorbs ~1000 MB/s, but after sustained multi-GB writing its SLC cache is exhausted and it
falls to ~450–500 MB/s. Recovery needs *minutes* of idle, not seconds. This is not thermal
throttling — check before blaming heat: `/sys/class/thermal/thermal_zone*/temp` stayed
~50 °C and per-core `cpufreq/scaling_cur_freq` showed no capping.
**How to benchmark so the numbers mean something:**
- Separate the two regimes deliberately: measure "fresh" (device idle for minutes) and
  "sustained" (after ≥20 GB written) as *different* data points. Never average them. The
  same 4-file batch measured **967 MB/s fresh and 680–740 MB/s worn** — a 40 % spread from
  the flash alone, easily mistaken for a code regression. Cross-time A/B over a long
  session is worthless; interleave the variants or compare within one run.
- **Make the file count a multiple of the worker count.** With 5 files and 4 workers the
  first wave runs 4-up (~1000 MB/s) and then the 5th runs *alone* at single-file speed; the
  "average aggregate" (total bytes ÷ wall clock) is dragged down by that tail and misleads
  badly. It also made a 7-worker config look competitive with 4 — at 7 all 5 files ran in
  one wave, so it had no tail at all. (The same tail effect must not be read as storage
  degradation by any throughput-reactive logic — that bug was found and fixed here.)
- Isolate the resource: run the CLI to `/dev/null` (`--verify`) for CPU+memory only, to
  `/data/local/tmp` for raw UFS, to `/sdcard` for the FUSE path. Comparing those three
  attributes the ceiling to the right layer.
- Warm the input page cache (`cat file > /dev/null`) when the aim is to measure *writes*.
- Sample `/proc/stat` busy% during the run: cores idling means I/O-bound, not CPU-bound.
  `/proc/pressure/*` (PSI) would be ideal but needs root — shell gets EACCES.
- Pin with `taskset` to compare big vs little cores (this device: cap 1024 vs 569, only a
  ~1.2× spread on decompression, so core choice matters far less than expected).
**Toolchain:** cross-compile the CLI with
`aarch64-linux-android31-clang`, linking the arm64 `libzstd.a` already built under
`app/.cxx/<cfg>/arm64-v8a/_deps/zstd-build/lib/`, and `-llog` (for `nsz_debug.c`).
`--verify` decompresses without writing; the CLI prints a `BENCH elapsed=… MBps=…` line.
**Trap when driving adb from Git Bash on Windows:** MSYS rewrites `/data/local/tmp` into a
Windows path, so `adb push` fails with `remote secure_mkdirs failed`. Prefix the command
with `MSYS_NO_PATHCONV=1`.

## G18: Comparing worker counts within one session is biased — the flash gets slower
**Symptom:** a search for the best worker count reports that whichever level was measured
**first** is the best, run after run, on any device and in any order. Or: a level looks
20 % slow, you act on it, and the next session disagrees.
**Root cause:** three biases stack, and each one alone is enough to produce a confident
wrong answer.
1. **Flash drift.** Sustained writing exhausts the SLC cache: the *same* 4 workers
   measured 1013 MB/s fresh and ~716 MB/s worn (**G17**, [architecture.md](architecture.md)
   A15). A schedule of "10 minutes at 8 workers, then at 7, then at 6" therefore measures
   every later level on a more tired flash. The decline is real; its attribution to the
   worker count is not.
2. **Partially loaded stretches.** When fewer files remain than there are workers,
   throughput falls for a structural reason. The batch tail was once read as storage
   degradation by throughput-reactive logic — that exact bug (G17).
3. **Writeback stalls.** Deep, short dips. A mean follows them down; a median does not.
**Fix / how to measure so the numbers mean something:**
- **Sweep the levels once each way** (1→N, then N→1) so every level is measured once on a
  fresher flash and once on a more worn one, and the drift cancels in the average. Never
  one long block per level, and never a single pass.
  [RealFileBenchmark.kt](../app/src/main/java/com/androNSZ/util/RealFileBenchmark.kt) does
  this.
- **Give every level the same work**, so none of them wears the flash more than the others
  — here that means the same file unpacked whole, N times over.
- **Score each level only against the other levels of its own round**, then average those
  ratios across rounds (`ThroughputAnalysis.levelScores`). Comparing raw MB/s across
  rounds is what produces bias 1.
- **Avoid partially loaded stretches by construction.** All N conversions of a level start
  together and the level ends when the last finishes, so there is no ramp-up or tail to
  filter out. (The window-filtering machinery that used to do this for in-job measurement
  went with the in-job search — A07.)
- **Require a ≥5 % margin** before believing a difference: repeat runs of an identical
  configuration disagree by 3–9 % on device, so anything smaller is noise. A tie inside
  that band resolves to the **larger** thread count — too few threads costs real time,
  too many costs nothing measurable (A07).
**How to spot:** the winning level correlates with measurement order rather than with its
value; or the verdict flips when you reverse the schedule. Reversing the order is the
cheapest way to test for this bias — a sound method gives the same answer both ways.
**Trap in the tooling itself:** a benchmark on this write path must reuse `async_writer.c`
*including* its `sync_file_range` + `FADV_DONTNEED` pacing — without it parallel writes
measure 2.2× slower (A15), so a "simpler" loop would characterise a system the app does
not have. Keeping the pacing is necessary but **not sufficient**: see **G20** for the
fourth bias, which bit exactly here.

## G19: Driving the GUI over `adb shell input tap` reliably
**Symptom:** a tap lands on the wrong element — a menu opens "About" instead of
"Settings" — or a whole step silently no-ops and the script keeps going as if it
succeeded, so every step after it is untrustworthy too.
**Root cause:** two failure modes stack. (1) Hardcoded coordinates drift: the same
button moves depending on what's above it on screen (list length, banners, scroll
position), so a coordinate that worked once stops working. (2) Text lookup by prefix
false-matches: `grep -F 'text="Выбрать файлы'` (no closing quote) also matches
`"Выбрать файлы/папки"` — a *longer* label that happens to start the same way — and
taps the wrong row.
**Fix / methodology:**
- **Dump → locate by exact text → tap the computed centre → verify the expected text
  is now on screen**, never blind coordinates. `adb shell uiautomator dump
  /sdcard/u.xml` gives `text="..."` + `bounds="[x1,y1][x2,y2]"` pairs to parse.
- **Match with the closing quote** (`text="Выбрать файлы"`, not `text="Выбрать
  файлы`) so a shorter label never matches a longer one that starts the same way.
- **Fail loudly when the expected element is missing** — abort the step rather than
  guessing a fallback coordinate. A silent miss on step N makes every later step's
  result meaningless.
- File rows in the in-app picker are marked via the **CheckBox on the left** (x≈91),
  not by tapping the filename text.
- `MSYS_NO_PATHCONV=1` is needed for `adb push`/shell path arguments in Git Bash, but
  **not** for the native cross-compile script — there it mangles the `/c/...` source
  paths clang needs. Same shell, different commands, different flag.
- Device note: POCO/HyperOS, Android 16, SM8735 — `input tap` injection works here.
  An older MIUI test device once blocked it; don't assume that limitation still holds.
**Minimal reusable pattern** (bash):
```bash
find_tap() {  # find_tap "exact button text"
   local d; d=$(adb shell 'uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml' \
      | tr '<' '\n' | grep -F "text=\"$1\"")
   [ -z "$d" ] && { echo "!! '$1' not found"; return 1; }
   local b; b=$(echo "$d" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | head -1)
   adb shell input tap $(( ($(echo $b|cut -d' ' -f1)+$(echo $b|cut -d' ' -f3))/2 )) \
                        $(( ($(echo $b|cut -d' ' -f2)+$(echo $b|cut -d' ' -f4))/2 ))
}
```

## G20: A write benchmark that doesn't force durability measures the page cache
**Symptom:** the benchmark reports speeds the storage cannot physically absorb, and it
finishes far faster than its own description claims — 6 GB "in a couple of minutes" done
in three seconds. Or: the tuned-for value disagrees with what a real job achieves, by more
than wear can explain.
**Root cause:** returning from `fwrite` means the data reached the page cache, nothing
more. Draining an async writer thread does not change that, and neither does the pacing:
`sync_file_range(fd, …, SYNC_FILE_RANGE_WRITE)` only *queues* writeback and returns
immediately — waiting needs `SYNC_FILE_RANGE_WAIT_AFTER` or `fdatasync`. If the benchmark
then deletes the file, part of the data never reaches the flash at all. Measured on the
reference device (SM8735 + UFS), 512 MB to `/data/local/tmp`:

| | Throughput |
|---|---|
| `dd` without `fsync` | 1.8 GB/s |
| `dd` with `conv=fsync` | 897 MB/s |

**The second-order trap, which is the reason this is its own entry.** The scale error is
obvious once suspected; the ranking error is not. Writeback was kicked once per fixed
interval (64 MB), while each stream wrote `block ÷ N` bytes — so *the fraction of each
block actually pushed toward storage varied with N*, the very parameter being compared.
The level that flushed least measured fastest. That is a fourth bias on top of the three
in **G18**, and unlike those it is created by the measuring tool rather than by the device.
**Fix — either:**
- force the data out before stopping the clock (`fdatasync` on each stream, after draining
  the writer and *before* deleting anything), which also makes the forced-I/O fraction
  100 % at every level and kills the second-order trap by construction; or
- measure **whole real runs** long enough that the kernel's dirty-page throttling drags the
  average back to the true rate. This is what the app does now
  ([RealFileBenchmark.kt](../app/src/main/java/com/androNSZ/util/RealFileBenchmark.kt)) —
  and it is the more trustworthy of the two, because it needs no assumption about what the
  synthetic pattern shares with real work.

Note that **production deliberately does not sync** — a conversion owes no durability
guarantee and paying for one would cost real wall-clock time. Only the *measurement* needs
it, which is exactly why the difference is easy to overlook.
**How to spot:** compare the benchmark's best level against a real job's aggregate on
comparably rested storage (G17), and against a single-stream `dd conv=fsync`. If the
benchmark is the fastest of the three, it is not measuring the flash.
**History:** found 2026-07-27 in `nativeBenchWrite`, which reported 1520 MB/s where the
real pipeline managed 699. Fixed with `fdatasync`, then the whole synthetic test was
deleted for an unrelated reason (A07) — the trap is recorded here because it belongs to
the next benchmark, not to that code.

## G21: "All files access" granted mid-session never reaches the running app
**Symptom:** the user grants "All files access", returns to the app, and the file picker
still shows the permission gate. Looking for the toggle afterwards, they don't find it in
the app's permission list at all.
**Root cause:** three separate things, which is why this looks unexplainable from inside
the app:
1. `MANAGE_EXTERNAL_STORAGE` is a **special** permission. It is not in the ordinary
   per-app permission list — it lives under Settings → Apps → Special app access → All
   files access. Nothing is broken when it is "missing"; the user is looking at the
   wrong screen.
2. The gate state was read once into a Compose `remember` and re-read only in the
   settings-launcher callback. A grant that arrives by any other route (the user finds
   the page by hand, a vendor prompt, `adb shell appops set`) never invalidates it.
3. **The storage mount mode is inherited when the process is forked.** Stock Android
   kills the app when the permission flips, so this is invisible there; HyperOS does
   not, leaving a process where `Environment.isExternalStorageManager()` returns true
   while `java.io.File` still cannot list the storage root. Only a restart fixes it.
**Fix:** [StoragePermission.kt](../app/src/main/java/com/androNSZ/util/StoragePermission.kt)
adds `canBrowseStorage()` — an actual listing of the storage root — as the *effective*
check on top of `isGranted()`, plus a fallback chain of settings intents (per-app page →
global list → app details) since not every skin has the per-app page.
[FilePickerScreen.kt](../app/src/main/java/com/androNSZ/ui/screen/FilePickerScreen.kt)
re-reads the permission on every `ON_RESUME` and, when granted but unusable, shows a
"restart needed" gate wired to
[AppRestart.kt](../app/src/main/java/com/androNSZ/util/AppRestart.kt).
**How to spot (adb, read-only):**
```
adb shell appops get com.androNSZ MANAGE_EXTERNAL_STORAGE   # "allow" => the OS granted it
adb shell ps -A -o PID,ETIME,NAME | grep androNSZ           # uptime > time since grant?
```
If the op says `allow` but the process is older than the grant, it is case 3, not a bug in
the check.
**History:** found 2026-07-28 on POCO / HyperOS OS3.0 (Android 16), uid op `allow` for
10 minutes against a 50-minute-old process.
