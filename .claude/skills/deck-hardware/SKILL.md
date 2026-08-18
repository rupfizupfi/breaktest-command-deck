---
name: deck-hardware
description: Extends command-deck's hardware and test-execution layer — serial/USB devices with WebSocket broadcasting, and material-test types in the test runner engine. Use when integrating a sensor, relay, motor controller, or serial device, working on device/loadcell/frequencyconverter/relayswitch or STOMP topics, or creating/modifying test types, test runners, signals, limits, or startup checks.
---

# Hardware and test engine (command-deck)

Sibling skills: `deck-feature` owns entities/views/security (a new test type's `TestParameter` columns and `views/test/*.tsx` follow its patterns); `deck-run` drives the UI headlessly for verification; `deck-ops` owns running the stack. This skill owns the device layer and test engine only.

Route to the right reference — read only what the task needs:

| Task | Read |
|---|---|
| New device, serial/USB integration, broadcasting, device layer changes | [references/devices.md](references/devices.md) |
| New test type, test runner, signals/limits, startup checks | [references/test-types.md](references/test-types.md) |

## Facts that always apply

- Hardware libraries: `lib/dscusb.jar` (load cell, tracked in git) and `lib/usbmodbus.jar` (CFW11, **gitignored — must be obtained manually**; `:command-deck` won't compile without it).
- No hardware attached in dev: devices throw on open — expected; there are no mocks and no tests in this repo. Verification is manual.
- STOMP topics in use: `/topic/load-cell`, `/topic/frequency-converter-info`, `/topic/logs`. The broker (`WebSocketConfig` in cms `messaging/`) only handles `/topic/**`.
- **Safety rule:** anything that can drive the motor or affect a running test must stay safe under the emergency path — `TestRunnerThread.retryShutdownOnException()` opens a fresh `Cfw11` and forces the motor off. Never make test shutdown depend on another component being healthy.
- Deep docs: `doc/03-backend/hardware-integration.md`, `doc/03-backend/test-execution-engine.md`, `doc/03-backend/test-types.md`. New devices/test types extend those docs (or get a `doc/06-feature-work/<item>/README.md`) in the house style — comments for why/constraints only, docs lean and reference-heavy: `.claude/skills/deck-doc/references/writing-style.md`. Safety-relevant invariants (signal semantics, shutdown paths, re-entrancy) are exactly the comments that MUST stay.

## Gotchas (session-earned — append with a date when a session teaches one)

- (none yet — add facts here that a work session proved and the docs don't hold)
