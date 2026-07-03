# Subsystem: Kotlin layer

Jetpack Compose UI + all conversion logic. A single Activity (`MainActivity`)
hosts `AndroNSZApp`. State and orchestration — in `MainViewModel`.

## Navigation

`MainActivity` → `AndroNSZApp` routes by `vm.currentScreen`. Navigation is a
**screen stack** in `MainViewModel`: `_screenStack` (`mutableStateListOf`, starting
at `Screen.ModeSelection`), `navigateTo(screen)` pushes, `navigateBack()` pops (not
below one). Screens (`model/Screen.kt`): `ModeSelection`, `Conversion`, `About`,
`Settings`.

`MainActivity.attachBaseContext()` synchronously reads the language and wraps the
context with the right `Locale` (per-app language, see
[../architecture.md](../architecture.md) A08). `onCreate()` cleans the temp cache
(`TempFileManager.cleanupManagedCache`).

## Screens (`ui/screen/`)

- **AndroNSZApp.kt** — the navigation root. On start `vm.checkKeys` + `vm.loadSettings`
  via `LaunchedEffect`; hosts the SAF launchers (output folder, prod.keys).
- **ModeSelectionScreen.kt** — mode selection: `SingleFiles` or `FolderMode` (only
  these two). Overflow menu: prod.keys, change output folder, Settings, About.
- **ConversionScreen.kt** — renders by `conversionMode`: `SingleFilesUI` (SingleFiles)
  or `FolderModeUI` (FolderMode). The `None` branch is unreachable (on this screen) and
  empty — needed only for an exhaustive `when`.
- **SettingsScreen.kt** — stats format (`StatsFormat`) and language
  (System/English/Russian; change → `recreate()`).
- **AboutScreen.kt** — app information.

## Conversion modes (`ui/conversion/`)

- **SingleFilesUI.kt** — file queue: adding, list, an overall bar + a bar per actively
  converting file (`activeFileProgress`), the contextual "Unpacking…/Unpacked" status +
  a timer.
- **FolderModeUI.kt** — folder: structure info, tree, an overall bar + a bar per actively
  unpacking file (`folderActiveFiles`) + average speed at the end (like `SingleFilesUI`).

## MainViewModel

The hub of state and logic. Key field groups:
- Navigation/mode: `_screenStack`/`currentScreen`, `conversionMode`, `keysInstalled`.
- Queue: `fileQueue`, `currentFileIndex`, `batchOverallProgress`,
  `batchProcessedFiles/TotalFiles`, `activeFileProgress` (by file index).
- Folder: `folderStructure`, `folderOverallProgress`, `folderActiveFiles` (the list of
  actively converting files for the per-file bars), `folderProcessedFiles/TotalFiles`,
  `folderAverageSpeedMBps`, `folderLogPath`.
- Common: `isConverting`, `progress`, `elapsedMs` (timer), `statusMessage`, `statusLog`,
  `isSuccess`, `compact2Stats`.
- Settings: `statsFormat`, `outputFolderUri`, `appLanguage`.

Key methods: `checkKeys/installKeys/deleteKeys`, `loadSettings`, `saveLanguage`,
`saveOutputFolder` (with a persistable SAF permission), `saveStatsFormat`,
`startBatchConversion` (queue, in parallel — A07), `startFolderConversion` (via
`FolderProcessor`), `resetConversionState`.

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
4. Verify via `nativeVerifyNsp` (if header_key is present). CNMT verification proper runs
   **inside** the native engine and is configured once per job (see MainViewModel below);
   it isn't a separate Kotlin call.
5. Temp cleanup; on a FUSE failure — fall back to a temp copy and retry (G03).

`startBatchConversion`/`startFolderConversion` in **MainViewModel** parse the keys
(`KeysParser.parseHeaderKey` + `parseKeyAreaKeys`) and call
`NszConverter.nativeSetVerification(verificationEnabled, headerKey, keyAreaKeys)` **once**
before launching the job (global native config, read-only during conversion — G11). When
the toggle is off, the post-conversion `nativeVerifyNsp` is skipped too (a null
`verifyKey` is passed into `convert`/`convertXcz`).

## Other modules

- **Constants.kt** — `PROGRESS_BAR_UPDATE_INTERVAL_MS=100`,
  `PROGRESS_NUMERIC_UPDATE_INTERVAL_MS=500` (A09).
- **data/SettingsRepository.kt** — a singleton. `language`/`theme`/`accent`/
  `verification_enabled` in SharedPreferences (synchronous — read at startup and at
  conversion start), `statsFormat`/`outputFolderUri` — in DataStore (Flow).
  `verification_enabled` defaults to **ON** (A12).
- **model/** — `ConversionMode` (None/SingleFiles/FolderMode), `StatsFormat`
  (COMPACT/COMPACT2/COMPACT3/DETAILED), `ConversionProgress` (done/total/speed),
  `FileEntry` (uri/name/size/status), `FolderConversionResult` (operation types and
  summary), `FolderStructure`/`FileNode`, `LogEntry`, `Screen`.
- **nut/** — `KeysManager` (stores prod.keys in `filesDir`), `KeysParser` (extracts
  `header_key` (32 bytes) and, for CNMT verification, every `key_area_key_application_XX`
  via `parseKeyAreaKeys` → 17-byte records `[generation][16-byte key]`).
- **fs/** — `FolderScanner` (recursive `DocumentsContract` walk → tree + NSZ/XCZ lists),
  `FolderProcessor` (NSZ→NSP, XCZ→XCI, everything else → copy; preserves structure,
  continues on errors). Since 2026-06-22 it mirrors the "new" file-mode pipeline: phase 1
  builds the output-folder tree and a flat `WorkItem` list; phase 2 runs files in parallel
  via `Semaphore(FOLDER_CONCURRENCY)`. Input is read via `fd:N` (no-copy, FUSE fallback to
  temp), the result is written **straight** into the destination descriptor (no
  temp-output+copy), verify via `nativeVerifyNsp` is enabled (a mismatch → the file is
  marked failed). Progress: a per-file `ProgressThrottler` → `FolderProgressUpdate.activeFiles`.
  `TempFileManager` (`cacheDir`, `andronsz_<UUID>_<name>.<ext>`), `FolderLogWriter`
  (thread-safe log writing under a `Mutex`).

## Data flow (brief)

- **Queue:** add → `startBatchConversion` → up to `BATCH_CONCURRENCY` files in parallel
  via a `Semaphore`, by extension `.xcz` → `NszConverter.convertXcz()`, otherwise
  `NszConverter.convert()`; statuses Pending→Converting→Completed/Failed; overall progress
  from the sum of `fileTotals`.
- **Folder:** `FolderScanner.scanFolder` → `FolderStructure` → `startFolderConversion` →
  `FolderProcessor.processFolder` (phase 1: folder tree + plan, phase 2: parallel unpacking
  via a `Semaphore`) → `FolderConversionSummary` → the log is closed, temp is cleaned.
  Progress is streamed via `FolderProgressUpdate` (overall bar + `activeFiles`).
