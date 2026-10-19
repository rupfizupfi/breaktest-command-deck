# Doc conventions — standards, references, gates

How a doc in this set is written and verified. Apply to any doc you add or edit; the four gates
enforce the mechanical half. The index and reading order are in [`../README.md`](../README.md).

## Reference verification

Markdown is read **only from `doc/`**; the references found in it are resolved
against the **whole repo**, from the repo root — never relative to `doc/`:

```text
doc/diagrams/src/hardware-layer.mmd   in convention, checked
diagrams/src/hardware-layer.mmd       shorthand (advisory), though it resolves from inside doc/
```

Run from the repo root. Reports any `module/path/File.ext[:NN]`-style reference
that doesn't resolve to a real file, **or** whose line number is past the end of
that file:

```bash
node doc/_verify-refs.mjs            # fail on broken refs
node doc/_verify-refs.mjs --strict   # also fail on unverifiable shorthand
node doc/_verify-refs.mjs --quiet    # summary only
```

Results split into two buckets:

- **Broken** — the reference is in convention (rooted at a real top-level
  directory, no `...`) and doesn't resolve. Exit code 1. Fix the reference;
  there is no exception list.
- **Unverifiable shorthand** — advisory, exit code 0: the `command-deck/.../File.java`
  elision, module specifiers (npm packages and imports through the Vite `'cms'`
  alias), module-agnostic paths like `src/main/frontend/…`, and files a design
  doc proposes but nobody has created yet. These can't be told apart, so the
  script reports rather than judges them.

Fenced code blocks are skipped wholesale, so Mermaid labels and import
statements don't trigger it. Inline code spans are *not* skipped — that is where
references live.

Relative Markdown links (`[label](../x.md#anchor)`) are resolved from the linking file: the
target must exist and stay inside this repo, and an anchor must match a heading in the target. A
link that climbs out of the repo is broken by rule — cite an `OQ-n` or `BL-nnn` id instead.

Not checked: references with no `/` (root files such as `build.gradle:19`); absolute
(`/home/appuser/…`) paths; and line *content* — a `:NN` is only range-checked, so it can still
point at the wrong line of the right file.

## Doc standards (definition of done)

Apply to any doc you add or edit.

**Size and navigability**

| Size | Requirement |
|---|---|
| ≤ 120 lines | The target. Headings are the map; no TOC needed. |
| > 120 lines | Needs a **TOC** under the purpose — a flat list of links to the `##` sections, because readers arrive mid-file from a cross-reference. |
| > 250 lines | **Split it.** One concern per file. If it truly can't split, say why in one line at the top. |

Prefer splitting over trimming: two focused docs each earn a file-tree
entry, and a reader after one stops paying for the other.

**Content**

- **Minimum words, zero lost facts.** Cut preamble, hedging ("it's worth
  noting", "simply"), explanations of Java/Spring/React/Vaadin, and anything
  the linked owning doc already says. Per sentence: *would a reader act
  differently without it?* No → cut.
- **Never compress away**: the *why* behind a decision and the cost accepted
  with it; constraints, units, ordering, footguns; and any "unverified /
  stale / owner-owed" marker. Compression must not launder uncertainty into
  false confidence.
- One home per fact — link, never restate.
- No narrative, history or provenance banners; that's what git is for.
- "Where to look in the code", and a "Known limits" section when the topic has
  a durable gap — a fact plus its `OQ-n` id, never a plan or a status.
- Run all four gates before calling it done: [`_check-size.py`](../_check-size.py)
  (sizes, TOC presence, in-page TOC links), [`_verify-refs.mjs`](../_verify-refs.mjs)
  (dangling file references), [`_check-diagrams.py`](../_check-diagrams.py) (inline
  `mermaid` fences against their `diagrams/src/*.mmd` source, which
  `_verify-refs.mjs` cannot see), and [`_check-inventories.py`](../_check-inventories.py)
  (a table that enumerates a code symbol set against that set — catches a doc
  being *incomplete*).

## Contributing to these docs

- Meet the [doc standards](#doc-standards-definition-of-done) above: size,
  TOC over 120 lines, minimum words with zero lost facts.
- Cross-link instead of duplicating.
- Keep diagrams as code (`.mmd` under `diagrams/src/`) — never paste
  rendered SVG into the markdown. The `.mmd` is **canonical**: an inline
  ` ```mermaid ` fence may be a summary of it, but must never contradict it
  or carry a fact the source lacks; [`_check-diagrams.py`](../_check-diagrams.py)
  enforces this, so edit the `.mmd` first.
- File references use the form `module/path/File.ext:NN` so the verifier
  catches drift. Avoid relative `./` or `../` in file references unless
  the link is rendered (Markdown link, not inline citation).
- A table that enumerates a set declared in code (an enum's constants, say)
  carries `<!-- inventory: enum <repo-relative-path> -->` on the line above it,
  so [`_check-inventories.py`](../_check-inventories.py) fails when the set grows
  and the table doesn't. Name the value's owner rather than copying the value:
  a default written out in prose drifts the moment somebody tunes it, and no
  gate compares prose against a literal.
- When adding a new doc, also add it to the file tree and the reading-order
  table in [`../README.md`](../README.md).
