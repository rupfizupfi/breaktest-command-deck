# Specimen profiles — the simulator's specimen, measured

The simulated specimen follows the bench's own destructive runs: each profile in
`command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/SpecimenProfile.java` is a
median over real force traces, and every mounted specimen draws its break force and length from
that profile's batch scatter (`Specimen#draw`). A simulated curve is a *typical run* of its
profile, never evidence about a real result. The drive's ramp and coast rates are still invented.

## Contents

- [Source data](#source-data)
- [Method](#method)
- [Profiles](#profiles)
  - [Which profile a run gets](#which-profile-a-run-gets)
- [Model](#model)
- [What the data cannot settle](#what-the-data-cannot-settle)
- [Data-quality findings](#data-quality-findings)

## Source data

A copy of the bench's `file.result.data.directory`, kept locally as `bench-results` at the repo
root and gitignored — it holds customer contact data and ~190 MB, and nothing at build or run
time reads it. Each run is `<test-result-id>/<epoch-millis>_force.csv`, `epoch-millis,newton` at
~115 Hz (8–9 ms per sample). One results export (under `exports`) names project, sample,
material and test parameters for runs 98–146; nothing else ties a run to a material or speed.

## Method

`script/derive-sim-profiles.py` (standard library only) prints every constant the enum carries;
re-run it on a fresh copy after adding runs and copy its output over. Deleting the copy loses
only the ability to re-derive; the constants stay in the enum.

1. Keep a run when it loads past 4 kN, breaks (force under 30 % of peak within 40 samples of the
   last sample above 50 %), and needs at least 4 s to do so. Drop the overlapping recordings and
   garbage samples listed [below](#data-quality-findings).
2. Time becomes extension at `speed / 60` mm/s: the deck divides speed by 0.375 for rpm and the
   simulated drive moves `mmPerRev` (0.375) per revolution. Speed 350 is assumed for every run.
3. Per run: pretension (median of the first 15 samples), peak, time to break, and the curve
   normalised as `(F − pretension) / (peak − pretension)` at 0.1 steps of the travel.
4. Per profile: medians of all of these; scatter is the median coefficient of variation inside
   *batches* (≥ 3 consecutive run ids with peaks within 15 %), so it is one product's spread, not
   the spread between products.

## Profiles

| Profile | Selection | Basis |
|---|---|---|
| `compliant` (default) | ≥ 9 kN, ≥ 35 s to break | the largest class; most look like slackline webbing |
| `stiff` | ≥ 25 kN, < 35 s | second-largest class |
| `stiff-low-strength` | 9–25 kN, < 35 s | three batches only |
| `polyamide-webbing` | runs 113–119, 143–146 | export: Nylove / Pinktube, material Polyamid |
| `polyamide-webbing-degraded` | runs 98–109, 140–142 | export: same webbing, used or UV-exposed |

### Which profile a run gets

Before its startup checks open the load cell, every run calls `BenchPreparation#prepare`
(`TestRunnerThread`); in simulated mode `SimulatedSpecimenSelector` mounts a fresh specimen and
writes its choice and the run's speed into the test log. The mount discards readings still queued
in the session stream (`SimulatedLoadCellStream#discardPending`), so a run never reads the specimen
it replaced — one left stretched above the new upper threshold would end the run at once.

| Input | Effect |
|---|---|
| `deck.simulated.profile` | When set, forces this profile for every run; an unknown name fails startup |
| the sample's materials | Otherwise the first, by name, that `deck.simulated.material-profiles` maps; unmapped → `compliant` |
| destructive `lowerShutOffThreshold` | The pretension is raised to `PRETENSION_OVER_LOWER_THRESHOLD` × the threshold when the profile's median is below it, as an operator tensions above it |
| speed, turn forces, upper threshold | Nothing to select: speed sets the travel rate, the forces act on the curve as on the bench |

The default mapping (`SimulatedBenchProperties`) sends `Polyamid` to `polyamide-webbing`, which
is measured, and the low-stretch fibres and metals to `stiff`, which is physics, not data. Sample
name and description are not read: in the export "exposed" webbing broke at 6 kN in one batch
and 19 kN in another. `polyamide-webbing-degraded` is reachable only by forcing it.

`deck.simulated.scatter=false` mounts the median specimen every time;
`deck.simulated.sample-model=UNBREAKABLE` keeps the curve and drops the break, for cyclic runs
that must not end on their own.

## Model

Position is crosshead travel from an unloaded specimen (`Specimen#forceAt`):

| Range | Force |
|---|---|
| 0 → toe | linear from 0 to pretension, at the curve's first measured slope; no trace shows this range, it exists so a cyclic release can unload |
| toe → toe + break extension | pretension + (break − pretension) × the measured shape, linear between knots |
| beyond | the last segment's slope continued, so a stronger draw or `UNBREAKABLE` keeps loading |

A fresh specimen sits at the toe, i.e. at pretension — above every lower shut-off threshold the
bench has used, which a destructive run needs (`MeasurementLoop` ends it on the first sample
below). At the drawn break force it collapses within ~10 ms to a bench-wide residual
(`SimulatedBench`), the median of the runs that recorded their settle. Sensor noise is Gaussian
at `noiseNewton` (`SimulatedBenchProperties`).

## What the data cannot settle

| Gap | Consequence |
|---|---|
| Speed recorded for runs 98–146 only | Extension in mm is right only if every run used 350 |
| No material for runs outside the export | `compliant`, `stiff`, `stiff-low-strength` are behaviour classes, not materials; the invented `MaterialPreset` table (steel, kevlar, …) was dropped rather than guessed |
| Specimen length unrecorded | It hides inside the extension scatter, the largest spread measured |
| Loading only, no unloading traces | A cyclic release retraces the loading curve; real textiles show hysteresis |
| Pre-break strand slips | Seen in a minority of runs (≥ 1.5 % steps in the last 3 s); not modelled |

## Data-quality findings

- **Overlapping recordings** — runs 5, 6, 9, 11, 15, 18 hold several files of the same
  hour-long stream (identical peak and minimum): a load-cell stream kept recording across runs.
- **Garbage samples** — runs 98, 99, 238 carry single samples of 10⁷–9·10⁷ N; today's
  plausibility bound in `MeasurementLoop` rejects them.
- **Instant ends** — 0.1 s files (e.g. runs 149, 162, 195) start below the lower threshold: the
  operator's pretension was under 1 kN, so the run stopped at its first sample.
- **Noisy era** — before run 121 the cell carries ~1 kN spikes at rest; from 121 on, ~10 N.
  Noise is measured on the later runs only.
- **Upper-threshold stops** — e.g. runs 25, 63, 289 end at peak without breaking.

Related: [README](simulated-bench.md) (plant model), [fault-injection](fault-injection.md).
