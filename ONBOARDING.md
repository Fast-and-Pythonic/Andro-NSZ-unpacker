# Joining AndroNSZ

AndroNSZ is an Android app that unpacks compressed Nintendo Switch files (NSZ → NSP,
XCZ → XCI): a Kotlin/Compose UI over a native C engine. Start reading at
[`project-workbook/start.md`](project-workbook/start.md); [README.md](README.md) is for
users of the app.

## What works with nothing installed

- **[`project-workbook/`](project-workbook/start.md)** — the project's working notes: the
  map, the current state, the gotchas (`G##`) and decisions (`A##`) that cost time to
  find, the NSZ format reference. Plain Markdown; start at `start.md`.
- **[`AGENTS.md`](AGENTS.md)** — the pointer any coding agent reads first, with the few
  rules that hold for every task: English in code and commits, the cryptography and
  decompression code left alone unless asked, three-space indentation.

## Command-line tools

**Required:**

- **Android Studio**, or headless: a JDK 21 toolchain, the Android SDK (compile and
  target SDK 36), NDK `28.2.13676358` and CMake `3.22.1`. Build order, flags and the
  toolchain notes are in [`project-workbook/conventions.md`](project-workbook/conventions.md).
- **Python 3.8+** (3.11+ to read `tools/lint_workbook.toml`) — runs the workbook linter.
- **The commit hook**, once per clone:

  ```bash
  git config core.hooksPath tools/hooks
  ```

  Before each commit it checks the register indexes and lints the workbook, and when
  code is staged with no workbook change it prints the update triggers — a reminder, it
  never blocks the commit.
- **The Project Workbook tools** — the standard checked out next to this repository or
  above it (`project-workbook-method`), or installed:

  ```bash
  pipx install git+https://github.com/Fast-and-Pythonic/project-workbook-method
  ```

  Without either, the hook lets the commit through and says the workbook was not checked.

**Recommended:**

- [`rg`](https://github.com/BurntSushi/ripgrep) — fast search; agents use it when present.
- [`ast-grep`](https://ast-grep.github.io/) — structural search over the Kotlin and C code.
- [nicoboss/nsz](https://github.com/nicoboss/nsz) — the Python reference the engine
  mirrors; parity with it is how the native engine is debugged.

## Claude Code

The repository ships nothing that loads by itself: no project hooks, skills or MCP
servers. Each item below is optional.

- **Mods you enable yourself.** A repository cannot turn a mod on for a visitor. From a
  terminal session:

  ```
  /plugin install workbook-loader --marketplace Fast-and-Pythonic/project-workbook-method
  /plugin install workbook-triggers --marketplace Fast-and-Pythonic/project-workbook-method
  ```

  - `workbook-loader` puts `start.md`, `status.md` and the newest journal entries into
    the session before its first turn, in full.
  - `workbook-triggers` asks, at the end of a turn with signs of recordable work, whether
    an update trigger fired, and has the agent write the entry before it stops.

## What the authors use and you do not need

- The machine paths in `conventions.md` (where the JDK sits on the authors' machine) are
  examples; point Gradle at your own JDK 21.
- `.claude/settings.local.json` and `.claude/worktrees/` are the authors' local Claude
  Code state and are not in the repository.
