> Branch: `dev-split` — external research captured 2026-08-17.

# `_research/` — evaluations of tooling this repo has **not** adopted

> [!IMPORTANT]
> **The caveat is about adoption, not subject matter.** Assume nothing
> *evaluated* here is implemented unless `CLAUDE.md` says otherwise. The agent
> tooling in [`agent-tooling.md`](agent-tooling.md) partly shipped on
> 2026-08-17; **every other tool named in this folder is absent.** Do not infer
> that this project uses Playwright, Testcontainers, pymodbus or Vitest — it
> does not, and it has **no tests, no CI and no hardware abstraction layer**.
>
> These files *do* describe this codebase where establishing the outside world's
> relevance required inspecting it, and those findings are **cited as authority
> by the reference docs** — the transport table and CFW11 register map
> decompiled from `lib/*.jar` and the `Cfw11`-is-`final` blocker in
> [`hardware-simulation.md`](hardware-simulation.md), the inert `P0314`
> watchdog in [`machine-safety.md`](machine-safety.md), the empty `src/test`
> trees in the two harness files. Each carries a confidence rating (below).
> Discount them for staleness, never for being in this folder.
>
> To learn how Breaktest Command Deck works, still start at
> [`../README.md`](../README.md) — that is where the timeless reference lives.

The `_` prefix marks this as meta, the same convention as
[`../_inventory.md`](../_inventory.md) and `../_verify-refs.mjs`. It is
deliberately absent from the reading order in [`../README.md`](../README.md).

---

## Index

| File | Question it answers |
|---|---|
| [`agent-tooling.md`](agent-tooling.md) | How Claude Code skills/hooks/plugins work, and what this repo's `.claude/skills/` set should change |
| [`test-harness-jvm.md`](test-harness-jvm.md) | What a first backend test suite would use on Java 26 + Boot 4.1, and which tools break there |
| [`test-harness-frontend.md`](test-harness-frontend.md) | What can actually test a Hilla/React frontend (short answer: nothing Vaadin ships) |
| [`hardware-simulation.md`](hardware-simulation.md) | How to run the test bench without hardware attached |
| [`machine-safety.md`](machine-safety.md) | Industry practice for interlocks/E-stop on motor-driven test rigs, and where this repo diverges |

---

## Conventions

**Every claim carries a confidence rating.** `HIGH` = verified against a
primary source (official docs, release notes, the artifact itself, or this
repo's own bytecode). `MEDIUM` = single credible source, or inference from
verified facts. `LOW` = plausible but unconfirmed — treat as a lead, not a
fact. Unverifiable items are named explicitly rather than omitted.

**Sources are URLs, and vendor marketing is labelled as such.** Where official
docs and the project's own issue tracker disagree, both sides are shown.

## Maintenance

Research rots faster than the reference docs, because it tracks other
people's release schedules rather than this codebase.

- **Re-verify versions before acting on them.** Every version number here has
  a capture date and will be wrong eventually.
- **This is not a task tracker.** Actionable repo findings belong in
  [`../OPEN-QUESTIONS.md`](../OPEN-QUESTIONS.md); where this research produced
  one, the file names the `OQ-n` id or flags it as a proposal.
- **Delete a file outright once its subject is adopted or rejected.** On
  adoption the surviving constraints move into the owning `doc/` collection —
  a harness that exists is project documentation and belongs in
  `02-modules/` or `05-ops/`, not here. Git history keeps the evaluation.

## Where this research already touches the backlog

It does not re-file anything. Existing items it strengthens or answers:

| Id | What the research adds |
|---|---|
| **OQ-17** | Answered: the Hilla generator cannot run standalone, so a CI typecheck must order Gradle generation before `tsc`. See [`test-harness-frontend.md`](test-harness-frontend.md). |
| **OQ-14** | Vaadin's own docs and their v25 Hilla example both gitignore `generated/`; a Vaadin issue documents stale generated files breaking compilation across upgrades. |
| **OQ-32** | The "adopting a test suite isn't decided" blocker is exactly what [`test-harness-jvm.md`](test-harness-jvm.md) exists to inform. |
| **OQ-44** | What `Cfw11Check` / `LoadCellCheck` should assert — device-identity handshake specifics. |
| **OQ-46** | Identity should be VID:PID + serial, not a descriptive-name substring. |
| **OQ-50** | Independent support: an emergency-stop path must not depend on fresh device enumeration. See [`machine-safety.md`](machine-safety.md). |
| **OQ-43** | Evidence about what `lib/usbmodbus.jar` actually contains, and therefore whose licence applies. |

[`machine-safety.md`](machine-safety.md) also proposes four **new** items that
are not yet in the backlog and have deliberately not been filed unilaterally.
