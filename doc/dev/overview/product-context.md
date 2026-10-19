# Product context

## What the machine does

**Breaktest Command Deck** is the control software for an electric-motor-driven
material-testing apparatus. The bench applies tensile force to a sample via a
motor (a WEG CFW11 frequency inverter) and measures the reaction force with a
DSCUSB load cell. Two test families are first-class:

- **Destructive test** — pull the sample until it breaks; record the force
  curve and the breaking load.
- **Cyclic test** — repeatedly load and unload a sample for a configured number
  of cycles or until failure. A **time-cyclic** variant first times the
  release and pull legs in a calibration phase, then reverses on those
  timings instead of on the turn forces.

Tests run unattended once started: the operator presses *start* in the browser,
the backend spawns a `TestRunnerThread`, force samples flow over a STOMP
WebSocket to a live chart, and a CSV file is written to disk for the result
record. See [`dev/backend/test-execution-engine.md`](../backend/test-execution-engine.md).

## Domain vocabulary

A **project** holds a customer's request; under it are **samples** (physical
parts) described by **material**, **gear type** and **gear standard** lookups.
Each sample has one or more **test parameters** (settings for a destructive,
cyclic or time-cyclic run) which a **test result** persists after execution
along with CSV measurements, optional photos and an outcome. Reference data
(materials, gear types/standards) is seeded by the
[`V2__reference_data.sql`](../../../cms/src/main/resources/db/migration/V2__reference_data.sql) migration.
Full vocabulary: [`glossary.md`](glossary.md).

## Who uses it

- **Test operators** sit at the bench computer, log in, pick a project +
  sample, configure parameters, and run a test. They mostly use the
  command-deck app's `/run` and `/control` views.
- **Lab admins / customers** never touch the bench: they manage projects,
  samples, and inspect results from the cms app's `/project`, `/sample`,
  `/result/...` views. Roles `USER` and `ADMIN` (the latter only via cms's
  `/admin/user` page).

The split is operational, not visual: the command-deck deployment is a
**runtime superset** of cms — it ships every cms screen plus hardware-control
views — but only one machine in the lab needs the hardware-bound deployment.
See [`dev/modules/module-layout.md`](../modules/module-layout.md) for why.

## Where to look in the code

| Concern | File |
|---|---|
| App entry points | `cms/src/main/java/ch/rupfizupfi/deck/Application.java`, `command-deck/src/main/java/ch/rupfizupfi/deck/Application.java` |
| Test definitions | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/{Destructive,Cyclic,TimeCyclic}Program.java` |
| Domain entities | `cms/src/main/java/ch/rupfizupfi/deck/data/{Project,Sample,TestParameter,TestResult,Customer,Material,GearType,GearStandard,User,FileMetadata}.java` |
| Seed data | `cms/src/main/resources/db/migration/V2__reference_data.sql` |
| User-facing views | `command-deck/src/main/frontend/views/`, `cms/src/main/frontend/views/` |
