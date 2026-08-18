> Branch: `dev-split` — captured 2026-04-25.

# Breaktest Command Deck — Documentation

This is the index. Start at the top, follow the suggested order, and you
should be productive on the codebase in one focused day.

> Over the 250-line split limit by design: an index earns its length from
> the file tree and topic-ownership table, which lose their value the moment
> they're split across files.

## Contents

- [Suggested reading order](#suggested-reading-order)
- [File tree](#file-tree)
- [Cross-reference: which doc owns which topic?](#cross-reference-which-doc-owns-which-topic)
- [Open questions](#open-questions)
- [Reference verification](#reference-verification)
- [A note on `_research/`](#a-note-on-_research)
- [Doc standards (definition of done)](#doc-standards-definition-of-done)
- [Contributing to these docs](#contributing-to-these-docs)

---

## Suggested reading order

| # | When you have... | Read | What it answers |
|---|---|---|---|
| 1 | 10 minutes | [`01-overview/product-context.md`](01-overview/product-context.md) | What does the machine do, and who uses it? |
| 2 | 15 minutes | [`01-overview/system-architecture.md`](01-overview/system-architecture.md) | The C4 context + container diagrams; fresh-clone quickstart. |
| 3 | as needed | [`01-overview/glossary.md`](01-overview/glossary.md) | Domain + tech vocabulary, alphabetical. |
| 4 | 30 minutes | `02-modules/*` | Why two Spring Boot apps, what's shared, what happens at build time. |
| 5 | 1 hour | `03-backend/*` | Persistence, security, Hilla services, hardware integration, test execution. |
| 6 | 1 hour | `04-frontend/*` | Vite/Hilla build, generated TS layer, routing, real-time data flow, components. |
| 7 | 30 minutes | `05-ops/*` | Docker profiles, database, runbook for common failures. |

The frontend section explicitly answers *"how does a button click reach a Java
method, and how does a sensor reading reach a chart?"* via
[`hilla-generated-layer.md`](04-frontend/hilla-generated-layer.md) and
[`state-and-realtime.md`](04-frontend/state-and-realtime.md).

---

## File tree

```
doc/
├── README.md                           ← you are here
├── OPEN-QUESTIONS.md                   the backlog, grouped by kind
├── _verify-refs.mjs                    scan all docs for broken file refs
├── _check-size.py                      enforce the size / TOC / anchor standard
├── _research/                          external tooling research, nothing adopted (some findings are about this code)
│   ├── README.md                       what this folder is, and what it is not
│   ├── agent-tooling.md                Claude Code skills/hooks/plugins; what .claude/ should change
│   ├── test-harness-jvm.md             a first backend suite on Java 26 + Boot 4.1
│   ├── test-harness-frontend.md        what can test a Hilla/React frontend
│   ├── hardware-simulation.md          running the bench with no hardware attached
│   └── machine-safety.md               interlock/E-stop practice; the P0314 gap
├── 01-overview/
│   ├── product-context.md              what the machine does, who uses it
│   ├── system-architecture.md          C4 context + container; fresh-clone quickstart
│   └── glossary.md                     terms, alphabetical, with cross-links
├── 02-modules/
│   ├── module-layout.md                :cms vs :command-deck, shared package root
│   ├── gradle-build.md                 settings.gradle, build.gradle, Vaadin plugin
│   ├── spring-boot-setup.md            profiles, configuration, startup sequence
│   └── shared-code-strategy.md         what command-deck reuses from cms, beans/aspects/services
├── 03-backend/
│   ├── persistence-model.md            JPA entities + ER diagram
│   ├── security-and-tenancy.md         Spring Security + @CheckUserCanOnlyAccessOwnData
│   ├── hilla-services.md               @BrowserCallable catalogue, REST vs RPC
│   ├── hardware-integration.md         loadcell, CFW11, relay, DSCUSB, broadcasters → STOMP
│   ├── driver-jars.md                  the two local driver JARs: provenance, build, contracts
│   ├── test-execution-engine.md        service → factory → thread → signal bus
│   └── test-types.md                   AbstractTest, the 3 strategies, FinishTestException
├── 04-frontend/
│   ├── build-and-tooling.md            Vite/Hilla dev-server handshake, productionMode flag
│   ├── hilla-generated-layer.md        what generated/ contains, RPC walk-through, never-edit rule
│   ├── routing-and-layout.md           file-based routing, route tree, auth-gated paths
│   ├── state-and-realtime.md           STOMP topics, run.tsx data flow, sequence diagram
│   ├── component-inventory.md          components/ by domain; webcam mid-rewrite call-out
│   └── frontend-module-split.md        cms vs command-deck frontend, alias 'cms', merge plugin
├── 05-ops/
│   ├── docker-and-profiles.md          topology (cloud cms / on-machine deck), compose, profiles, secrets
│   ├── docker-images.md                two-stage image build + shared startup.sh entrypoint
│   ├── db.md                           dev H2 + docker Postgres, backup/restore, seed
│   └── runbook.md                      common failure modes, one-fix-per-entry
├── 06-feature-work/
│   ├── address-open-questions/
│   │   ├── README.md                   index of the three files
│   │   ├── TASKS.md                    how to do the open items: files, change, verify
│   │   ├── TASKS-hardware.md           the same, for hardware + the two driver repos
│   │   └── DECISIONS.md               decision record: rejected options, accepted costs
│   ├── hardware-layer-redesign/
│   │   ├── README.md                   structural refactor of drive/device/runner (OQ-64)
│   │   └── target-design.md            the five types + the invariants they buy
│   ├── testrunner-safety/
│   │   ├── README.md                   scope of the safety audit + in-flight state
│   │   ├── audit-findings.md           surviving C / H findings, verified against source
│   │   ├── loadcell-recovery-design.md reconnect-and-resume design (OQ-45)
│   │   └── staleness-and-lifecycle-findings.md
│   │                                   second audit: stale values, leaks, phase-1 debts
│   └── virtual-devices/
│       ├── README.md                   simulated load cell / drive, dev-only (OQ-62)
│       ├── driver-api-extraction.md    deck-owned hardware API, both jars optional (OQ-43)
│       └── fault-injection.md          the switches that trip the detectors and all three stop tiers
└── diagrams/
    └── src/                            Mermaid (.mmd) diagram sources
        ├── c4-context.mmd              system context
        ├── c4-container.mmd            containers + DB + hardware
        ├── module-graph.mmd            Gradle module dependency graph
        ├── package-overlap.mmd         which sub-packages live where
        ├── build-pipeline.mmd          gradle → Hilla → Vite → Docker
        ├── er-diagram.mmd              JPA entity relationships
        ├── ownership-aspect.mmd        @CheckUserCanOnlyAccessOwnData flow
        ├── hilla-rpc.mmd               Hilla RPC round-trip sequence
        ├── stomp-sequence.mmd          STOMP push sequence (test → chart)
        ├── route-tree-cms.mmd          cms file-based route tree
        ├── route-tree-deck.mmd         command-deck file-based route tree (after merge)
        ├── frontend-venn.mmd           cms vs command-deck frontend overlap
        ├── hardware-layer.mmd          USB/Serial → driver → broadcaster → STOMP
        └── deployment.mmd              docker compose deployment topology
```

Every doc records its branch and the date it was last touched (three shapes
are in use — see the [doc standards](#doc-standards-definition-of-done)), so
readers can tell when they have drifted from the snapshot it was written
against.

---

## Cross-reference: which doc owns which topic?

For when the title isn't enough. Each row points to the *primary* doc; other
docs may touch the topic but defer there.

| Topic | Primary doc |
|---|---|
| Why two Spring Boot apps | [`02-modules/module-layout.md`](02-modules/module-layout.md) |
| Gradle build outputs (boot vs plain JAR) | [`02-modules/gradle-build.md`](02-modules/gradle-build.md) |
| Spring component-scan / `@EntityScan` behaviour | [`02-modules/spring-boot-setup.md`](02-modules/spring-boot-setup.md) |
| `command-deck` reusing cms beans | [`02-modules/shared-code-strategy.md`](02-modules/shared-code-strategy.md) |
| ER diagram + JPA entities | [`03-backend/persistence-model.md`](03-backend/persistence-model.md) |
| `DataWithOwner` + AOP ownership check | [`03-backend/security-and-tenancy.md`](03-backend/security-and-tenancy.md) |
| `@BrowserCallable` catalogue | [`03-backend/hilla-services.md`](03-backend/hilla-services.md) |
| Load cell, CFW11, relay drivers | [`03-backend/hardware-integration.md`](03-backend/hardware-integration.md) |
| `lib/*.jar` provenance, driver contracts, sibling-repo builds | [`03-backend/driver-jars.md`](03-backend/driver-jars.md) |
| Running a test with no hardware (simulated devices) | [`06-feature-work/virtual-devices/README.md`](06-feature-work/virtual-devices/README.md) |
| Building without the vendor jars, deck-owned device API | [`06-feature-work/virtual-devices/driver-api-extraction.md`](06-feature-work/virtual-devices/driver-api-extraction.md) |
| Tripping the watchdogs and the safe-stop tiers on demand | [`06-feature-work/virtual-devices/fault-injection.md`](06-feature-work/virtual-devices/fault-injection.md) |
| Test lifecycle, `TestRunnerThread` | [`03-backend/test-execution-engine.md`](03-backend/test-execution-engine.md) |
| Proposed restructuring of the drive + runner layer | [`06-feature-work/hardware-layer-redesign/README.md`](06-feature-work/hardware-layer-redesign/README.md) |
| `AbstractTest` subclasses, `FinishTestException`, `LoadCellThread` | [`03-backend/test-types.md`](03-backend/test-types.md) |
| `vite.config.ts`, dev-server, productionMode | [`04-frontend/build-and-tooling.md`](04-frontend/build-and-tooling.md) |
| Java→TypeScript RPC walkthrough | [`04-frontend/hilla-generated-layer.md`](04-frontend/hilla-generated-layer.md) |
| File-based routing + route tree | [`04-frontend/routing-and-layout.md`](04-frontend/routing-and-layout.md) |
| STOMP topics + sensor → chart | [`04-frontend/state-and-realtime.md`](04-frontend/state-and-realtime.md) |
| Component reuse + webcam mid-rewrite | [`04-frontend/component-inventory.md`](04-frontend/component-inventory.md) |
| `cms` vs `command-deck` frontend overlap | [`04-frontend/frontend-module-split.md`](04-frontend/frontend-module-split.md) |
| Deployment topology (cloud cms vs on-machine deck) | [`05-ops/docker-and-profiles.md`](05-ops/docker-and-profiles.md) |
| Docker compose, profiles, secrets | [`05-ops/docker-and-profiles.md`](05-ops/docker-and-profiles.md) |
| Image build, Dockerfiles, entrypoint script | [`05-ops/docker-images.md`](05-ops/docker-images.md) |
| Database (H2 + Postgres, seed, backup) | [`05-ops/db.md`](05-ops/db.md) |
| "It broke" — common failures + fixes | [`05-ops/runbook.md`](05-ops/runbook.md) |

---

## Open questions

[`OPEN-QUESTIONS.md`](OPEN-QUESTIONS.md) is the backlog, grouped by kind (defects, security, investigations, decided-awaiting-work,
mechanical cleanups, blocked). Every item links to the reference doc that
owns its topic.

Supporting files in
[`06-feature-work/address-open-questions/`](06-feature-work/address-open-questions/README.md):

- `TASKS.md` and `TASKS-hardware.md` — files to touch, the change, how to verify
- `DECISIONS.md` — the decision record: what was rejected and why,
  and which costs were knowingly accepted

---

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
  directory, no `...`) and still doesn't resolve. Exit code 1. **A reported
  reference is a real defect — fix it rather than adding it to an exception
  list.**
- **Unverifiable shorthand** — advisory, exit code 0: the `command-deck/.../File.java`
  elision, module specifiers (npm packages and imports through the Vite `'cms'`
  alias), module-agnostic paths like `src/main/frontend/…`, and files a design
  doc proposes but nobody has created yet. Structurally indistinguishable from
  each other, so the script reports rather than judges them.

Fenced code blocks are skipped wholesale, so Mermaid labels and import
statements don't trigger it. Inline code spans are *not* skipped — that is where
real references live, and missing that is what made the previous PowerShell
version check 6 references out of 300+.

Not checked: references with no `/` (root files such as `build.gradle:19`);
relative (`../diagrams/src/x.mmd`) and absolute (`/home/appuser/…`) paths, which
is why a rendered link should carry the repo-relative form in its *label*; and
line *content* — a `:NN` is only range-checked, so it can still point at the
wrong line of the right file.

---

## A note on `_research/`

[`_research/`](_research/README.md) sits outside the reading order above and
holds evaluations of *external* tooling — test harnesses, device simulators,
agent configuration — considered for future adoption. **Assume nothing it
evaluates is implemented** unless `CLAUDE.md` says so; the hooks and
verification gates are the one exception. Do not infer from it that this project
uses Playwright, Testcontainers, or any other tool it names; the repo still has
no tests, no CI, and no hardware abstraction layer.

The caveat is about **adoption, not subject matter**. Several of its findings are
about this codebase — decompiled from `lib/*.jar` or read off the source — and the
reference docs cite them as authority. Treat those as first-class; `_research/`
owns them until the work they inform lands, at which point they move into the
owning collection and the file is deleted.

It also follows different conventions from the rest of `doc/`: claims carry
HIGH/MEDIUM/LOW confidence ratings and external URLs, because most of the subject
is other people's software on other people's release schedules.

---

## Doc standards (definition of done)

Apply to any doc you add or edit. The canonical rules, with rationale and
worked examples, live in
[`.claude/skills/deck-doc/references/writing-style.md`](../.claude/skills/deck-doc/references/writing-style.md).

**Size and navigability**

| Size | Requirement |
|---|---|
| ≤ 120 lines | The target. Headings are the map; no TOC needed. |
| > 120 lines | Needs a **TOC** under the purpose — a flat list of links to the `##` sections, because readers arrive mid-file from a cross-reference. |
| > 250 lines | **Split it.** One concern per file. If it truly can't split, say why in one line at the top. |

Prefer splitting over trimming: two focused docs each earn a `File tree`
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
- No narrative or history; that's what git is for.
- Branch + date-last-touched at the top. Three shapes are in use and all are
  accepted: a `> Branch: …` blockquote (most docs), YAML frontmatter with
  `phase`/`branch`/`date` (`02-modules/`), or the banner directly under the
  H1 (`04-frontend/`). Match the file you're editing; don't convert one to
  another.
- "Where to look in the code", and an "Open questions" section that stays in
  sync with [`OPEN-QUESTIONS.md`](OPEN-QUESTIONS.md).
- Run [`_check-size.py`](_check-size.py) **and** [`_verify-refs.mjs`](_verify-refs.mjs)
  before calling it done. The first checks sizes, TOC presence and that every
  in-page TOC link resolves; the second catches dangling file references.

---

## Contributing to these docs

- Meet the [doc standards](#doc-standards-definition-of-done) above: size,
  TOC over 120 lines, minimum words with zero lost facts.
- Cross-link instead of duplicating.
- Keep diagrams as code (`.mmd` under `diagrams/src/`) — never paste
  rendered SVG into the markdown.
- File references use the form `module/path/File.ext:NN` so the verifier
  catches drift. Avoid relative `./` or `../` in file references unless
  the link is rendered (Markdown link, not inline citation).
- When adding a new doc, also add it to the file-tree and reading-order
  table above.
