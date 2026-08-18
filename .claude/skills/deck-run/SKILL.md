---
name: deck-run
description: Boots and drives the breaktest-command-deck app headlessly — screenshots every view, runs a CRUD smoke test, and verifies routes with PASS/FAIL exit codes. Use when asked to run, start, screenshot, or smoke-test the app, to verify a change works in the real UI, or as the verification step after feature work (this repo has no automated tests). Runs in its own forked subagent and returns a compact PASS/FAIL verdict.
argument-hint: "[full|shots|crud-smoke|up|down|shot <route> <file.png>]"
context: fork
agent: general-purpose
background: false
disallowed-tools: AskUserQuestion
---

# Task: verify the app headlessly and report a verdict

You are running as a **forked subagent**. You have none of the caller's conversation
history, and the caller will see **only your final message** — not your tool output.
Your job is to execute the verification run below and return the verdict block
specified in "What to report back" (last section). Do not narrate the steps.

This repo has **no automated tests** — the driver in this skill directory
(`driver.mjs`, puppeteer-core against system Chrome/Edge, no browser download) is the
verification harness. `deck-ops` owns *how* to run/deploy manually; this skill *drives*
the running app.

`${CLAUDE_SKILL_DIR}` below is this skill's directory
(`<repo>/.claude/skills/deck-run`). The driver resolves its own paths from its file
location, so it can be invoked from any working directory — prefer absolute paths, since
a subagent's Bash working directory resets between calls.

## Scope of this run

`$ARGUMENTS` selects what to do. Empty or `full` → the default full verification.

| Arguments | Do this |
|---|---|
| *(empty)* / `full` | step 1 → 2 → 3 → 4 → report |
| `shots` | steps 1, 2, 3 (skip crud-smoke) |
| `crud-smoke` | steps 1, 2, 4 (skip shots) |
| `up` / `down` | that single driver command, then report |
| `shot <route> <file.png>` | that single route screenshot, then report |

## Steps

1. **Dependencies.** If `${CLAUDE_SKILL_DIR}/node_modules` is missing, run
   `pnpm install --dir "${CLAUDE_SKILL_DIR}"` (this project uses **pnpm, not npm**).
   Skip it when `node_modules` is already there.
2. **Bring the app up:** `node "${CLAUDE_SKILL_DIR}/driver.mjs" up`
   Detects an existing `:8080` or spawns `gradlew :command-deck:bootRun` detached and
   polls for up to 8 min. If it fails, it prints the tail of `.run/bootrun.log` — stop
   here and report FAIL with at most ~20 lines of that tail.
3. **Screenshot every route:** `node "${CLAUDE_SKILL_DIR}/driver.mjs" shots`
   One `PASS`/`FAIL`/`SKIP` line per route; exit non-zero on any failure.
4. **CRUD smoke test:** `node "${CLAUDE_SKILL_DIR}/driver.mjs" crud-smoke`
   Exit non-zero on any failed check.

Run steps 3 and 4 even if the earlier one failed (unless step 2 failed — nothing can be
verified without a running app), so one run reports every problem.

**Leaving the stack up:** by default leave the app running — repeat runs are much faster
against a warm instance — and say so in the report, including the `down` command. Only
run `node "${CLAUDE_SKILL_DIR}/driver.mjs" down` when the arguments asked for it. `down`
kills **only** a stack this driver spawned (guarded by `.run/boot.json`), never a foreign
`:8080`.

**You cannot ask a question mid-run.** If something blocks you — no Chrome/Edge found,
`pnpm` unavailable, `:8080` held by a foreign process the driver refuses to kill, or
`DECK_BASE` pointing at anything that is not local dev — do **not** guess and do **not**
try to ask. Stop and report `VERDICT: BLOCKED` with the one-line reason and the exact
command that failed.

## Reference

```bash
node driver.mjs up            # detect :8080 or spawn gradlew :command-deck:bootRun (detached), poll up to 8 min
node driver.mjs shots         # login + screenshot the route set -> ./shots/*.png, PASS/FAIL per route, exit != 0 on failure
node driver.mjs crud-smoke    # /customer round-trip: create -> assert in grid -> delete -> assert gone (PASS/FAIL)
node driver.mjs shot /sample sample.png   # one route -> ./shots/sample.png
node driver.mjs down          # kill ONLY a stack that `up` spawned (.run/boot.json); never a foreign :8080
```

Screenshots land in `${CLAUDE_SKILL_DIR}/shots/` (git-ignored) — report absolute paths.
Overrides: `DECK_BASE`, `DECK_BROWSER`, `DECK_USER`/`DECK_PASS` (default `user`/`user`),
`DECK_ADMIN_USER`/`DECK_ADMIN_PASS` (default `admin`/`admin`).

