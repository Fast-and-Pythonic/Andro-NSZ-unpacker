# Overview

## Назначение

AndroNSZ распаковывает сжатые образы Nintendo Switch на Android:
- **NSZ → NSP** — пакеты (контейнер PFS0);
- **XCZ → XCI** — образы картриджей (контейнер HFS0).

Сжатие внутри — формат NCZ (zstd + AES-CTR на зашифрованных секциях NCA).
Логика движка повторяет Python-референс [nicoboss/nsz](https://github.com/nicoboss/nsz).
Три режима работы: один файл (legacy, без UI), очередь файлов, целая папка.

## Стек

- **UI:** Kotlin, Jetpack Compose, Material Design 3.
- **Native:** C11, CMake 3.22.1, zstd 1.5.5 (только декомпрессор, static).
- **Bridge:** JNI (`jni_bridge.c` ↔ `NszConverter.kt`).
- **Persistence:** DataStore Preferences (`statsFormat`, `outputFolderUri`) +
  SharedPreferences (`language` — нужен синхронно в `attachBaseContext`).
- **Build:** Gradle (Kotlin DSL), AGP + NDK `28.2.13676358`, CMake `3.22.1`.
- **SDK:** `compileSdk`/`targetSdk` 36, `minSdk` 31. ABI: `arm64-v8a`, `armeabi-v7a`.
- **Java:** source/target 11.

## Структура (высокоуровневая)

```
AndroNSZ/
├── CLAUDE.md                     # тонкий указатель на ai_docs/start.md
├── ai_docs/                      # документация (этот стандарт)
└── app/src/main/
    ├── AndroidManifest.xml
    ├── cpp/                      # === нативный C-движок (libAndroNSZ) ===
    │   ├── CMakeLists.txt        # zstd + движок, -O3, ThinLTO, +crypto
    │   ├── jni_bridge.c          # JNI Kotlin ↔ C
    │   ├── ncz_engine.c          # оркестратор: NSZ→NSP, XCZ→XCI
    │   ├── ncz.c / ncz_decompress.c  # парсер NCZ + распаковка (solid/block)
    │   ├── async_writer.c        # фоновый поток записи+SHA-256 (async I/O)
    │   ├── pfs0.c / hfs0.c       # контейнеры NSP/NSZ и XCI/XCZ
    │   ├── aes_ctr.c / aes_xts.c # AES-128 CTR (секции) и XTS (заголовок NCA)
    │   ├── sha256.c              # SHA-256 (hardware + software)
    │   ├── nca_verifier.c        # проверка NCA в готовом NSP
    │   ├── nsz_debug.c           # лог в файл + logcat
    │   └── nsz_types.h           # коды ошибок, типы коллбэков
    └── java/com/androNSZ/        # === Kotlin-слой ===
        ├── MainActivity.kt       # единственная Activity, per-app locale
        ├── NszConverter.kt       # JNI-обёртка, Flow прогресса, MediaStore I/O
        ├── Constants.kt          # интервалы троттлинга прогресса
        ├── data/                 # SettingsRepository (DataStore + SharedPrefs)
        ├── model/                # data-классы и sealed-классы состояния
        ├── nut/                  # KeysManager / KeysParser (prod.keys)
        ├── fs/                   # сканер папки, FolderProcessor, temp, логи
        ├── ui/                   # screen/ + conversion/ + components/ + theme/
        └── viewmodel/            # MainViewModel — всё состояние и логика
```

Детали по модулям — в [subsystems/kotlin-layer.md](subsystems/kotlin-layer.md) и
[subsystems/native-engine.md](subsystems/native-engine.md).

## Текущее состояние

**Работает:**
- Все три режима конвертации (один файл, очередь, папка), NSZ→NSP и XCZ→XCI.
- Аппаратные AES-CTR и SHA-256 (ARMv8 crypto-расширения) с software-фоллбэком.
- No-copy чтение входа через `fd:N` с откатом на temp-копию для FUSE-провайдеров.
- Асинхронная запись вывода (`async_writer`), zstd собран на `-O3`, ThinLTO.
- Core-adaptive batch-параллелизм в режиме очереди (`BATCH_CONCURRENCY` = 1..3).
- Локализация EN/RU, выбор языка внутри приложения, выбор папки вывода (SAF).
- Verify готового NSP по SHA-256 (если в prod.keys есть header_key).

**С оговорками / отложено:** см. [status.md](status.md).

## Команды

Запускать из корня проекта (Windows, PowerShell):

```powershell
.\gradlew.bat :app:compileDebugKotlin     # быстрая проверка Kotlin (первый проход)
.\gradlew.bat :app:assembleDebug          # полная debug-сборка (вкл. нативную)
.\gradlew.bat :app:testDebugUnitTest      # unit-тесты
.\gradlew.bat :app:lintDebug              # Android lint
.\gradlew.bat :app:externalNativeBuildDebug  # только нативная часть (C/CMake)
.\gradlew.bat :app:installDebug           # установить на подключённое устройство
```

Минимум перед коммитом и порядок проверок — в [conventions.md](conventions.md).
