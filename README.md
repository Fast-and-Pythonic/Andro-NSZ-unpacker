# Andro-NSZ-unpacker

or **Andro-NSZ** for short

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Fast-and-Pythonic/Andro-NSZ-unpacker)](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/releases)
[![Android 12+](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3ddc84.svg)](#requirements)

Native Android unpacker for compressed NSZ/XCZ files — a port of the original
[nicoboss/nsz](https://github.com/nicoboss/nsz) with a native C engine and a GUI
built for phones. Unpacking runs at the speed of your storage, not of a Python
interpreter: on a Snapdragon 8s Gen 4 a single file decompresses at ~400 MB/s and a
parallel queue reaches ~950 MB/s.

The app is being developed using vibe coding. I am not a programmer and I am not
familiar with Android and C, so it would be very nice if experienced developers checked
the correctness of the project. I try to make the project as consistent and compatible
as possible with the original [nicoboss/nsz](https://github.com/nicoboss/nsz).

## Contents

- [Overview](#overview)
- [Screenshots](#screenshots)
- [Installation](#installation)
- [Usage](#usage)
- [Settings](#settings)
- [Features](#features)
- [Requirements](#requirements)
- [Troubleshooting](#troubleshooting)
- [Technical Details](#technical-details)
- [Building from Source](#building-from-source)
- [Comparison with Original NSZ](#comparison-with-original-nsz)
- [Contributing](#contributing)
- [Legal Notice](#legal-notice)
- [Credits](#credits)

## Overview

**Andro-NSZ** converts compressed files back to their standard formats:

- **NSZ → NSP** (compressed packages → standard packages)
- **XCZ → XCI** (compressed cartridge images → standard images)

Inside those containers the payload is the NCZ format: **Zstandard** compression over
AES-CTR-encrypted NCA sections. Unpacking is lossless and preserves every encryption
structure, so the result is a byte-compatible NSP/XCI. The UI is Jetpack Compose +
Material 3; the engine is C, called over JNI.

**Privacy:** the app works entirely offline. The only network request it ever makes is
the version check against the GitHub Releases API — no file, log or telemetry ever
leaves the device.

## Screenshots

<!--
   TODO: drop the PNGs into docs/img/ and uncomment the block below.
   Suggested set: mode selection, a running conversion with per-file bars,
   the unpacking-threads settings screen.

| Mode selection | Unpacking | Thread settings |
|---|---|---|
| ![Mode selection](docs/img/modes.png) | ![Unpacking](docs/img/unpacking.png) | ![Thread settings](docs/img/threads.png) |
-->

_Screenshots pending._

## Installation

1. Download the latest APK from the [Releases](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/releases) page
2. Enable "Install from Unknown Sources" in Android settings if needed
3. Install the APK

**First-time setup:**

- On first launch, grant **All files access** (`MANAGE_EXTERNAL_STORAGE`) — the built-in
  file picker browses storage directly, which is what lets it select many files at once
  and hand them to the engine without copying
- Use the overflow menu (⋮) to install your `prod.keys` file — needed for verification
- Optionally pick an output folder; without one, results go to **Downloads**

Later releases are offered by the app itself: it checks GitHub Releases on start and can
download and install the new APK (hence `INTERNET` and `REQUEST_INSTALL_PACKAGES` in the
manifest). The banner on the main screen can be turned off in Settings; the ⋮ menu entry
stays.

## Usage

Pick one of three modes on the start screen.

### Files & folders (combined)

Select files and whole folders together in one pass; each one is unpacked into the
output folder.

1. Tap **Add files & folders** and mark whatever you need — the picker is split-screen
   and browses real storage, so multi-select works across directories
2. Review the selection (the counter shows items and total size; remove with ×)
3. Tap **Unpack N item(s)**
4. Watch the overall bar plus one bar per file currently being unpacked

### Files only

The same flow, restricted to individual NSZ/XCZ files — useful when a queue is all you
want.

### Folder

Recursively processes one folder and preserves its structure.

1. Tap **Select folder** and choose the root
2. Wait for the scan to finish, then review the detected files and structure
3. Tap **Convert folder**
4. Output goes to `<name>_unpacked/` next to your chosen output folder (auto-numbered to
   `_unpacked_2`, … if it already exists)
5. Non-game files are copied as-is; a summary is shown at the end

Several files are unpacked **in parallel** in every mode — see
[Settings](#settings) for how many.

## Settings

- **Unpacking threads** — how many files are unpacked at once. This has its own screen,
  because the right number is a property of your phone's storage, not of its CPU
  (unpacking is write-bound). Three sources:
  - **Half the cores** — the default, and the fallback for everything else
  - **Manual** — a slider, 1…cores
  - **From speed test** — the real-file test: it fully unpacks one NSZ/XCZ you pick at
    every thread count, 1→cores and back down, and picks the winner.
    ⚠️ This writes a *lot* — a full sweep on an 8-core phone is 72 complete unpacks. Use
    a 1–2 GB file (a couple of minutes) and don't run it casually.
- **Output verification** — SHA-256 of every unpacked NCA against the CNMT metadata,
  computed inline during decompression. **On by default**, nearly free, and always
  non-fatal: a mismatch is a warning and the output is kept.
- **Output folder** — any folder; Downloads is the fallback.
- **Language** — English / Russian, switchable in-app.
- **Theme and accent color** — system dark/light, plus a fixed brand accent, Material You
  or a manual hex value.
- **Statistics format**, compact file-card names, update banner.

**Logs** live in the app's external files directory
(`Android/data/com.androNSZ/files/`). Four sinks, each holding exactly **one run** with
the previous one kept as `<name>.prev.<ext>`, and every header stamped with the same
`Run #N`:

| File | What's in it |
|---|---|
| `nsz_debug.log` | native engine trace |
| `nsz_folder_debug.log` | Kotlin status lines for folder/combined runs and scans |
| `nsz_screen_log.txt` | manual snapshot of the on-screen log ("Save on-screen log") |
| `nsz_throughput.csv` | aggregate throughput samples, 10 Hz |

## Features

- **NSZ → NSP** and **XCZ → XCI**, with full NCA support
- **Three modes**: files & folders combined, files only, one folder recursively
- **Parallel unpacking** with a per-device thread count (default, slider, or measured)
- **Inline CNMT verification** — SHA-256 per NCA during decompression, no re-read of the
  output, non-fatal
- **Built-in file picker** — split-screen, browses raw storage, multi-select
- **Chosen output folder**, or Downloads by default
- **Hardware crypto** — AES-128-CTR/XTS and SHA-256 on ARMv8 crypto extensions, with a
  software fallback
- **Real-time progress** — an overall bar, a bar per active file, MB/s and a timer
- **Cancellation** at any point, **error resilience** (one bad file doesn't stop a batch)
- **Status log** on screen with tag filtering, plus the four log files above
- **In-app update check** against GitHub Releases
- **EN/RU localization**, Material 3, dynamic colors, system dark/light theme

**About performance:** the numbers quoted above come from one phone (Snapdragon 8s Gen 4,
UFS) in one wear state. Decompression itself reaches ~2400 MB/s there, but the flash
accepts only ~1000 MB/s and drops to ~450 once its SLC cache is spent — so your mileage
depends on storage, not on how fast the CPU is.

## Requirements

- **Android 12+** (API level 31 or higher) — it may be lowered in the future. Or it
  won't. I'm not sure about that yet.
- **All files access** permission, granted on first launch
- **Device storage**: enough space for the output (an NSP/XCI is the full uncompressed
  size)
- **prod.keys**: needed for verification. Without it, unpacking still works and falls
  back to a non-authoritative filename check. Must be obtained from your own console.

## Troubleshooting

- **"prod.keys not found" / verification skipped** — install the file via ⋮ → *Install
  prod.keys*. Unpacking itself does not need it.
- **The app can't see my files** — the picker needs *All files access*. If the prompt was
  dismissed, grant it in Android Settings → Apps → Andro-NSZ → Permissions.
- **Unpacking feels slow** — it is almost always the storage, not the CPU. More threads
  will not help past your device's write ceiling, and a phone whose flash cache is
  exhausted can be ~2× slower than the same phone half an hour earlier. The threads
  screen exists for exactly this.
- **A file is marked `CORRUPTED`** — the NCA hash didn't match the CNMT. The output is
  kept on purpose so you can inspect it; the source file is the first suspect.
- **Reporting a bug** — attach `nsz_debug.log` and `nsz_folder_debug.log` from
  `Android/data/com.androNSZ/files/`, and check the `Run #` in the header matches the run
  you mean (only the current run and one previous are kept).

## Technical Details

### Architecture

```
┌─────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)             │
│  - Material 3 Components                │
│  - Screens: Mode, Picker, Conversion,   │
│    Settings, Threads, About             │
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
│  - WorkerPool, ThroughputRecorder       │
│  - TempFileManager, LogFiles            │
└──────────────┬──────────────────────────┘
               │ JNI
┌──────────────▼──────────────────────────┐
│  Native C Engine (libAndroNSZ)          │
│  - ncz_engine: conversion orchestration │
│  - ncz_decompress: Zstd + AES-CTR       │
│  - async_writer: write + SHA-256 thread │
│  - pfs0/hfs0: container parsing         │
│  - aes_ctr/aes_xts: encryption          │
│  - nca_cnmt: expected hashes from CNMT  │
│  - sha256: hash verification            │
└─────────────────────────────────────────┘
```

The full documentation set lives in [`ai_docs/`](ai_docs/start.md) — architecture
decisions, gotchas, subsystem notes and a decision log.

### Supported Formats

| Input Format | Output Format | Description | Status |
|-------------|---------------|-------------|--------|
| NSZ | NSP | Compressed game package → Standard package | ✅ Full support |
| XCZ | XCI | Compressed cartridge image → Standard image | ✅ Trimmed XCI tested; full XCI unverified |
| NCZ | NCA | Individual compressed content → Standard content | ✅ (within NSZ/XCZ) |

**Container formats:**

- **PFS0** (Partition File System 0): used by NSP/NSZ packages
- **HFS0** (Hash File System 0): used by XCI/XCZ cartridge images

### Compression & Encryption

**Compression:**

- Algorithm: **Zstandard (zstd) v1.5.5** — decompressor only, built statically
- Compression levels: 18–22 (original NSZ tool range)
- Two modes supported:
  - **Block-based**: chunks compressed separately with caching
  - **Solid**: continuous stream for maximum compression

**Encryption:**

- **AES-128-CTR** (counter mode): NCA section decryption
  - Block-by-block with counter `nonce[0:8] || (offset >> 4)`
- **AES-128-XTS** (XEX-based tweaked-codebook mode): NCA header decryption
  - IEEE P1619, 0x200-byte sectors
- **Hardware acceleration**: ARM64 uses NEON crypto extensions (AESE/AESMC)

**Verification:**

- Expected NCA hashes are read from the input's **CNMT** (`nca_cnmt.c`) and checked
  against SHA-256 computed **during** decompression — no second pass over the output
- Without keys, it degrades to a filename content-id check, marked `(by name)`
- A mismatch is always a warning, never a deleted file

### Performance

**Optimization techniques:**

- **Native C implementation** on the critical path
- **Hardware crypto** where the CPU offers it
- **Zero-copy input** — file descriptors handed straight to the native layer, with a
  temp-copy fallback for awkward FUSE providers
- **Async writer** with page-cache pacing (`sync_file_range` + `FADV_DONTNEED`) — worth
  ~2.2× on parallel writes
- **File-level parallelism** with a per-device thread count
- **ThinLTO** and `-O3` on the native build
- **Progress throttling** so the UI stays smooth

## Building from Source

### Prerequisites

- **Android Studio**: Ladybug (2024.2.1) or newer
- **Android SDK**: API 36
- **Android NDK**: 28.2.13676358
- **CMake**: 3.22.1 (bundled with the Android SDK)
- **JDK**: 17+ — the JetBrains Runtime shipped with Android Studio is what the project is
  built with; sources target Java 11

### Build Steps

1. **Clone the repository:**

   ```bash
   git clone https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker.git
   cd Andro-NSZ-unpacker
   ```

2. **Open in Android Studio:** File → Open → select the project directory, wait for the
   Gradle sync.

3. **Build the APK** (`.\gradlew.bat` on Windows/PowerShell):

   ```bash
   ./gradlew assembleDebug
   ./gradlew assembleRelease
   ```

4. **Run the checks:**

   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew lintDebug
   ```

5. **Install to a device:**

   ```bash
   ./gradlew installDebug
   ```

Useful during development: `./gradlew :app:compileDebugKotlin` for a quick Kotlin-only
pass, and `./gradlew :app:externalNativeBuildDebug` to rebuild just the C engine.

**APK location:**

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

### Native Library Compilation

The C engine is compiled automatically during the Gradle build:

```cmake
# Zstd: fetched from GitHub and built as a static library (decompressor only)
# AndroNSZ native library: -O3, ThinLTO
# ARM64: -march=armv8-a+crypto for hardware AES/SHA
```

To force a native rebuild:

```bash
./gradlew clean
./gradlew assembleDebug
```

## Comparison with Original NSZ

### What's Different

- **No compression**: Andro-NSZ only **decompresses** (NSZ→NSP, XCZ→XCI). For compression
  (NSP→NSZ), use the original nsz tool on desktop.
  (This restriction is temporary. It is not yet completely certain that the application
  packages data completely correctly, like the original nicoboss/nsz. So far, this is
  exactly the unpacker. I don't know if the packaging will be added in the future.)
- **Mobile-optimized**: designed for touchscreens and mobile workflows.
- **Native performance**: C engine built with the Android NDK, not Python.
- **Simplified**: no advanced options like custom compression levels (since we only
  decompress); the one knob that matters — how many files unpack in parallel — is
  exposed and can be measured.

### What's the Same

- **File format compatibility**: 100% compatible with the original NSZ format
- **Zstd algorithm**: same decompression logic
- **Encryption handling**: identical AES-CTR/XTS implementation
- **Verification**: same CNMT-hash-based approach

## Contributing

Bug reports and PRs are welcome — see [Troubleshooting](#troubleshooting) for what to
attach to a report.

Before writing code, read [`ai_docs/start.md`](ai_docs/start.md): it maps the
architecture decisions (`A##`), the known traps (`G##`) and the fragile areas. Two rules
that matter more than they look:

- Indentation is **exactly 3 spaces**, kept on blank lines too — auto-formatters strip
  it, so check after formatting ([`ai_docs/conventions.md`](ai_docs/conventions.md))
- Don't touch the crypto (`aes_ctr.c`, `aes_xts.c`, `sha256.c`), the decompression
  algorithm (`ncz_decompress.c`) or the JNI signatures without a reason — they are
  debugged against the Python reference
- Code, comments and commit messages in English

## Legal Notice

**Important Information:**

1. **No Copyrighted Material**: This software does NOT contain, distribute, or
   incorporate any copyrighted Nintendo content, game files, encryption keys, or
   proprietary firmware.

2. **Technology Protection Measures**: This tool does NOT circumvent, remove, or bypass
   any technological protection measures. It processes files that users already possess
   and have legal rights to use.

3. **User Responsibility**: Users are solely responsible for:
   - Obtaining their own `prod.keys` file legally from their own Nintendo Switch console
   - Ensuring they have legal rights to possess and process any game files
   - Complying with all applicable laws and terms of service in their jurisdiction

4. **Educational Purpose**: This software is provided for educational and archival
   purposes, enabling users to manage their legally owned game backups.

5. **This project is MIT licensed.** Check [LICENSE](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/blob/main/LICENSE) for more information.

6. **No Warranty**: This software is provided "as is" without warranty of any kind. Use
   at your own risk.

**prod.keys Disclaimer:** The `prod.keys` file must be obtained from YOUR OWN Nintendo
Switch console using legal homebrew tools. This app does not provide, generate, or help
obtain encryption keys. Distribution of prod.keys files is illegal and violates
Nintendo's intellectual property rights.

## Credits

**Original NSZ Tool:**

- Created by [nicoboss](https://github.com/nicoboss)
- Original repository: [nicoboss/nsz](https://github.com/nicoboss/nsz)

**Andro-NSZ Development:**

- Android port with native C engine
- Repository: [Fast-and-Pythonic/Andro-NSZ-unpacker](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker)

**Contributors:**

- [Fast-and-Pythonic](https://github.com/Fast-and-Pythonic) — initiator and project manager.
- Claude Code — Main developer, project architect, analyst, and advisor.
- Claude Code, Claude Design, Fast-and-Pythonic — GUI desing.
- GitHub Chat-gpt — Sometimes he criticizes the mistakes and shortcomings of the project, during the PRs.
- [manx98](https://github.com/manx98) — fix for XCZ→XCI extraction on gamecards with an
  empty HFS0 partition ([#6](https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker/pull/6))

**Libraries Used:**

- [Zstandard (zstd) 1.5.5](https://github.com/facebook/zstd) — fast real-time compression algorithm
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — modern Android UI toolkit
- [Material 3](https://m3.material.io/) — Google's design system
- [Android NDK](https://developer.android.com/ndk) — native development kit

**Special Thanks:**

- The Nintendo Switch homebrew community
- To all participants of the project [nicoboss/nsz](https://github.com/nicoboss/nsz)
- All contributors and testers
