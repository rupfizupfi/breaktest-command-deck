> Branch: `dev-split` — implemented 2026-08-18.

# Fault injection — driving the paths a healthy bench never reaches

**Shipped.** Each switch trips a path that no healthy bench reaches, and each has now
been observed doing so. Armed through `SimulatedFaultService` (`@BrowserCallable`,
`ADMIN`-only, and — like the providers — a bean that *only exists* in simulated mode):
`list()`, `arm(name)`, `clear(name)`, `clearAll()`. Switches are process-scoped, so a
restart is always a clean bench.

| `SimulatedFault` | Trips | Observed outcome |
|---|---|---|
| `LOAD_CELL_SILENT` | no-data timeout → `sensorLost` → `safeStop` | trip reason "no measurement for more than 250 ms" |
| `LOAD_CELL_STREAM_DEATH` | the same escalation, but diagnosable | reason gains "(load cell driver error 14: …)" from `getStreamFailure` |
| `LOAD_CELL_FROZEN` | frozen-sample detector | "force frozen at … for 100 consecutive bit-identical samples" |
| `LOAD_CELL_NAN` | 3-of-5 plausibility vote | "3 of the last 5 samples were rejected, last value NaN" |
| `LOAD_CELL_IMPLAUSIBLE_FORCE` | the same vote, magnitude branch | same wording, last value 1000000.0 |
| `DRIVE_STALE_HANDLE` | tier 1 fails → **tier 2** re-enumerates | "safeStop verified at tier FRESH_HANDLE" |
| `DRIVE_UNRESPONSIVE` | tier 1 and tier 2 both fail → **tier 3** | "escalated to the operator after both software tiers ran", result tier `NONE` |
| `DRIVE_MOTOR_NEVER_SLOWS` | tier 1 not verified but drive answering | `coasting()`, "de-energized … could not confirm standstill … 533 rpm" |
| `DRIVE_CLOSE_THROWS` | `closeDriveHandle()` best-effort path | "Failed to close CFW11 USB communication while invalidating the handle", then tier 2 still verifies |

**`DRIVE_MOTOR_NEVER_SLOWS` does not reach tier 3**, and the earlier version of this
table was wrong to say so. Escalation is gated on drive *responsiveness*, not on the
clock: a drive that takes the writes and answers the reads is not a suspect handle, so
re-enumerating it would only delay a stop that is already commanded. Tier 3 requires a
handle that answers nothing — at both tiers — which is what `DRIVE_UNRESPONSIVE` gives.

`DRIVE_STALE_HANDLE` is the isolated tier-2 exercise: handles opened *before* the switch
refuse, freshly opened ones work, so tier 2's re-enumeration is what rescues the stop.
It depends on `DriveProvider.open()` handing back a genuinely distinct handle
(`MotorSafetyController.java:242`).

This exercises the tier-2 **code path**; it says nothing about whether two concurrent
modbus handles on one physical device are safe. That is OQ-50 and still needs hardware.
