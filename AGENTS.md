# AGENTS.md

AndroNSZ — Android-приложение, распаковывающее сжатые файлы Nintendo Switch
(NSZ → NSP, XCZ → XCI). Kotlin/Compose UI + нативный C-движок через JNI.

**Точка входа в документацию:** [`ai_docs/start.md`](ai_docs/start.md) — прочитай первым.

Critical rules:
- Общение с пользователем — на русском. Все комментарии в коде, документация и
  commit-сообщения — на английском.
- Не трогать без явного запроса: криптографию (`aes_ctr.c`, `aes_xts.c`,
  `sha256.c`), алгоритм распаковки (`ncz_decompress.c`), сигнатуры JNI.
- Отступы — ровно 3 пробела, с сохранением отступа на пустых строках. См.
  [`ai_docs/conventions.md`](ai_docs/conventions.md).
