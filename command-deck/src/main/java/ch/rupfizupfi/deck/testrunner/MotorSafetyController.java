package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.frequencyconverter.CFW11Device;
import ch.rupfizupfi.deck.device.frequencyconverter.DriveUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single entry point for stopping the motor. Every stop is verified against the drive's own
 * measured motor speed instead of being fire-and-forget, and escalates through three tiers.
 * <p>
 * Escalation is driven by drive RESPONSIVENESS, never by the clock alone: the tier 1 stop
 * de-energizes the output stage and lets the motor coast, so a still-turning shaft is a normal
 * mechanical outcome, not evidence of a broken USB handle.
 */
@Service
public class MotorSafetyController {
    private static final Logger logger = LoggerFactory.getLogger(MotorSafetyController.class);

    private static final int STOPPED_RPM_TOLERANCE = 5;
    private static final long VERIFY_DEADLINE_MS = 5000;
    private static final long VERIFY_POLL_INTERVAL_MS = 50;
    /** One quiet sample can be a stale or defaulted register, so demand consecutive ones. */
    private static final int REQUIRED_CONSECUTIVE_STOPPED_READINGS = 2;
    /** Speed drop, in rpm, above which the shaft counts as measurably slowing down. */
    private static final int COASTING_DROP_RPM = 10;

    private final CFW11Device frequencyConverter;

    /** Tier 2 opens its own handle, bypassing the one the device holds — see stopWithFreshHandle. */
    private final DriveProvider driveProvider;

    /** Set by safeStop, blocks any further energize until the next run clears it. */
    private final AtomicBoolean stopLatched = new AtomicBoolean(false);
    private volatile String stopReason;

    /**
     * True once this run genuinely enabled the drive output stage. A run that died in the startup
     * checks or in setup() never energized anything, so its cleanup stop has no motor to catch and
     * must not tear the USB down. Reset per run by clearStopLatch().
     */
    private final AtomicBoolean motorEnergized = new AtomicBoolean(false);

    /**
     * Result of the last completed safeStop of this run. cleanup() runs twice per test by design and
     * a LoadCellThread failure can add a third call, so an escalation that already happened is
     * replayed from here instead of being repeated against the hardware.
     */
    private volatile SafeStopResult lastResult;

    public MotorSafetyController(DeviceService deviceService, DriveProvider driveProvider) {
        this.frequencyConverter = deviceService.getFrequencyConverter();
        this.driveProvider = driveProvider;
    }

    public boolean isDriveAvailable() {
        return frequencyConverter.isDriveHandleOpen();
    }

    public void withDrive(Consumer<Drive> action) {
        frequencyConverter.withDrive(action);
    }

    public <T> T queryDrive(Function<Drive, T> action) {
        return frequencyConverter.queryDrive(action);
    }

    /** True once safeStop() has been requested for the current run, until clearStopLatch(). */
    public boolean isStopLatched() {
        return stopLatched.get();
    }

    /** Clears the latch. Called when a new test run starts. */
    public void clearStopLatch() {
        String reason = stopReason;
        stopReason = null;
        // A new run starts with the motor known-off and with no stop of its own on record, so the
        // next safeStop neither replays the previous run's escalation nor assumes a live motor.
        motorEnergized.set(false);
        lastResult = null;
        if (stopLatched.getAndSet(false)) {
            logger.info("motor safety stop latch cleared, previous stop reason: {}", reason);
        }
    }

    /**
     * Runs an energize action, but only if no safety stop has been requested for this run.
     * The latch is checked INSIDE the drive lock so it cannot race a concurrent safeStop():
     * setup() starts the load cell thread before it energizes, so a load cell failure can stop
     * the motor while the energize block is still queued behind it on the drive lock.
     *
     * @throws IllegalStateException if a safety stop has been requested
     */
    public void energize(Consumer<Drive> action) {
        frequencyConverter.withDrive(drive -> {
            if (stopLatched.get()) {
                throw new IllegalStateException(
                        "refusing to energize the motor, a safety stop was requested for this run: " + stopReason);
            }
            // Recorded before the action, and only after the latch check has passed: from this point
            // on a stop has a real motor to catch, even if the action itself throws halfway through
            // or the handle disappears later in the run.
            motorEnergized.set(true);
            action.accept(drive);
        });
    }

