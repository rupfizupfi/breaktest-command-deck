package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.Device.LifecycleLockTimeoutException;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.ContractVersion;
import ch.rupfizupfi.deck.device.api.DeviceApi;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.StaleStopEpochException;
import ch.rupfizupfi.deck.device.frequencyinverter.DriveUnavailableException;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.security.CodeSource;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single entry point for stopping the motor. Every stop is verified against the drive's own
 * measured motor speed instead of being fire-and-forget, and escalates through three tiers.
 * <p>
 * The tiers, their command order and the backstops behind tier 3 are owned by
 * {@code doc/dev/backend/safety-and-recovery.md}. Escalation is driven
 * by drive responsiveness, never by the clock: tier 1 lets the motor coast, so a still-turning shaft
 * is not evidence of a broken USB handle.
 */
@Service
public class MotorSafetyController {
    private static final Logger logger = LoggerFactory.getLogger(MotorSafetyController.class);

    private static final int STOPPED_RPM_TOLERANCE = 5;
    private static final long VERIFY_DEADLINE_MS = 5000;
    private static final long VERIFY_POLL_INTERVAL_MS = 50;
    /**
     * One quiet sample arrives a single Modbus round trip after the write and can be a stale or
     * defaulted register value, so demand that standstill holds across a poll.
     */
    private static final int REQUIRED_CONSECUTIVE_STOPPED_READINGS = 2;
    /** Speed drop, in rpm, above which the shaft counts as measurably slowing down. */
    private static final int COASTING_DROP_RPM = 10;

    /** {@link #VERIFY_DEADLINE_MS} outside tests. */
    private final long verifyDeadlineMs;

    private final FrequencyInverterDevice frequencyInverter;

    /** Tier 2 opens its own handle, bypassing the one the device holds — see stopWithFreshHandle. */
    private final DriveProvider driveProvider;

    /**
     * Armed on the drive by every {@link #energize}; the drive stops itself after this much silence.
     * The gated driver that armed it feeds it from its own traffic.
     */
    private final Duration linkLossTimeout;

    /** Test seam: runs between a fenced call's latch check and its linked group's submit. */
    private volatile Runnable afterLatchCheck = () -> {
    };

    /** Set by safeStop, blocks any further energize until the next run clears it. */
    private final AtomicBoolean stopLatched = new AtomicBoolean(false);
    private volatile String stopReason;

    /**
     * True once this run enabled the drive output stage; a run that never did must not tear the USB
     * down on cleanup. Reset per run by clearStopLatch().
     */
    private final AtomicBoolean motorEnergized = new AtomicBoolean(false);

    /**
     * Result of the last completed safeStop of this run. safeStop is called several times per run,
     * so an escalation is replayed from here instead of repeated against the hardware.
     */
    private volatile SafeStopResult lastResult;

    /**
     * True when {@link #lastResult} is a tier 2 that timed out on the device's lifecycle lock: it
     * did nothing, so the next stop runs the ladder again rather than replaying it. Written with
     * {@link #lastResult} under {@link #latchLock}.
     */
    private boolean lastResultRetryable;

    /**
     * Stops between latching and recording their result, so the latch cannot clear mid-stop. A count,
     * not a flag: the measurement thread and the runner can stop concurrently, and the first to finish
     * must not release the guard for the other.
     */
    private int stopsInProgress = 0;

    /**
     * Guards the latch bookkeeping only: {@link #stopLatched}, {@link #stopReason},
     * {@link #motorEnergized}, {@link #lastResult}, {@link #lastResultRetryable} and
     * {@link #stopsInProgress}. Never held across a drive call, so it cannot delay a stop reaching
     * the hardware.
     */
    private final Object latchLock = new Object();

    @Autowired
    public MotorSafetyController(DeviceService deviceService, DriveProvider driveProvider,
                                 DriveLinkProperties driveLinkProperties) {
        this(deviceService, driveProvider, driveLinkProperties, VERIFY_DEADLINE_MS);
    }

