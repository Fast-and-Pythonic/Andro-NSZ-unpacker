# AndroNSZ — Project Structure

Android application for converting compressed Nintendo Switch files to standard formats.
Supports **NSZ → NSP** (packages) and **XCZ → XCI** (cartridge images).
Kotlin/Compose UI + native C engine via JNI. Port of the Python reference [nicoboss/nsz](https://github.com/nicoboss/nsz).

## Tech stack

- **UI:** Kotlin, Jetpack Compose, Material Design 3
- **Native:** C11, CMake 3.22.1, zstd 1.5.5
- **Target:** Android API 31+, ARM (arm64-v8a, armeabi-v7a)
- **Build:** Gradle (Kotlin DSL), compileSdk 36, NDK

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
│       │   ├── MainActivity.kt   # Activity + ViewModel + Compose UI
│       │   ├── NszConverter.kt   # JNI wrapper, progress Flow, MediaStore I/O
│       │   ├── KeysManager.kt    # Installing/checking prod.keys
│       │   ├── KeysParser.kt     # Parsing header_key from prod.keys
│       │   ├── model/
│       │   │   └── Screen.kt     # Navigation screens (ModeSelection, Conversion, About)
│       │   ├── ui/
│       │   │   ├── screen/
│       │   │   │   ├── ModeSelectionScreen.kt  # Mode selection UI
│       │   │   │   ├── ConversionScreen.kt     # Main conversion UI
│       │   │   │   ├── AboutScreen.kt          # About app screen
│       │   │   │   └── AndroNSZApp.kt          # Main navigation
│       │   │   └── components/   # Reusable UI components
│       │   ├── fs/
│       │   │   ├── FolderScanner.kt   # Recursive folder scan (DocumentsContract)
│       │   │   ├── FolderProcessor.kt # Batch folder processing with progress
│       │   │   └── FolderStructure.kt # Folder data models (FileNode, File/Directory)
│       │   └── core/
│       │       ├── NszConverter.kt     # JNI wrapper, progress Flow, MediaStore I/O
│       │       ├── KeysManager.kt      # Installing/checking prod.keys
│       │       ├── KeysParser.kt       # Parsing header_key from prod.keys
│       │       └── TempFileManager.kt  # Temp file management in cache
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
}
```

Navigation is state-based via `MainViewModel.currentScreen`.

### UI Structure

**AndroNSZApp.kt** - Main navigation composable, switches between screens based on `vm.currentScreen`

**Screens:**
- **ModeSelectionScreen.kt** - Choose between single file or folder mode
- **ConversionScreen.kt** - Main conversion UI with overflow menu (three dots):
  - Change/Remove prod.keys
  - About app (navigates to AboutScreen)
- **AboutScreen.kt** - Information about the app:
  - What is this app
  - Supported formats (NSZ→NSP, XCZ→XCI)
  - How it works (technical details)
  - Requirements (prod.keys)
  - Credits (nicoboss/nsz)

### MainActivity.kt
The only Activity. Contains `MainViewModel` and hosts `AndroNSZApp` composable.

**ViewModel — state and logic:**
- `checkKeys()` / `installKeys()` — prod.keys management
- `startConversion()` — single file conversion (legacy)
- `startBatchConversion()` — batch conversion of queued files
- `selectFolder()` — folder scanning via `FolderScanner`
- `startFolderConversion()` — folder conversion via `FolderProcessor`

**Key data classes:**
- `FileEntry` (uri, name, size, status) — queue element
- `FolderStructure` (rootUri, nszFiles, xczFiles, allFiles, totalSize) — scan result
- `FileNode` (sealed: File / Directory) — folder tree with isNsz/isXcz flags
- `ConversionMode` (sealed: None / SingleFiles / FolderMode)

### NszConverter.kt
Singleton wrapper over the native library `libAndroNSZ`.

**Main methods:**
- `convert()` — NSZ → NSP conversion
  - Resolves URI -> path (copies to temp if needed)
  - Creates output NSP via MediaStore in Downloads
  - Calls `nativeConvert()`, streams progress via Flow
  - Verifies result via `nativeVerifyNsp()` (if header_key is available)
  - Cleans up temp files

- `convertXcz()` — XCZ → XCI conversion
  - Same flow as NSZ, but outputs XCI format
  - Calls `nativeConvertXcz()` JNI method
  - Uses HFS0 container parsing instead of PFS0

### KeysManager.kt
Stores `prod.keys` in `context.filesDir`. Methods: `isInstalled()`, `installFromUri()`, `deleteKeys()`.

### KeysParser.kt
Parses the prod.keys file, extracts `header_key` (32 bytes) from the line `header_key = <hex>`.

### FolderScanner.kt
Recursively traverses a folder via `DocumentsContract`, builds a `FileNode` tree, collects lists of NSZ and XCZ files. Detects file types by extension (`.nsz`, `.xcz`).

### FolderProcessor.kt
Processes all files from `FolderStructure`:
- **NSZ** → converts to NSP via `NszConverter`, copies to output folder
- **XCZ** → converts to XCI via `NszConverter.convertXcz()`, copies to output folder
- **Other files** → copies as-is
- Preserves folder structure, continues on individual file errors
- Result: `FolderConversionSummary` (success/failed/skipped counts)

### TempFileManager.kt
Temp files in `cacheDir` named `andronsz_<UUID>_<name>.<ext>`. Method `cleanupManagedCache()` cleans all.

### FolderLogWriter.kt
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
 ├─ NszConverter        (conversion)
 ├─ KeysManager         (key status)
 ├─ KeysParser          (header_key extraction)
 ├─ FolderScanner       (folder scan)
 ├─ FolderProcessor     (folder processing)
 ├─ TempFileManager     (cache cleanup)
 └─ FolderLogWriter     (logging)

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
  -> MediaStore: create NSP in Downloads
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
  -> create output folder in Downloads
  -> recursively processNodes():
     -> NSZ: convertAndSaveNsz() -> copy to output folder
     -> others: copyFile()
  -> FolderConversionSummary
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