    /**
     * Stops the motor and verifies it actually stood still. Never throws: callers use this from
     * finally blocks and emergency paths, where an escaping exception would mask the original
     * failure and leave the drive running. Idempotent, safe to call repeatedly: a repeat call that
     * finds an escalation already recorded for this run replays it instead of re-running it.
     */
    public SafeStopResult safeStop(String reason) {
        // Latch before taking any lock, so an energize already queued on the drive lock still
        // observes the latch by the time it gets in. getAndSet makes latching and noticing that
        // somebody already latched one atomic step, so two concurrent stops cannot both conclude
        // they are the first one.
        stopReason = reason;
        boolean alreadyLatched = stopLatched.getAndSet(true);

        SafeStopResult previous = lastResult;
        if (alreadyLatched && previous != null && previous.tier() != SafeStopResult.Tier.EXISTING_HANDLE) {
            // A previous stop already went past the cheap tier: repeating it would mean a second USB
            // re-enumeration, a second 5 s drive lock hold and a second "use the E-stop" alert for
            // one and the same incident. Tier 1 results are deliberately NOT cached: re-checking a
            // motor over a working handle is cheap, non-destructive and worth doing again.
            logger.info("safeStop({}) not repeated, a stop for this run already escalated to tier {}: {}",
                    reason, previous.tier(), previous.detail());
            return previous;
        }

        // Read the flag ONCE for the whole stop and thread it into every result built below.
        // Re-reading the atomic per construction site could observe a concurrent energize() and
        // produce two results that disagree about the same stop.
        boolean wasEnergized = motorEnergized.get();
        if (wasEnergized) {
            logger.warn("safeStop requested: {}", reason);
        } else {
            // Nothing was ever enabled in this run (failed startup check, setup() threw early), so
            // this is bookkeeping, not an incident.
            logger.info("safeStop requested for a run that never energized the motor: {}", reason);
        }

        SafeStopResult result;
        boolean nothingToStop = false;
        try {
            var tier1 = stopWithExistingHandle(wasEnergized);
            result = tier1.result();
            // Responsive means the drive took the de-energize command and is reporting a real,
            // non-zero speed: the shaft is coasting on its own inertia, which no amount of USB
            // re-enumeration can shorten. An interrupt likewise only truncates the evidence, it
            // never proves the handle is dead, so neither may trigger a tier 2 teardown.
            boolean handleSuspect = !result.verified() && !tier1.responsive() && !tier1.interrupted();

            if (handleSuspect && !wasEnergized) {
                // Tier 1 found nothing to talk to, but this run never enabled the drive either, so
                // there is no motor to chase. Escalating here would punish a failed startup check -
                // which terminates long before setup() connects anything - with a full USB
                // re-enumeration, a 5 s drive lock hold and an E-stop alert.
                nothingToStop = true;
                result = new SafeStopResult(result.tier(), result.verified(), result.driveResponsive(),
                        wasEnergized, result.motorSpeedRpm(),
                        "no stop was needed, the drive was never energized during this run ("
                                + result.detail() + ")");
            } else if (handleSuspect) {
                String tier1Detail = result.detail();
                var tier2 = stopWithFreshHandle(result.motorSpeedRpm(), wasEnergized);
                result = tier2.result();
                if (!result.verified()) {
                    result = escalateToOperator(result.motorSpeedRpm(), tier2.responsive(),
                            wasEnergized, tier1Detail, result.detail());
                }
            }
        } catch (Throwable t) {
            // Last resort: even the tier dispatch must not propagate.
            logger.error("safeStop failed unexpectedly, motor state unknown, use the physical E-stop", t);
            result = new SafeStopResult(SafeStopResult.Tier.NONE, false, false, wasEnergized, null,
                    "safeStop failed unexpectedly: " + t);
        }

        if (nothingToStop) {
            logger.debug("safeStop({}) had nothing to stop: {}", reason, result.detail());
        } else if (result.verified()) {
            logger.warn("safeStop verified at tier {}, motor speed {} rpm ({})",
                    result.tier(), result.motorSpeedRpm(), reason);
        } else if (result.coasting()) {
            // Not an operator emergency: the drive is talking and de-energized, the mass is coasting.
            logger.warn("safeStop de-energized the drive but could not confirm standstill at tier {},"
                            + " last measured motor speed {} rpm ({}): {}",
                    result.tier(), result.motorSpeedRpm(), reason, result.detail());
        } else {
            logger.error("safeStop NOT verified, last measured motor speed {} rpm, result tier {} ({}): {}",
                    result.motorSpeedRpm(), result.tier(), reason, result.detail());
        }

        lastResult = result;
        return result;
    }

