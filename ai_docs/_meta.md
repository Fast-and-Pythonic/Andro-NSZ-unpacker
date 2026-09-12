# _meta — where the rules for these docs live

The rules for maintaining this documentation are **not copied here**. Two sources of
truth drift apart, which is the disease the method exists to cure — and this file was
the proof: it carried its own copy of the trigger table, the session protocol and the
"what not to write" list, and that copy still named a `Decision log` inside `status.md`
months after the chronicle moved to `journal.md`.

Standard: https://github.com/Fast-and-Pythonic/ai-docs-method — audited against v2.1
on 2026-09-12.

| You need | Read |
|----------|------|
| What is mandatory, the update triggers, the session protocol | `RULES.md` of the standard |
| How to format a `G##` / `A##`, links, indexes | `FORMATS.md` |
| Which files this project's size warrants | `RULES.md` §6, "Tiers and scaling" |
| What goes on a subsystem page, and what does not | the engineering profile of the standard |

There is no local method folder: this project has no departures from the standard. If
one becomes necessary it goes in `ai_docs_method/` at the root as a `DELTA.md`, and this
file gains a line saying the local method wins where the two disagree.

Entry point is [start.md](start.md), pointed to by the root `CLAUDE.md`.

## Project settings

**Profile:** engineering
**Tier:** M — three subsystem pages (the Kotlin layer, the native engine, the
localisation layer), so four areas counting the JNI boundary between the first two.
Both registers are well under the split threshold, so this is M rather than L.
**Language:** English, including these files themselves.

Non-English text is permitted **only as quoted data**: UI labels, test fixtures, sample
input strings. `G19` quotes the app's Russian button captions verbatim because they are
the literal arguments a `grep -F` must match — translating them would break the recipe.
Prose in any other language is a defect to be fixed.

## Stop and ask

Beyond the standard's own rule about invariants: **the C engine is not modified without
asking.** `aes_*`, `sha256`, `ncz_decompress`, the container parsers and the JNI
signatures are debugged against the Python reference, and parity with it is the
project's invariant — see `conventions.md` and [status.md](status.md).

## The three things most often broken here

1. **`status.md` collecting the chronicle.** It reached 349 lines against a limit of 80,
   two thirds of it an append-only decision log. State goes in `status.md`, reasoning in
   an `A##`, and the story in `journal.md`.
2. **Documented paths going stale.** A deleted component stays named in a subsystem page,
   and a toolchain path outlives the toolchain. Both happened here. The linter catches
   them; run it.
3. **Register indexes.** Do not hand-write one — `make_index.py` regenerates it from the
   entries, and a hand-made index drifts by the next session.

## Checking

```
python <standard>/tools/lint_docs.py  --config tools/lint_docs.toml
python <standard>/tools/make_index.py --config tools/lint_docs.toml
```

Both work from any directory. The project is under git, so nothing here is demoted to
advice.
