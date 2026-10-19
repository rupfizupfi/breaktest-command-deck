# Recovery gates — the numbers, and why they are configurable

The configurable thresholds that decide a load-cell loss, a reconnect and a resume; the detectors and
the resume flow that apply them are in [`safety-and-recovery.md`](safety-and-recovery.md).

The bench tracks at maximum resolution and pulls material apart, so seconds of missing data are
critical. Every gate is seconds-scale and expressed in **time, not sample counts**: the sample rate
is a driver and configuration fact, so a count would change meaning after a `RATE` change;
`plausibilityGateMinSamples` is only a floor on evidence. `RecoveryProperties`
(`@ConfigurationProperties("deck.testrunner.recovery")`, `@Validated`) holds them; defaults live in
the class, not in `application*.properties`.

| Property | Default | What it bounds |
|---|---|---|
| `reconnectWindowMillis` | 10 000 | Total time the reconnector retries before the loss is unrecoverable |
| `backoffMillis` | 1000, 2000, 4000 | Delay before each attempt; last entry repeats |
| `safeHoldTimeoutMillis` | 60 000 | Hold the server auto-aborts, with no UI involvement |
| `maxHoldForResumeMillis` | 30 000 | Past this the specimen has crept — resume refused, hold still alive |
| `plausibilityGateMillis` | 100 | Continuous plausible samples a reconnected stream must deliver |
| `plausibilityGateMinSamples` | 10 | Plausible samples that same unbroken run must also hold (at least 2) |
| `driftFraction` | 0.02 | \|F − lastKnownForce\| against 2 % of the force envelope |
| `minEnvelopeNewton` | 1000 | Floor for that envelope, so near-zero limits don't make the gate unpassable |
| `maxLossesPerRun` | 2 | The next loss aborts instead of offering another recovery |
| `maxSampleIntervalMillis` | 100 | Mean time per sample a drain may show before it votes sparse; uncalibrated ([detection](safety-and-recovery.md#detection-in-measurementloop-woken-by-each-batch)) |
| `maxFrozenMillis` | 1000 | Longest one bit-identical value may last; at least the no-data timeout; uncalibrated ([detection](safety-and-recovery.md#detection-in-measurementloop-woken-by-each-batch)) |

Three cross-field `@AssertTrue` checks refuse a configuration that reads as working and is not: a
resume window longer than the hold (Resume offered for a run already auto-aborted), a first backoff
at or beyond the reconnect window (logged as "reconnect failed" for a sensor nobody retried), and a
sample-interval bound at or past the no-data timeout (`RecoveryGates.NO_DATA_TIMEOUT_MS`, fixed, not
a gate), which could never vote before silence had already tripped. **The applied values are
snapshotted per run** (`RecoveryGates`, an immutable record taken at start) and copied into both the
`interruptionLog` and the gaps sidecar, so a run records the gates it actually used.
`MeasurementLoop`, `SensorReconnector` and `ResumeGates` read the snapshot, not the bean;
`RunLoop`'s hold entry applies the `minEnvelopeNewton` floor once, so every gate sees the final
envelope.
