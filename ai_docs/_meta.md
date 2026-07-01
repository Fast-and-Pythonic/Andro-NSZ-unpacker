# _meta — rules for maintaining the docs

This folder follows the `ai_docs/` standard (method description:
`E:\a_My_Programming\ai_docs_method`). Entry format: `G##` for gotchas, `A##`
for architecture decisions.

Entry point is `ai_docs/start.md`, pointed to by the root `CLAUDE.md`.

## Language

**All documentation is written in English** — including the `ai_docs/` files
themselves (this `_meta.md`, gotchas, architecture, status, subsystems, etc.),
not just README and code comments. See [conventions.md](conventions.md).

## Update triggers

| Trigger | File / action |
|---------|---------------|
| Debugging > 30 min with a non-obvious cause | `gotchas.md` — new `G##` |
| An architecture decision was made | `architecture.md` — new `A##` |
| A fragile point was identified | `status.md` — Fragile points |
| A feature was deliberately deferred | `status.md` — Deferred + reason |
| A feature was finished | `status.md` — Working + date at top of file |
| Code style / workflow / build commands changed | `conventions.md` |
| A module was renamed / structure changed | `overview.md` (tree) and subsystems if needed |
| A subsystem changed | `subsystems/<name>.md` |
| A new doc file / subsystem appeared | `start.md` (map) |
| The doc-keeping methodology changed | this file |

## What NOT to write in the docs

- Things derivable from the code: signatures, variable names, imports, obvious
  type hierarchies. Exception — the high-level tree in `overview.md`.
- Change history (that's `git log`). Exception — the Decision log in `status.md`
  ("why we decided", not "what changed").
- Current TODOs/tasks. Exception — Deferred in `status.md` (deliberately deferred
  + reason).
- Long tutorials and general theory. Project specifics only.
- Precise counters that go stale fast (line counts, sizes). Give a ballpark and a
  link to the source.

## Session start protocol

1. Always: read `start.md`.
2. Per the situational guide in `start.md` — decide what else to read.
3. Non-trivial task → additionally `gotchas.md`.
4. Engine/build change → `architecture.md` + `status.md` (Fragile).
5. Breaking a convention from `conventions.md` → stop and ask the user.

## Freshness principle

A document that lies is worse than a missing one: the agent will trust it and
break something that works. Stale → update now or delete the section. `status.md`
always carries a date at the top.

## Tool-specific files

`ai_docs/` must not contain tool-specific files (`CLAUDE.md`, `.cursorrules`,
etc.). Those live at the project root and only point to `ai_docs/start.md`. There
is currently a root `CLAUDE.md` (Claude Code). Personal developer settings go in
`CLAUDE.local.md` (in `.gitignore`), not in the repository.
