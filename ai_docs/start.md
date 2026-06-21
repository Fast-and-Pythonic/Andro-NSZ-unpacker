# AndroNSZ — точка входа в документацию

Android-приложение для распаковки сжатых файлов Nintendo Switch: **NSZ → NSP**
(пакеты) и **XCZ → XCI** (картриджи). Kotlin/Compose UI + нативный C-движок через
JNI. Порт Python-референса [nicoboss/nsz](https://github.com/nicoboss/nsz).

## Карта документации

| Файл | Что внутри | Когда читать |
|------|-----------|--------------|
| [overview.md](overview.md) | Что/зачем/стек, дерево файлов, текущее состояние, команды | почти всегда |
| [conventions.md](conventions.md) | Код-стиль (3 пробела!), язык комментариев, сборка, проверки | перед написанием кода |
| [architecture.md](architecture.md) | Глубокие техрешения (A##): перф-пайплайн, hw-крипта, ThinLTO | при изменениях движка/сборки |
| [gotchas.md](gotchas.md) | Нетривиальные ловушки (G##) | перед нетривиальной задачей |
| [status.md](status.md) | Что работает, хрупкие места, отложенное, лог решений | при работе с хрупким |
| [subsystems/kotlin-layer.md](subsystems/kotlin-layer.md) | Kotlin-слой: ViewModel, режимы, JNI, data flow | работа с UI/логикой |
| [subsystems/native-engine.md](subsystems/native-engine.md) | C-движок: модули, контейнеры, крипта | работа с C |
| [subsystems/localization.md](subsystems/localization.md) | Локализация: строки, как добавить язык | работа со строками UI |
| [references/nsz-format.md](references/nsz-format.md) | Внешняя спецификация формата NSZ/NCZ | разбор формата |
| [_meta.md](_meta.md) | Правила ведения самих доков | при обновлении доков |

## Ситуативный гайд

- **Меняешь UI / логику режимов** → `subsystems/kotlin-layer.md`, при правках
  прогресса — `gotchas.md` (G01) и `architecture.md` (A09).
- **Меняешь C-движок / крипту / распаковку** → `subsystems/native-engine.md` +
  `conventions.md` (правило «не трогать без запроса»).
- **Трогаешь сборку (CMake/Gradle/NDK)** → `architecture.md` (A01–A06) +
  `gotchas.md` (G02).
- **Добавляешь строку/язык** → `subsystems/localization.md`.

## 3 факта, которые надо знать сразу

1. **Перф уже выжат** и работает в `stable`: аппаратные AES/SHA, no-copy I/O
   через `fd:N`, асинхронный writer, zstd `-O3`, core-adaptive batch-параллелизм.
   Подробности и причины — `architecture.md`. Не «переоптимизируй» вслепую.
2. **Verify всегда включён и бесплатен** (SHA-256 считается на лету в hardware).
   После конвертации NSP проверяется через `nativeVerifyNsp`, если есть header_key.
3. **Ветки:** `stable` (рабочая), `dev`, `block-parallel-wip` (отложенный
   эксперимент с блочным параллелизмом в C — сейчас сломан, см. `status.md`).
