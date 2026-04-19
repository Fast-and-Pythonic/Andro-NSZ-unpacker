# Andro-NSZ-unpacker
or **Andro-NSZ** for short

Native Android unpacker for compressed NSZ/XCZ files.  
Port of the original [nicoboss/nsz](https://github.com/nicoboss/nsz ) with:
- Native C engine for high performance
- And GUI interface, tailored to the convenience on Android.

The app is being developed using vibe coding. I am not a programmer and I am not familiar with Android and C, so it would be very nice if experienced developers checked the correctness of the project. I try to make the project as consistent and compatible as possible with the original [nicoboss/nsz](https://github.com/nicoboss/nsz ).

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
- **Edge-to-Edge**: Immersive full-screen experience
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

- **Android 12+** (API level 31 or higher)
- **Device Storage**: Sufficient space for output files (NSP/XCI sizes equal to original uncompressed size)
- **prod.keys File** (optional but recommended): Contains Nintendo Switch decryption keys
  - Required for NCA verification after conversion
  - Conversion works without it, but verification will be skipped
  - Must be legally obtained from your own Nintendo Switch console

## Installation

1. Download the latest APK from the [Releases](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/releases) page
2. Enable "Install from Unknown Sources" in Android settings if needed
3. Install the APK
4. (Optional) Install your `prod.keys` file via the app menu for verification support

**First-time Setup:**
- On first launch, grant file access permissions when prompted
- Use the overflow menu (⋮) to install prod.keys if you have it
- Select your conversion mode and start processing files

## Usage

### Mode 1: Legacy Single File
Perfect for quick one-off conversions.

1. Launch the app and select "Legacy Single File" mode
2. Tap "Select file" and choose an NSZ or XCZ file
3. Tap "Convert" button
4. Wait for conversion to complete
5. Output saved to Downloads folder

### Mode 2: Batch Conversion
Process multiple files in sequence with cumulative progress.

1. Select "Batch Multiple Files" mode
2. Tap "Add files" and select multiple NSZ/XCZ files
3. Review the queue (remove unwanted files with × button)
4. Tap "Convert X file(s)" to start
5. Monitor per-file and overall progress
6. Files are converted sequentially, status updates in real-time

### Mode 3: Folder Mode
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

### prod.keys Management

**Installing prod.keys:**
1. Open overflow menu (⋮) from main screen
2. Select "Install prod.keys"
3. Browse to your prod.keys file
4. File is copied to app's private storage

**Changing or Removing:**
- Use "Change prod.keys" to select a different file
- Use "Remove prod.keys" to delete the installed keys

**What is prod.keys?**
- Contains Nintendo Switch system decryption keys
- Must include `header_key` line for NCA verification
- File format: `key_name = hex_value` (one per line)
- Must be legally obtained from your own console

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
- Modern ARM64 devices: 50-150 MB/s typical
- Older ARM32 devices: 20-50 MB/s typical
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

### Advantages of Android Port

| Feature | Original NSZ | AndroNSZ |
|---------|-------------|----------|
| **Platform** | Windows/Linux/macOS | Android 12+ |
| **Interface** | Command-line + optional GUI | Native Material 3 UI |
| **Batch Processing** | Via CLI arguments | Visual queue with status tracking |
| **Folder Mode** | Basic directory processing | Recursive with structure preview |
| **Progress Display** | Text-based percentage | Real-time bars + speed metrics |
| **Cancellation** | Ctrl+C interrupt | UI button with instant response |
| **Multi-language** | English only | English + Russian (extensible) |
| **Error Handling** | Stops on first error | Continues batch, logs all errors |
| **Keys Management** | Manual file placement | Built-in installer via file picker |
| **Portability** | Requires Python + dependencies | Self-contained APK |

### What's Different

- **No Compression**: AndroNSZ only **decompresses** (NSZ→NSP, XCZ→XCI). For compression (NSP→NSZ), use the original nsz tool on desktop.
- **Mobile-Optimized**: Designed for touchscreens and mobile workflows.
- **Native Performance**: C engine built with Android NDK, not Python.
- **Simplified**: No advanced options like custom compression levels or multi-threaded compression (since we only decompress).

### What's the Same

- **File Format Compatibility**: 100% compatible with original NSZ format
- **Zstd Algorithm**: Same decompression logic
- **Encryption Handling**: Identical AES-CTR/XTS implementation
- **Verification**: Same SHA-256 validation approach

## Legal Notice

**Important Information:**

1. **No Copyrighted Material**: This software does NOT contain, distribute, or incorporate any copyrighted Nintendo content, game files, encryption keys, or proprietary firmware.

2. **Technology Protection Measures**: This tool does NOT circumvent, remove, or bypass any technological protection measures. It processes files that users already possess and have legal rights to use.

3. **User Responsibility**: Users are solely responsible for:
   - Obtaining their own `prod.keys` file legally from their own Nintendo Switch console
   - Ensuring they have legal rights to possess and process any game files
   - Complying with all applicable laws and terms of service in their jurisdiction

4. **Educational Purpose**: This software is provided for educational and archival purposes, enabling users to manage their legally owned game backups.

5. **No Warranty**: This software is provided "as is" without warranty of any kind. Use at your own risk.

**prod.keys Disclaimer:** The `prod.keys` file must be obtained from YOUR OWN Nintendo Switch console using legal homebrew tools. This app does not provide, generate, or help obtain encryption keys. Distribution of prod.keys files is illegal and violates Nintendo's intellectual property rights.

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

**Made with ❤️ for the Nintendo Switch community**
