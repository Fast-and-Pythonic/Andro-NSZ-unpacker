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

## Manual scenario checks

A build is not enough for runtime logic. Check on a device/emulator. Example
(output folder naming):

1. Pick a source folder, start unpacking.
2. Confirm that `Original_unpacked` is created.
3. Run again → `Original_unpacked_2` should appear (only if the first already exists).

To verify a change to the running app — see the `/verify` and `/run` skills.

## Git

- Working branch — `stable`. Commit/push only when the user asks.
- Commit messages in English.
