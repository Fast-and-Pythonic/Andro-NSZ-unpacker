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

1. **Performance is already squeezed** and shipping in `stable`: hardware AES/SHA,
   no-copy I/O via `fd:N`, an async writer, zstd `-O3`, core-adaptive batch
   parallelism. Details and rationale — `architecture.md`. Don't "re-optimize" blindly.
2. **Verification is non-fatal.** After conversion the NCA hash is compared, but a
   mismatch only warns (`WARN`, output kept) — it no longer deletes the output. The
   filename content-id is only half the NCA hash, so a mismatch isn't authoritative;
   see `architecture.md` A12 and the verification roadmap in `status.md`.
3. **Branches:** `stable` (working), `dev`, `block-parallel-wip` (a deferred
   block-parallelism experiment in C — currently broken, see `status.md`).
