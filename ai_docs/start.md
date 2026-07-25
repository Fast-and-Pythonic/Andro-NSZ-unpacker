# AndroNSZ — documentation entry point

Android app for unpacking compressed Nintendo Switch files: **NSZ → NSP**
(packages) and **XCZ → XCI** (gamecards). Kotlin/Compose UI + a native C engine
via JNI. A port of the Python reference [nicoboss/nsz](https://github.com/nicoboss/nsz).

## Documentation map

| File | What's inside | When to read |
|------|--------------|--------------|
| [overview.md](overview.md) | What/why/stack, file tree, current state, commands | almost always |
| [conventions.md](conventions.md) | Code style (3 spaces!), language, build, checks | before writing code |
| [architecture.md](architecture.md) | Deep technical decisions (A##): perf pipeline, hw crypto, ThinLTO | when changing engine/build |
| [gotchas.md](gotchas.md) | Non-trivial traps (G##) | before a non-trivial task |
| [status.md](status.md) | What works, fragile points, deferred items, decision log | when touching fragile code |
| [subsystems/kotlin-layer.md](subsystems/kotlin-layer.md) | Kotlin layer: ViewModel, modes, JNI, data flow | working on UI/logic |
| [subsystems/native-engine.md](subsystems/native-engine.md) | C engine: modules, containers, crypto | working on C |
| [subsystems/localization.md](subsystems/localization.md) | Localization: strings, how to add a language | working on UI strings |
| [references/nsz-format.md](references/nsz-format.md) | External NSZ/NCZ format spec | parsing the format |
| [_meta.md](_meta.md) | Rules for maintaining the docs themselves | when updating the docs |

## Situational guide

- **Changing UI / mode logic** → `subsystems/kotlin-layer.md`; for progress
  changes — `gotchas.md` (G01) and `architecture.md` (A09).
- **Changing the C engine / crypto / decompression** → `subsystems/native-engine.md` +
  `conventions.md` (the "don't touch without asking" rule).
- **Touching the build (CMake/Gradle/NDK)** → `architecture.md` (A01–A06) +
  `gotchas.md` (G02).
- **Adding a string/language** → `subsystems/localization.md`.

## 3 facts to know up front

1. **Performance is already squeezed, and it is WRITE-bound.** Hardware AES/SHA, no-copy
   I/O via `fd:N`, an async writer with page-cache pacing, zstd `-O3`, and parallelism
   fixed at 4 files. Measured: decompression reaches ~2400 MB/s
   but the flash accepts only ~1000 MB/s (and ~450 when its SLC cache is spent), so
   throwing more workers at it just slices per-file speed. Read `architecture.md` A15 +
   `gotchas.md` G17 **before** touching anything perf-related.
2. **Verification is non-fatal and inline.** Every unpacked NCA's SHA-256 is checked
   against the CNMT *during* decompression (no output re-read); a mismatch only warns
   (`WARN` + a `CORRUPTED` tag, output kept). The filename content-id fallback is only half
   the NCA hash, so a mismatch there isn't authoritative; see `architecture.md` A12.
3. **Branches:** `stable` (working), `dev`, `block-parallel-wip` (a deferred
   block-parallelism experiment in C — currently broken, see `status.md`).
