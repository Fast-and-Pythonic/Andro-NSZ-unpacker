# Conventions

## Language

- **Code, comments, documentation, commit messages** — **English**. This includes
  the `ai_docs/` files themselves, not only README and code comments (see
  [_meta.md](_meta.md)).
- **UI strings** — never hardcode, always via resources (see
  [subsystems/localization.md](subsystems/localization.md)).

## Formatting (strictly required)

- **Indent — exactly 3 spaces.** No tabs.
- **Blank lines inside a block keep the indentation** of the current nesting level
  (3 spaces per level). Do NOT strip indentation on blank lines. This is a hard
  rule — many auto-formatters break it, so check after running them.

Example (C; for Kotlin — the same 3 spaces):

```c
int calculate_sum(int arr[], int size) {
   if (size <= 0) {
      return 0;
   }
   
   int sum = 0;
   
   for (int i = 0; i < size; i++) {
      if (arr[i] > 0) {
         sum += arr[i];
      }
   }
   
   return sum;
}
```

## Comments

- English only.
- Document **why**, not **what**: don't comment on what's obvious from the code.
- Keep numeric constants in comments in sync with the code. Example trap: in
  `Constants.kt` and `FolderProcessor.kt` there are stale "every 250ms" comments
  while the actual interval is 100 ms. When editing nearby — fix them.

## What NOT to touch without an explicit request

These modules are debugged and match the Python reference. Changing them breaks
compatibility:

- Cryptography: `aes_ctr.c`, `aes_xts.c`, `sha256.c`.
- Decompression algorithm: `ncz_decompress.c`.
- JNI signatures (`jni_bridge.c` ↔ `NszConverter.kt` `native*` methods).
- Container parsers `pfs0.c` / `hfs0.c` (especially entry sizes: PFS0 — 24 bytes,
  HFS0 — 64 bytes; HFS0 hash fields are zeroed for compatibility).

If something on this list must change — ask the user first.

## Build and checks

Commands — from the project root (PowerShell). Ordered fast to full:

```powershell
.\gradlew.bat :app:compileDebugKotlin     # 1. quick pass; if it fails, no point going further
.\gradlew.bat :app:assembleDebug          # 2. full debug build, incl. native
.\gradlew.bat :app:testDebugUnitTest      # 3. unit tests
.\gradlew.bat :app:lintDebug              # 4. Android lint
```

- After `*.kt` edits — at least step 1, then 2.
- After C/CMake edits — `:app:assembleDebug` (or `:app:externalNativeBuildDebug`
  for the native part only).
- "Broken" = step 1 or 2 fails. Lint warnings are not blocking, but avoid adding
  new ones.
- Full run in one command: `.\gradlew.bat :app:build` (slower, harder to localize
  the failing step).

### CLI prerequisites & troubleshooting

Android Studio's Build menu is the primary path (it uses its bundled JBR 21 and
handles the daemon). The notes below are for headless / agent CLI builds.

- **JDK 21 toolchain (required).** The build needs a JDK 21 toolchain. The one on this
  machine is the JBR bundled with Android Studio (OpenJDK 21), at
  `C:\Program Files\Android\Android Studio\jbr`.
  That is also what `JAVA_HOME` points at, so a CLI build and an Android Studio build
  use the same toolchain. Point Gradle at it via `JAVA_HOME` or a project flag:
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  # or, without changing the environment:
  .\gradlew.bat :app:compileDebugKotlin `
     "-Porg.gradle.java.installations.paths=C:\Program Files\Android\Android Studio\jbr"
  ```
  Missing 21 (offline) → `Unable to download toolchain … languageVersion=21`.
- **Offline.** Add `--offline` when there's no network, so Gradle doesn't hang
  trying to fetch the toolchain (foojay) or dependencies.
- **Daemon won't connect** — `Could not connect to the Gradle daemon`, even though
  the daemon log says "Daemon server started": a localhost IPv4/IPv6 mismatch. Force
  IPv4: `$env:GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"`.
- **Journal cache lock** — `Timeout waiting to lock journal cache
  (…\.gradle\caches\journal-1)`: another Gradle instance is running (usually Android
  Studio). Don't run a CLI build concurrently with AS; stop stray daemons with
  `.\gradlew.bat --stop`.
- **Benign:** the `CXX5304 … SDK XML version 4` NDK warning is ignorable.
- Confusing `Unresolved reference` errors on code you didn't change are usually a
  dropped Kotlin daemon, not a real error — see [gotchas.md](gotchas.md) **G09**.

Known-good offline CLI invocation (run only when AS isn't building):
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
.\gradlew.bat :app:compileDebugKotlin --offline
```

## Manual scenario checks

A build is not enough for runtime logic. Check on a device/emulator. Example
(output folder naming):

1. Pick a source folder, start unpacking.
2. Confirm that `Original_unpacked` is created.
3. Run again → `Original_unpacked_2` should appear (only if the first already exists).

To verify a change to the running app — see the `/verify` and `/run` skills. For
driving the GUI over `adb shell input tap` (menus, pickers) — see
[gotchas.md](gotchas.md) **G19** for the dump-locate-tap-verify methodology; blind
coordinates and prefix text matches are a known trap.

## Git

- Working branch — `stable`. Commit/push only when the user asks.
- Commit messages in English.
