# Breaktest Command Deck — Documentation

The index. Follow the suggested order below.

## Contents

- [Suggested reading order](#suggested-reading-order)
- [File tree](#file-tree)
- [Cross-reference: which doc owns which topic?](#cross-reference-which-doc-owns-which-topic)
- [Decisions, questions and work](#decisions-questions-and-work)
- [Conventions and gates](#conventions-and-gates)

---

## Suggested reading order

| # | When you have... | Read | What it answers |
|---|---|---|---|
| 1 | 10 minutes | [`dev/overview/product-context.md`](dev/overview/product-context.md) | What does the machine do, and who uses it? |
| 2 | 15 minutes | [`dev/overview/system-architecture.md`](dev/overview/system-architecture.md) | The C4 context + container diagrams; fresh-clone quickstart. |
| 3 | as needed | [`dev/overview/glossary.md`](dev/overview/glossary.md) | Domain + tech vocabulary, alphabetical. |
| 4 | 30 minutes | `dev/modules/*` | Why two Spring Boot apps, what's shared, what happens at build time. |
| 5 | 1 hour | `dev/backend/*` | Persistence, security, Hilla services, hardware integration, test execution. |
| 6 | 1 hour | `dev/frontend/*` | Vite/Hilla build, generated TS layer, routing, real-time data flow, components. |
| 7 | 30 minutes | `ops/*` | Docker profiles, database, runbook for common failures. |
| — | operating the bench | `user/*` | Signing in, the records, running a test, settings and administration — for operators and admins, not developers. |

*"How does a button click reach a Java method, and a sensor reading reach a
chart?"* is answered by
[`hilla-generated-layer.md`](dev/frontend/hilla-generated-layer.md) and
[`state-and-realtime.md`](dev/frontend/state-and-realtime.md).

---

## File tree

```
doc/
├── README.md                           ← you are here
├── decisions.md                        decision record: rejected options, accepted costs
├── _verify-refs.mjs                    scan all docs for broken file refs
├── _check-size.py                      enforce the size / TOC / anchor standard
├── _check-diagrams.py                  hold inline mermaid fences to their .mmd source
├── _check-inventories.py               hold a doc table to the symbol set it enumerates
├── dev/
│   ├── conventions.md                  doc standards, reference forms, diagrams, inventory tables
│   ├── overview/
│   │   ├── product-context.md          what the machine does, who uses it
│   │   ├── system-architecture.md      C4 context + container; fresh-clone quickstart
│   │   └── glossary.md                 terms, alphabetical, with cross-links
│   ├── modules/
│   │   ├── module-layout.md            :cms vs :command-deck, shared package root
│   │   ├── gradle-build.md             settings.gradle, build.gradle, Vaadin plugin
│   │   ├── spring-boot-setup.md        profiles, configuration, startup sequence
│   │   └── shared-code-strategy.md     what command-deck reuses from cms, beans/aspects/services
│   ├── backend/
│   │   ├── persistence-model.md        JPA entities + ER diagram
│   │   ├── security-and-tenancy.md     Spring Security + @CheckUserCanOnlyAccessOwnData
│   │   ├── hilla-services.md           @BrowserCallable catalogue, REST vs RPC
│   │   ├── hardware-integration.md     loadcell, CFW11, relay, DSCUSB, broadcasters → STOMP
│   │   ├── driver-jars.md              the two local driver JARs: provenance, build, contracts
│   │   ├── device-api.md               deck-owned hardware API, drivers as runtime plugins
│   │   ├── simulated-bench.md          simulated load cell / drive, dev-only (OQ-62)
│   │   ├── fault-injection.md          the switches that trip the detectors and all three stop tiers
│   │   ├── specimen-profiles.md        simulated specimen measured off real force traces
│   │   ├── test-execution-engine.md    service → factory → thread → run loop
│   │   ├── testrunner-architecture.md  packages, one run, ArchitectureTest, safety invariants
│   │   ├── safety-and-recovery.md      load-cell loss detection, safe stop, reconnect-and-resume
│   │   ├── recovery-gates.md           the configurable loss, reconnect and resume thresholds
│   │   └── test-types.md               TestProgram, the 3 programs, verdicts
│   └── frontend/
│       ├── build-and-tooling.md        Vite/Hilla dev-server handshake, productionMode flag
│       ├── hilla-generated-layer.md    what generated/ contains, RPC walk-through, never-edit rule
│       ├── routing-and-layout.md       file-based routing, route tree, auth-gated paths
│       ├── state-and-realtime.md       STOMP topics, run.tsx data flow, sequence diagram
│       ├── component-inventory.md      components/ by domain; webcam mid-rewrite call-out
│       └── frontend-module-split.md    cms vs command-deck frontend, alias 'cms', merge plugin
├── user/
│   ├── getting-started.md              the two apps, sign-in, roles, owned vs shared records, request → result
│   ├── running-a-test.md               parameters, the three types, start/stop, sensor-loss hold, run status
│   └── settings-and-admin.md           users, rig limits, server settings, shared records, simulated faults
├── ops/
│   ├── docker-and-profiles.md          topology (cloud cms, may run as production / simulated deck), compose, profiles, secrets
│   ├── docker-images.md                two-stage image build + shared startup.sh entrypoint
│   ├── bench-deployment.md             running the deck natively on the Windows bench (the only path to hardware)
│   ├── db.md                           dev H2 + docker Postgres, backup/restore, seed
│   └── runbook.md                      common failure modes, one-fix-per-entry
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

---

## Cross-reference: which doc owns which topic?

Each row points to the *primary* doc; others defer to it.

| Topic | Primary doc |
|---|---|
| Why two Spring Boot apps | [`dev/modules/module-layout.md`](dev/modules/module-layout.md) |
| Gradle build outputs (boot vs plain JAR) | [`dev/modules/gradle-build.md`](dev/modules/gradle-build.md) |
| Spring component-scan / `@EntityScan` behaviour | [`dev/modules/spring-boot-setup.md`](dev/modules/spring-boot-setup.md) |
| `command-deck` reusing cms beans | [`dev/modules/shared-code-strategy.md`](dev/modules/shared-code-strategy.md) |
| ER diagram + JPA entities | [`dev/backend/persistence-model.md`](dev/backend/persistence-model.md) |
| `DataWithOwner` + AOP ownership check | [`dev/backend/security-and-tenancy.md`](dev/backend/security-and-tenancy.md) |
| `@BrowserCallable` catalogue | [`dev/backend/hilla-services.md`](dev/backend/hilla-services.md) |
| Load cell, CFW11, relay drivers | [`dev/backend/hardware-integration.md`](dev/backend/hardware-integration.md) |
| `drivers/*.jar` provenance, driver contracts, sibling-repo builds | [`dev/backend/driver-jars.md`](dev/backend/driver-jars.md) |
| Running a test with no hardware (simulated devices) | [`dev/backend/simulated-bench.md`](dev/backend/simulated-bench.md) |
| Building without the vendor jars, deck-owned device API | [`dev/backend/device-api.md`](dev/backend/device-api.md) |
| Tripping the watchdogs and the safe-stop tiers on demand | [`dev/backend/fault-injection.md`](dev/backend/fault-injection.md) |
| Simulated specimen profiles measured off real force traces | [`dev/backend/specimen-profiles.md`](dev/backend/specimen-profiles.md) |
| Test lifecycle, `TestRunnerThread` | [`dev/backend/test-execution-engine.md`](dev/backend/test-execution-engine.md) |
| Operating the bench: sign-in, running a test, settings | [`user/getting-started.md`](user/getting-started.md), [`user/running-a-test.md`](user/running-a-test.md), [`user/settings-and-admin.md`](user/settings-and-admin.md) |
| Writing and verifying a doc: standards, reference forms, gates | [`dev/conventions.md`](dev/conventions.md) |
| Test-runner packages, one run end to end, the structure lock, the safety invariants | [`dev/backend/testrunner-architecture.md`](dev/backend/testrunner-architecture.md) |
| Load-cell loss: detection, safe-stop tiers, reconnect and resume | [`dev/backend/safety-and-recovery.md`](dev/backend/safety-and-recovery.md) |
| Load-cell loss and resume thresholds (`RecoveryProperties`) | [`dev/backend/recovery-gates.md`](dev/backend/recovery-gates.md) |
| `TestProgram` and the three programs, `Verdict`, `MeasurementLoop` | [`dev/backend/test-types.md`](dev/backend/test-types.md) |
| `vite.config.ts`, dev-server, productionMode | [`dev/frontend/build-and-tooling.md`](dev/frontend/build-and-tooling.md) |
| Java→TypeScript RPC walkthrough | [`dev/frontend/hilla-generated-layer.md`](dev/frontend/hilla-generated-layer.md) |
| File-based routing + route tree | [`dev/frontend/routing-and-layout.md`](dev/frontend/routing-and-layout.md) |
| STOMP topics + sensor → chart | [`dev/frontend/state-and-realtime.md`](dev/frontend/state-and-realtime.md) |
| Component reuse + webcam mid-rewrite | [`dev/frontend/component-inventory.md`](dev/frontend/component-inventory.md) |
| `cms` vs `command-deck` frontend overlap | [`dev/frontend/frontend-module-split.md`](dev/frontend/frontend-module-split.md) |
| Deployment topology (cloud cms vs on-machine deck) | [`ops/docker-and-profiles.md`](ops/docker-and-profiles.md) |
| Docker compose, profiles, secrets | [`ops/docker-and-profiles.md`](ops/docker-and-profiles.md) |
| Image build, Dockerfiles, entrypoint script | [`ops/docker-images.md`](ops/docker-images.md) |
| Running on the bench natively (Windows-only drivers) | [`ops/bench-deployment.md`](ops/bench-deployment.md) |
| Database (H2 + Postgres, seed, backup) | [`ops/db.md`](ops/db.md) |
| "It broke" — common failures + fixes | [`ops/runbook.md`](ops/runbook.md) |

---

## Decisions, questions and work

[`decisions.md`](decisions.md) is the decision record: what was rejected and why,
and which costs were knowingly accepted.

Open owner questions (`OQ-n`) and work items (`BL-nnn`) are tracked outside
this repo, in the umbrella workspace that holds the deck and the driver repos
(versioned there, never part of a deck commit), and worked by the backlog loop
in `script/loop/`. Reference docs cite them by id only — never by path — and
name a durable gap in a short "Known limits" section.

---

## Conventions and gates

How to write and verify a doc — size and TOC rules, the reference forms the verifier checks,
diagrams as code, inventory tables — is in [`dev/conventions.md`](dev/conventions.md). Run the
four gates from the repo root after any change under `doc/`:

```bash
python doc/_check-size.py
node doc/_verify-refs.mjs
python doc/_check-diagrams.py
python doc/_check-inventories.py
```
