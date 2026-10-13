# Subsystem: Kotlin layer

Jetpack Compose UI + all conversion logic. A single Activity (`MainActivity`)
hosts `AndroNSZApp`. State and orchestration — in `MainViewModel`.

## Navigation

`MainActivity` → `AndroNSZApp` routes by `vm.currentScreen`. Navigation is a
**screen stack** in `MainViewModel`: `_screenStack` (`mutableStateListOf`, starting
at `Screen.ModeSelection`), `navigateTo(screen)` pushes, `navigateBack()` pops (not
below one). Screens (`model/Screen.kt`): `ModeSelection`, `Conversion`, `About`,
`Settings`, `ThreadSettings`, `FilePicker(mode)` (a data class carrying `PickerMode`).

`MainActivity.attachBaseContext()` synchronously reads the language and wraps the
context with the right `Locale` (per-app language, see
[../architecture.md](../architecture.md) A08). `onCreate()` cleans the temp cache
(`TempFileManager.cleanupManagedCache`).

## Screens (`ui/screen/`)

- **AndroNSZApp.kt** — the navigation root. On start `vm.checkKeys` + `vm.loadSettings`
  via `LaunchedEffect`; hosts the SAF launchers (output folder, prod.keys) and the
  `FilePicker` branch (routes its result to `addFilesToQueue` / `selectFolderFromFile`).
- **FilePickerScreen.kt** — custom split-screen file picker over the raw filesystem
  (`java.io.File`), gated by `MANAGE_EXTERNAL_STORAGE` (A14). `PickerMode.FilesOnly`
  multi-selects files; `PickerMode.FoldersOnly` selects one folder. Replaces the SAF
  input pickers in both modes. Hands the engine `file://` uris. (The gear menu's live
  row-spacing fields are a temporary tuning control.)
- **ModeSelectionScreen.kt** — mode selection: `SingleFiles`, `FolderMode` or
  `Combined` (all routed through `onModeSelected` → the `Conversion` screen).
  Overflow menu: prod.keys, change output folder, Settings, About.
- **ConversionScreen.kt** — renders by `conversionMode`: `SingleFilesUI` (SingleFiles),
  `FolderModeUI` (FolderMode) or `CombinedModeUI` (Combined). The `None` branch is
  unreachable (on this screen) and empty — needed only for an exhaustive `when`.
- **SettingsScreen.kt** — a grouped list (no cards) built from `ui/components/SettingsItem.kt`:
  section headers, 56 dp rows with the value in a compact dropdown pill on the right,
  dividers between sections, and a footer with the version and a GitHub link. Sections:
  *Appearance* (language — change → `recreate()`; theme; accent mode + the always-visible
  `AccentPresetRow`), *Unpacking* (a row into `ThreadSettings`, stats format `StatsFormat`),
  *Updates* (update banner). The `ThreadSettings` subtitle is computed with the same
  `resolveConcurrency` a job uses, so it cannot drift from reality.
  Accent: the first swatch is `DefaultAccent` and means `AccentMode.DEFAULT`, the rest set
  `CUSTOM` + that color; in `SYSTEM` mode nothing is marked. The color wheel and the hex
  field are gone, and the `ColorWheelPicker` component was deleted with them.
  The **verification switch is commented out** — CNMT verification is now always on:
  `MainViewModel.loadSettings` pins `verificationEnabled = true` and writes it back, so a
  device where it was once turned off does not stay unverified with no control to fix it.
  The parameters and strings are kept, so restoring the row is one uncomment away.
- **ThreadSettingsScreen.kt** — everything about parallel unpacking, in the same section
  grid as Settings. Opens with a `primaryContainer` card holding the number a job would use
  right now (via `resolveConcurrency`) and one line saying where it came from; then a radio
  group (`SettingsRadioRow`) for the `ThreadMode`, with the manual slider unfolding under
  its own option; then the real-file test with a bordered results table (threads / MB/s /
  score, the chosen level tinted). While the test runs the card becomes a live progress
  (`RealFileBenchmark.Progress`, 5 ticks a second: the level *in flight*, its running
  speed, "run N of M", a determinate bar — display only, the verdict is still end-to-end)
  with a Cancel button, the mode rows are disabled, and the test section is hidden. Split off the main screen because it serves two audiences — one number for most
  users, a measurement rig for the rest.
- **AboutScreen.kt** — app information.

## Conversion modes (`ui/conversion/`)

- **SingleFilesUI.kt** — file queue: adding, list, an overall bar + a bar per actively
  converting file (`activeFileProgress`), the contextual "Unpacking…/Unpacked" status +
  a timer.
- **FolderModeUI.kt** — folder: structure info, tree, an overall bar + a bar per actively
  unpacking file (`folderActiveFiles`) + average speed at the end (like `SingleFilesUI`).
  The progress + stats + log block is extracted as `FolderStyleProgressAndStats(vm)` and
  reused by combined mode.
