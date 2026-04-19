# Andro-NSZ-unpacker
or **Andro-NSZ** for short

Native Android unpacker for compressed NSZ/XCZ files.  
Port of the original [nicoboss/nsz](https://github.com/nicoboss/nsz) with:
- Native C engine for high performance
- And GUI interface, tailored to the convenience on Android.

The app is being developed using vibe coding. I am not a programmer and I am not familiar with Android and C, so it would be very nice if experienced developers checked the correctness of the project. I try to make the project as consistent and compatible as possible with the original [nicoboss/nsz](https://github.com/nicoboss/nsz ).

## Legal Notice

**Important Information:**

1. **No Copyrighted Material**: This software does NOT contain, distribute, or incorporate any copyrighted Nintendo content, game files, encryption keys, or proprietary firmware.

2. **Technology Protection Measures**: This tool does NOT circumvent, remove, or bypass any technological protection measures. It processes files that users already possess and have legal rights to use.

3. **User Responsibility**: Users are solely responsible for:
   - Obtaining their own `prod.keys` file legally from their own Nintendo Switch console
   - Ensuring they have legal rights to possess and process any game files
   - Complying with all applicable laws and terms of service in their jurisdiction

4. **Educational Purpose**: This software is provided for educational and archival purposes, enabling users to manage their legally owned game backups.

5. **This project is MIT licensed.** Check [LICENSE](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/blob/main/LICENSE) for more information.

6. **No Warranty**: This software is provided "as is" without warranty of any kind. Use at your own risk.

**prod.keys Disclaimer:** The `prod.keys` file must be obtained from YOUR OWN Nintendo Switch console using legal homebrew tools. This app does not provide, generate, or help obtain encryption keys. Distribution of prod.keys files is illegal and violates Nintendo's intellectual property rights.

## Overview

**AndroNSZ** converts compressed files to standard formats:
- **NSZ → NSP** (compressed packages to standard packages)
- **XCZ → XCI** (compressed cartridge images to standard images)

The app uses **Zstandard (zstd)** compression algorithm for lossless decompression, preserving all encryption structures while maintaining file integrity. Built with Jetpack Compose and Material 3 for a modern Android experience, powered by a native C engine via JNI for optimal performance.

## Features

### Core Functionality
- **NSZ → NSP Conversion**: Decompress compressed packages with full NCA support
- **XCZ → XCI Conversion**: Decompress compressed cartridge images
- **Three Conversion Modes**:
  - **Legacy Single File**: Quick one-file conversion
  - **Batch Queue**: Add multiple files and convert them sequentially
  - **Folder Mode**: Recursive folder processing with structure preservation
- **Optional NCA Verification**: Integrity checking after conversion (requires prod.keys)
- **Hardware Decryption**: AES-128-CTR and AES-128-XTS encryption support
- **Real-time Progress**: Live progress bars with speed metrics (MB/s)
- **Cancellation Support**: Stop conversion at any time
- **Status Logging**: Detailed operation log with tag-based filtering

### User Interface
- **Material 3 Design**: Modern Android UI with dynamic colors
- **Touch-Optimized**: Gesture-friendly interface for mobile devices
- **Multi-Language**: English and Russian localizations
- **Dark/Light Theme**: Follows system theme automatically

### Technical Features
- **Native C Engine**: High-performance conversion via JNI
- **Zstd Decompression**: Industry-standard compression (levels 18-22 supported)
- **AES Encryption**: CTR mode for sections, XTS mode for headers
- **SHA-256 Verification**: File integrity checking
- **MediaStore I/O**: Direct output to Downloads folder without storage permissions
- **Async Processing**: All operations run on background threads
- **Error Resilience**: Batch operations continue despite individual file failures
- **Auto-Cleanup**: Temporary files managed automatically

## Requirements

- **Android 12+** (API level 31 or higher) - It may be lowered in the future. Or it won't be. I'm not sure about that yet.
- **Device Storage**: Sufficient space for output files (NSP/XCI sizes equal to original uncompressed size)
- **prod.keys File**:
  - Required for NCA verification after conversion
  - Must be legally obtained from your own console

## Installation

1. Download the latest APK from the [Releases](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/releases) page
2. Enable "Install from Unknown Sources" in Android settings if needed
3. Install the APK
4. Install your `prod.keys` file via the app menu for verification support

**First-time Setup:**
- On first launch, grant file access permissions when prompted
- Use the overflow menu (⋮) to install prod.keys
- Select your conversion mode and start processing files

## Usage

### Mode 1: Single File/Batch Conversion
Process multiple files in sequence with cumulative progress.

1. Select "Batch Multiple Files" mode
2. Tap "Add files" and select multiple NSZ/XCZ files
3. Review the queue (remove unwanted files with × button)
4. Tap "Convert X file(s)" to start
5. Monitor per-file and overall progress
6. Files are converted sequentially, status updates in real-time

### Mode 2: Folder Mode
Recursively process entire folders while preserving structure.

1. Select "Folder Processing" mode
2. Tap "Select folder" and choose a folder containing NSZ/XCZ files
3. Wait for folder scanning to complete
4. Review detected files and folder structure
5. Tap "Convert folder" to start
6. Output saved to `Original_unpacked/` in Downloads (auto-numbered if exists)
7. Non-game files are copied as-is
8. Conversion summary displayed at completion
9. Debug log saved to `nsz_folder_debug.log`

## Technical Details

### Architecture

```
┌─────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)            │
│  - Material 3 Components                │
│  - Screens: Mode Selection, Conversion  │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│  ViewModel (MainViewModel)              │
│  - State management                     │
│  - Conversion orchestration             │
│  - Progress tracking                    │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│  Kotlin Utilities                       │
│  - NszConverter (JNI Bridge)            │
│  - KeysManager, FolderScanner           │
│  - TempFileManager, LogWriter           │
└──────────────┬──────────────────────────┘
               │ JNI
┌──────────────▼──────────────────────────┐
│  Native C Engine (libAndroNSZ)          │
│  - ncz_engine: Conversion orchestration │
│  - ncz_decompress: Zstd + AES-CTR       │
│  - pfs0/hfs0: Container parsing         │
│  - aes_ctr/aes_xts: Encryption          │
│  - sha256: Hash verification            │
│  - nca_verifier: Post-conversion check  │
└─────────────────────────────────────────┘
```

### Supported Formats

| Input Format | Output Format | Description | Status |
|-------------|---------------|-------------|--------|
| NSZ | NSP | Compressed game package → Standard package | ✅ Full support |
| XCZ | XCI | Compressed cartridge image → Standard image | ✅ Full support |
| NCZ | NCA | Individual compressed content → Standard content | ✅ (within NSZ/XCZ) |

**Container Formats:**
- **PFS0** (Partition File System 0): Used by NSP/NSZ packages
- **HFS0** (Hash File System 0): Used by XCI/XCZ cartridge images

### Compression & Encryption

**Compression:**
- Algorithm: **Zstandard (zstd) v1.5.5**
- Compression levels: 18-22 (original NSZ tool range)
- Two modes supported:
  - **Block-based**: Chunks compressed separately with caching
  - **Solid**: Continuous stream for maximum compression

**Encryption:**
- **AES-128-CTR** (Counter mode): For NCA section decryption
  - Block-by-block processing with counter: `nonce[0:8] || (offset >> 4)`
- **AES-128-XTS** (XEX-based Tweaked-codebook mode): For NCA header decryption
  - IEEE P1619 standard, 0x200 byte sectors
- **Hardware Acceleration**: ARM64 devices use NEON crypto extensions (AESE/AESMC)

**Verification:**
- **SHA-256** hashing for file integrity
- NCA header verification (requires header_key from prod.keys)
- Optional post-conversion validation

### Performance

**Optimization Techniques:**
- **Native C Implementation**: Critical path in C for maximum speed
- **Hardware Crypto**: ARM64 crypto extensions when available
- **Zero-copy I/O**: Direct file descriptor passing to native layer
- **Streaming Processing**: Memory-efficient chunk-based decompression
- **Progress Throttling**: UI updates limited to 10 Hz for smooth animation

**Typical Performance:**
- Speed varies by device CPU and file compression ratio
- Snapdragon 8s gen 4 - 30-40 MB/s
- Storage I/O is often the bottleneck, not CPU

## Building from Source

### Prerequisites

- **Android Studio**: Ladybug (2024.2.1) or newer
- **Android SDK**: API 36
- **Android NDK**: 28.2.13676358
- **CMake**: 3.22.1 (bundled with Android SDK)
- **Java**: JDK 11 or higher

### Build Steps

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker.git
   cd Andro-NSZ-unpacker
   ```

2. **Open in Android Studio:**
   - File → Open → Select project directory
   - Wait for Gradle sync to complete

3. **Build APK:**
   ```bash
   # Debug build
   ./gradlew assembleDebug
   
   # Release build
   ./gradlew assembleRelease
   ```

4. **Run tests:**
   ```bash
   # Unit tests
   ./gradlew testDebugUnitTest
   
   # Lint checks
   ./gradlew lintDebug
   ```

5. **Install to device:**
   ```bash
   ./gradlew installDebug
   ```

**APK Location:**
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

### Native Library Compilation

The C engine is compiled automatically during Gradle build:

```cmake
# Zstd library: Fetched from GitHub and built as static library
# AndroNSZ native library: Compiled with -O3 -ffast-math optimizations
# ARM64: Enabled with -march=armv8-a+crypto for hardware AES
```

To force native rebuild:
```bash
./gradlew clean
./gradlew assembleDebug
```

## Comparison with Original NSZ

### What's Different

- **No Compression**: AndroNSZ only **decompresses** (NSZ→NSP, XCZ→XCI). For compression (NSP→NSZ), use the original nsz tool on desktop.  
  (This restriction is temporary. It is not yet completely certain that the application packages data completely correctly, like the original nicoboss/nsz. So far, this is exactly the unpacker. I don't know if the packaging will be added in the future.)
- **Mobile-Optimized**: Designed for touchscreens and mobile workflows.
- **Native Performance**: C engine built with Android NDK, not Python.
- **Simplified**: No advanced options like custom compression levels or multi-threaded compression (since we only decompress).

### What's the Same

- **File Format Compatibility**: 100% compatible with original NSZ format
- **Zstd Algorithm**: Same decompression logic
- **Encryption Handling**: Identical AES-CTR/XTS implementation
- **Verification**: Same SHA-256 validation approach

## Credits

**Original NSZ Tool:**
- Created by [nicoboss](https://github.com/nicoboss)
- Original repository: [nicoboss/nsz](https://github.com/nicoboss/nsz)

**AndroNSZ Development:**
- Android port with native C engine
- Repository: [Fast-and-Pythonic/Andro-NSZ-unpacker](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker)

**Libraries Used:**
- [Zstandard (zstd) 1.5.5](https://github.com/facebook/zstd) - Fast real-time compression algorithm
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI toolkit
- [Material 3](https://m3.material.io/) - Google's design system
- [Android NDK](https://developer.android.com/ndk) - Native development kit

**Special Thanks:**
- The Nintendo Switch homebrew community
- To all participants of the project [nicoboss/nsz](https://github.com/nicoboss/nsz)
- All contributors and testers

## Changelog

### Version 1.0.0
- ✅ NSZ → NSP conversion support
- ✅ XCZ → XCI conversion support
- ✅ Three conversion modes (single file, batch, folder)
- ✅ NCA verification with prod.keys
- ✅ Real-time progress with speed metrics
- ✅ Multi-language support (English, Russian)
- ✅ Material 3 design
- ✅ Folder structure preservation
- ✅ Error resilience in batch processing
- ✅ Debug logging for troubleshooting

## License

This project is open source. License information to be determined.

---