    /** With a shorter verify deadline, so a test can run a tier over a silent drive quickly. */
    MotorSafetyController(DeviceService deviceService, DriveProvider driveProvider,
                          DriveLinkProperties driveLinkProperties, long verifyDeadlineMs) {
        this.verifyDeadlineMs = verifyDeadlineMs;
        this.frequencyInverter = deviceService.getFrequencyInverter();
        this.driveProvider = driveProvider;
        this.linkLossTimeout = driveLinkProperties.requireLinkLossTimeout();
    }

    /** Lets a test land a stop exactly between a fenced call's latch check and its submit. */
    void afterLatchCheck(Runnable seam) {
        this.afterLatchCheck = Objects.requireNonNull(seam);
    }

    public boolean isDriveAvailable() {
        return frequencyInverter.isDriveHandleOpen();
    }

    /**
     * Runs {@code action} against the drive, latched or not: the run teardown's second-ramp reset.
     * Refused through a plugin that does not gate its own calls, like every non-stop write, since
     * nothing serializes it against the info poll on such a handle.
     *
     * @throws IllegalStateException if the plugin does not gate its own calls; nothing is sent
     */
    public void withDrive(Consumer<Drive> action) {
        frequencyInverter.withDrive(drive -> {
            refuseUngated(drive, "command the drive");
            action.accept(drive);
        });
    }

    public <T> T queryDrive(Function<Drive, T> action) {
        return frequencyInverter.queryDrive(action);
    }

    /** True once safeStop() has been requested for the current run, until clearStopLatch(). */
    public boolean isStopLatched() {
        return stopLatched.get();
    }

    /** How this run's last stop went, or null if none completed. Nulled by {@link #clearStopLatch()}. */
    public SafeStopResult getLastStopResult() {
        return lastResult;
    }

    /**
     * Clears the latch. Called when a new test run starts.
     *
     * @throws IllegalStateException if a stop is still executing
     */
    public void clearStopLatch() {
        String reason;
        boolean wasLatched;

        // Refused under the latch lock: clearing mid-stop would let the next energize() re-enable
        // the drive, and resetting motorEnergized would make a later stop skip tiers 2 and 3.
        synchronized (latchLock) {
            if (stopsInProgress > 0) {
                throw new IllegalStateException(
                        "refusing to clear the motor safety stop latch, a stop is still executing: "
                                + stopReason);
            }

            reason = stopReason;
            stopReason = null;
            motorEnergized.set(false);
            lastResult = null;
            lastResultRetryable = false;
            wasLatched = stopLatched.getAndSet(false);
        }

        if (wasLatched) {
            logger.info("motor safety stop latch cleared, previous stop reason: {}", reason);
        }
    }

    /**
     * Latches without touching the drive, for a stop that arrives before anything was energized: every
     * later {@link #energize} of this run refuses, and the run's own teardown performs the stop.
     */
    public void latchStop(String reason) {
        synchronized (latchLock) {
            if (!stopLatched.getAndSet(true)) {
                stopReason = reason;
            }
        }
        logger.info("motor safety stop latched without a drive stop: {}", reason);
    }

