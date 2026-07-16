# Overview

## Purpose

AndroNSZ unpacks compressed Nintendo Switch images on Android:
- **NSZ → NSP** — packages (PFS0 container);
- **XCZ → XCI** — gamecard images (HFS0 container).

The inner compression is the NCZ format (zstd + AES-CTR on the encrypted NCA
sections). The engine logic mirrors the Python reference
[nicoboss/nsz](https://github.com/nicoboss/nsz). Three modes: single file
(legacy, no UI), a file queue, and a whole folder.

## Stack

- **UI:** Kotlin, Jetpack Compose, Material Design 3.
- **Native:** C11, CMake 3.22.1, zstd 1.5.5 (decompressor only, static).
- **Bridge:** JNI (`jni_bridge.c` ↔ `NszConverter.kt`).
- **Persistence:** DataStore Preferences (`statsFormat`, `outputFolderUri`) +
  SharedPreferences (`language` — needed synchronously in `attachBaseContext`).
- **Build:** Gradle (Kotlin DSL), AGP + NDK `28.2.13676358`, CMake `3.22.1`.
- **SDK:** `compileSdk`/`targetSdk` 36, `minSdk` 31. ABIs: `arm64-v8a`, `armeabi-v7a`.
- **Java:** source/target 11.

## Structure (high-level)

```
AndroNSZ/
├── CLAUDE.md                     # thin pointer to ai_docs/start.md
├── ai_docs/                      # documentation (this standard)
└── app/src/main/
    ├── AndroidManifest.xml
    ├── cpp/                      # === native C engine (libAndroNSZ) ===
    │   ├── CMakeLists.txt        # zstd + engine, -O3, ThinLTO, +crypto
    │   ├── jni_bridge.c          # JNI Kotlin ↔ C
    │   ├── ncz_engine.c          # orchestrator: NSZ→NSP, XCZ→XCI
    │   ├── ncz.c / ncz_decompress.c  # NCZ parser + decompression (solid/block)
    │   ├── async_writer.c        # background write+SHA-256 thread (async I/O)
    │   ├── pfs0.c / hfs0.c       # NSP/NSZ and XCI/XCZ containers
    │   ├── aes_ctr.c / aes_xts.c # AES-128 CTR (sections) and XTS (NCA header)
    │   ├── sha256.c              # SHA-256 (hardware + software)
    │   ├── nca_verifier.c        # NCA verification inside a finished NSP
    │   ├── nsz_debug.c           # log to file + logcat
    │   └── nsz_types.h           # error codes, callback types
    └── java/com/androNSZ/        # === Kotlin layer ===
        ├── MainActivity.kt       # the single Activity, per-app locale
        ├── NszConverter.kt       # JNI wrapper, progress Flow, MediaStore I/O
        ├── Constants.kt          # progress-throttling intervals
        ├── data/                 # SettingsRepository (DataStore + SharedPrefs)
        ├── model/                # data classes and sealed state classes
        ├── nut/                  # KeysManager / KeysParser (prod.keys)
        ├── fs/                   # folder scanners (SAF + raw FS), FolderProcessor, temp, logs
        ├── util/                 # FileUtils/FormatUtils, StoragePermission (all-files access)
        ├── ui/                   # screen/ (incl. FilePickerScreen) + conversion/ + components/ + theme/
        └── viewmodel/            # MainViewModel — all state and logic
```

Per-module details — in [subsystems/kotlin-layer.md](subsystems/kotlin-layer.md) and
[subsystems/native-engine.md](subsystems/native-engine.md).

## Current state

**Working:**
- All three conversion modes (single file, queue, folder), NSZ→NSP and XCZ→XCI.
- Hardware AES-CTR and SHA-256 (ARMv8 crypto extensions) with a software fallback.
- No-copy input reading via `fd:N` with a temp-copy fallback for FUSE providers.
- Async output writing (`async_writer`), zstd built with `-O3`, ThinLTO.
- Core-adaptive batch/folder parallelism (`resolveConcurrency` → auto 1..3);
  overridable up to the core count via a Settings slider when verification is off
  (an experiment to measure core scaling — see [architecture.md](architecture.md) A07).
- EN/RU localization, in-app language selection, output folder selection (SAF).
- SHA-256 verification of the finished NCA/NSP (non-fatal — see
  [architecture.md](architecture.md) A12; needs header_key in prod.keys).

**With caveats / deferred:** see [status.md](status.md).

## Commands

Run from the project root (Windows, PowerShell):

```powershell
.\gradlew.bat :app:compileDebugKotlin     # quick Kotlin check (first pass)
.\gradlew.bat :app:assembleDebug          # full debug build (incl. native)
.\gradlew.bat :app:testDebugUnitTest      # unit tests
.\gradlew.bat :app:lintDebug              # Android lint
.\gradlew.bat :app:externalNativeBuildDebug  # native part only (C/CMake)
.\gradlew.bat :app:installDebug           # install to a connected device
```

The minimum before a commit and the check order — in [conventions.md](conventions.md).