    /**
     * Tier 1: use the handle the running test already holds, so no USB re-enumeration is needed.
     */
    private TierOutcome stopWithExistingHandle(boolean wasEnergized) {
        var commandProblems = new AtomicReference<>("");
        var outcome = new AtomicReference<VerifyOutcome>();

        try {
            frequencyConverter.withDrive(drive -> {
                commandProblems.set(commandStop(drive));
                outcome.set(verifyStopped(drive));
            });
        } catch (DriveUnavailableException e) {
            // No handle open: tier 1 is simply not applicable, this is not a hardware fault.
            return TierOutcome.unresponsive(SafeStopResult.Tier.EXISTING_HANDLE, null, wasEnergized,
                    "no open drive handle: " + e.getMessage());
        } catch (Throwable t) {
            logger.warn("stop over the existing drive handle failed", t);
            var partial = outcome.get();
            return TierOutcome.unresponsive(SafeStopResult.Tier.EXISTING_HANDLE,
                    partial == null ? null : partial.lastSpeed(), wasEnergized,
                    "existing handle failed: " + t);
        }

        return describe(SafeStopResult.Tier.EXISTING_HANDLE, "existing handle",
                commandProblems.get(), outcome.get(), null, wasEnergized);
    }

    /**
     * Tier 2: the existing handle is dead or lying. Drop it and enumerate the drive fresh.
     */
    private TierOutcome stopWithFreshHandle(Integer previousSpeed, boolean wasEnergized) {
        var commandProblems = new AtomicReference<>("");
        var outcome = new AtomicReference<VerifyOutcome>();

        // Reset the connection bookkeeping FIRST, with no lock held. It takes the device's instance
        // monitor and the lock order is monitor-before-driveLock, so doing it inside runExclusive
        // would deadlock against closeConnection(). Doing it before the close is also what makes a
        // later connect() genuinely re-open instead of handing back the handle we are about to kill.
        frequencyConverter.dropConnectionBookkeeping();

        try {
            // Exclusive for the rest of the tier, for two reasons: the 400 ms info poller must not
            // talk on the bus while we re-enumerate, and - because DriveProvider.open() eagerly grabs
            // the USB device - closing the old handle and opening the fresh one has to be ONE atomic step.
            // With the close hoisted out of here, a Device.connect() landing in the gap would open a
            // second live session on one physical drive, exactly the dual-handle condition this
            // teardown exists to prevent (OQ-50).
            // Deadlock-free: closeDriveHandle() takes driveLock only, never the instance monitor, so
            // nothing in this block inverts the monitor-before-driveLock order. A concurrent
            // connect() holds the monitor, blocks on driveLock for the whole escalation, and then
            // re-opens cleanly against the reset bookkeeping.
            frequencyConverter.runExclusive(() -> {
                frequencyConverter.closeDriveHandle();
                Drive fresh = null;
                try {
                    fresh = driveProvider.open();
                    commandProblems.set(commandStop(fresh));
                    outcome.set(verifyStopped(fresh));
                } finally {
                    if (fresh != null) {
                        try {
                            fresh.close();
                        } catch (Throwable ignored) {
                            // best effort, the stop result must not depend on a clean close
                        }
                    }
                }
            });
        } catch (Throwable t) {
            logger.warn("stop over a freshly enumerated drive handle failed", t);
            var partial = outcome.get();
            Integer lastSpeed = partial == null || partial.lastSpeed() == null
                    ? previousSpeed : partial.lastSpeed();
            return TierOutcome.unresponsive(SafeStopResult.Tier.FRESH_HANDLE, lastSpeed, wasEnergized,
                    "fresh handle failed: " + t);
        }

        // The device is left without a handle on purpose: every later withDrive throws
        // DriveUnavailableException so the test fails fast instead of driving blind.
        return describe(SafeStopResult.Tier.FRESH_HANDLE, "fresh handle",
                commandProblems.get(), outcome.get(), previousSpeed, wasEnergized);
    }