- **CombinedModeUI.kt** — combined files + folders: one editable list (`vm.combinedItems`)
  showing folders as expandable containers (reusing `FolderTreeView`) and standalone
  NSZ/XCZ as queue cards (reusing `FileQueueItem`). Reuses the folder-* progress/stats via
  `FolderStyleProgressAndStats`. Unpacks straight into the output folder (no wrapper).

## MainViewModel

The hub of state and logic. Key field groups:
- Navigation/mode: `_screenStack`/`currentScreen`, `conversionMode`, `keysInstalled`.
- Queue: `fileQueue`, `currentFileIndex`, `batchOverallProgress`,
  `batchProcessedFiles/TotalFiles`, `activeFileProgress` (by file index).
- Folder: `folderStructure`, `folderOverallProgress`, `folderActiveFiles` (the list of
  actively converting files for the per-file bars), `folderProcessedFiles/TotalFiles`,
  `folderAverageSpeedMBps`, `folderLogPath`.
- Combined: `combinedItems` (the editable file+folder selection) + `combinedScanning`.
  Merged into the folder-* fields by `rebuildCombinedStructure`, so it drives the same
  progress/stats; `setCombinedSelection`/`removeCombinedItem` edit the selection.
- Common: `isConverting`, `progress`, `elapsedMs` (timer), `statusMessage`, `statusLog`,
  `isSuccess`, `compact2Stats`.
- Settings: `statsFormat`, `outputFolderUri`, `appLanguage`.

Key methods: `checkKeys/installKeys/deleteKeys`, `loadSettings`, `saveLanguage`,
`saveOutputFolder` (with a persistable SAF permission), `saveStatsFormat`,
`startBatchConversion` (queue, in parallel — A07), `startFolderConversion` and
`startCombinedConversion` (both via the shared `runFolderStyleConversion`, differing only
in `FolderProcessor.processFolder` vs `processCombined`), `resetConversionState`. Folder
selection: `selectFolder` (SAF tree) and `selectFolderFromFile` (raw path, from the in-app
picker) share `scanFolderInto`, which differs only by the scanner (`FolderScanner` vs
`RawFolderScanner`).

> **Dead legacy code:** `startConversion()`, `pickFile()`, `selectedUri`, `selectedName`
> remain after removing `LegacySingleFileUI` and are no longer called (see
> [../status.md](../status.md)).

## NszConverter (JNI wrapper)

A singleton over `libAndroNSZ`. `convert()` (NSZ→NSP) and `convertXcz()` (XCZ→XCI):
1. Resolve the input `Uri` → a native path: preferably `"fd:N"` (no-copy), otherwise a
   temp copy (A05/A06).
2. Create the output file in Downloads (or `outputFolderUri`) via MediaStore, path
   `/proc/self/fd/<fd>`.
3. `nativeConvert`/`nativeConvertXcz`, progress is streamed via `callbackFlow`
   (throttling — A09).
4. Derive the verify verdict from the engine's **inline** hashing tags: a per-call
   `VerifyTracker` (a `StatusCallback` decorator) watches for `VERIFIED` / `CORRUPTED`
   and reports `VerifyStatus` via `onVerified`. There is **no** post-conversion
   output re-read any more — that pass cost a full re-read of the output, which is
   expensive on write-bound storage (A15), and was deleted along with `nca_verifier.c`.
   Both `convert` and `convertXcz` report a verdict.
5. Temp cleanup; on a FUSE failure — fall back to a temp copy and retry (G03).

`startBatchConversion`/`startFolderConversion` in **MainViewModel** parse the keys
(`KeysParser.parseHeaderKey` + `parseKeyAreaKeys`) and call
`NszConverter.nativeSetVerification(verificationEnabled, headerKey, keyAreaKeys)` **once**
before launching the job (global native config, read-only during conversion — G11). That
single call is the only place keys are passed; `convert`/`convertXcz`/`convertDirect` no
longer take a `headerKey`/`verifyKey` parameter. With the toggle off nothing is hashed, so
the verdict is `NOT_CHECKED`.

## Other modules

- **Constants.kt** — `PROGRESS_BAR_UPDATE_INTERVAL_MS=100`,
  `PROGRESS_NUMERIC_UPDATE_INTERVAL_MS=500` (A09).
- **data/SettingsRepository.kt** — a singleton. `language`/`theme`/`accent`/
  `verification_enabled` in SharedPreferences (synchronous — read at startup and at
  conversion start), `statsFormat`/`outputFolderUri` — in DataStore (Flow).
  `verification_enabled` defaults to **ON** (A12).