### What the commands assert

- `shots` logs in as the seeded `user`, screenshots `/`, `/customer`, `/sample`, `/project`, the three `/test/*` views, `/system/setting`, `/control` + `/run` (deck-only — SKIPped when a cms-only instance is detected), and `/admin/user` in a second browser context as `admin`. A route FAILs on non-200 or a blank render (`innerText` < 40 chars). Exit non-zero on any failure.
- `crud-smoke` proves the full Hilla data path (browser → generated TS client → `@BrowserCallable` → JPA → back): creates a Customer with a unique marker via the AutoCrud form, asserts it appears in the grid, deletes it via the form's Delete + confirm dialog, asserts it's gone. On failure it screenshots `shots/crud-smoke-fail.png` and names the leftover row for manual cleanup — pass that marker through to your report.
- `up` never touches an already-running stack; it spawns detached with output to `.run/bootrun.log` and prints the log tail on boot failure — never a silent timeout.

### Gotchas (session-earned)

- **Use `127.0.0.1`, not `localhost`** in `DECK_BASE` — Node's fetch can resolve `localhost` to `::1` while the server answers IPv4. (Default already does; 2026-08-15.)
- A 200 on `/login` minutes ago proves nothing — dev instances come and go; the driver re-probes before every command.
- Cold Vaadin dev-mode boots compile the frontend bundle — first `shots` after `up` can be slow; navigation timeout is 120 s for that reason. A slow first run is not a failure.
- `crud-smoke` writes to the real dev H2 DB (creates then deletes its own row, and sweeps stale `drvsmoke*` leftovers). Don't run it against production.
- Login is the Vaadin `LoginOverlay`: the driver types into the light-DOM `input[name="username"]`/`[name="password"]` and presses Enter — keyboard typing works there. **Form fields are different**: headless keyboard events don't reliably reach Vaadin's slotted form inputs, so `fillFieldByLabel` sets the native value + dispatches `input`/`change` and asserts the value landed (2026-08-15).
- `Customer.code` has `@Pattern(^\d{4,5}$)` which **rejects the empty string** the AutoForm submits for untouched fields — the smoke must fill it (`8000`).
- AutoCrud's delete confirm is NOT a `vaadin-confirm-dialog-overlay`; assert on the dialog text ("Are you sure") and click the button labeled **Confirm** (2026-08-15).
- The spawned `cmd.exe` wrapper PID detaches from the Gradle/Java tree, and `netstat` output is **localized** (German: ABHÖREN, not LISTENING) — `down` therefore kills by local-address column match on the port, never by state word or wrapper PID (2026-08-15).
- The Spring app listens on IPv6 `[::]:8080`; `netstat -p tcp` (IPv4-only) hides it.

Last certified green: `up` + `shots` (11/11 routes) + `crud-smoke` + `down` on 2026-08-15 against `:command-deck:bootRun` dev.

### Extending

New view → add it to `ROUTES` in `driver.mjs` (mark `deckOnly`/`admin` as appropriate).
New end-to-end scenario → new command function; keep the contract: PASS/FAIL per check,
non-zero exit on failure, screenshot on failure, self-cleaning test data. Record
certification runs in the line above with a date ("shots + crud-smoke green 2026-08-15")
instead of narrating them in commit messages.

## What to report back

Your final message is the whole deliverable. Emit **exactly this block and nothing
else** — no step-by-step narration, no console/browser log dumps, no repetition of the
PASS lines that already passed:

```
VERDICT: PASS | FAIL | BLOCKED
routes: <n> PASS / <n> FAIL / <n> SKIP   (or "not run")
crud-smoke: PASS | FAIL | not run
failures:
  - <route or check name> — <status / one-line reason> — <absolute screenshot path>
stack: <left running at http://127.0.0.1:8080, spawned by this run | was already up, untouched | stopped>
       stop with: node "<absolute path to driver.mjs>" down
notes: <at most 2 lines: leftover test-data marker, cms-only detection, or omit entirely>
```

Rules for the report:

- `VERDICT: PASS` only when every check that ran passed. `SKIP`ped deck-only routes on a
  cms-only instance do not break PASS — mention them under `notes`.
- Omit the `failures:` section entirely when there are none. Never list passing routes
  individually.
- On FAIL, name **every** failing route and the absolute path of its screenshot — that
  path is how the caller inspects the problem without re-running anything.
- Keep the whole message under ~15 lines. The only allowed exception is a boot failure,
  where you may append at most ~20 lines of `.run/bootrun.log` tail.
- Do not propose or apply fixes for what you found, and do not edit application code:
  reporting the verdict is the entire job.