    /**
     * Tier 3: nothing in software could confirm a stop. Log loudly, there is nothing left to command.
     */
    // TODO hardware kill line: relay 2 of FourWayRelaySwitch wired in series with the CFW11
    //  general-enable/STO input would give this tier something to actually pull. Needs a relay
    //  firmware extension before it can be driven from software.
    private SafeStopResult escalateToOperator(Integer lastSpeed, boolean tier2Responsive,
                                              boolean wasEnergized, String tier1Detail,
                                              String tier2Detail) {
        // Name every tier that ran: the returned marker is Tier.NONE for compatibility, which on its
        // own would read as "nothing was attempted" in a post-incident trace.
        String detail = "tier 1 (EXISTING_HANDLE) ran and reported: " + tier1Detail
                + " | tier 2 (FRESH_HANDLE) ran, re-enumerated the drive and reported: " + tier2Detail
                + " | tier 3 (operator escalation): both software stop tiers failed, motor may still be "
                + "running - USE THE PHYSICAL E-STOP. The only active backstop left is the drive's own "
                + "setActionInCaseOfCommunicationError(2), which reacts to loss of the CFW11 link only "
                + "and not to loss of the load cell.";
        logger.error("safeStop escalated to the operator after both software tiers ran."
                + " Last measured motor speed: {} rpm. {}", lastSpeed, detail);
        // Carry tier 2's responsiveness verdict, not a blanket false: if the fresh handle did answer
        // and only the standstill was missing, the operator alert is still warranted but the caller
        // can report it as a drive that is talking rather than as a silent one.
        return new SafeStopResult(SafeStopResult.Tier.NONE, false, tier2Responsive, wasEnergized,
                lastSpeed, detail);
    }

    /**
     * Turns a completed tier into its result plus the responsiveness verdict that decides whether
     * the next tier is allowed to run.
     */
    private TierOutcome describe(SafeStopResult.Tier tier, String label, String commandProblems,
                                 VerifyOutcome outcome, Integer fallbackSpeed,
                                 boolean wasEnergized) {
        // Responsive means the drive both took our writes and answered our reads. Only when it did
        // neither is the handle itself suspect, and only then may tier 2 tear down the USB.
        boolean responsive = commandProblems.isEmpty() && outcome.anyReadSucceeded();
        Integer lastSpeed = outcome.lastSpeed() == null ? fallbackSpeed : outcome.lastSpeed();

        var detail = new StringBuilder(commandProblems);
        detail.append("stop commands sent over the ").append(label);

        if (outcome.verified()) {
            detail.append(", standstill confirmed by ").append(REQUIRED_CONSECUTIVE_STOPPED_READINGS)
                    .append(" consecutive readings within ").append(STOPPED_RPM_TOLERANCE).append(" rpm");
        } else if (outcome.interrupted()) {
            detail.append(", verification aborted by interrupt (operator stop) ")
                    .append(outcome.anyReadSucceeded()
                            ? "at " + outcome.lastSpeed() + " rpm"
                            : "before any speed reading came back")
                    .append(outcome.trend());
        } else if (outcome.anyReadSucceeded()) {
            detail.append(", motor still turning at ").append(outcome.lastSpeed())
                    .append(" rpm when the ").append(VERIFY_DEADLINE_MS)
                    .append(" ms verify deadline expired").append(outcome.trend());
        } else {
            detail.append(", but no motor speed reading could be obtained - the drive is not answering");
        }

        return new TierOutcome(
                new SafeStopResult(tier, outcome.verified(), responsive, wasEnergized, lastSpeed,
                        detail.toString()),
                responsive, outcome.interrupted());
    }

    /**
     * Sends the three stop commands in safety order. Each is wrapped individually so that a failing
     * command still lets the other two through. Returns a description of whatever failed.
     */
    private String commandStop(Drive drive) {
        var problems = new StringBuilder();

        try {
            // General enable first: this drops the output stage and lets the motor coast. A ramp
            // stop would keep the drive loading the sample for the whole stopRampSeconds, and a
            // blind direction reversal could slam the crosshead through zero. Never ramp, never
            // reverse.
            drive.setGeneralEnable(false);
        } catch (Throwable t) {
            logger.warn("setGeneralEnable(false) failed during safeStop", t);
            problems.append("setGeneralEnable failed: ").append(t).append("; ");
        }

        try {
            drive.setSpeedReferenceValueAsRpm(0);
        } catch (Throwable t) {
            logger.warn("setSpeedReferenceValueAsRpm(0) failed during safeStop", t);
            problems.append("setSpeedReference failed: ").append(t).append("; ");
        }

        try {
            drive.setStart(false);
        } catch (Throwable t) {
            logger.warn("setStart(false) failed during safeStop", t);
            problems.append("setStart failed: ").append(t).append("; ");
        }

        return problems.toString();
    }