- **model/** — `ConversionMode` (None/SingleFiles/FolderMode/Combined), `StatsFormat`
  (COMPACT/COMPACT2/COMPACT3/DETAILED), `ConversionProgress` (done/total/speed),
  `FileEntry` (uri/name/size/status), `FolderConversionResult` (operation types and
  summary), `FolderStructure`/`FileNode`, `CombinedItem` (one file/folder in the
  combined selection), `LogEntry`, `Screen`, `PickerMode`
  (FilesOnly/FoldersOnly/FilesAndFolders — drives the in-app picker).
- **nut/** — `KeysManager` (stores prod.keys in `filesDir`), `KeysParser` (extracts
  `header_key` (32 bytes) and, for CNMT verification, every `key_area_key_application_XX`
  via `parseKeyAreaKeys` → 17-byte records `[generation][16-byte key]`).
- **fs/** — `FolderScanner` (recursive `DocumentsContract` walk → tree + NSZ/XCZ lists),
  `RawFolderScanner` (the same walk over a `java.io.File` tree → `FolderStructure` with
  `file://` uris, for the in-app picker's folder mode — A14),
  `FolderProcessor` (NSZ→NSP, XCZ→XCI, everything else → copy; preserves structure,
  continues on errors). Since 2026-06-22 it mirrors the "new" file-mode pipeline: phase 1
  builds the output-folder tree and a flat `WorkItem` list; phase 2 runs files in parallel
  via the caller's `WorkerPool` (A07). Phase 2 is factored into the private `executePlan`,
  shared by `processFolder` (wraps everything in one `<name>_unpacked` folder) and
  `processCombined` (combined mode: plants the top level straight into the output base — no
  wrapper — appending `_unpacked` to a folder name only on collision). Input is read via `fd:N` (no-copy, FUSE fallback to
  temp), the result is written **straight** into the destination descriptor (no
  temp-output+copy); the verify verdict comes from the engine's inline `VERIFIED`/`CORRUPTED`
  tags (non-fatal — output kept). Progress: a per-file `ProgressThrottler` → `FolderProgressUpdate.activeFiles`.
  `TempFileManager` (`cacheDir`, `andronsz_<UUID>_<name>.<ext>`), `FolderLogWriter`
  (thread-safe log writing under a `Mutex`; **truncates and rotates** on construction, one
  instance per run — A16b).
- **util/LogFiles.kt** — the logging invariant in one place: `rotate(file)` (current →
  `*.prev.*`, older dropped), `prevOf(file)`, and `banner(runId, kind, mode)` — the shared
  header carrying the run number and a short description of every sink (plus
  `commentedBanner()`, the `#`-prefixed form `nsz_throughput.csv` uses). Used by
  `NszConverter.openJobDebugLog`, `FolderLogWriter` and `StatusLogPanel.writeScreenLog`.
  Run numbers come from `SettingsRepository.nextRunId()` and are allocated once per job in
  `startBatchConversion`, `runFolderStyleConversion` and `scanFolderInto`; the screen
  snapshot uses `lastRunId()` instead of allocating.
- **Parallelism (A07).** `util/WorkerPool` is the single gate both start sites use: a
  fixed-size `Semaphore` plus the (target, active) callback the telemetry needs. It was
  resizable — created wide with the surplus held as ballast permits, narrowing by waiting
  for a file to finish rather than aborting one — but that existed only for the in-job
  search, and went with it (2026-07-28).
  `util/ThroughputRecorder` samples the job's
  **aggregate** byte counter at 10 Hz into `nsz_throughput.csv`; `util/ThroughputAnalysis`
  is pure (no Android APIs, unit-tested) and scores the calibration levels.
  `util/RealFileBenchmark` is the only measurement: it unpacks a user-picked file whole,
  N times in parallel per level, sweeping 1→cores then back. **Before changing any of it,
  read [gotchas.md](../gotchas.md) G18 and G20** — the rules there are not incidental, and
  two of them were learned by getting this wrong.

  The CSV is diagnostics only now; its `level_tag` column stays for format stability and
  is always empty, because nothing tags samples mid-job any more.

## Data flow (brief)

- **Queue:** add → `startBatchConversion` → files in parallel via a `WorkerPool`
  (`resolveConcurrency(mode, manualN, calibratedN, cores, queueSize)`, A07), by extension
  `.xcz` → `NszConverter.convertXcz()`, otherwise `NszConverter.convert()`; statuses
  Pending→Converting→Completed/Failed; overall progress from the sum of `fileTotals`.
- **Folder:** `FolderScanner.scanFolder` → `FolderStructure` → `startFolderConversion` →
  `FolderProcessor.processFolder` (phase 1: folder tree + plan, phase 2: parallel unpacking
  via the `WorkerPool`) → `FolderConversionSummary` → the log is closed, temp is cleaned.
  Progress is streamed via `FolderProgressUpdate` (overall bar + `activeFiles`).
