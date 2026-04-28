# Система локализации AndroNSZ

## Обзор

AndroNSZ v2 использует стандартную систему локализации Android с поддержкой множества языков. Все строки пользовательского интерфейса вынесены в ресурсные файлы, что позволяет легко добавлять новые языки без изменения кода.

**Текущие языки:**
- 🇬🇧 Английский (fallback) — `values/strings.xml`
- 🇷🇺 Русский — `values-ru/strings.xml`

## Структура файлов

```
app/src/main/res/
├── values/
│   └── strings.xml          # English (по умолчанию для всех языков)
└── values-ru/
    └── strings.xml          # Русский перевод
```

Android автоматически выбирает правильный файл строк на основе языка устройства пользователя.

## Категории строк

Все строки организованы по префиксам для лучшей структуры:

### `app_*` — Приложение
```xml
<string name="app_name">Andro-NSZ unpacker</string>
<string name="app_title">AndroNSZ</string>
```

### `action_*` — Действия и кнопки
```xml
<string name="action_add_files">Add files</string>
<string name="action_select_folder">Select folder</string>
<string name="action_convert">Convert</string>
<string name="action_cancel">Cancel</string>
<string name="action_settings">Settings</string>
<string name="action_change_output_folder">Change output folder</string>
```

### `label_*` — Метки полей
```xml
<string name="label_current_file">Current file</string>
<string name="label_folder_selected">Folder selected</string>
```

### `msg_*` — Сообщения и подсказки
```xml
<string name="msg_prod_keys_required">prod.keys required for proper NSZ to NSP decompression.</string>
<string name="msg_select_mode">Select working mode</string>
```

### `status_*` — Статусы операций
```xml
<string name="status_waiting">Waiting</string>
<string name="status_converting">Converting…</string>
<string name="status_done">Done</string>
<string name="status_error">Error</string>
```

### `error_*` — Сообщения об ошибках
```xml
<string name="error_scan_failed">Scan failed: %s</string>
<string name="error_general">Error: %s</string>
```

### `format_*` — Строки с форматированием
```xml
<string name="format_files_queued">Files queued: %d</string>
<string name="format_file_n_of_m">File %1$d of %2$d</string>
<string name="format_files_processed">Files: %1$d / %2$d</string>
```

### `settings_*` — Экран настроек
```xml
<string name="settings_title">Settings</string>
<string name="settings_stats_format">Final statistics format</string>
<string name="settings_language">App language</string>
<string name="stats_format_compact">Compact</string>
<string name="stats_format_detailed">Detailed</string>
<string name="language_system">System default</string>
<string name="language_english">English</string>
<string name="language_russian">Russian</string>
```

### `stats_*` — Статистика обработки папки
```xml
<string name="stats_title">Processing Statistics</string>
<string name="stats_all_files">Total files processed:</string>
<string name="stats_success">✅ Successful: %1$d of %2$d</string>
<string name="stats_failed">❌ Failed: %1$d of %2$d</string>
<string name="stats_nsz_conversion">NSZ files decompressed:</string>
<string name="stats_xcz_conversion">XCZ files decompressed:</string>
<string name="stats_files_copied">Files copied:</string>
```

### `cd_*` — Content Descriptions (для accessibility)
```xml
<string name="cd_back">Back</string>
<string name="cd_settings">Settings</string>
```

## Использование в коде

### В Composable функциях (UI)

Используйте `stringResource()`:

```kotlin
import androidx.compose.ui.res.stringResource
import com.androNSZ.R

@Composable
fun MyScreen() {
    // Простая строка
    Text(stringResource(R.string.action_add_files))
    
    // Строка с параметрами
    Text(stringResource(R.string.format_files_queued, fileCount))
    
    // Строка с несколькими параметрами
    Text(stringResource(R.string.format_file_n_of_m, current, total))
}
```

### В ViewModel или обычных классах

Используйте `context.getString()`:

