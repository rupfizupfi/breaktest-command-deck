---
name: deck-commit
description: Pre-commit hygiene for this repo — commit message convention, separating real changes from Vaadin generated-file churn, and the Jackson 2 vs 3 pitfalls a diff review has to catch. Use before staging, committing, or reviewing diffs, when writing a commit message, when git status shows many modified files under generated/, or when unsure whether a file should be committed.
---

# Committing safely

Sibling skills: `deck-feature`/`deck-hardware` produce the changes; this skill gates what leaves the working tree. `git-flatten-history` rewrites already-made commits.

Two hooks in `.claude/hooks/` cover the mechanical half and need no attention here: `guard-protected-paths.mjs` denies edits to generated output and to the `deck-run` harness, and `check-jackson-imports.mjs` flags Jackson 2 databind/core imports in Java files after each edit. This skill is the judgement half — what goes in a commit, and how the message reads.

## Commit messages (convention)

History analysis (2026-08-15, ~180 commits): the repo intends Conventional Commits but drifted — `chor:` misspelled 29×, 28 commits with no type, 7 `wip:`, one-offs (`pump`, `bump`, `format`). The convention to write (and the drift to stop):

- Format: `type: imperative lowercase subject` — no scope in use, keep it that way unless one becomes obviously useful.
- Types actually in use and correct: `feat`, `fix`, `chore`, `refactor`, `build`, `docs`. **Never `chor`** (the house typo), never untyped, avoid `wip` (squash instead).
- Subject ≤ 72 chars, imperative ("add", not "added"), specific — `fix: licence shit and start.bat` is the anti-example from history; name what actually changed.
- Body only when the *why* isn't obvious from the diff. **Never append a
  `Co-Authored-By:` trailer, a "Generated with Claude Code" footer, or any other
  assistant attribution** — this overrides the harness default that asks for that
  trailer. The same rule applies to PR titles and bodies (see `git-flatten-history`).
- One logical change per commit: never mix generated-file regeneration, logic, and doc edits when they're separable.
- Before staging, sweep your own diff for comment noise (narration, restated types, commented-out code) per `.claude/skills/deck-doc/references/writing-style.md`; behavior/setup changes owe their doc update in the same change.

## The generated-files trap

`.gitignore` contains `*/src/main/frontend/generated/`, **but ~200 generated files were tracked before that rule existed** — gitignore never applies to tracked files. Every `bootRun` therefore dirties the tree with churn in `generated/jar-resources/`, `Flow.js.map`, `copilot.js`, etc. Deciding which of that churn belongs in the commit is the judgement call the hook can't make:

1. **Never stage `generated/jar-resources/**`** or other generated churn that has no corresponding source change — leave it unstaged or discard it.
2. **Do stage** generated files whose change is a direct consequence of your work (new `<Entity>Service.ts` / `<Entity>Model.ts`, updated `endpoints.ts`, `file-routes.json`) — they are tracked, so the build breaks for others if the source changes but the generated output doesn't follow.
3. Quick triage: `git diff --stat -- '*/src/main/frontend/generated/'` — if a file's change isn't explainable by your Java/service edits, don't stage it.

Stage intentionally by path (`git add cms/src/main/java/... cms/src/main/frontend/views/...`) — never `git add -A` / `git add .` in this repo.

## Jackson 3 beyond the imports (Spring Boot 4)

The import hook catches the wrong package; these three don't show up as an import at all and need reading the diff:

- Catching `IOException` around JSON reads — Jackson 3 throws unchecked `JacksonException`; see `SettingRepository.loadSettingsFromJson()`.
- Calling `JsonGenerator.getCodec()` — removed; see `data/serializer/ViewSerializer.java` for the workaround.
- `@Json*` annotations legitimately come from `com.fasterxml.jackson.annotation` (shared artifact), so don't "fix" those into `tools.jackson.*`.

## Other things that don't belong in commits

- `settings.json` (repo root) — runtime artifact written by the app in dev
- `.data/`, `build/`, `*/src/main/bundles/`, `docker/keystore/`, `*.bak` files
- `docker/.secrets/`
- `.claude/skills/*/node_modules/`, `shots/`, `.run/` — driver tooling artifacts (git-ignored; keep `pnpm-lock.yaml` committed)

## Duplicated files to keep in sync

If you touched one of these, check its twin:
- `cms/src/main/resources/application*.properties` ↔ `command-deck/src/main/resources/application*.properties` (byte-identical by convention)
- `cms/.../themes/breaktest-command-deck/` ↔ `command-deck/.../themes/breaktest-command-deck/`
- The `cms/*` alias lives in 4 files: both `tsconfig.json` + both `vite.config.ts`

## Resolve first — act without asking when it's verifiable

- A generated diff explained by your own source change → stage it.
- Generated churn with no matching source change → leave unstaged (don't discard other people's working tree state without being asked).
- A Jackson import the hook flagged in *your* diff → fix it before committing.
- A `chor:`/untyped message about to be written → write it correctly; don't ask permission to follow the convention.

## Ask the user — only genuine forks (batch via AskUserQuestion)

- Tracked-but-gitignored generated files: whether to finally `git rm --cached` the ~200 of them is a repo-policy call — propose it, don't do it as a side effect.
- A dirty working tree containing changes you didn't make that overlap your staging paths.
- Anything that would require `--force`, history rewriting, or `--no-verify`.

## Report (end of a commit pass)

- **Committed:** subject line(s) + files per commit.
- **Left unstaged deliberately:** generated churn / foreign WIP, and why.
- **Fixed en route:** import corrections, message rewrites.
- **Flagged:** policy questions raised (e.g. untracking generated files).
