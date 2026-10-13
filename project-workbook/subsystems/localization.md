# Subsystem: Localization

Standard Android localization: all UI strings live in resources, languages are
added without touching code. The source of truth for the strings themselves is the
`strings.xml` files (don't duplicate exact values/counters here — they go stale).

## Files

```
app/src/main/res/
├── values/strings.xml       # English — default fallback
└── values-ru/strings.xml     # Russian
```

Android picks the file by language (respecting the per-app locale, see
[../architecture.md](../architecture.md) A08). If a string is missing in
`values-XX/` — `values/` is used; if it's missing there too — a compile error.
**Every language must have ALL keys from `values/strings.xml`.**

## Categories (key prefixes)

| Prefix | Purpose |
|--------|---------|
| `app_*` | app name/title |
| `action_*` | buttons and actions |
| `label_*` | field labels |
| `msg_*` | messages and hints |
| `status_*` | operation statuses (`status_unpacking`, `status_unpacked`, …) |
| `error_*` | error messages |
| `format_*` | strings with parameters (`%d`, `%1$d`, …) |
| `settings_*`, `stats_format_*`, `language_*`, `theme_*`, `accent_*` | settings screen |
| `picker_*` | in-app file picker (titles, hints, permission gate) |
| `stats_*` | folder-processing statistics |
| `cd_*` | content descriptions (accessibility) |

Put a new string in the right category, and immediately in ALL language files.

## Usage in code

- In a Composable: `stringResource(R.string.key)` / `stringResource(R.string.key, arg)`.
- In the ViewModel and non-Compose classes: `context.getString(R.string.key, args…)`
  (the ViewModel has no direct resource access, so its methods take a
  `context: Context`).

## Format parameters

- `%d` int, `%s` string, `%f` float, `%.1f` — 1 decimal place.
- Positional `%1$d`, `%2$d` let translations reorder arguments — **keep them when
  translating**, otherwise the format breaks.
- Multi-line messages — via `\n`. Optional suffixes (log path, etc.) are concatenated
  in code.

## Adding a language

1. `app/src/main/res/values-XX/` (XX — language code: `uk`, `de`, `fr`, …).
2. Copy `values/strings.xml` there.
3. Translate all strings, **keeping the parameters** (`%d`, `%1$d`).
4. Build (`:app:assembleDebug`), switch the device language, walk the screens.

## Testing

- `@Preview(locale = "ru")` in a Composable.
- On a device: Settings → System → Languages, then walk all screens.

## Where strings are used

`stringResource`: the `ui/screen/*` and `ui/conversion/*` screens, components
(`StatusLogPanel`, etc.). `context.getString`: `MainViewModel` (errors, statuses,
folder-processing results).

## Best practices

✅ Don't hardcode text; use `stringResource`/`getString`; keep format parameters; add
the key to all languages.
❌ `Text("Add files")`; `"Files: "` instead of `"Files: %d"`; a key in one language
only; `stringResource()` outside a Composable.