```kotlin
import com.androNSZ.R

class MainViewModel : ViewModel() {
    fun someMethod(context: Context) {
        // Простая строка
        val message = context.getString(R.string.status_scanning_folder)
        
        // Строка с параметрами
        val error = context.getString(R.string.error_scan_failed, errorMessage)
        
        // Строка с несколькими параметрами
        val progress = context.getString(
            R.string.format_files_processed, 
            processed, 
            total
        )
    }
}
```

## Как добавить новый язык

### Шаг 1: Создать папку для языка

```bash
mkdir -p app/src/main/res/values-XX
```

Где `XX` — код языка:
- `uk` — украинский
- `de` — немецкий
- `fr` — французский
- `es` — испанский
- `ja` — японский
- `zh` — китайский
- и т.д.

### Шаг 2: Скопировать strings.xml

```bash
cp app/src/main/res/values/strings.xml app/src/main/res/values-XX/strings.xml
```

### Шаг 3: Перевести все строки

Откройте `values-XX/strings.xml` и переведите все строки, **сохраняя параметры форматирования**:

```xml
<!-- НЕ ПРАВИЛЬНО -->
<string name="format_files_queued">Файлов в очереди</string>

<!-- ПРАВИЛЬНО -->
<string name="format_files_queued">Файлов в очереди: %d</string>
```

### Шаг 4: Проверить

1. Соберите приложение: `./gradlew assembleDebug`
2. Измените язык устройства/эмулятора на новый язык
3. Запустите приложение — все тексты должны быть на новом языке

## Как добавить новую строку

### Шаг 1: Добавить в values/strings.xml

```xml
<string name="new_string_key">English text</string>
```

Выберите правильный префикс:
- Кнопка → `action_*`
- Метка → `label_*`
- Сообщение → `msg_*`
- Статус → `status_*`
- Ошибка → `error_*`
- С параметрами → `format_*`

### Шаг 2: Добавить в values-ru/strings.xml

```xml
<string name="new_string_key">Русский текст</string>
```

### Шаг 3: Добавить во все другие языки

Если есть другие папки `values-XX/`, добавьте перевод и туда.

### Шаг 4: Использовать в коде

```kotlin
// В UI
Text(stringResource(R.string.new_string_key))

// В ViewModel
val text = context.getString(R.string.new_string_key)
```

## Форматирование строк с параметрами

### Один параметр

```xml
<string name="format_count">Files: %d</string>
```

```kotlin
stringResource(R.string.format_count, 42)
// Результат: "Files: 42"
```

### Несколько параметров

```xml
<string name="format_progress">%1$d of %2$d files</string>
```

```kotlin
stringResource(R.string.format_progress, 5, 10)
// Результат: "5 of 10 files"
```

Позиции `%1$d`, `%2$d` позволяют менять порядок параметров в разных языках!

### Типы форматирования

- `%s` — строка (String)
- `%d` — целое число (Int)
- `%f` — число с плавающей точкой (Float/Double)
- `%.1f` — число с 1 знаком после запятой

## Текущие файлы с локализацией

### UI компоненты (используют stringResource)

1. `app/src/main/java/com/androNSZ/ui/screen/ConversionScreen.kt`
   - Заголовок приложения
   - Меню (настройки, смена папки вывода)

2. `app/src/main/java/com/androNSZ/ui/screen/ModeSelectionScreen.kt`
   - Выбор режима работы
   - Предупреждение о prod.keys
   - Меню (настройки, смена папки вывода)

3. `app/src/main/java/com/androNSZ/ui/screen/SettingsScreen.kt`
   - Выбор формата статистики (Compact / Detailed)
   - Выбор языка приложения (System / English / Russian)

4. `app/src/main/java/com/androNSZ/ui/screen/AboutScreen.kt`
   - Все разделы информации о приложении

5. `app/src/main/java/com/androNSZ/ui/conversion/SingleFilesUI.kt`
   - UI для режима нескольких файлов
   - Кнопки, статусы, прогресс

