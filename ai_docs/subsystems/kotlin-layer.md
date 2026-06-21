# Subsystem: Kotlin layer

UI на Jetpack Compose + вся логика конвертации. Единственная Activity
(`MainActivity`) хостит `AndroNSZApp`. Состояние и оркестрация — в `MainViewModel`.

## Навигация

`MainActivity` → `AndroNSZApp` роутит по `vm.currentScreen`. Навигация — это
**стек экранов** в `MainViewModel`: `_screenStack` (`mutableStateListOf`,
старт `Screen.ModeSelection`), `navigateTo(screen)` пушит, `navigateBack()` снимает
(не опускаясь ниже одного). Экраны (`model/Screen.kt`): `ModeSelection`,
`Conversion`, `About`, `Settings`.

`MainActivity.attachBaseContext()` синхронно читает язык и оборачивает context
нужной `Locale` (per-app язык, см. [../architecture.md](../architecture.md) A08).
`onCreate()` чистит temp-кэш (`TempFileManager.cleanupManagedCache`).

## Экраны (`ui/screen/`)

- **AndroNSZApp.kt** — корень навигации. На старте `vm.checkKeys` + `vm.loadSettings`
  через `LaunchedEffect`; хостит SAF-лаунчеры (папка вывода, prod.keys).
- **ModeSelectionScreen.kt** — выбор режима: `SingleFiles` или `FolderMode`
  (только эти два). Overflow-меню: prod.keys, смена папки вывода, Settings, About.
- **ConversionScreen.kt** — рендерит по `conversionMode`: `SingleFilesUI`
  (SingleFiles) или `FolderModeUI` (FolderMode). Ветка `None` недостижима (на этом
  экране) и пустая — нужна лишь для исчерпывающего `when`.
- **SettingsScreen.kt** — формат статистики (`StatsFormat`) и язык
  (System/English/Russian; смена → `recreate()`).
- **AboutScreen.kt** — информация о приложении.

## Режимы конвертации (`ui/conversion/`)

- **SingleFilesUI.kt** — очередь файлов: добавление, список, общий бар + по бару на
  каждый активно конвертируемый файл (`activeFileProgress`), контекстный статус
  «Распаковываем…/Распаковано» + таймер.
- **FolderModeUI.kt** — папка: инфо о структуре, дерево, общий + текущий бар.

## MainViewModel

Хаб состояния и логики. Ключевые группы полей:
- Навигация/режим: `_screenStack`/`currentScreen`, `conversionMode`, `keysInstalled`.
- Очередь: `fileQueue`, `currentFileIndex`, `batchOverallProgress`,
  `batchProcessedFiles/TotalFiles`, `activeFileProgress` (по индексу файла).
- Папка: `folderStructure`, `folderOverallProgress`, `folderCurrentFileProgress`,
  `folderProcessedFiles/TotalFiles`, `folderLogPath`.
- Общее: `isConverting`, `progress`, `elapsedMs` (таймер), `statusMessage`,
  `statusLog`, `isSuccess`, `compact2Stats`.
- Настройки: `statsFormat`, `outputFolderUri`, `appLanguage`.

Ключевые методы: `checkKeys/installKeys/deleteKeys`, `loadSettings`, `saveLanguage`,
`saveOutputFolder` (с persistable SAF-разрешением), `saveStatsFormat`,
`startBatchConversion` (очередь, параллельно — A07), `startFolderConversion`
(через `FolderProcessor`), `resetConversionState`.

> **Мёртвый legacy-код:** `startConversion()`, `pickFile()`, `selectedUri`,
> `selectedName` остались после удаления `LegacySingleFileUI` и больше не вызываются
> (см. [../status.md](../status.md)).

## NszConverter (JNI-обёртка)

Синглтон над `libAndroNSZ`. `convert()` (NSZ→NSP) и `convertXcz()` (XCZ→XCI):
1. Резолв входного `Uri` → нативный путь: предпочтительно `"fd:N"` (no-copy), иначе
   temp-копия (A05/A06).
2. Создание выходного файла в Downloads (или `outputFolderUri`) через MediaStore,
   путь `/proc/self/fd/<fd>`.
3. `nativeConvert`/`nativeConvertXcz`, прогресс стримится через `callbackFlow`
   (троттлинг — A09).
4. Verify через `nativeVerifyNsp` (если есть header_key).
5. Очистка temp; при FUSE-сбое — откат на temp-копию и ретрай (G03).

## Прочие модули

- **Constants.kt** — `PROGRESS_BAR_UPDATE_INTERVAL_MS=100`,
  `PROGRESS_NUMERIC_UPDATE_INTERVAL_MS=500` (A09).
- **data/SettingsRepository.kt** — синглтон. `language` в SharedPreferences
  (синхронно), `statsFormat`/`outputFolderUri` — в DataStore (Flow).
- **model/** — `ConversionMode` (None/SingleFiles/FolderMode), `StatsFormat`
  (COMPACT/COMPACT2/COMPACT3/DETAILED), `ConversionProgress` (done/total/speed),
  `FileEntry` (uri/name/size/status), `FolderConversionResult` (типы операций и
  summary), `FolderStructure`/`FileNode`, `LogEntry`, `Screen`.
- **nut/** — `KeysManager` (хранит prod.keys в `filesDir`), `KeysParser`
  (извлекает `header_key`, 32 байта).
- **fs/** — `FolderScanner` (рекурсивный обход `DocumentsContract` → дерево +
  списки NSZ/XCZ), `FolderProcessor` (батч: NSZ→NSP, XCZ→XCI, прочее → копия;
  сохраняет структуру, продолжает при ошибках; свой троттлинг прогресса),
  `TempFileManager` (`cacheDir`, `andronsz_<UUID>_<name>.<ext>`), `FolderLogWriter`
  (потокобезопасная запись лога под `Mutex`).

## Data flow (кратко)

- **Очередь:** добавление → `startBatchConversion` → до `BATCH_CONCURRENCY` файлов
  параллельно через `Semaphore`, каждый `NszConverter.convert()`; статусы
  Pending→Converting→Completed/Failed; общий прогресс по сумме `fileTotals`.
- **Папка:** `FolderScanner.scanFolder` → `FolderStructure` →
  `startFolderConversion` → `FolderProcessor.processFolder` рекурсивно →
  `FolderConversionSummary` → лог закрывается, temp чистится.