    /**
     * Runs an energize action, but only if no safety stop has been requested for this run, and only
     * through a drive plugin that gates its own calls.
     * <p>
     * The arming of the drive's link-loss stop - the communication-error action, then the timeout
     * that triggers it - and the action run as one linked group fenced on the stop epoch read before
     * the latch check ({@link #runFenced}): a stop that latches after the check either rejects the
     * group before anything is sent or cuts it short, and is the last command the drive gets. Arming
     * is here rather than in each test type so no energize can skip it.
     * <p>
     * A zero rpm in the action is itself a STOP-lane write: it goes out, bumps the epoch and cuts
     * the group, so the energize fails without starting the motor. Fail-safe, and no program commands
     * 0 rpm (BL-156).
     *
     * @throws IllegalStateException if a safety stop has been requested or overtook the group, the
     *                               plugin does not gate its own calls, or the drive cannot arm its
     *                               link-loss stop
     */
    public void energize(Consumer<Drive> action) {
        frequencyInverter.withDrive(drive -> {
            refuseUngated(drive, "energize the motor");
            boolean ran;
            try {
                ran = runFenced(drive, linked -> {
                    // Ahead of motorEnergized: arming energizes nothing, so a refusal leaves nothing
                    // to stop.
                    armLinkLossStop(linked);
                    // Before the action, so a stop has a motor to catch even if the action throws
                    // halfway.
                    motorEnergized.set(true);
                    action.accept(linked);
                });
            } catch (StaleStopEpochException e) {
                throw new IllegalStateException("refusing to energize the motor, a safety stop overtook"
                        + " the energize (" + stopReason + "): " + e.getMessage(), e);
            }
            if (!ran) {
                throw new IllegalStateException(
                        "refusing to energize the motor, a safety stop was requested for this run: " + stopReason);
            }
        });
    }