6. `app/src/main/java/com/androNSZ/ui/conversion/FolderModeUI.kt`
   - UI для режима папки
   - Информация о структуре папки

7. `app/src/main/java/com/androNSZ/ui/conversion/LegacySingleFileUI.kt`
   - Legacy UI для одного файла
   - Кнопки конвертации

8. `app/src/main/java/com/androNSZ/ui/components/StatusLogPanel.kt`
   - Панель логов (показать/скрыть)

### ViewModel (использует context.getString)

9. `app/src/main/java/com/androNSZ/viewmodel/MainViewModel.kt`
   - Сообщения об ошибках
   - Статусы конвертации
   - Результаты обработки (включая статистику по типам операций)

## Общее количество строк

**~134 строки** организовано в категории:
- 2 строки приложения (`app_*`)
- 18 строк действий (`action_*`)
- 5 строк меток (`label_*`)
- 9 строк сообщений (`msg_*`)
- 6 строк статусов (`status_*`)
- 6 строк ошибок (`error_*`)
- 11 строк форматирования (`format_*`)
- 2 строки результатов
- 2 content descriptions (`cd_*`)
- 9 строк настроек (`settings_*` + `stats_format_*` + `language_*`)
- 7 строк статистики папки (`stats_*`)
- ~57 строк экрана About

## Особенности реализации

### ViewModel и Context

ViewModel не имеет прямого доступа к ресурсам, поэтому:
- Все методы ViewModel принимают `context: Context`
- Используется `context.getString(R.string.xxx)`

### Длинные сообщения

Многострочные сообщения используют `\n`:

```xml
<string name="result_done_no_verify">Done! Saved to Downloads.\n(header_key not found in prod.keys — verification skipped)</string>
```

### Динамические суффиксы

Для сообщений с опциональными суффиксами (например, путь к логу):

```kotlin
val logSuffix = if (logPath != null) {
    "\n${context.getString(R.string.format_debug_log, logPath)}"
} else {
    ""
}
statusMessage = context.getString(R.string.error_general, errorMsg) + logSuffix
```

## Тестирование локализации

### На устройстве/эмуляторе

1. Откройте Settings → System → Languages
2. Добавьте русский язык или выберите другой
3. Запустите AndroNSZ
4. Все тексты должны быть на выбранном языке

### В Android Studio

1. Preview в Composable: `@Preview(locale = "ru")`
2. Выбор языка в Device Manager
3. Проверка всех экранов приложения

## Fallback-логика

Если строка не найдена в `values-XX/`:
1. Android ищет в `values/` (английский)
2. Если и там нет — ошибка компиляции

**Важно:** Все языки должны иметь ВСЕ строки из `values/strings.xml`!

## Лучшие практики

✅ **ПРАВИЛЬНО:**
- Использовать stringResource() в Composable
- Использовать context.getString() в ViewModel
- Сохранять параметры форматирования (%d, %s)
- Организовывать строки по категориям
- Добавлять новые строки во ВСЕ языки

❌ **НЕ ПРАВИЛЬНО:**
- Захардкодить текст в коде: `Text("Добавить файлы")`
- Забыть параметры: `"Files: "` вместо `"Files: %d"`
- Добавить строку только в один язык
- Использовать stringResource() вне Composable

## История изменений

**2026-04-16:** Создана система локализации
- Вынесены все ~60 hardcoded строк
- Организованы категории (app_, action_, label_, msg_, status_, error_, format_)
- Добавлена поддержка русского языка
- Обновлены все UI компоненты и ViewModel

**2026-04-28:** Расширение до ~134 строк
- Добавлены строки экрана настроек (`settings_*`, `stats_format_*`, `language_*`)
- Добавлены строки статистики папки (`stats_*`)
- Добавлены `action_settings`, `action_change_output_folder`
- Полная локализация `AboutScreen` и `SettingsScreen`
- Обновлён список файлов с локализацией
