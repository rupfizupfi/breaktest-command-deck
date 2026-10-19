# Fault injection — driving the paths a healthy bench never reaches

**Shipped.** Each switch trips a path that no healthy bench reaches. The ten original switches have
been observed doing so; the three load-cell recovery switches and `DRIVE_TRANSACTION_WEDGED` have
**not been run yet** in a deck run. Armed
through `SimulatedFaultService` (`@BrowserCallable`, `ADMIN`-only, a bean that exists only in
simulated mode): `list()`, `arm(name)`, `clear(name)`, `clearAll()`. Switches are process-scoped,
so a restart is always a clean bench.

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
| `DRIVE_TRANSACTION_WEDGED` | one transaction hangs for `deck.simulated.wedge-millis` (3 s; at most a day, larger values clamp) before it reaches the drive, or until `SimulatedBench#endWedge()`: a call queued behind it fails with `DriveDeadlineException` if its lane deadline expires before the wedge ends (one whose deadline outlasts the wedge just waits), the drive's link-loss stop fires, and a `close()` of that handle gives up at 1500 ms, so tier 2 opens no fresh handle and tier 3 follows at once | "Could not finish closing the drive handle while invalidating it, its port is still held", then "tier 2 (FRESH_HANDLE) not attempted"; `RunScenarioTest` wedges a run's energize at the one-day maximum, `SimulatedLinkLossStopTest` shows a stop failing at its 1500 ms deadline after the watchdog already removed general enable |

**`DRIVE_MOTOR_NEVER_SLOWS` does not reach tier 3.** Escalation is gated on drive
*responsiveness*, not the clock: a drive that takes the writes and answers the reads is not a
suspect handle, so re-enumerating it would only delay a stop already commanded. Tier 3 needs a
handle that answers nothing at both tiers, which `DRIVE_UNRESPONSIVE` gives.

`LOAD_CELL_DROPPED_SAMPLES` is the one switch whose success is a **non-trip**. It models the real
driver dropping a transient fault (OQ-74) and verifies the deck half: a hole shorter than the
250 ms no-data timeout must pass the reader's 20 ms tick and the watchdog's poll without escalating, while the
discard count still reaches the log and the incident line. Held long enough it trips the watchdog,
which is correct. It exercises no `dscusb` code; the 80 ms retry budget can only be tested on the
bench.

The three recovery switches reach branches a successful reconnect never takes:

- **`LOAD_CELL_RECONNECT_GARBAGE`** — a freshly registered stream reports a constant +25 kN offset
  for its first 500 ms, outlasting the 100 ms `plausibilityGateMillis`, so the drift test
  (`|F − lastKnownForce|` against `driftFraction` = 2 % of the envelope, floored at
  `minEnvelopeNewton`) must reject. Without it the gate only ever sees passing data, and an
  inverted comparison would go unnoticed.
- **`LOAD_CELL_STREAM_OPEN_FAILS`** — thrown from `SimulatedLoadCellStreamProvider#open`, because
  the path under test is what `Device.reset()` leaves when `openConnection()` throws: references
  preserved, `connectionFuture` incomplete, so `isConnected()` stays false and the reconnect
  backoff retries.
- **`LOAD_CELL_FLAPPING`** — every stream dies ~2 s after it registers, the only way to reach
  `maxLossesPerRun` (default 2) and the `RESUMING → SENSOR_LOST` edge.

Defaults named here are `RecoveryProperties` values, not literals in the simulator; the offset,
its 500 ms window and the 2 s flap lifetime are `SimulatedBench` constants.

`DRIVE_TRANSACTION_WEDGED` is the only switch that reaches the gate's deadline path, and the
proof that a bus stop is latency, not the guarantee: while the stop waits behind the hung
transaction, the drive's own watchdog coasts the motor. It is one-shot — the transaction that
takes it disarms it — so the next transaction answers normally. The hang sits on the handle's I/O
thread, where a hung native transfer would, so closing that handle waits for it at most 1500 ms:
a shorter wedge ends inside the bound and `close()` returns with the stop queued behind it sent and
the port closed; a longer one makes `close()` throw `DriveClosePendingException`, tier 2 skips the
fresh handle rather than overlap it, and the handle closes its port by itself once the wedge ends.
A wedge set to never end is released by `SimulatedBench#endWedge()`, which the run harness calls
on teardown so the stops parked behind it are released before the next scenario starts.

`DRIVE_STALE_HANDLE` isolates tier 2: handles opened before the switch refuse, freshly opened
ones work, so tier 2's re-enumeration rescues the stop. It depends on `DriveProvider.open()`
returning a distinct handle.

This exercises the tier-2 code path only; whether two concurrent modbus handles on one physical
device are safe is OQ-50 and needs hardware.
