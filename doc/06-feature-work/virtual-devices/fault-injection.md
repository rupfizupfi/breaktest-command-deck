> Branch: `dev-split` — implemented 2026-08-18.

# Fault injection — driving the paths a healthy bench never reaches

**Shipped.** Each switch trips a path that no healthy bench reaches. The ten original switches have
each been observed doing so; the three added for load-cell recovery have **not been run yet** and
are marked as such below. Armed through `SimulatedFaultService` (`@BrowserCallable`, `ADMIN`-only,
and — like the providers — a bean that *only exists* in simulated mode): `list()`, `arm(name)`,
`clear(name)`, `clearAll()`. Switches are process-scoped, so a restart is always a clean bench.

<!-- inventory: enum command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/SimulatedFault.java -->

| `SimulatedFault` | Trips | Observed outcome |
|---|---|---|
| `LOAD_CELL_SILENT` | no-data timeout → `sensorLost` → `safeStop` | trip reason "no measurement for more than 250 ms" |
| `LOAD_CELL_STREAM_DEATH` | the same escalation, but diagnosable | reason gains "(load cell driver error 14: …)" from `getStreamFailure` |
| `LOAD_CELL_FROZEN` | frozen-sample detector | "force frozen at … for 100 consecutive bit-identical samples" |
| `LOAD_CELL_NAN` | 3-of-5 plausibility vote | "3 of the last 5 samples were rejected, last value NaN" |
| `LOAD_CELL_IMPLAUSIBLE_FORCE` | the same vote, magnitude branch | same wording, last value 1000000.0 |
| `LOAD_CELL_DROPPED_SAMPLES` | **nothing**, while the hole stays under the no-data timeout | rising discard count in the log and on the incident line |
| `LOAD_CELL_RECONNECT_GARBAGE` | the resume drift gate's **REJECT** branch | *not yet observed* |
| `LOAD_CELL_STREAM_OPEN_FAILS` | `Device.reset()`'s "openConnection threw" path, and the reconnect backoff above it | *not yet observed* |
| `LOAD_CELL_FLAPPING` | the max-losses-per-run cap, and the `RESUMING → SENSOR_LOST` edge | *not yet observed* |
| `DRIVE_STALE_HANDLE` | tier 1 fails → **tier 2** re-enumerates | "safeStop verified at tier FRESH_HANDLE" |
| `DRIVE_UNRESPONSIVE` | tier 1 and tier 2 both fail → **tier 3** | "escalated to the operator after both software tiers ran", result tier `NONE` |
| `DRIVE_MOTOR_NEVER_SLOWS` | tier 1 not verified but drive answering | `coasting()`, "de-energized … could not confirm standstill … 533 rpm" |
| `DRIVE_CLOSE_THROWS` | `closeDriveHandle()` best-effort path | "Failed to close CFW11 USB communication while invalidating the handle", then tier 2 still verifies |

**`DRIVE_MOTOR_NEVER_SLOWS` does not reach tier 3**, and the earlier version of this
table was wrong to say so. Escalation is gated on drive *responsiveness*, not on the
clock: a drive that takes the writes and answers the reads is not a suspect handle, so
re-enumerating it would only delay a stop that is already commanded. Tier 3 requires a
handle that answers nothing — at both tiers — which is what `DRIVE_UNRESPONSIVE` gives.

`LOAD_CELL_DROPPED_SAMPLES` is the one switch whose success is a **non-**trip. It models what the
real driver does to a fault a retry could get past (OQ-74), and verifies the deck half of that
bound: a hole shorter than the 250 ms no-data timeout must survive the two stacked 20 ms poll
layers without escalating, while the discard count still reaches the log and the incident line.
Held long enough it does trip the watchdog, which is correct — past its own budget a persistent
fault should end the run either way. It exercises no line of `dscusb`, whose 80 ms retry budget can
only be tested on the bench.

The three recovery switches each exist because the branch they reach is one a *successful*
reconnect never takes, so nothing else would ever execute it:

- **`LOAD_CELL_RECONNECT_GARBAGE`** — a freshly registered stream reports a constant +25 kN offset
  for its first 500 ms. That window deliberately outlasts the 100 ms `plausibilityGateMillis`, so
  the samples the gate inspects are the bad ones and the drift test (`|F − lastKnownForce|` against
  `driftFraction` = 2 % of the envelope, floored at `minEnvelopeNewton`) rejects. Without it the
  gate is only ever exercised on data that passes — and an **inverted comparison would ship**,
  because a force that jumped to nothing like its pre-loss value is exactly what a garbage
  reconnect looks like.
- **`LOAD_CELL_STREAM_OPEN_FAILS`** — thrown from `SimulatedLoadCellStreamProvider#open` rather
  than injected in the bench, because the path under test is what `Device.reset()` leaves behind
  when `openConnection()` throws: references preserved, `connectionFuture` left incomplete, so
  `isConnected()` stays false and the reconnect backoff retries. A stream that opened cannot reach
  any of it.
- **`LOAD_CELL_FLAPPING`** — every stream dies again ~2 s after it registers, which is the only way
  to reach `maxLossesPerRun` (default 2) and the `RESUMING → SENSOR_LOST` edge. A single clean loss
  reaches neither.

Defaults named here are `RecoveryProperties` values, not literals in the simulator; the offset,
its 500 ms window and the 2 s flap lifetime are `SimulatedBench` constants.

`DRIVE_STALE_HANDLE` is the isolated tier-2 exercise: handles opened *before* the switch
refuse, freshly opened ones work, so tier 2's re-enumeration is what rescues the stop.
It depends on `DriveProvider.open()` handing back a genuinely distinct handle
(`MotorSafetyController.java:242`).

This exercises the tier-2 **code path**; it says nothing about whether two concurrent
modbus handles on one physical device are safe. That is OQ-50 and still needs hardware.
