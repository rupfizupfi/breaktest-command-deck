# Writing style — code comments and docs (the canonical rules)

The reader is a skilled Java/Spring/React developer who also knows this machine.
Document what the code *can't* say; reference what is said elsewhere. Clean, lean, DRY.

## Code comments

The test for every comment: **does it tell that reader something the next lines don't already say?**
No → delete. Yes → keep and tighten. Wrong/stale → verify against the code, then fix or delete
(a comment contradicting the code is a bug report — surface it, don't silently rewrite).

Keep (information code can't carry):
- **Why, not what** — rationale, trade-offs, accepted risks.
- **Non-obvious constraints and footguns** — the kind this repo already documents well:
  `AbstractTest.cleanup()` "can be executed twice", `ViewSerializer` (Jackson 3 dropped
  `JsonGenerator.getCodec()`), `SettingRepository.loadSettingsFromJson()` (unchecked
  `JacksonException`, not `IOException`). **Point to these canonical spots instead of
  restating them** at call sites.
- **Invariants, units, ranges, ordering** — signal semantics (1=release, 2=pull, 0=stop),
  force units, poll intervals, "null owner = shared".
- **Pointers to the source of truth** — `see doc/03-backend/test-execution-engine.md`,
  "keep in sync with <twin file>" (the duplicated properties/themes).
- **Tool directives** — `@ts-ignore`, `eslint-disable`, codegen markers. Never touch.

Delete (noise for this audience):
- Restatement of the next line, control-flow narration, type-echoing Javadoc/JSDoc
  (`@param id the id`).
- Commented-out code (git has it), changelog-in-comments, decorative banners.
- "Just in case" explanations of the language, Spring, React, or Vaadin itself.

Dedup rule: a multi-line explanation that recurs keeps its fullest version at the most
canonical location; other sites get a one-line pointer. One-line repeats stay in place —
a pointer is no shorter and costs locality.

Staleness clusters in **cross-boundary claims** — a comment naming another file, module,
or doc path rots first. Treat those as claims to verify, not prose to polish.

## Implementation / user-story docs

When a feature or mechanism deserves explanation beyond comments, write a doc — one per
story or topic, in its one home:

| Content | Home |
|---|---|
| A user story / feature-work item (what + how it was built) | `doc/06-feature-work/<kebab-case>/README.md` — folder per item (see `address-open-questions/`), supporting files beside it |
| How a mechanism works (timeless reference) | The matching collection: `doc/03-backend/`, `doc/04-frontend/`, `doc/05-ops/` |
| A diagram that genuinely clarifies | Mermaid source in `doc/diagrams/src/` |

Shape (lead with the point):
1. **What it enables / guarantees** — one or two sentences a reader can act on.
2. **How it works** — a walk of the real code path using repo-relative references
   (`command-deck/src/main/java/.../TestRunnerThread.java`, `ClassName#method`), not
   copied source. A short excerpt is allowed only when the code's *shape* is the point.
3. **Constraints and gotchas** — table, each one verifiable in the code.
4. **Links** — related docs, the OPEN-QUESTIONS items it touches. Never restate a fact
   another file owns; link it.

Rules:
- **DRY across the doc set**: before writing a fact, check it isn't already owned by
  `CLAUDE.md` or another `doc/` file — link instead. Duplicated facts are the failure
  mode the doc model exists to prevent.
- **No narrative or history** — how the work unfolded, dead ends, pass counts belong to
  git history, not the doc.
- **Skimmable**: important thing first, tables over prose.
- **Part of done**: behavior or setup changed → create/update the doc in the same change,
  then run `node doc/_verify-refs.mjs` so no reference dangles.

## Size and structure (all of `doc/**` and `CLAUDE.md`)

A doc nobody can hold in their head is a doc nobody reads. Three thresholds,
measured as `wc -l` reports (newline count — don't argue the off-by-one).
`doc/_check-size.py` enforces them; run it after any doc change.

| Size | Requirement |
|---|---|
| **≤ 120 lines** | The target. No TOC — the headings are already the map. |
| **> 120 lines** | Add a **TOC** directly under the purpose: a flat list of links to the `##` sections. The reader is now arriving mid-file from a cross-reference and needs to see the shape without scrolling it. |
| **> 250 lines** | **Split it.** One concern per file is the doc model. If it genuinely cannot split, say why in one line at the top — an unexplained 300-line file reads as neglect. |

Splitting beats trimming when a file is over: two focused docs each earn their
own entry in `doc/README.md`, and a reader looking for one of them stops
paying for the other. Trim only what is genuinely redundant.

Exempt from the split rule (but not the TOC rule): captured snapshots whose
value *is* their completeness — `_inventory.md`, dependency dumps, the id map
in `06-feature-work/address-open-questions/README.md`. Mark these as
snapshots so nobody mistakes them for live reference.

## Compress hard — cut words, never facts

Reduce every doc to the minimum that still says what it must. The rule is
**lossless compression**: the fact, the constraint, the caveat and the
pointer all survive; the words around them do not.

The test, sentence by sentence: *would a reader act differently if this were
gone?* No → cut it.

Cut on sight:
- Preamble that restates the heading, and "as mentioned above" back-references.
- Hedging that carries no information — "it's worth noting that", "essentially",
  "in practice" where nothing contrasts it, "simply", "just".
- Explaining Java, Spring, React or Vaadin to a reader who knows them.
- A paragraph where a table row works, and a table where a sentence works.
- Anything the linked owning doc already says — link and delete.

Never cut, however long the file:
- The **why** behind a decision, and the **cost** knowingly accepted with it.
- Constraints, invariants, units, ordering, footguns — the things code can't say.
- The fact that something is **unverified, stale, or owner-owed**. Compression
  must not launder uncertainty into false confidence.
- Repo-relative paths and cross-links. They're what makes brevity safe:
  the detail is one click away, so the doc doesn't have to carry it.

Terseness that leaves the reader guessing is not compression — it's a second
draft that lost the point. If cutting a sentence forces the reader into the
source to understand *what the doc is even about*, put it back.