    /**
     * Polls the drive until the measured motor speed has been within tolerance of zero for
     * {@link #REQUIRED_CONSECUTIVE_STOPPED_READINGS} consecutive samples, or the deadline expires.
     * This read back is the entire point of the controller: a written command is not evidence that
     * the motor stopped.
     */
    private VerifyOutcome verifyStopped(Drive drive) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(VERIFY_DEADLINE_MS);
        Integer firstSpeed = null;
        Integer lastSpeed = null;
        int consecutiveStopped = 0;

        while (true) {
            try {
                // getMotorSpeedValueAsRpm() is the MEASURED speed. Never verify against
                // getSpeedReferenceValueAsRpm(): that reads back the commanded setpoint and reports
                // 0 the instant we write 0, while the motor is still spinning.
                // The sign convention for direction is unverified, so compare the magnitude.
                int rpm = drive.getMotorSpeedValueAsRpm();
                if (firstSpeed == null) {
                    firstSpeed = rpm;
                }
                lastSpeed = rpm;

                if (Math.abs(rpm) <= STOPPED_RPM_TOLERANCE) {
                    // One quiet sample arrives a single Modbus round trip after the write and can be
                    // a stale or defaulted register value, so require it to hold across a poll.
                    consecutiveStopped++;
                    if (consecutiveStopped >= REQUIRED_CONSECUTIVE_STOPPED_READINGS) {
                        return new VerifyOutcome(true, true, false, firstSpeed, lastSpeed);
                    }
                } else {
                    consecutiveStopped = 0;
                }
            } catch (Throwable t) {
                // A single failed read is not proof of anything, keep trying until the deadline,
                // but it does break the run of quiet samples.
                consecutiveStopped = 0;
                logger.debug("motor speed read failed during stop verification", t);
            }

            if (System.nanoTime() >= deadline) {
                return new VerifyOutcome(false, firstSpeed != null, false, firstSpeed, lastSpeed);
            }

            try {
                Thread.sleep(VERIFY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                // TestRunnerThread.stopThread() interrupts exactly this thread. Restore the flag and
                // report the abort, so a truncated verification is never read as "polled the full
                // deadline and the motor never stopped".
                Thread.currentThread().interrupt();
                return new VerifyOutcome(false, firstSpeed != null, true, firstSpeed, lastSpeed);
            }
        }
    }

    /**
     * What one verification pass observed.
     *
     * @param anyReadSucceeded at least one measured speed came back, so the drive is answering
     * @param interrupted      polling was cut short by a thread interrupt, not by the deadline
     */
    private record VerifyOutcome(boolean verified, boolean anyReadSucceeded, boolean interrupted,
                                 Integer firstSpeed, Integer lastSpeed) {

        /** Falling or not tells the incident log apart from a runaway. */
        String trend() {
            if (firstSpeed == null || lastSpeed == null || firstSpeed.equals(lastSpeed)) {
                return "";
            }
            int first = Math.abs(firstSpeed);
            int last = Math.abs(lastSpeed);
            String label = first - last >= COASTING_DROP_RPM ? " (still coasting: " : " (speed not falling: ";
            return label + firstSpeed + " -> " + lastSpeed + " rpm)";
        }
    }

    /**
     * A tier result plus the two flags that gate escalation. Both mean "do not tear down the USB":
     * responsive because the handle demonstrably works, interrupted because we stopped looking.
     */
    private record TierOutcome(SafeStopResult result, boolean responsive, boolean interrupted) {

        /** Nothing came back from the drive on this tier, so the result is not responsive either. */
        static TierOutcome unresponsive(SafeStopResult.Tier tier, Integer lastSpeed,
                                        boolean wasEnergized, String detail) {
            return new TierOutcome(
                    new SafeStopResult(tier, false, false, wasEnergized, lastSpeed, detail),
                    false, false);
        }
    }
}
