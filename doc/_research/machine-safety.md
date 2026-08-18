> Branch: `dev-split` — external research captured 2026-08-17.
> **Nothing evaluated here is adopted** — see [`README.md`](README.md).

# Machine safety — external practice, and one gap it exposes

**This file does not re-audit the code** — but it does name one code-level gap the
audit missed, [`P0314`](#the-gap-p0314-is-never-set-so-the-drive-side-watchdog-may-be-inert).
The audit's own findings live in
[`../06-feature-work/testrunner-safety/audit-findings.md`](../06-feature-work/testrunner-safety/audit-findings.md)
(C1–C8, H1–H11) and the recovery design in
[`loadcell-recovery-design.md`](../06-feature-work/testrunner-safety/loadcell-recovery-design.md).
What follows is the *industry practice* those findings should be measured
against, plus the one gap the research turned up that the audit does not name.

---

## Contents

- [The gap: `P0314` is never set, so the drive-side watchdog may be inert](#the-gap-p0314-is-never-set-so-the-drive-side-watchdog-may-be-inert)
- [What the standards actually require](#what-the-standards-actually-require)
  - [The closest industry analogue](#the-closest-industry-analogue)
  - [Watchdog design rules (Koopman, Ganssle — HIGH)](#watchdog-design-rules-koopman-ganssle--high)
  - [Therac-25, because it is the same shape of system](#therac-25-because-it-is-the-same-shape-of-system)
- [Fail-safe defaults: the mode question](#fail-safe-defaults-the-mode-question)
  - [Bypass management (IEC 61511 practice — MEDIUM, practitioner summary)](#bypass-management-iec-61511-practice--medium-practitioner-summary)
- [Interlock patterns worth adopting](#interlock-patterns-worth-adopting)
- [Proposed backlog items (not filed)](#proposed-backlog-items-not-filed)
- [Sources](#sources)

## The gap: `P0314` is never set, so the drive-side watchdog may be inert

Finding **C5** and the recovery design both treat
`cfw11.setActionInCaseOfCommunicationError(2)` as the backstop for CFW11 link
loss — "load-bearing, keep it". That call writes **P0313** (*action on
communication error*). It is set in all three test types:

- `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/DestructiveTest.java:24`
- `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/CyclicTest.java:32`
- `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TimeCyclicTest.java:44`

**P0313 is the *action*. P0314 is the *timeout that triggers it*.** Per the WEG
serial manual (0899.5741 §5), P0314 is the serial watchdog in seconds, range
0.0–999.0, where **0.0 disables the watchdog entirely**. A grep of the whole
repository finds **no reference to P0314 anywhere** (HIGH — verified
2026-08-17).

So unless P0314 has been set on the drive's own keypad, the configured action
never fires, and the backstop C5 relies on does not exist. Confidence: **HIGH**
that the Java source never sets it; **MEDIUM** that the watchdog is therefore
off, because it may be persisted in the drive's parameter set from a manual
configuration.

**This is a physical check on the machine, not a code question** — read P0314
on the CFW11 keypad. If it reads 0.0, one write is the cheapest safety
improvement available to this project. Proposed as a backlog item below.

Related, from the same manual (all HIGH, useful when implementing OQ-44):
`P0316` is the serial-interface status register, where **0 = communication
board not installed** — a direct device-presence assertion. Serial timeout
raises alarm **A128** / fault **F228**, with the response governed by P0313.
Note also that the **default value of P0313 changed between firmware
revisions** — 0 (*inactive*) in the 2010 manual, 1 (*ramp stop*) in the 2024
V7.1X revision — so an unconfigured drive's behaviour depends on its firmware.

---

## What the standards actually require

| Source | Requirement |
|---|---|
| **ISO 13850 / EN 60204-1** | Only stop **category 0** (immediate removal of power to actuators) and **category 1** (controlled stop, then removal of power) may be used for emergency stop. Electrical E-stop devices require direct-opening action with mechanical latching. |
| **Practitioner consensus** | An E-stop must *physically remove power*, routed through a safety relay or safety PLC — not implemented in standard control logic, so that wire breaks are detectable. The named failure: after an E-stop cuts power, "the output to the conveyor motor would still be on in the logic but off in the real world." |
| **Motion-control vendor practice** | E-stop as **Safe Torque Off via a hardwired input**, cutting power to the motor driver while leaving the controller alive so state and settings persist. Explicitly *not* a power-supply switch, because that also kills communication. |

Confidence: **HIGH** that these are the requirements as reported; **MEDIUM** on
clause-level precision — ISO 13850:2015, IEC 60204-1 and ISO 13849-1 are all
paywalled and were not read directly. Treat the clause attributions as
secondary-source claims.

### The closest industry analogue

ITW/Instron's materials-testing patent **US11592376B2** (2023) describes a
machine of the same class and is worth reading as a target architecture. Four
operating states — **Disabled** (control processor cannot command actuators),
**Setup** (upper speed limit *and* upper grip-pressure limit while an operator
is inside the protected volume), **Caution**, **Testing** — plus redundant
processing cores comparing outputs, redundant crosshead travel-limit monitoring
wired **directly** to actuator disable, interlocked guard doors, and an E-stop
that shuts down the power amplifier directly. Declared as ISO 13849-1
compliant, implementing an IEC 60204-1 Category 1 stop via motor braking + STO.

> The transferable idea is the **state machine with a speed-limited Setup
> state**, not the redundant hardware. This project has one implicit state
> ("a test is running or it isn't").

### Watchdog design rules (Koopman, Ganssle — HIGH)

Directly applicable to the C5/C6 shutdown-path findings:

- **"The best watchdog is one that doesn't rely on the processor or its
  software. It's external to the CPU, shares no resources, and is utterly
  simple, thus devoid of latent defects."** This is the argument for P0314:
  the drive stopping itself is categorically stronger than the application
  stopping the drive.
- **Every task must contribute to the kick.** A single timer unconditionally
  kicking the watchdog is named as a specifically bad practice — it proves only
  that the timer works. Applied here: a heartbeat that the load-cell thread
  cannot influence would not detect C1/C2/C3 (a dead sensor thread).
- **Kick only after several unrelated good things have happened**, and
  structure the kick so runaway code stumbling into it cannot errantly satisfy
  it.
- **Record and investigate every watchdog trip observed during testing.**

This is also independent support for **OQ-50** and **C6**: a last-resort stop
must not depend on fresh device enumeration, a new driver session, and a
healthy JVM — which is exactly what
`command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java:115`
does by constructing a new `Cfw11`.

### Therac-25, because it is the same shape of system

AECL removed the hardware interlocks present on the Therac-6/20 on the
assumption that software would catch dangerous configurations. *"Each bug
contained in the Therac-25 software was also found in the software of the
Therac-20, however, the hardware safety interfaces in the Therac-20 prevented
any accidents."* Leveson & Turner's conclusion — safety must hold at the system
level **despite** software errors — is the reason the last line of defence must
be outside the application, no matter how good the code gets.

---

## Fail-safe defaults: the mode question

Saltzer & Schroeder: *"Base access decisions on permission rather than
exclusion… the default situation is lack of access."* Permission-based design
errors fail by refusing — safe and quickly noticed. Exclusion-based mistakes
fail by allowing, and *"may go unnoticed in normal use."*

Applied to a hardware-mode switch, the safe arrangement is **simulation by
default, real hardware behind an explicit positive opt-in**. Two current
properties point the other way:

| Observation | Where |
|---|---|
| `spring.profiles.default=dev` — an unset `SPRING_PROFILES_ACTIVE` resolves to a profile that talks to real hardware, so "no configuration" is the dangerous state | `command-deck/src/main/resources/application.properties:1` |
| Devices are constructed with `new` at seven sites rather than injected, so there is no single seam a profile could switch | see [`hardware-simulation.md`](hardware-simulation.md) |

**The critical rule if a simulation mode is ever added: never fall back to a
simulator when hardware is absent.** That is the exclusion-based direction —
it makes "hardware missing" silently equivalent to "hardware working". Require
the mode explicitly, fail startup if unset, and refuse `real` if a
device-identity handshake fails.

### Bypass management (IEC 61511 practice — MEDIUM, practitioner summary)

A simulation flag is a bypass, and process safety has settled practice for
those: a formal permit naming what is bypassed and why; **a time limit that is
not automatically extended**; **annunciation that cannot be silenced while
active**, naming the affected function and who enabled it; automatic logging
with user id and timestamp; and fail-safe return to a safer state on loss of
control. The analogous recognised hazard in PLC work is **forced I/O left in
place after maintenance**.

Minimum viable version here: a persistent banner in the UI, a WARN log line on
every test start naming the mode, and **simulated runs flagged in the database
so their results can never be mistaken for real test data** — which also
interacts with **H10** (test outcome is never persisted at all).

---

## Interlock patterns worth adopting

1. **Device-identity handshake as a startup check** (feeds **OQ-44**, **C4**).
   Before any motion: read Modbus **FC43** objects 00h/01h/02h and assert
   vendor `WEG` plus a known product and firmware string; assert `P0316 == 1`.
   For the load cell, assert `SERL`/`SERH` and `VER`. For the relay, match
   VID:PID and serial rather than a descriptive-name substring (**OQ-46**).
   A simulator must return a *distinguishable* identity.
2. **Explicit, mutually exclusive mode** — never inferred, never a fallback.
3. **Loud, continuous annunciation** of simulated mode, per the bypass practice
   above.
4. **Keep the last line of defence out of the software**: hardwired E-stop into
   the CFW11 **STO** input plus a contactor, ISO 13850 category 0 or 1. The
   application must be incapable of overriding or simulating it. Set **P0314**
   so the drive stops on its own when the application goes quiet.
5. **Test the watchdog independently** — assert the drive actually stops when
   the master goes silent.

⚠️ **Unverified:** whether this specific CFW11 has the STO option fitted, and
at what PL/SIL rating. `P0680` bit 0 is documented as **STO** on firmware
V7.1X, which establishes that the platform supports it, but WEG's catalogue
pages could not be fetched and no CFW11 STO-specific document was located.
This is an owner-owed hardware check.

---

## Proposed backlog items (not filed)

These are new — not covered by the existing audit or `OPEN-QUESTIONS.md`.
Deliberately left as proposals rather than assigned `OQ-n` ids.

| Proposal | Kind | Note |
|---|---|---|
| **Read P0314 on the drive; set it if 0.0.** Sharpens C5 — the configured P0313 action cannot fire without it. | Investigation → owner-owed | Highest value-per-effort item found. |
| **Verify whether the CFW11 has STO fitted, and whether a hardwired E-stop chain exists.** | Blocked / owner-owed | Determines whether the software is currently the last line of defence. |
| **Decide the hardware-mode contract** before any simulation work: explicit selection, no fallback, simulated results flagged in the DB. | Decision | Prerequisite for [`hardware-simulation.md`](hardware-simulation.md). |

## Sources

Stop categories and E-stop practice: [GT Engineering on stop functions](https://www.gt-engineering.it/en/insights/machinery-safety/stop-functions/) ·
[Control Design, 2026-05-18](https://www.controldesign.com/safety/machine-guarding/article/55377748/why-your-emergency-stop-button-logic-can-be-a-liability) ·
[Zaber on E-stops / STO](https://www.zaber.com/articles/zaber-devices-emergency-stops) ·
[US11592376B2](https://patents.google.com/patent/US11592376B2/en) ·
Watchdogs: [Koopman](https://betterembsw.blogspot.com/2014/05/proper-watchdog-timer-use.html) ·
[Ganssle](https://www.ganssle.com/watchdogs.htm) ·
[Therac-25 (Leveson & Turner)](https://escholarship.org/content/qt5dr206s3/qt5dr206s3.pdf) ·
[Saltzer & Schroeder](https://web.mit.edu/Saltzer/www/publications/protection/Basic.html) ·
[IEC 61511 bypass practice](https://automationforum.co/iec-61511-safety-bypass-override-sis-maintenance/) ·
WEG CFW11 register map: [RS232/RS485 manual 0899.5741](https://static.weg.net/medias/downloadcenter/h58/h90/WEG-cfw11-rs232-and-rs485-manual-0899.5741-en.pdf) ·
[Programming manual V7.1X, rev 06 09/2024](https://static.weg.net/medias/downloadcenter/he7/h71/WEG-CFW11-programming-manual-10004274148-en.pdf)
