---
name: deck-doc
description: Owns documentation in this repo — the writing style for code comments and feature/implementation docs, plus review and lifecycle passes over the doc/ set (contradictions, staleness vs code, OPEN-QUESTIONS gardening). Use when documenting code or a feature, writing a user-story or how-it-works doc, pruning or reviewing comments, cleaning up docs, checking docs for contradictions, or triaging OPEN-QUESTIONS.
---

# Documentation (doc/ + code comments)

Sibling skills: the `deck-*` work skills *cite* these docs and follow the writing style defined here; this skill keeps the doc set itself trustworthy.

**Writing style — the canonical rules** for code comments (no narration, why/constraints only, dedup to pointers), for feature/user-story/implementation docs (where they live, their shape, DRY-by-reference), and for **file size, TOCs and lossless compression**: [references/writing-style.md](references/writing-style.md). Read it before documenting code, writing a doc, or pruning comments — it is also the bar the review pass applies.

Size rules in brief (details and exemptions in the reference): target **≤120 lines**; over **120** a doc needs a TOC under its purpose; over **250** it splits. Compress by cutting words, never facts — the why, the accepted cost, constraints, and any "unverified / stale / owner-owed" marker all survive compression.

Three passes, run whichever the task asks for:

## The doc model

- `doc/01-overview/ … 06-feature-work/` — numbered topic collections; deep reference lives here, one concern per file.
- `doc/OPEN-QUESTIONS.md` — the triaged issue backlog, canonical `OQ-n` ids grouped by kind, with a work-item cluster table on top. **The only doc-side task tracker.**
- `doc/_verify-refs.mjs` — scans docs for broken file references and out-of-range line numbers; run it after any doc restructuring (`node doc/_verify-refs.mjs`).
- `doc/_check-size.py` — enforces the size/TOC/anchor standard (`python doc/_check-size.py`, exit 1 on violation).
- `CLAUDE.md` (repo root) — agent-facing orientation; points into `doc/`, never duplicates it.
- Every fact has exactly one home; other files link to it. Git history is the record for anything closed — **delete, don't archive.**

`doc/` and `CLAUDE.md` are tracked in git as of `e06de80`, so a doc pass has history to fall back on. Still commit a dirty `doc/` tree before gardening it — the working tree is not a fallback.

## Pass 1 — Review (coherence)

Hunt for, in scope `CLAUDE.md` + `doc/**`:

| Defect | Default resolution |
|---|---|
| Contradiction between two files or doc-vs-code | The **code** is ground truth — read the source before "fixing" either side |
| Stale paths, commands, versions | Verify against the repo; correct |
| Duplication | Keep the owning file's copy; replace others with a link |
| Misfiled fact (task state in a reference doc, a decision only in a work log) | Move to its one home |
| Bloat / finished-work narration / style violations | Delete or rewrite per [references/writing-style.md](references/writing-style.md); git history keeps the narrative |
| Doc over 250 lines, or over 120 with no TOC | Split, or add the TOC. Prefer splitting over trimming — trim only genuine redundancy, and never a fact |

Finish with `python doc/_check-size.py` (sizes, TOCs, in-page anchors) and `node doc/_verify-refs.mjs` (it's allowlisted) — a dangling reference is a lost fact by another name. Its "unverifiable shorthand" bucket is advisory, not a pass/fail list; only **BROKEN** fails the gate.

## Pass 2 — Lifecycle (OPEN-QUESTIONS gardening)

For each Q item, classify before touching:

- **Resolved in code** (verify by reading the source, not the claim) → salvage any still-true constraint into the relevant `doc/` file, then delete the item.
- **Superseded** (a landed change made it moot) → one provenance line on whatever replaced it if re-filing is a risk, then delete.
- **Human-only next action** (owner decision, hardware check, ops step) → mark it clearly as owner-owed; don't let it block agent passes.
- **Still open and codeable** → leave it; tighten wording only.

Salvage before cut, always. Never delete an open item.

## Pass 3 — Comment prune (code, comments only)

Scope: the current diff, or a path the user names — state it before starting. Apply the comment test from [references/writing-style.md](references/writing-style.md) to every comment: delete restatements, fix or surface stale cross-boundary claims (verify against the code first — a wrong comment may be a bug report), tighten survivors, dedup multi-line repeats into pointers. **No logic changes** in this pass; the one exception is correcting a comment that is factually wrong. Expect a low delete count in already-disciplined files — don't force deletions to feel productive. Commit separately (`refactor: prune comments` / `docs: …`) so the diff reviews as comments-only.

## Resolve first — act without asking when it's verifiable

- Code settles a contradiction → fix the doc.
- A Q item's fix is demonstrably merged (read the source) → salvage + delete.
- Pure wording/clarity with meaning preserved → just do it.

## Ask the user — only genuine forks (batch via AskUserQuestion)

- Doc and code disagree and the **code** may be the bug — surface it, don't bless it.
- Deleting anything that records a decision (as opposed to a task) — propose, don't act.
- A salvaged fact with no obvious home.

## Report

- **Fixed:** contradictions/staleness corrected (with the code evidence).
- **Deleted:** items removed + where their salvage went (or "narrative — git history").
- **Pruned:** comments deleted/corrected/deduped (pass 3), confirmed comments-only.
- **Left open deliberately:** and why.
- **Flagged:** doc-vs-code conflicts that look like code bugs; the untracked-docs status.

Commit as its own `docs:` commit (see `deck-commit`), never mixed with logic changes.
