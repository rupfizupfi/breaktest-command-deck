package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.RunStatus;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Closes the books on runs that a crash left open, and on a real bench switches the motor off.
 * <p>
 * Every row still in a non-terminal {@link RunStatus} belongs to a dead process, so it is swept to
 * {@link RunStatus#ABORTED} with a marker. Independently of that sweep, every real-mode boot
 * de-energizes the drive: a crash before the first status save leaves no row behind, and a drive
 * left enabled is just as live without one. The one exception is a drive that reports
 * {@link ControlSource#REMOTE}: on this bench that is the push-button control board, so an operator
 * may be running it by hand and it is left alone (owner decision, OQ-87). LOCAL is the deck's own
 * USB control and is de-energized.
 * <p>
 * The de-energize goes through the {@link FrequencyInverterDevice}'s own reference-counted handle,
 * never a private handle (H-13), and {@link BootDeEnergizeGate} holds every Start back until it is
 * done, so a run can neither share the bus with it nor be de-energized mid-setup.
 * <p>
 * On {@link ApplicationReadyEvent} rather than an {@code ApplicationRunner}, so a wedged drive open
 * cannot hold the application out of service.
 */
@Component
public class StartupRecoveryRunner {

    private static final Logger logger = LoggerFactory.getLogger(StartupRecoveryRunner.class);

    /** False switches the boot de-energize off, for a real-mode context test that must touch no hardware. */
    public static final String DE_ENERGIZE_PROPERTY = "deck.startup.de-energize";

    private static final String ORPHAN_ABORT_REASON =
            "the process running this test did not shut down cleanly; status swept on the next boot";

    private final TestResultRepository repository;
    private final ObjectMapper objectMapper;
    private final HardwareModeInfo hardwareModeInfo;
    private final Supplier<FrequencyInverterDevice> frequencyInverter;
    private final boolean deEnergizeEnabled;
    private final Function<Drive, ControlSource> controlSourceProbe;

    /** Null unless this boot owes a de-energize; taken at construction so no Start slips ahead. */
    private final @Nullable CountDownLatch gateToken;

    /** The device is looked up lazily, so a context without a usable drive provider still sweeps. */
    @Autowired
    public StartupRecoveryRunner(TestResultRepository repository,
                                 ObjectMapper objectMapper,
                                 HardwareModeInfo hardwareModeInfo,
                                 ObjectProvider<DeviceService> deviceServiceProvider,
                                 @Value("${" + DE_ENERGIZE_PROPERTY + ":true}") boolean deEnergizeEnabled) {
        this(repository, objectMapper, hardwareModeInfo, () -> {
            DeviceService deviceService = deviceServiceProvider.getIfAvailable();
            return deviceService == null ? null : deviceService.getFrequencyInverter();
        }, deEnergizeEnabled, Drive::readControlSource);
    }

    StartupRecoveryRunner(TestResultRepository repository,
                          ObjectMapper objectMapper,
                          HardwareModeInfo hardwareModeInfo,
                          Supplier<FrequencyInverterDevice> frequencyInverter,
                          boolean deEnergizeEnabled,
                          Function<Drive, ControlSource> controlSourceProbe) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.hardwareModeInfo = hardwareModeInfo;
        this.frequencyInverter = frequencyInverter;
        this.deEnergizeEnabled = deEnergizeEnabled;
        this.controlSourceProbe = controlSourceProbe;
        // At construction, before the web server takes requests: a Start between the server coming
        // up and ApplicationReadyEvent would otherwise energize ahead of the de-energize.
        this.gateToken = deEnergizeEnabled && !hardwareModeInfo.isSimulated() ? BootDeEnergizeGate.begin() : null;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        CountDownLatch token = this.gateToken;
        boolean handedOff = false;
        try {
            // The sweep is the part that must always happen, so it comes first and decides nothing.
            int orphans = abortOrphanRuns();
            if (orphans > 0) {
                logger.warn("{} interrupted run(s) recovered", orphans);
            }

            if (token == null) {
                logger.info("skipping the boot de-energize: {}", deEnergizeEnabled
                        ? "hardware mode is " + hardwareModeInfo.modeName()
                        : DE_ENERGIZE_PROPERTY + " is false");
                return;
            }

            FrequencyInverterDevice device = frequencyInverter.get();
            if (device == null) {
                logger.warn("no frequency inverter device is available, the drive cannot be"
                        + " de-energized at boot");
                return;
            }

            // Daemon: DriveProvider.open() can wedge uninterruptibly in native code with no drive
            // attached, and this keeps that out of readiness and JVM shutdown.
            Thread.ofPlatform()
                    .daemon()
                    .name("startup-drive-deenergize")
                    .start(() -> {
                        try {
                            deEnergize(device);
                        } finally {
                            BootDeEnergizeGate.complete(token);
                        }
                    });
            handedOff = true;
        } catch (Throwable t) {
            logger.warn("could not start the boot de-energize", t);
        } finally {
            // A path that never reached the thread must not hold every Start back for good.
            if (token != null && !handedOff) {
                BootDeEnergizeGate.complete(token);
            }
        }
    }

    /**
     * @return how many rows were swept
     */
    private int abortOrphanRuns() {
        List<TestResult> orphans;
        try {
            orphans = repository.findByRunStatusIn(List.of(RunStatus.IN_PROGRESS, RunStatus.INTERRUPTED));
        } catch (Exception e) {
            logger.error("could not query for interrupted runs, skipping the orphan sweep", e);
            return 0;
        }

        int swept = 0;
        for (TestResult orphan : orphans) {
            try {
                RunStatus previous = orphan.runStatus;
                orphan.runStatus = RunStatus.ABORTED;
                orphan.interruptionLog = appendOrphanMarker(orphan.interruptionLog, previous);
                repository.save(orphan);
                swept++;
                logger.warn("test result {} was left in {} by a process that did not shut down cleanly,"
                        + " marked ABORTED", orphan.getId(), previous);
            } catch (Exception e) {
                // One unsaveable row must not strand the others.
                logger.error("could not mark interrupted test result {} as aborted", orphan.getId(), e);
            }
        }
        return swept;
    }

    /**
     * Merges the marker into the existing {@code interruptionLog} object instead of concatenating
     * onto it, so the column stays one parseable JSON document. Content that does not parse as an
     * object is preserved verbatim under {@code previousLog}.
     */
    private String appendOrphanMarker(@Nullable String existing, @Nullable RunStatus previous) {
        Map<String, Object> root = null;
        if (existing != null && !existing.isBlank()) {
            try {
                root = objectMapper.readValue(existing, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                logger.debug("interruptionLog is not a JSON object, keeping it verbatim", e);
            }
        }
        if (root == null) {
            root = new LinkedHashMap<>();
            if (existing != null && !existing.isBlank()) {
                root.put("previousLog", existing);
            }
        }

        var marker = new LinkedHashMap<String, Object>();
        marker.put("event", "orphan-abort");
        marker.put("at", System.currentTimeMillis());
        marker.put("previousStatus", previous == null ? null : previous.name());
        marker.put("reason", ORPHAN_ABORT_REASON);

        List<Object> events = new ArrayList<>();
        if (root.get("events") instanceof List<?> previousEvents) {
            events.addAll(previousEvents);
        }
        events.add(marker);
        root.put("events", events);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Not {@code MotorSafetyController.safeStop()}: on a fresh process {@code motorEnergized} is
     * false, so it would treat this as "nothing to stop", and its tier 2 tears down a USB handle.
     * <p>
     * Through the device's own reference, so a holder that already has the handle shares it rather
     * than a second session opening on the bus. Best effort: a bench that is switched off must
     * produce warnings, not a failed boot.
     */
    private void deEnergize(FrequencyInverterDevice device) {
        try {
            device.connect();
        } catch (Throwable t) {
            logger.warn("could not open the drive for the boot de-energize", t);
            return;
        }

        try {
            device.withDrive(this::deEnergizeUnlessRemote);
        } catch (Throwable t) {
            logger.warn("boot de-energize failed", t);
        } finally {
            try {
                device.disconnect();
            } catch (Throwable t) {
                logger.warn("releasing the boot de-energize's drive reference failed", t);
            }
        }
    }

    /**
     * The probe is one read-only drive request ahead of the stop, bounded like it by the driver's own
     * deadline, so it adds one request's worth to how long {@link BootDeEnergizeGate} stays closed.
     * Only an affirmative {@link ControlSource#REMOTE} skips: an unknown source de-energizes, as every
     * boot did before the drive could report it.
     * <p>
     * {@link Drive#stop()}, as {@code MotorSafetyController#commandStop} sends it: start and general
     * enable cleared and the speed reference zero, never a ramp or a reversal. One telegram on a gated
     * driver; an older plugin runs the contract's two-call fallback, which this boot may send although
     * the deck refuses to energize through such a plugin - it is idempotent, and the poll only reads.
     */
    private void deEnergizeUnlessRemote(Drive drive) {
        ControlSource source;
        String unknownBecause;
        try {
            source = controlSourceProbe.apply(drive);
            unknownBecause = "the drive plugin answered UNKNOWN, as one built against device-api older"
                    + " than 1.3.0 always does";
        } catch (Throwable t) {
            logger.warn("could not read the drive's control source", t);
            source = ControlSource.UNKNOWN;
            unknownBecause = "reading it failed: " + t;
        }
        if (source == null) {
            source = ControlSource.UNKNOWN;
            unknownBecause = "the drive plugin answered null";
        }

        switch (source) {
            case REMOTE -> {
                logger.warn("boot de-energize SKIPPED: the drive is under REMOTE control (button control"
                        + " board), so it was left as is - an operator may be running it; no command was"
                        + " sent to the drive");
                return;
            }
            case LOCAL -> logger.info("de-energizing the drive at boot, it is under LOCAL (USB/deck) control");
            case UNKNOWN -> logger.warn("de-energizing the drive at boot although its control source is"
                    + " unknown - {}; a drive under the button board is not exempted", unknownBecause);
        }

        try {
            drive.stop();
        } catch (DriveDeadlineException e) {
            logger.warn("boot de-energize missed its deadline behind a transaction still in flight; the"
                    + " stop stays queued and goes out once that transaction ends: {}", e.getMessage());
        } catch (Throwable t) {
            logger.warn("the stop failed during the boot de-energize", t);
        }
    }
}
