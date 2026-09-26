package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.RunStatus;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes the books on runs that a crash left open. Every row still in a non-terminal
 * {@link RunStatus} belongs to a process that is no longer alive — this one just started — so it is
 * swept to {@link RunStatus#ABORTED} with a marker saying why, and, if the bench is real, the drive
 * is de-energized in case the crash left it enabled.
 * <p>
 * On {@link ApplicationReadyEvent} rather than an {@code ApplicationRunner}: a runner executes
 * before the context is ready to serve, so a slow or wedged drive open would hold the whole
 * application out of service.
 */
@Component
public class StartupRecoveryRunner {

    private static final Logger logger = LoggerFactory.getLogger(StartupRecoveryRunner.class);

    private static final String ORPHAN_ABORT_REASON =
            "the process running this test did not shut down cleanly; status swept on the next boot";

    private final TestResultRepository repository;
    private final ObjectMapper objectMapper;
    private final HardwareModeInfo hardwareModeInfo;

    /**
     * A driver jar absent at launch means no provider bean at all ({@code loader.path}
     * governs this), and boot recovery must not be the thing that turns that into a failure.
     */
    private final ObjectProvider<DriveProvider> driveProviderProvider;

    public StartupRecoveryRunner(TestResultRepository repository,
                                 ObjectMapper objectMapper,
                                 HardwareModeInfo hardwareModeInfo,
                                 ObjectProvider<DriveProvider> driveProviderProvider) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.hardwareModeInfo = hardwareModeInfo;
        this.driveProviderProvider = driveProviderProvider;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        int orphans = abortOrphanRuns();
        if (orphans == 0) {
            return;
        }

        // Everything below is a defensive hardware touch, gated hard: the database sweep above is
        // the part that must always happen, and it has already happened by this point.
        if (hardwareModeInfo.isSimulated()) {
            logger.info("skipping the boot de-energize, hardware mode is {}", hardwareModeInfo.modeName());
            return;
        }

        DriveProvider driveProvider = driveProviderProvider.getIfAvailable();
        if (driveProvider == null) {
            logger.warn("{} interrupted run(s) recovered, but no DriveProvider bean is present, so the"
                    + " drive cannot be de-energized from here", orphans);
            return;
        }

        // Detached and daemon: DriveProvider.open() enumerates USB and can wedge indefinitely on a
        // bench with no drive attached, and a wedged native call cannot be interrupted. Daemon-ness
        // is therefore the only real bound available — it keeps the wedge out of both the readiness
        // path and JVM shutdown.
        Thread.ofPlatform()
                .daemon()
                .name("startup-drive-deenergize")
                .start(() -> deEnergize(driveProvider, orphans));
    }

    /**
     * Cheap and inline: a handful of rows at most, and the drive work below is allowed to fail
     * without taking this with it.
     *
     * @return how many rows were swept
     */
    private int abortOrphanRuns() {
        List<TestResult> orphans;
        try {
            orphans = repository.findByRunStatusIn(List.of(RunStatus.IN_PROGRESS, RunStatus.INTERRUPTED));
        } catch (Exception e) {
            logger.error("could not query for interrupted runs, skipping startup recovery", e);
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
                // One unsaveable row must not strand the others, nor the de-energize that follows.
                logger.error("could not mark interrupted test result {} as aborted", orphan.getId(), e);
            }
        }
        return swept;
    }

    /**
     * Merges the marker into the existing {@code interruptionLog} object instead of concatenating
     * onto it, so the column stays one parseable JSON document. Content that does not parse as an
     * object is preserved verbatim under {@code previousLog} rather than discarded — this runs
     * against rows written by an unknown earlier version.
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
     * Deliberately NOT {@code MotorSafetyController.safeStop()}. Two reasons, both fatal here:
     * safeStop keys its whole decision on {@code motorEnergized}, which is false on a singleton this
     * process has just constructed, so it would classify a boot stop as "nothing to stop" and touch
     * no hardware at all; and its tier 2 path drops the device's connection bookkeeping and tears
     * down a USB handle, which is not a thing to do to a freshly booted process.
     * <p>
     * Best effort throughout: this is a precaution against a state nobody has observed, so a bench
     * that is simply switched off must produce warnings, not a failed boot.
     */
    private void deEnergize(DriveProvider driveProvider, int orphans) {
        logger.warn("{} interrupted run(s) recovered, de-energizing the drive in case the crash left"
                + " it enabled", orphans);

        Drive drive;
        try {
            drive = driveProvider.open();
        } catch (Throwable t) {
            logger.warn("could not open the drive for the boot de-energize", t);
            return;
        }

        try {
            // Same safety order as MotorSafetyController#commandStop: general enable first, which
            // drops the output stage and lets the motor coast. Never a ramp stop (it keeps the drive
            // loading the sample), never a reversal.
            try {
                drive.setGeneralEnable(false);
            } catch (Throwable t) {
                logger.warn("setGeneralEnable(false) failed during the boot de-energize", t);
            }
            try {
                drive.setSpeedReferenceValueAsRpm(0);
            } catch (Throwable t) {
                logger.warn("setSpeedReferenceValueAsRpm(0) failed during the boot de-energize", t);
            }
            try {
                drive.setStart(false);
            } catch (Throwable t) {
                logger.warn("setStart(false) failed during the boot de-energize", t);
            }
        } finally {
            try {
                drive.close();
            } catch (Throwable t) {
                // The handle must not stay open past this: the run that follows opens its own.
                logger.warn("closing the boot de-energize drive handle failed", t);
            }
        }
    }
}
