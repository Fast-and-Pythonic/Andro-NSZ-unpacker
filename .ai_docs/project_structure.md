# AndroNSZ — Project Structure

Android application for converting compressed Nintendo Switch files to standard formats.
Supports **NSZ → NSP** (packages) and **XCZ → XCI** (cartridge images).
Kotlin/Compose UI + native C engine via JNI. Port of the Python reference [nicoboss/nsz](https://github.com/nicoboss/nsz).

## Tech stack

- **UI:** Kotlin, Jetpack Compose, Material Design 3
- **Native:** C11, CMake 3.22.1, zstd 1.5.5
- **Target:** Android API 31+, ARM (arm64-v8a, armeabi-v7a)
- **Build:** Gradle (Kotlin DSL), compileSdk 36, NDK
- **Persistence:** DataStore Preferences (`androidx.datastore:datastore-preferences:1.1.1`)

---

## File tree

```
AndroNSZ/
├── build.gradle.kts              # Root Gradle config
├── settings.gradle.kts           # Module settings, repos
├── gradle.properties             # JVM args, Kotlin style
├── app/
│   ├── build.gradle.kts          # App config: SDK, NDK, CMake, dependencies
│   └── src/main/
│       ├── AndroidManifest.xml   # Permissions, intent-filter for .nsz
│       ├── cpp/                  # === Native C engine ===
│       │   ├── CMakeLists.txt    # Build: zstd + libAndroNSZ, flags -O3, AES
│       │   ├── jni_bridge.c      # JNI bridge Kotlin <-> C
│       │   ├── ncz_engine.c/.h   # Conversion orchestrator (NSZ->NSP, XCZ->XCI)
│       │   ├── ncz.c/.h          # NCZ header parser (sections, blocks)
│       │   ├── ncz_decompress.c/.h # Decompression (solid/block + AES-CTR)
│       │   ├── pfs0.c/.h         # PFS0 container parser/writer (NSP/NSZ)
│       │   ├── hfs0.c/.h         # HFS0 container parser/writer (XCI/XCZ)
│       │   ├── aes_ctr.c/.h      # AES-128-CTR section encryption
│       │   ├── aes_xts.c/.h      # AES-128-XTS NCA header decryption
│       │   ├── sha256.c/.h       # SHA-256 hashing
│       │   ├── nca_verifier.c/.h # NCA verification after conversion
│       │   ├── nsz_debug.c/.h    # Debug log (file + logcat)
│       │   └── nsz_types.h       # Types, error codes, callbacks
│       ├── java/com/androNSZ/    # === Kotlin layer ===
│       │   ├── MainActivity.kt   # Activity: locale config + Compose host
│       │   ├── NszConverter.kt   # JNI wrapper, progress Flow, MediaStore I/O
│       │   ├── Constants.kt      # App-wide constants (progress throttling intervals)
│       │   ├── data/
│       │   │   └── SettingsRepository.kt  # DataStore/SharedPrefs settings singleton
│       │   ├── model/
│       │   │   ├── Screen.kt              # Navigation screens (sealed class)
│       │   │   ├── StatsFormat.kt         # Enum: COMPACT / DETAILED
│       │   │   ├── ConversionMode.kt      # Sealed: None / SingleFiles / FolderMode
│       │   │   ├── ConversionProgress.kt  # Progress data (done, total, speed)
│       │   │   ├── FileEntry.kt           # Queue element (uri, name, size, status)
│       │   │   ├── FolderConversionResult.kt  # Folder processing results and types
│       │   │   ├── FolderStructure.kt     # Folder scan result (tree, NSZ/XCZ lists)
│       │   │   ├── LogEntry.kt            # Single log entry (tag, message)
│       │   │   └── NszExceptions.kt       # Custom exceptions
│       │   ├── nut/
│       │   │   ├── KeysManager.kt         # Install/check prod.keys
│       │   │   └── KeysParser.kt          # Parse header_key from prod.keys
│       │   ├── fs/
│       │   │   ├── FolderScanner.kt       # Recursive folder scan (DocumentsContract)
│       │   │   ├── FolderProcessor.kt     # Batch folder processing with progress
│       │   │   ├── FolderStructure.kt     # Folder data models (FileNode, File/Directory)
│       │   │   ├── FolderLogWriter.kt     # Thread-safe log writing
│       │   │   └── TempFileManager.kt     # Temp file management in cache
│       │   ├── ui/
│       │   │   ├── screen/
│       │   │   │   ├── AndroNSZApp.kt          # Main navigation root
│       │   │   │   ├── ModeSelectionScreen.kt  # Mode selection + menu
│       │   │   │   ├── ConversionScreen.kt     # Conversion UI (routes to sub-UIs)
│       │   │   │   ├── AboutScreen.kt          # App info
│       │   │   │   └── SettingsScreen.kt       # Language + stats format settings
│       │   │   ├── conversion/
│       │   │   │   ├── LegacySingleFileUI.kt   # Single file UI (legacy/fallback)
│       │   │   │   ├── SingleFilesUI.kt        # Batch file conversion UI
│       │   │   │   └── FolderModeUI.kt         # Folder conversion UI with tree
│       │   │   ├── components/
│       │   │   │   ├── CompactTopAppBar.kt     # Custom compact top bar
│       │   │   │   ├── StatusLogPanel.kt       # Collapsible log panel
│       │   │   │   ├── StatusMessageCard.kt    # Success/error card
│       │   │   │   └── ToggleButtonDefaults.kt # Toggle button for show/hide
│       │   │   └── theme/
│       │   │       ├── Color.kt
│       │   │       ├── Theme.kt
│       │   │       └── Type.kt
│       │   ├── util/
│       │   │   ├── FileUtils.kt               # File handling utilities
│       │   │   └── FormatUtils.kt             # Formatting utilities
│       │   └── viewmodel/
│       │       └── MainViewModel.kt           # All UI state and conversion logic
│       └── res/                  # Resources: icons, themes, strings
```

---

## Kotlin layer

### Navigation (model/Screen.kt)
Simple sealed class-based navigation without NavController:
```kotlin
sealed class Screen {
    object ModeSelection : Screen()  // Mode selection screen
    object Conversion : Screen()     // Main conversion screen
    object About : Screen()          // About app screen
    object Settings : Screen()       // App settings screen
}
```

Navigation is state-based via `MainViewModel.currentScreen`.

### UI Structure

**AndroNSZApp.kt** — Main navigation composable. On launch:
- Calls `vm.checkKeys(context)` and `vm.loadSettings(context)` via `LaunchedEffect`
- Hosts the `outputFolderPicker` SAF launcher
- Routes to one of four screens based on `vm.currentScreen`

**Screens:**
- **ModeSelectionScreen.kt** — Choose between single file or folder mode. Overflow menu:
  - Install/Remove prod.keys
  - Change output folder (SAF folder picker)
  - Settings → navigates to SettingsScreen
  - About → navigates to AboutScreen
- **ConversionScreen.kt** — Main conversion UI. Dynamically renders one of:
  - `LegacySingleFileUI` (mode = None)
  - `SingleFilesUI` (mode = SingleFiles)
  - `FolderModeUI` (mode = FolderMode)
  - Same overflow menu as ModeSelectionScreen
- **AboutScreen.kt** — App information: what it is, formats, how it works, requirements, credits
- **SettingsScreen.kt** — App preferences:
  - Stats format dropdown: COMPACT / DETAILED (affects final folder conversion report)
  - Language dropdown: System default / English / Russian
  - Language change calls `(context as Activity).recreate()` to apply immediately

### Constants.kt
```kotlin
const val PROGRESS_BAR_UPDATE_INTERVAL_MS = 100L    // 10 updates/sec — smooth animation
const val PROGRESS_NUMERIC_UPDATE_INTERVAL_MS = 500L // 2 updates/sec — readable text
```

### MainActivity.kt
The only Activity. Hosts `AndroNSZApp` composable.

Key overrides:
- `attachBaseContext()` — reads language from `SettingsRepository.getLanguage()` and wraps context with the correct `Locale` if not "system". This runs before UI inflation, enabling per-app language without changing system settings.
- `onCreate()` — calls `TempFileManager.cleanupManagedCache(this)` on startup to remove leftover temp files.

### data/SettingsRepository.kt
Singleton (`getInstance(context: Context)`). Handles all persistent user settings.

| Setting | Storage | Type |
|---------|---------|------|
| `statsFormat` | DataStore Preferences | `Flow<StatsFormat>` |
| `outputFolderUri` | DataStore Preferences | `Flow<Uri?>` |
| `language` | SharedPreferences | String (synchronous) |

SharedPreferences is used for `language` specifically because `attachBaseContext()` runs synchronously before coroutines are available.

**Public API:**
- `getLanguage(): String` — synchronous read ("system" / "en" / "ru")
- `saveLanguage(lang: String)` — synchronous write
- `statsFormatFlow: Flow<StatsFormat>` — reactive stats format
- `outputFolderUriFlow: Flow<Uri?>` — reactive output folder URI
- `suspend fun saveStatsFormat(format: StatsFormat)`
- `suspend fun saveOutputFolderUri(uri: Uri?)`

### model/StatsFormat.kt
```kotlin
enum class StatsFormat {
    COMPACT,   // Brief summary
    DETAILED   // Per-type breakdown (NSZ/XCZ/copy counts)
}
```

### MainViewModel.kt
State and logic hub. State fields:
- `currentScreen`, `keysInstalled`, `conversionMode` — navigation and mode
- `statsFormat: StatsFormat` — current stats display format
- `outputFolderUri: Uri?` — user-selected output folder (null = Downloads)
- `appLanguage: String` — current language code
- Conversion state: `isConverting`, `progress`, `statusMessage`, `statusLog`, etc.

Key methods:
- `checkKeys(context)` / `installKeys(context, uri)` / `deleteKeys(context)` — prod.keys management
- `loadSettings(context)` — collects flows from SettingsRepository into state fields
- `saveLanguage(context, lang)` — persists language preference
- `saveOutputFolder(context, uri)` — persists output folder with persistent SAF permissions
- `saveStatsFormat(context, format)` — persists stats format
- `startConversion()` — single file conversion (legacy)
- `startBatchConversion()` — batch conversion of queued files
- `startFolderConversion()` — folder conversion via `FolderProcessor`
- `resetConversionState()` — resets all transient conversion state

### NszConverter.kt
Singleton wrapper over `libAndroNSZ`.

**Main methods:**
- `convert()` — NSZ → NSP conversion
  - Resolves URI → path (copies to temp if needed)
  - Creates output NSP via MediaStore in Downloads (or custom `outputFolderUri`)
  - Calls `nativeConvert()`, streams progress via Flow
  - Verifies result via `nativeVerifyNsp()` (if header_key is available)
  - Cleans up temp files

- `convertXcz()` — XCZ → XCI conversion
  - Same flow as above, outputs XCI format
  - Calls `nativeConvertXcz()` JNI method
  - Uses HFS0 container parsing instead of PFS0

### nut/KeysManager.kt
Stores `prod.keys` in `context.filesDir`. Methods: `isInstalled()`, `installFromUri()`, `deleteKeys()`.

### nut/KeysParser.kt
Parses the prod.keys file, extracts `header_key` (32 bytes) from line `header_key = <hex>`.

### fs/FolderScanner.kt
Recursively traverses a folder via `DocumentsContract`, builds a `FileNode` tree, collects lists of NSZ and XCZ files. Detects file types by extension (`.nsz`, `.xcz`).

### fs/FolderProcessor.kt
Processes all files from `FolderStructure`:
- **NSZ** → converts to NSP via `NszConverter`, copies to output folder
- **XCZ** → converts to XCI via `NszConverter.convertXcz()`, copies to output folder
- **Other files** → copies as-is
- Preserves folder structure, continues on individual file errors
- Progress throttling: bar updates every `PROGRESS_BAR_UPDATE_INTERVAL_MS` (100ms), numeric text every `PROGRESS_NUMERIC_UPDATE_INTERVAL_MS` (500ms)
- Result: `FolderConversionSummary` with per-operation-type counters

### model/FolderConversionResult.kt
Contains all types related to folder conversion results:

```kotlin
enum class FileOperationType {
    NSZ_CONVERSION,  // NSZ → NSP
    XCZ_CONVERSION,  // XCZ → XCI
    FILE_COPY        // Regular file copy
}

sealed class FileConversionResult {
    data class Success(operationType, durationMs, outputName): FileConversionResult()
    data class Failed(operationType, errorCode, errorMessage): FileConversionResult()
    data class Skipped(reason): FileConversionResult()
}

data class FolderConversionSummary(
    totalFiles, successCount, failedCount, skippedCount,
    nszFilesProcessed, nszSuccessCount, nszFailedCount,
    xczFilesProcessed, xczSuccessCount, xczFailedCount,
    copyFilesProcessed, copySuccessCount, copyFailedCount
)

data class FolderProgressUpdate(
    overallProgress: ConversionProgress,
    currentFileProgress: ConversionProgress?,
    currentFileName: String?,
    processedFiles: Int,
    totalFiles: Int
)
```

### fs/TempFileManager.kt
Temp files in `cacheDir` named `andronsz_<UUID>_<name>.<ext>`. Method `cleanupManagedCache()` removes all.

### fs/FolderLogWriter.kt
Thread-safe log writing to `nsz_folder_debug.log` with timestamps. Protected by `Mutex`.

---

## C layer (native engine)

### jni_bridge.c
JNI entry points. Marshals strings/callbacks between Java and C. Manages thread attachment to JVM.

**Key JNI methods:**
- `nativeConvert()` - NSZ → NSP conversion
- `nativeConvertXcz()` - XCZ → XCI conversion
- `nativeVerifyNsp()` - NCA verification in NSP
- `nativeSetDebugLog()` / `nativeCloseDebugLog()` - Debug logging
- `nativeCancel()` - Request cancellation

### ncz_engine.c
Main conversion orchestrator with two functions:

**`ncz_convert_nsz_to_nsp()`** - NSZ → NSP:
1. Parses the PFS0 container of the input NSZ
2. Pre-scans NCZ files to determine decompressed sizes
3. Writes a new PFS0 header with updated sizes
4. For each file: NCZ → decompress, otherwise → copy
5. SHA-256 verification by filename (hex prefix)
6. Removes partial output on error

**`ncz_convert_xcz_to_xci()`** - XCZ → XCI:
1. Copies XCI header (0x200 bytes) verbatim from input
2. Parses HFS0 container at offset (typically 0xF000)
3. Pre-scans NCZ files to determine decompressed sizes
4. Writes updated HFS0 header with new sizes
5. For each file: NCZ → decompress, otherwise → copy
6. Updates XCI header with new HFS0 size

Also: `ncz_request_cancel()`, `ncz_reset_cancel()`, `ncz_error_string()`.

### ncz.c
NCZ header parser: sections (`NczSection`), blocks (`NczBlockHeader`), FakeSection (gap between NCA header and first section).

### ncz_decompress.c
Two decompression modes:
- **BlockReader** — block-by-block zstd decompression with cache
- **SolidReader** — streaming zstd decompression (ZSTD_DStream)

Applies AES-CTR only for crypto_type 3, 4. FakeSection (type 1) — plaintext. Feeds SHA-256 context.

### pfs0.c
PFS0 (Partition File System 0) - Nintendo package container format (NSP/NSZ).
- File entries: **24 bytes** each
- `pfs0_parse()` - reads the container
- `pfs0_write_header()` - writes new header with .ncz → .nca and recalculated sizes

### hfs0.c
HFS0 (Hash File System 0) - Nintendo cartridge container format (XCI/XCZ).
- File entries: **64 bytes** each (includes SHA-256 hash field)
- `hfs0_parse()` - reads the container from XCI
- `hfs0_write_header()` - writes new header with .ncz → .nca and recalculated sizes
- Hash fields are always zeroed (compatibility with Python reference)

### aes_ctr.c
AES-128-CTR for NCA section decryption. Counter: `nonce[0:8] || (offset >> 4)` big-endian.

### aes_xts.c
AES-128-XTS for NCA header decryption (sectors of 0x200 bytes). IEEE 1619.

### sha256.c
SHA-256: one-shot `sha256()` and streaming API (`init/update/final`).

### nca_verifier.c
`nca_verify_nsp()` — NCA verification in a finished NSP: AES-XTS header decryption, magic check ("NCA3"), SHA-256 section hashes.

### nsz_debug.c
`dbg_open/close/log/hex` — log to file + Android logcat, with millisecond timestamps.

### nsz_types.h
Shared types: error codes, callback typedefs (`NczProgressCb`, `NczStatusCb`), constants (`NCA_HEADER_SIZE = 0x4000`).

---

## Dependency graph

```
=== Kotlin ===

MainActivity
 ├─ SettingsRepository  (language — synchronous, in attachBaseContext)
 └─ TempFileManager     (cleanup on start)

MainViewModel
 ├─ NszConverter        (conversion)
 ├─ KeysManager         (key status)
 ├─ KeysParser          (header_key extraction)
 ├─ FolderScanner       (folder scan)
 ├─ FolderProcessor     (folder processing)
 ├─ TempFileManager     (cache cleanup)
 ├─ FolderLogWriter     (logging)
 └─ SettingsRepository  (settings persistence)

FolderProcessor
 ├─ NszConverter        (native conversion)
 ├─ KeysParser          (keys)
 ├─ FolderLogWriter     (log)
 └─ TempFileManager     (temp files)

NszConverter
 ├─ TempFileManager     (temp files)
 └─ [JNI] -> libAndroNSZ

=== C (libAndroNSZ) ===

jni_bridge.c
 └─ ncz_engine.c
     ├─ pfs0.c
     ├─ ncz.c
     └─ ncz_decompress.c
         ├─ aes_ctr.c
         └─ sha256.c

nca_verifier.c  (separate entry point via JNI)
 ├─ pfs0.c
 ├─ aes_xts.c
 └─ sha256.c

nsz_debug.c     (used throughout)
```

---

## Data Flow

### Mode 1: Single file
```
File selection (URI) -> NszConverter.convert()
  -> resolveUri -> temp copy (if needed)
  -> MediaStore: create NSP in Downloads (or custom outputFolderUri)
  -> JNI: nativeConvert(input, output)
     -> pfs0_parse -> ncz pre-scan -> pfs0_write_header
     -> for each file: ncz_decompress / copy
     -> sha256 verification
  -> JNI: nativeVerifyNsp (if header_key is available)
  -> cleanup temp
```

### Mode 2: File batch
```
Adding files to queue -> startBatchConversion()
  -> loop over FileEntry:
     -> NszConverter.convert() for each
     -> status update: Pending -> Converting -> Completed/Failed
     -> cumulative progress across all files
  -> cleanup
```

### Mode 3: Folder
```
Folder selection -> FolderScanner.scanFolder()
  -> recursive DocumentsContract traversal
  -> FolderStructure (tree, NSZ list, size)
-> startFolderConversion() -> FolderProcessor.processFolder()
  -> create output folder in Downloads (or custom outputFolderUri)
  -> recursively processNodes():
     -> NSZ: convertAndSaveNsz() -> copy to output folder
     -> XCZ: convertAndSaveXcz() -> copy to output folder
     -> others: copyFile()
  -> FolderConversionSummary (per-type counts)
  -> FolderLogWriter.close()
  -> cleanup
```

---

## JNI interface (NszConverter.kt <-> jni_bridge.c)

| Kotlin method                                                | Description                     |
|--------------------------------------------------------------|---------------------------------|
| `nativeConvert(input, output, progressCb, statusCb): Int`    | NSZ → NSP conversion            |
| `nativeConvertXcz(input, output, progressCb, statusCb): Int` | XCZ → XCI conversion            |
| `nativeVerifyNsp(nspPath, headerKey): String?`               | NCA verification in NSP         |
| `nativeSetDebugLog(path)`                                    | Open debug log file             |
| `nativeCloseDebugLog()`                                      | Close log                       |
| `nativeCancel()`                                             | Request conversion cancellation |
| `nativeErrorString(code): String`                            | Error code → text               |

**Callbacks:**
- `ProgressCallback.onProgress(done, total)` — progress (~20 times/sec)
- `StatusCallback.onStatus(tag, msg)` — status messages

**Status tags:** `OPEN`, `EXISTS`, `NCA_HASH`, `VERIFIED`, `ERROR`, `INFO`

---

## Error codes (nsz_types.h)

| Code | Constant                | Description                |
|------|-------------------------|----------------------------|
| 0    | `NCZ_OK`                | Success                    |
| -1   | `NCZ_ERR_OPEN_INPUT`    | Failed to open input file  |
| -2   | `NCZ_ERR_OPEN_OUTPUT`   | Failed to open output file |
| -3   | `NCZ_ERR_INVALID_PFS0`  | Invalid PFS0 container     |
| -4   | `NCZ_ERR_INVALID_NCZ`   | Invalid NCZ header         |
| -5   | `NCZ_ERR_ZSTD`          | Zstd decompression error   |
| -6   | `NCZ_ERR_IO`            | I/O error                  |
| -7   | `NCZ_ERR_OOM`           | Out of memory              |
| -8   | `NCZ_ERR_CANCELLED`     | Cancelled by user          |
| -9   | `NCZ_ERR_HASH_MISMATCH` | SHA-256 hash mismatch      |