    /**
     * Runs {@code action} as one linked group unless a stop is latched: the RunLoop's reversals and
     * phase setpoints. Fenced like {@link #energize}, so a call queued before a stop never runs after
     * it.
     *
     * @return false when latched or a stop overtook the group; a group cut short ran its earlier calls
     * @throws IllegalStateException if the plugin does not gate its own calls
     */
    public boolean withDriveUnlessLatched(Consumer<Drive> action) {
        return frequencyInverter.queryDrive(drive -> {
            refuseUngated(drive, "command the drive");
            try {
                return runFenced(drive, action);
            } catch (StaleStopEpochException e) {
                logger.info("drive command dropped, a stop overtook it: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * The epoch fence: read the stop epoch, then check the latch, then submit the group fenced on
     * that epoch. A stop latches before it bumps the epoch, so one that slips in after the read is
     * either seen by the latch check or rejects and cuts the group in the driver.
     *
     * @return false when latched and nothing was sent
     * @throws StaleStopEpochException if a stop overtook the group before or during it
     */
    private boolean runFenced(Drive drive, Consumer<Drive> group) {
        long epoch = drive.stopEpoch();
        if (stopLatched.get()) {
            return false;
        }
        afterLatchCheck.run();
        drive.runLinked(epoch, group);
        return true;
    }

    /**
     * Refused the same way a pre-1.2.0 plugin is refused for {@code enableLinkLossStop}: the deck
     * does not serialize drive calls, so a handle that relies on its caller to do so may carry stops
     * and reads but never a test.
     */
    private static void refuseUngated(Drive drive, String what) {
        if (drive.gating() == DriveGating.DRIVER_SERIALIZES) {
            return;
        }
        throw new IllegalStateException("refusing to " + what + ": the drive plugin "
                + drive.getClass().getName() + " (" + origin(drive.getClass()) + ") answers Drive#gating() "
                + drive.gating() + ", as every plugin built before device-api 1.5.0 does, and this deck"
                + " (device-api " + ContractVersion.VALUE + ") no longer serializes drive calls itself. It"
                + " can still stop and de-energize the drive, never run a test. Rebuild the driver plugin"
                + " against this contract and replace its jar (doc/dev/backend/driver-jars.md).");
    }

    /** Where {@code type} was loaded from, and the contract its plugin reported building against. */
    private static String origin(Class<?> type) {
        URL location = location(type);
        String builtAgainst = DeviceApi.reportedBuiltAgainst().entrySet().stream()
                .filter(reported -> location != null && location.equals(location(reported.getKey())))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("an unreported version");
        return "loaded from " + (location == null ? "an unknown location" : location)
                + ", built against device-api " + builtAgainst;
    }

    private static URL location(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    /**
     * Without the timeout the action is inert (the drive's default is 0, watchdog off), so a drive
     * that cannot take it is refused rather than run unguarded.
     */
    private void armLinkLossStop(Drive drive) {
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        try {
            drive.enableLinkLossStop(linkLossTimeout);
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("refusing to energize the motor, the drive cannot stop "
                    + "itself on a lost link: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the motor and verifies it stood still. Never throws, since callers use it from finally
     * blocks and emergency paths. Idempotent: an escalation already recorded for this run is replayed,
     * except one whose tier 2 timed out on the device's lifecycle lock and so did nothing (I2).
     */
    public SafeStopResult safeStop(String reason) {
        boolean alreadyLatched;
        // Latched before the stop bumps the drive's stop epoch, so a fenced call that read the epoch
        // earlier either sees the latch or has its group rejected (runFenced).
        synchronized (latchLock) {
            stopReason = reason;
            alreadyLatched = stopLatched.getAndSet(true);
            stopsInProgress++;
        }

        try {
            return performStop(reason, alreadyLatched);
        } finally {
            // A stranded count would refuse every later clearStopLatch() and so every new run.
            synchronized (latchLock) {
                stopsInProgress--;
            }
        }
    }

    private SafeStopResult performStop(String reason, boolean alreadyLatched) {
        SafeStopResult previous;
        boolean previousRetryable;
        synchronized (latchLock) {
            previous = lastResult;
            previousRetryable = lastResultRetryable;
        }
        if (alreadyLatched && previous != null && previous.tier() != SafeStopResult.Tier.EXISTING_HANDLE
                && !previousRetryable) {
            // Only escalations past tier 1 are replayed; re-checking over a working handle is cheap.
            logger.info("safeStop({}) not repeated, a stop for this run already escalated to tier {}: {}",
                    reason, previous.tier(), previous.detail());
            return previous;
        }

        // Read once so a concurrent energize() cannot make results of the same stop disagree.
        boolean wasEnergized = motorEnergized.get();
        if (wasEnergized) {
            logger.warn("safeStop requested: {}", reason);
        } else {
            logger.info("safeStop requested for a run that never energized the motor: {}", reason);
        }

        SafeStopResult result;
        boolean nothingToStop = false;
        boolean retryable = false;
        try {
            var tier1 = stopWithExistingHandle(wasEnergized);
            result = tier1.result();
            // Neither a responsive drive (coasting) nor an interrupt proves a dead handle.
            boolean handleSuspect = !result.verified() && !tier1.responsive() && !tier1.interrupted();

            if (tier1.wedged()) {
                // A fresh handle beside the transaction still in flight would be a second session
                // on the bus (OQ-50): nothing in software is left to try.
                result = escalateToOperator(result.motorSpeedRpm(), false, wasEnergized,
                        result.detail(), "a fresh handle beside the transaction still in flight on the"
                                + " existing one would be a second session on the drive (OQ-50)", false);
            } else if (handleSuspect && !wasEnergized) {
                // Nothing to talk to and no motor to chase.
                nothingToStop = true;
                result = new SafeStopResult(result.tier(), result.verified(), result.driveResponsive(),
                        wasEnergized, result.motorSpeedRpm(),
                        "no stop was needed, the drive was never energized during this run ("
                                + result.detail() + ")");
            } else if (handleSuspect) {
                String tier1Detail = result.detail();
                var tier2 = stopWithFreshHandle(result.motorSpeedRpm(), wasEnergized);
                result = tier2.result();
                retryable = tier2.retryable();
                if (!result.verified()) {
                    result = escalateToOperator(result.motorSpeedRpm(), tier2.responsive(),
                            wasEnergized, tier1Detail, result.detail(), !tier2.notAttempted());
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

        synchronized (latchLock) {
            lastResult = result;
            lastResultRetryable = retryable;
        }
        return result;
    }

    /** Tier 1: the handle the running test already holds, so no USB re-enumeration is needed. */
    private TierOutcome stopWithExistingHandle(boolean wasEnergized) {
        var commandProblems = new AtomicReference<>("");
        var outcome = new AtomicReference<VerifyOutcome>();

        try {
            frequencyInverter.withDrive(drive -> {
                commandProblems.set(commandStop(drive));
                outcome.set(verifyStopped(drive));
            });
        } catch (DriveDeadlineException e) {
            logger.error("safeStop could not reach the drive within the stop deadline: {}", e.getMessage());
            return TierOutcome.wedged(wasEnergized, "existing handle unreachable, the stop missed its "
                    + DriveLane.STOP_DEADLINE_MILLIS + " ms deadline behind a transaction still in flight"
                    + " (it goes out once that transaction ends): " + e.getMessage());
        } catch (DriveUnavailableException e) {
            // No handle open: tier 1 does not apply; not a hardware fault.
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
     * <p>
     * Waits for the device's lifecycle lock at most
     * {@link FrequencyInverterDevice#RUN_EXCLUSIVE_WAIT_MS}: past it tier 2 is not attempted and
     * touches no handle, since anything it opened or closed outside the lock could be a second
     * session (OQ-50). That outcome is retryable: the holder may be a {@code connect()} that leaves
     * a working handle for the next stop's tier 1.
     */
    private TierOutcome stopWithFreshHandle(Integer previousSpeed, boolean wasEnergized) {
        var tier = new AtomicReference<TierOutcome>();
        try {
            // Exclusive from the old handle's close to the lost mark: a Device.connect() anywhere in
            // between would open a second session on one drive, or have its session closed under it
            // (OQ-50); one arriving after the mark re-opens rather than reuses. The lost mark sits
            // outside the tier's own catch, so even a failure there reaches safeStop's last-resort
            // result.
            frequencyInverter.runExclusive(() -> {
                try {
                    tier.set(stopWithFreshHandleExclusively(previousSpeed, wasEnergized));
                } finally {
                    frequencyInverter.invalidateConnection();
                }
            });
        } catch (LifecycleLockTimeoutException e) {
            return TierOutcome.lockTimedOut(previousSpeed, wasEnergized, e.getMessage()
                    + ", so no fresh handle was opened and nothing was closed or marked lost outside it"
                    + " (OQ-50); the next safeStop of this run retries");
        }
        return tier.get();
    }

    private TierOutcome stopWithFreshHandleExclusively(Integer previousSpeed, boolean wasEnergized) {
        var commandProblems = new AtomicReference<>("");
        var outcome = new AtomicReference<VerifyOutcome>();
        var portStillHeld = new AtomicBoolean();

        try {
            // The device's handle is dropped first, so the info poll and the run's calls fail fast
            // instead of reaching the old session. Bounded by the contract's close bound. Pending:
            // the old handle's transfer is still in flight, and a fresh handle beside it would be a
            // second session - and would let that transfer, an energizing write perhaps, land after
            // the fresh stop (BL-135).
            if (frequencyInverter.closeDriveHandle() == FrequencyInverterDevice.HandleClose.PENDING) {
                portStillHeld.set(true);
            } else {
                Drive fresh = null;
                try {
                    fresh = driveProvider.open();
                    commandProblems.set(commandStop(fresh));
                    outcome.set(verifyStopped(fresh));
                } finally {
                    if (fresh != null) {
                        try {
                            fresh.close();
                        } catch (DriveClosePendingException e) {
                            // Its own transfer outlasted the bound: the next connect must wait for it.
                            frequencyInverter.parkPendingClose(fresh);
                        } catch (Throwable ignored) {
                            // best effort, the stop result must not depend on a clean close
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("stop over a freshly enumerated drive handle failed", t);
            var partial = outcome.get();
            Integer lastSpeed = partial == null || partial.lastSpeed() == null
                    ? previousSpeed : partial.lastSpeed();
            return TierOutcome.unresponsive(SafeStopResult.Tier.FRESH_HANDLE, lastSpeed, wasEnergized,
                    "fresh handle failed: " + t);
        }

        if (portStillHeld.get()) {
            return TierOutcome.notAttempted(previousSpeed, wasEnergized, "the existing handle's transaction"
                    + " is still in flight after the " + DriveLane.STOP_DEADLINE_MILLIS + " ms close bound,"
                    + " so no fresh handle was opened beside it (OQ-50); the stops queued on it go out"
                    + " when that transaction ends, and its port is closed no later than the next"
                    + " connect's retry of that close");
        }

        // Left without a handle, so every later withDrive fails fast with DriveUnavailableException.
        return describe(SafeStopResult.Tier.FRESH_HANDLE, "fresh handle",
                commandProblems.get(), outcome.get(), previousSpeed, wasEnergized);
    }

    /**
     * Tier 3: nothing in software could confirm a stop, so it only logs. The hardware kill line it
     * would pull is a paired task in {@code safety-and-recovery.md}.
     */
    private SafeStopResult escalateToOperator(Integer lastSpeed, boolean tier2Responsive,
                                              boolean wasEnergized, String tier1Detail,
                                              String tier2Detail, boolean tier2Ran) {
        // Name every tier and what it did, so a post-incident trace reads the whole ladder from one line.
        String detail = "tier 1 (EXISTING_HANDLE) ran and reported: " + tier1Detail
                + (tier2Ran
                ? " | tier 2 (FRESH_HANDLE) ran, re-enumerated the drive and reported: " + tier2Detail
                + " | tier 3 (operator escalation): both software stop tiers failed"
                : " | tier 2 (FRESH_HANDLE) not attempted: " + tier2Detail
                + " | tier 3 (operator escalation): tier 1 failed and tier 2 could not run")
                + ", motor may still be "
                + "running - USE THE PHYSICAL E-STOP. Do not count on the drive stopping itself: its "
                + "link-loss stop, armed at energize, removes general enable only once no request has "
                + "reached it for " + linkLossTimeout.toMillis() + " ms, so a deck that still reaches "
                + "the drive keeps it from firing, and it never reacts to loss of the load cell.";
        logger.error("safeStop escalated to the operator after {}. Last measured motor speed: {} rpm. {}",
                tier2Ran ? "both software tiers ran" : "tier 1 failed and tier 2 could not run",
                lastSpeed, detail);
        // Tier 2's responsiveness, so the caller can tell a talking drive from a silent one.
        return new SafeStopResult(SafeStopResult.Tier.OPERATOR_ESCALATION, false, tier2Responsive,
                wasEnergized, lastSpeed, detail);
    }

    /**
     * Turns a completed tier into its result plus the responsiveness verdict that decides whether
     * the next tier is allowed to run.
     */
    private TierOutcome describe(SafeStopResult.Tier tier, String label, String commandProblems,
                                 VerifyOutcome outcome, Integer fallbackSpeed,
                                 boolean wasEnergized) {
        // Responsive: the drive read back the de-energize and answered a speed read. Judged by the
        // read-back, not by the first write: a write lost to one dropped frame and completed by a
        // re-send is a working handle, and tearing it down would leave the run unresumable.
        boolean responsive = outcome.deEnergizeConfirmed() && outcome.anyReadSucceeded();
        Integer lastSpeed = outcome.lastSpeed() == null ? fallbackSpeed : outcome.lastSpeed();

        var detail = new StringBuilder(commandProblems);
        detail.append("stop commands sent over the ").append(label);
        if (outcome.deEnergizeConfirmed()) {
            detail.append(", general enable and start read back clear");
            if (outcome.reissues() > 0) {
                detail.append(" after ").append(outcome.reissues()).append(" re-sent de-energize write(s)");
            }
        } else {
            detail.append(", general enable and start NOT read back clear despite ")
                    .append(outcome.reissues()).append(" re-sent de-energize write(s)");
        }

        if (outcome.verified()) {
            detail.append(", standstill confirmed by ").append(REQUIRED_CONSECUTIVE_STOPPED_READINGS)
                    .append(" consecutive readings within ").append(STOPPED_RPM_TOLERANCE).append(" rpm");
        } else if (outcome.interrupted()) {
            detail.append(", verification aborted by interrupt (operator stop) ")
                    .append(outcome.anyReadSucceeded()
                            ? "at " + outcome.lastSpeed() + " rpm"
                            : "before any speed reading came back")
                    .append(outcome.trend());
        } else if (outcome.anyReadSucceeded() && !outcome.deEnergizeConfirmed()
                && Math.abs(outcome.lastSpeed()) <= STOPPED_RPM_TOLERANCE) {
            detail.append(", motor reads ").append(outcome.lastSpeed())
                    .append(" rpm but the drive never confirmed it is de-energized within the ")
                    .append(verifyDeadlineMs).append(" ms verify deadline");
        } else if (outcome.anyReadSucceeded()) {
            detail.append(", motor still turning at ").append(outcome.lastSpeed())
                    .append(" rpm when the ").append(verifyDeadlineMs)
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
     * Sends the stop with {@link Drive#stop()}: start and general enable cleared and the speed
     * reference zero, direction and ramp untouched, so it never ramps or reverses
     * (safety-and-recovery.md). One telegram at the head of the STOP lane on a gated CFW11 driver;
     * on an older plugin the contract's fallback, the one-write de-energize then the zero reference,
     * each going out whatever the other did. Returns a description of whatever failed;
     * {@link #verifyStopped} re-sends the de-energize until the drive reads it back.
     *
     * @throws DriveDeadlineException if the stop missed its deadline: the handle is wedged, and tier 1
     *                                sends nothing more over it
     */
    private String commandStop(Drive drive) {
        try {
            drive.stop();
            return "";
        } catch (DriveDeadlineException e) {
            throw e;
        } catch (Throwable t) {
            logger.warn("the stop command failed during safeStop", t);
            return "stop command failed: " + t + "; ";
        }
    }

    /** General enable and start off in one write, every other control bit untouched. */
    private static void deEnergize(Drive drive) {
        drive.setControlParameters(false, false, null, null, null);
    }

    /** True only for a control-word read-back with general enable and start both present and clear. */
    private static boolean readsDeEnergized(Map<String, Boolean> controlParameters) {
        return controlParameters != null
                && Boolean.FALSE.equals(controlParameters.get("generalEnable"))
                && Boolean.FALSE.equals(controlParameters.get("start"));
    }

    /**
     * Polls the drive until the measured motor speed has been within tolerance of zero for
     * {@link #REQUIRED_CONSECUTIVE_STOPPED_READINGS} consecutive samples AND the control word has
     * read back general enable and start clear, or the deadline expires.
     * <p>
     * Until that read-back, every poll re-sends the de-energize write: a stop whose first write was
     * lost to one dropped frame is completed here instead of being handed to tier 2, which would tear
     * down a working handle. Once confirmed, the control word is not read again.
     */
    private VerifyOutcome verifyStopped(Drive drive) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(verifyDeadlineMs);
        Integer firstSpeed = null;
        Integer lastSpeed = null;
        int consecutiveStopped = 0;
        boolean deEnergizeConfirmed = false;
        int reissues = 0;

        while (true) {
            try {
                // Measured speed, never getSpeedReferenceValueAsRpm(), which reads back the setpoint.
                // Magnitude, because the direction sign convention is unverified.
                int rpm = drive.getMotorSpeedValueAsRpm();
                if (firstSpeed == null) {
                    firstSpeed = rpm;
                }
                lastSpeed = rpm;

                if (Math.abs(rpm) <= STOPPED_RPM_TOLERANCE) {
                    consecutiveStopped++;
                } else {
                    consecutiveStopped = 0;
                }
            } catch (Throwable t) {
                // Keep trying until the deadline, but a failed read breaks the run of quiet samples.
                consecutiveStopped = 0;
                logger.debug("motor speed read failed during stop verification", t);
            }

            if (!deEnergizeConfirmed) {
                deEnergizeConfirmed = confirmDeEnergized(drive);
                if (!deEnergizeConfirmed) {
                    reissues++;
                    try {
                        deEnergize(drive);
                    } catch (Throwable t) {
                        logger.debug("re-sending the de-energize write failed during stop verification", t);
                    }
                }
            }

            if (deEnergizeConfirmed && consecutiveStopped >= REQUIRED_CONSECUTIVE_STOPPED_READINGS) {
                return new VerifyOutcome(true, true, false, true, reissues, firstSpeed, lastSpeed);
            }

            if (System.nanoTime() >= deadline) {
                return new VerifyOutcome(false, firstSpeed != null, false, deEnergizeConfirmed, reissues,
                        firstSpeed, lastSpeed);
            }

            try {
                Thread.sleep(VERIFY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                // TestRunnerThread.stopThread() interrupts this thread; report it as interrupted,
                // not as a motor that never stopped.
                Thread.currentThread().interrupt();
                return new VerifyOutcome(false, firstSpeed != null, true, deEnergizeConfirmed, reissues,
                        firstSpeed, lastSpeed);
            }
        }
    }

    /** One control-word read; a failed read is "not confirmed", never an exception. */
    private static boolean confirmDeEnergized(Drive drive) {
        try {
            return readsDeEnergized(drive.getControlParameters());
        } catch (Throwable t) {
            logger.debug("control word read failed during stop verification", t);
            return false;
        }
    }

    /**
     * What one verification pass observed.
     *
     * @param anyReadSucceeded    at least one measured speed came back, so the drive is answering
     * @param interrupted         polling was cut short by a thread interrupt, not by the deadline
     * @param deEnergizeConfirmed the control word read back general enable and start clear
     * @param reissues            de-energize writes re-sent before that read-back, or until the end
     */
    private record VerifyOutcome(boolean verified, boolean anyReadSucceeded, boolean interrupted,
                                 boolean deEnergizeConfirmed, int reissues,
                                 Integer firstSpeed, Integer lastSpeed) {

        /** Whether the speed was falling, to tell coasting from a runaway in the incident log. */
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
     * A tier result plus the flags that gate escalation: responsive or interrupted means "do not tear
     * down the USB"; wedged (tier 1) and notAttempted (tier 2) mean tier 2 sent nothing; retryable
     * means the next stop runs the ladder again instead of replaying this result.
     */
    private record TierOutcome(SafeStopResult result, boolean responsive, boolean interrupted,
                               boolean wedged, boolean notAttempted, boolean retryable) {

        TierOutcome(SafeStopResult result, boolean responsive, boolean interrupted) {
            this(result, responsive, interrupted, false, false, false);
        }

        /** Tier 2 sent nothing: the old handle still holds the port, so no fresh one was opened. */
        static TierOutcome notAttempted(Integer lastSpeed, boolean wasEnergized, String detail) {
            return new TierOutcome(
                    new SafeStopResult(SafeStopResult.Tier.FRESH_HANDLE, false, false, wasEnergized,
                            lastSpeed, detail),
                    false, false, false, true, false);
        }

        /** Tier 2 sent nothing: the device's lifecycle lock was not free within its bound. */
        static TierOutcome lockTimedOut(Integer lastSpeed, boolean wasEnergized, String detail) {
            return new TierOutcome(
                    new SafeStopResult(SafeStopResult.Tier.FRESH_HANDLE, false, false, wasEnergized,
                            lastSpeed, detail),
                    false, false, false, true, true);
        }

        static TierOutcome unresponsive(SafeStopResult.Tier tier, Integer lastSpeed,
                                        boolean wasEnergized, String detail) {
            return new TierOutcome(
                    new SafeStopResult(tier, false, false, wasEnergized, lastSpeed, detail),
                    false, false);
        }

        /**
         * The stop missed the STOP lane's deadline behind a transaction still in flight
         * ({@link DriveDeadlineException}). On a gated handle it is still queued and goes out once
         * that transaction ends; tier 1 sent nothing more and read nothing back.
         */
        static TierOutcome wedged(boolean wasEnergized, String detail) {
            return new TierOutcome(
                    new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, false, false, wasEnergized,
                            null, detail),
                    false, false, true, false, false);
        }
    }
}
