# Running a test

Setting up a run on the bench PC, what the three test types do, what happens when the load cell
drops out, and what a result's status means. Start with
[`getting-started.md`](getting-started.md) if the records below are new to you.

## Contents

- [Test parameters](#test-parameters)
- [The three test types](#the-three-test-types)
- [Starting and stopping a run](#starting-and-stopping-a-run)
- [When the load cell drops out](#when-the-load-cell-drops-out)
- [Run status](#run-status)
- [Results, photos and export](#results-photos-and-export)

## Test parameters

A test parameter is created under **Test** for one type. A value outside the rules below is
refused when you save it, and checked again when the run starts — an administrator may have
changed the rig limits in between ([`settings-and-admin.md`](settings-and-admin.md#rig-limits)).

| Field | Used by | Rule |
|---|---|---|
| Speed | all | crosshead speed in mm/min, above 0 and at most the rig's maximum speed |
| Upper shut-off threshold | all | in kN, above 0 and at most the load cell's rated capacity — the hard trip for every type |
| Lower shut-off threshold | destructive | in kN, 0 or more (never negative), below the upper shut-off |
| Upper / lower turn force | cyclic, time-cyclic | in kN, 0 ≤ lower < upper < upper shut-off |
| Cycle count | cyclic, time-cyclic | 1 or more |
| Start / stop ramp | cyclic, time-cyclic | empty, or 0 to 999 s; empty or 0 means no second ramp |

The authoritative rule set is [`dev/backend/test-types.md`](../dev/backend/test-types.md#parameter-rules).

## The three test types

- **Destructive** — pulls at the set speed until the force crosses a shut-off threshold. A
  drop through the **lower** shut-off after the force has risen at least the suction load margin
  above it counts as the break; once the motor has stopped, the run switches on the suction (relay 1) for the
  configured time, if suction is enabled in the settings. Ending at the **upper** shut-off never
  starts the suction.
- **Cyclic** — loads and unloads between the lower and upper turn force for the set number of
  cycles. Crossing the upper shut-off ends the run as `FAULT`.
- **Time cyclic** — like cyclic, but it first runs a short calibration at 50 mm/min (or the rig
  maximum, if lower) to time the release and pull legs, then reverses on those timings at the
  set speed.

## Starting and stopping a run

1. Open **Execute test** on the bench PC and add a row: owner, test parameter, sample, and a
   description.
2. Save it. **Run test** is enabled only for a saved row whose form has no unsaved changes.
3. Press **Run test**. Below the list a live panel opens: the current and maximum force in kN,
   the force chart, and the run log.

Only one run at a time: starting another while a run is still open asks you to stop the old one
first. On a real bench that has just booted, a Start is refused as "bench still initialising" until
the drive has been de-energized.

- **Stop** stops the motor and ends the run. Once the machine has taken the command, a notice
  says *stop sent to the machine*. If the stop does not reach the machine, a red notice says so and stays on screen —
  press **Stop** again or use the **physical E-stop**.
- **Close** on a run that is still active asks *Stop run … and close?* first; confirming stops
  this run only.
- An amber line under the panel means the view is not live — no connection to the machine, or
  no force data for a while. The run on the machine is not affected by the browser.

## When the load cell drops out

If the force readings stop, the machine stops the motor by itself and a red banner appears:
**SENSOR LOST — the motor has been stopped**, then **SAFE HOLD — the motor is stopped, the run
is waiting for you**. It shows the last known force, how long nothing has been measured, when
the run will abort itself, and the number of losses and reconnect attempts. Meanwhile the
machine keeps trying to reconnect the sensor.

- **Abort** is always available and ends the run.
- **Resume** is enabled only when the machine allows it, and asks you to confirm. A resumed run
  continues with a hole in its force trace and is recorded as `COMPLETED_WITH_GAPS`.

When resume is allowed (defaults; the values are configurable on the server):

| Rule | Default |
|---|---|
| Destructive runs | never — abort only, because a curve with a gap is not a valid break test |
| Time-cyclic runs | resume recalibrates before continuing |
| The run aborts itself after a hold of | 60 s |
| Resume is refused after a hold of | 30 s (the specimen may have crept) |
| Losses per run before the next one aborts | 2 |

The sensor must also deliver plausible readings again, close to the last known force. The full
rules are in [`dev/backend/safety-and-recovery.md`](../dev/backend/safety-and-recovery.md#recovery-and-resume).

## Run status

Shown in the **Execute test** list and on the result page, under the stored name:

| Status | Meaning |
|---|---|
| `IN_PROGRESS` | running, or resuming after a sensor loss |
| `COMPLETED` | finished without a sensor loss — at its limit, its last cycle, or your **Stop** |
| `COMPLETED_WITH_GAPS` | finished after at least one sensor loss — not comparable with a clean run |
| `INTERRUPTED` | in a sensor-loss hold; one left behind by a crash is marked `ABORTED` at the next start of the app |
| `ABORTED` | ended by the operator's abort, a hold that expired, or a crash |
| `FAULT` | ended by a fault — the upper shut-off on a cyclic run, a motor stop the machine could not confirm (the run log then says to use the physical E-stop), or an internal error |

## Results, photos and export

Each row in **Execute test** links to:

- **Results** — the run's status, its incident record, and a chart of a chosen force recording
  with its duration and maximum and minimum force.
- **Images** — upload and delete photos of the sample.
- **Tracking** — webcam distance tracking (provisional).

The **Export** link in **Projects** downloads every result of the project as an Excel workbook.
