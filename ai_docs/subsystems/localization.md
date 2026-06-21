# Subsystem: Localization

Стандартная Android-локализация: все строки UI вынесены в ресурсы, языки
добавляются без правки кода. Источник истины по самим строкам — файлы
`strings.xml` (точные значения и счётчики здесь не дублируем — устаревают).

## Файлы

```
app/src/main/res/
├── values/strings.xml       # English — fallback по умолчанию
└── values-ru/strings.xml     # Русский
```

Android сам выбирает файл по языку (с учётом per-app локали, см.
[../architecture.md](../architecture.md) A08). Если строки нет в `values-XX/` —
берётся `values/`; если нет и там — ошибка компиляции. **Каждый язык обязан иметь
ВСЕ ключи из `values/strings.xml`.**

## Категории (префиксы ключей)

| Префикс | Назначение |
|---------|-----------|
| `app_*` | имя/заголовок приложения |
| `action_*` | кнопки и действия |
| `label_*` | метки полей |
| `msg_*` | сообщения и подсказки |
| `status_*` | статусы операций (`status_unpacking`, `status_unpacked`, …) |
| `error_*` | сообщения об ошибках |
| `format_*` | строки с параметрами (`%d`, `%1$d`, …) |
| `settings_*`, `stats_format_*`, `language_*` | экран настроек |
| `stats_*` | статистика обработки папки |
| `cd_*` | content descriptions (accessibility) |

Новую строку — в нужную категорию, и сразу во ВСЕ языковые файлы.

## Использование в коде

- В Composable: `stringResource(R.string.key)` / `stringResource(R.string.key, arg)`.
- В ViewModel и не-Compose классах: `context.getString(R.string.key, args…)`
  (ViewModel не имеет прямого доступа к ресурсам, поэтому методы принимают
  `context: Context`).

## Параметры форматирования

- `%d` int, `%s` string, `%f` float, `%.1f` — 1 знак после запятой.
- Позиционные `%1$d`, `%2$d` позволяют менять порядок аргументов в переводах —
  **сохранять их при переводе**, иначе формат сломается.
- Многострочные сообщения — через `\n`. Опциональные суффиксы (путь к логу и т.п.)
  склеиваются в коде.

## Добавить язык

1. `app/src/main/res/values-XX/` (XX — код языка: `uk`, `de`, `fr`, …).
2. Скопировать `values/strings.xml` туда.
3. Перевести все строки, **сохраняя параметры** (`%d`, `%1$d`).
4. Собрать (`:app:assembleDebug`), сменить язык устройства, пройти экраны.

## Тестирование

- `@Preview(locale = "ru")` в Composable.
- На устройстве: Settings → System → Languages, затем пройти все экраны.

## Где используются строки

`stringResource`: экраны `ui/screen/*` и `ui/conversion/*`, компоненты
(`StatusLogPanel` и др.). `context.getString`: `MainViewModel` (ошибки, статусы,
результаты обработки папки).

## Best practices

✅ Не хардкодить текст; использовать `stringResource`/`getString`; сохранять
параметры формата; добавлять ключ во все языки.
❌ `Text("Добавить файлы")`; `"Files: "` вместо `"Files: %d"`; ключ в одном языке;
`stringResource()` вне Composable.
