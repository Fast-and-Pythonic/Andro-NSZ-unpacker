# AndroNSZ

AndroNSZ — an Android app that unpacks compressed Nintendo Switch files
(NSZ → NSP, XCZ → XCI). Kotlin/Compose UI + a native C engine via JNI.

**Read `project-workbook/start.md` before acting on any task, a one-line edit included.** It routes
each kind of task to the file that task needs.

**This file is a pointer. Never add rules or knowledge here** — they go into `project-workbook/`.

## Critical rules

- Code comments, documentation, and commit messages — in English
  (`project-workbook/conventions.md`).
- Don't touch without an explicit request: cryptography (`aes_ctr.c`, `aes_xts.c`,
  `sha256.c`), the decompression algorithm (`ncz_decompress.c`), JNI signatures
  (`project-workbook/conventions.md`).
- Indentation — exactly 3 spaces, keeping the indentation on blank lines. See
  [`project-workbook/conventions.md`](project-workbook/conventions.md).
