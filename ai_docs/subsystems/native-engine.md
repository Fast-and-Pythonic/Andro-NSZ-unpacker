# Subsystem: Native engine (libAndroNSZ)

C11-движок распаковки. Собирается через CMake (`app/src/main/cpp/CMakeLists.txt`)
вместе со static-zstd. Вызывается из Kotlin через JNI. Логика повторяет
Python-референс nicoboss/nsz.

> Криптографию, распаковку, парсеры контейнеров и сигнатуры JNI **не менять без
> явного запроса** — см. [../conventions.md](../conventions.md).

## Модули

| Файл | Назначение |
|------|-----------|
| `jni_bridge.c` | JNI entry points; маршалинг строк/коллбэков; attach потока к JVM |
| `ncz_engine.c` | Оркестратор: `ncz_convert_nsz_to_nsp`, `ncz_convert_xcz_to_xci`, cancel, `ncz_error_string` |
| `ncz.c` | Парсер NCZ-заголовка: секции (`NczSection`), блоки (`NczBlockHeader`), FakeSection (зазор между NCA-хедером и первой секцией) |
| `ncz_decompress.c` | Распаковка: `BlockReader` (поблочный zstd с кэшем) и `SolidReader` (потоковый `ZSTD_DStream`). AES-CTR только для crypto_type 3/4; FakeSection (type 1) — plaintext. Кормит SHA-256 |
| `async_writer.c` | Фоновый поток записи+хеширования (см. [../architecture.md](../architecture.md) A04) |
| `pfs0.c` | Контейнер PFS0 (NSP/NSZ). Запись — 24 байта. `pfs0_parse`, `pfs0_write_header` (.ncz→.nca, пересчёт размеров) |
| `hfs0.c` | Контейнер HFS0 (XCI/XCZ). Запись — 64 байта (есть поле SHA-256). Хеш-поля обнуляются ради совместимости с референсом |
| `aes_ctr.c` | AES-128-CTR для секций NCA. Counter: `nonce[0:8] \|\| (offset>>4)` big-endian. Hardware + software (A02) |
| `aes_xts.c` | AES-128-XTS для расшифровки заголовка NCA (сектора 0x200, IEEE 1619) |
| `sha256.c` | SHA-256: one-shot `sha256()` и streaming (`init/update/final`). Hardware + software (A03) |
| `nca_verifier.c` | `nca_verify_nsp()`: AES-XTS заголовка, проверка magic «NCA3», SHA-256 секций |
| `nsz_debug.c` | `dbg_open/close/log/hex` — лог в файл + logcat, миллисекундные таймстампы |
| `nsz_types.h` | Коды ошибок, типы коллбэков, константы (`NCA_HEADER_SIZE=0x4000`) |

## Граф зависимостей (C)

```
jni_bridge.c
 └─ ncz_engine.c
     ├─ pfs0.c / hfs0.c
     ├─ ncz.c
     └─ ncz_decompress.c
         ├─ aes_ctr.c
         ├─ sha256.c
         └─ async_writer.c

nca_verifier.c      (отдельный entry point через JNI)
 ├─ pfs0.c
 ├─ aes_xts.c
 └─ sha256.c

nsz_debug.c         (используется повсюду)
```

## Потоки конвертации

**`ncz_convert_nsz_to_nsp()`** (NSZ→NSP):
1. Парс PFS0 входа.
2. Пре-скан NCZ-файлов → размеры распакованного.
3. Запись нового PFS0-хедера с обновлёнными размерами.
4. По каждому файлу: NCZ → распаковать, иначе → скопировать.
5. SHA-256-сверка по имени файла (hex-префикс).
6. При ошибке — удалить частичный вывод.

**`ncz_convert_xcz_to_xci()`** (XCZ→XCI):
1. Копирование XCI-хедера (0x200 байт) как есть.
2. Парс HFS0 по смещению (обычно 0xF000).
3. Пре-скан NCZ → размеры; запись обновлённого HFS0.
4. По каждому файлу: NCZ → распаковать, иначе → скопировать.
5. Обновление XCI-хедера новым размером HFS0.

## JNI-интерфейс

Сигнатуры — в `NszConverter.kt` (`native*`) ↔ `jni_bridge.c`.

| Kotlin-метод | Описание |
|--------------|----------|
| `nativeConvert(input, output, progressCb, statusCb): Int` | NSZ → NSP |
| `nativeConvertXcz(input, output, progressCb, statusCb): Int` | XCZ → XCI |
| `nativeVerifyNsp(nspPath, headerKey): String?` | Проверка NCA в NSP |
| `nativeSetDebugLog(path)` / `nativeCloseDebugLog()` | Debug-лог |
| `nativeCancel()` | Запрос отмены |
| `nativeErrorString(code): String` | Код ошибки → текст |

`input` принимает обычный путь, `"file://"` или `"fd:N"` (no-copy, см.
[../architecture.md](../architecture.md) A05). `output` — `/proc/self/fd/<fd>`.

**Коллбэки:**
- `ProgressCallback.onProgress(done, total)` — байты распакованного вывода.
- `StatusCallback.onStatus(tag, msg)` — структурированные сообщения. Теги (нативные):
  `OPEN`, `EXISTS`, `HEAD`, `NCA_HASH`, `VERIFIED`, `OK`, `SUCCESS`, `CANCELLED`,
  `ERROR`. Kotlin дополнительно использует `FILE_START`, `FOLDER`, `NSZ`, `INFO`.

## Коды ошибок (`nsz_types.h`)

| Код | Константа | Описание |
|-----|-----------|----------|
| 0 | `NCZ_OK` | Успех |
| -1 | `NCZ_ERR_OPEN_INPUT` | Не открылся вход |
| -2 | `NCZ_ERR_OPEN_OUTPUT` | Не открылся выход |
| -3 | `NCZ_ERR_INVALID_PFS0` | Невалидный PFS0 |
| -4 | `NCZ_ERR_INVALID_NCZ` | Невалидный NCZ-хедер |
| -5 | `NCZ_ERR_ZSTD` | Ошибка zstd |
| -6 | `NCZ_ERR_IO` | I/O-ошибка |
| -7 | `NCZ_ERR_OOM` | Нет памяти |
| -8 | `NCZ_ERR_CANCELLED` | Отменено пользователем |
| -9 | `NCZ_ERR_HASH_MISMATCH` | Несовпадение SHA-256 |

Формат NSZ/NCZ как таковой — в [../references/nsz-format.md](../references/nsz-format.md).
