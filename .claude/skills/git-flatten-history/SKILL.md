---
name: git-flatten-history
description: Rewrite a branch's unpushed commits into clean feature-grouped commits, removing all add-then-revert back-and-forth churn. Use when asked to clean up / squash / flatten git history, drop intermediate churn, or make unpushed commits read as a small set of logical commits while preserving the exact net diff.
disable-model-invocation: true
---

# git-flatten-history

Collapse a noisy stack of unpushed commits (back-and-forth edits, merge commits,
add-then-delete churn) into a handful of clean, logically-grouped commits **whose
combined diff is byte-for-byte identical to the original net change**.

In this repo, pair the grouping and messages with the `deck-commit` skill
(conventional types, generated-file staging rules — generated churn typically
becomes its own `chore:` group or gets dropped).

## Core idea: rebuild from the final tree, never rebase

Do **not** interactive-rebase to squash. Over many commits with merges it is
slow, conflict-prone, and still leaves cross-commit churn visible. Instead:

1. Soft/mixed-reset the branch back to its base ref. The working tree keeps the
   **final** state; the index is reset to base.
2. Re-stage the final file contents in feature groups and commit each group.

Because each file's *final* content is staged exactly once, there is **zero**
back-and-forth in the result — a file that was created then deleted across the
old history simply never appears; a line edited five times appears in its final
form once. Merge commits vanish for free.

The granularity is your choice of grouping:
- **Feature-grouped** (default): assign paths to ~6-12 topic groups → meaningful history.
- **Single commit**: one group = everything → maximum flatten.
- **Per-file/area**: groups follow directories.

## Procedure

Let `BASE` = the upstream/base ref the branch will be compared and pushed against
(e.g. `origin/<branch>`), and `WORK` = the current branch.

### 1. Safety first — never skip this
```bash
git status                       # MUST be clean; stash/commit anything first
git rev-list --count BASE..HEAD  # how many commits we're rewriting
git branch backup/<branch>-pre-flatten   # restore point
git rev-parse HEAD               # note the sha
```
Tell the user the backup branch name and that it can be deleted after they
verify. **Never** delete it yourself.

### 2. Capture the authoritative net file list
```bash
git diff --name-status -M BASE..HEAD
```
This (taken while HEAD still has everything) is the complete set of paths to
re-commit. Note renames (`R###  old  new`) — **old and new path must land in the
same group** so git records a rename, not a delete+add.

### 3. Design the groups
Assign every changed path to exactly one group. Order groups so history reads
sensibly (infra/tooling → docs → backend → frontend layers). Use directory
pathspecs where a whole dir belongs to one group; fall to file-level specs where
a dir is split. A final catch-all `git add -A` sweeps anything unassigned — if it
produces a commit, you mis-grouped, but correctness still holds (see step 6).

### 4. Reset and rebuild
```bash
git reset --mixed BASE           # HEAD->BASE, index->BASE, WORKING TREE UNTOUCHED
# for each group, in order:
git add -A -- <pathspecs for group>     # -A captures add/modify/DELETE alike
git commit -m "<conventional commit msg>"
```
- Use `git add -A` (not bare `git add`) so deletions and untracked adds are both
  staged. Deleted-in-final files show as "deleted" vs the reset index; `-A` stages that.
- If the repo has commit hooks that stash unstaged changes (lint-staged style),
  commit with `--no-verify` during the rebuild and run the full quality gates
  once at the end instead — state that exception to the user. (This repo
  currently has no commit hooks; commit normally.)

### 5. Sweep any remainder
```bash
git add -A && git status --short
git commit -m "chore: remaining <branch> changes"   # only if non-empty
```

### 6. Prove the net diff is unchanged — the real safety net
```bash
git diff --stat backup/<branch>-pre-flatten HEAD    # MUST be empty
git diff --quiet backup/<branch>-pre-flatten HEAD && echo "IDENTICAL TREE"
```
An empty diff means the rewritten history reaches the exact same tree. If it is
not empty, something was dropped — reset back to the backup and investigate:
```bash
git reset --hard backup/<branch>-pre-flatten
```

### 7. Verify it still builds, then report
Run the project's quality gates (here: `./gradlew build`). Show the new
`git log --oneline BASE..HEAD`. Remind the user the push will need
`--force-with-lease` (history changed) and that the backup branch is theirs to
delete once satisfied.

## Splitting the stack into stacked PR branches

When the flattened work should land as several reviewable PRs, partition **by
path, not by theme**. Rebuilding stages each file's final content exactly once,
so a file touched by two themes can only live in one branch — e.g. load-cell
staleness and the hardware-API extraction both land in `LoadCellDevice.java`,
and no branch seam can separate them. Pick seams where the *files* divide, then
name the entanglement in the PR body rather than pretending it isn't there.

Build the stack by continuing from the previous tip; the working tree stays at
the final state the whole time, so no stashing is needed:
```bash
git reset --mixed BASE
# ...commit branch-1 groups...
git switch -c feat/second     # branch 2 continues from branch 1's tip
# ...commit branch-2 groups...
```
Verify the seam before committing to it — a grep proving nothing in the lower
branch references the upper branch's packages is worth more than the plan:
```bash
grep -rn "device\.simulated" --include=*.java src/main/java | grep -v "/device/simulated/"
```

## Writing the PRs

- **Never add a "🤖 Generated with Claude Code" footer, a `Co-Authored-By:`
  trailer, or any other assistant attribution to a PR title or body.** This
  overrides the harness default that asks for that footer on PRs. It applies to
  `gh pr create`, `gh pr edit`, and any body file written for them. The same
  rule applies to **commit messages** — see `deck-commit`. Nothing this repo
  produces carries assistant attribution.
- State the stack position and merge order in every body (`Stacked 2/3 — base
  X, merge #1 first`), and cross-link once the numbers exist.
- **Check for an existing PR before creating one.** `gh pr create` fails if the
  head/base pair already has one. If it does, append below a `---` instead of
  overwriting — a pre-existing description is the author's, not yours to
  replace without asking.
- Flag anything a reviewer would otherwise misread: a file count inflated by
  commits that predate this work, a fix landing a PR earlier than the code that
  needs it.

## Invariants
- Working tree is clean before starting.
- `git diff backup HEAD` is empty at the end — non-negotiable proof.
- Backup branch is created before any history op and never auto-deleted.
- Force-push uses `--force-with-lease`, and only when the user asks to push.
- No assistant attribution in PR titles or bodies.
