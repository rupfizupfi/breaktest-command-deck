package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.Application;
import ch.rupfizupfi.deck.api.services.SuckService;
import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.simulated.SimulatedBench;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedFaultSwitches;
import ch.rupfizupfi.deck.device.simulated.SimulatedLoadCellStreamProvider;
import ch.rupfizupfi.deck.rig.RigLimits;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * Step 1 of the OQ-64 migration ({@code doc/dev/backend/testrunner-architecture.md}): the
 * characterisation harness. Boots the whole deck on {@code deck.hardware.mode=simulated} and runs
 * real runs end to end - {@link TestRunnerThread}, the run loop, the three test programs, the
 * measurement loop, {@link MotorSafetyController}, the reconnector - against the simulated bench, with nothing
 * mocked on the control path.
 * <p>
 * What a scenario pins, in its golden ({@code src/test/resources/.../testrunner/goldens/*.txt}):
 * the {@link TestStateMachine#history()} (from, to, reason, actor), the run's {@link TestLogger}
 * file, the gap sidecar, and the exact sequence of drive writes per handle
 * ({@link RecordingDriveProvider#golden()}). Numbers the clock decides (measured forces, analysed
 * times, re-send counts) are normalised to {@code #}. Beside the golden, every run is held to its
 * force CSV being an in-order, duplicate-free subsequence of what the bench delivered.
 * <p>
 * Synchronisation is on events only: state transitions (a listener on the run's machine), run-log
 * lines (a spy on the STOMP template), drive calls (recorder hooks) and the motor-safety probe. No
 * scenario waits a fixed time.
 * <p>
 * {@code DECK_GOLDENS_UPDATE=1} in the environment rewrites the goldens instead of comparing; a
 * mismatch writes the actual text to {@code build/run-goldens-actual/}.
 */
@SpringBootTest(classes = Application.class, properties = {
        // Its own database: a scenario persists results, and the other context tests share theirs.
        "spring.datasource.url=jdbc:h2:mem:deck-run-harness;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "vaadin.launch-browser=false",
        "deck.hardware.mode=simulated",
        // One median specimen every run, so a scenario's force path repeats.
        "deck.simulated.profile=stiff",
        "deck.simulated.scatter=false",
        // The motor reaches its setpoint and stands still within a tick, so a stop verifies in two
        // polls and a short verify deadline only ever bites a drive that does not answer.
        "deck.simulated.ramp-rpm-per-second=1000000",
        "deck.simulated.coast-rpm-per-second=1000000",
        // Recovery at test speed; see restoreRecoveryDefaults for the values every scenario starts from.
        "deck.testrunner.recovery.backoff-millis=100",
        "deck.testrunner.recovery.reconnect-window-millis=1000",
        "deck.testrunner.recovery.plausibility-gate-millis=20",
        "deck.testrunner.recovery.drift-fraction=0.5"
})
@ActiveProfiles("dev")
@Import(SimulatedRunHarness.HarnessDevices.class)
abstract class SimulatedRunHarness {

    /** Short, so tiers 1 and 2 over a drive that never answers take a second, not ten. */
    static final long VERIFY_DEADLINE_MS = 500;
    static final long STATE_TIMEOUT_MS = 15_000;
    static final String OPERATOR = "harness operator";

    private static final Path RESULTS = createResultRoot();
    private static final String GOLDEN_RESOURCE_DIR = "src/test/resources/ch/rupfizupfi/deck/testrunner/goldens";

    /** Decorated providers and the probed controller, each the {@code @Primary} of its type. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HarnessDevices {
        @Bean
        @Primary
        RecordingDriveProvider recordingDriveProvider(SimulatedDriveProvider simulated) {
            return new RecordingDriveProvider(simulated);
        }

        @Bean
        @Primary
        RecordingLoadCellStreamProvider recordingLoadCellStreamProvider(SimulatedLoadCellStreamProvider simulated) {
            return new RecordingLoadCellStreamProvider(simulated);
        }

        @Bean
        @Primary
        ProbedMotorSafetyController probedMotorSafetyController(DeviceService deviceService,
                                                                RecordingDriveProvider driveProvider,
                                                                DriveLinkProperties driveLinkProperties) {
            return new ProbedMotorSafetyController(deviceService, driveProvider, driveLinkProperties,
                    VERIFY_DEADLINE_MS);
        }
    }

    @MockitoSpyBean
    SimpMessagingTemplate template;
    @MockitoSpyBean
    SettingRepository settings;
    @MockitoSpyBean
    SuckService suckService;

    @Autowired
    TestRunnerFactory testRunnerFactory;
    @Autowired
    RecoveryProperties recoveryProperties;
    @Autowired
    SimulatedFaultSwitches faults;
    @Autowired
    SimulatedDriveProvider simulatedDrive;
    @Autowired
    SimulatedBench bench;
    @Autowired
    DeviceService deviceService;
    @Autowired
    RecordingDriveProvider drive;
    @Autowired
    RecordingLoadCellStreamProvider stream;
    @Autowired
    ProbedMotorSafetyController motorSafety;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    EntityManager entityManager;
    @Autowired
    ObjectMapper objectMapper;

    /** Run-log lines as broadcast on {@code /topic/logs}, for waiting on; the golden reads the file. */
    private final List<String> broadcastLog = new ArrayList<>();
    private final List<Run> runs = new ArrayList<>();

    private static Path createResultRoot() {
        try {
            return Files.createTempDirectory("deck-run-harness");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void prepareBench() {
        faults.clearAll();
        drive.reset();
        stream.reset();
        motorSafety.reset();
        BootDeEnergizeGate.resetForTests();
        restoreRecoveryDefaults();
        synchronized (broadcastLog) {
            broadcastLog.clear();
        }

        // Every artefact into a temp tree, never ~/breaktester; and fixed rig limits and suction
        // settings, whatever the developer's settings.json says.
        doReturn(RESULTS.toString()).when(settings).getSettingValue(Setting.Key.FILE_RESULT_DATA);
        doReturn(true).when(settings).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        doReturn(0).when(settings).getSettingValue(Setting.Key.TESTRUNNER_SUCK_DURATION);
        doReturn(Setting.create(Setting.Key.RIG_RATED_CAPACITY, RigLimits.DEFAULT_RATED_CAPACITY_KILONEWTON))
                .when(settings).getSetting(Setting.Key.RIG_RATED_CAPACITY.getKey());
        doReturn(Setting.create(Setting.Key.RIG_MAX_SPEED, RigLimits.DEFAULT_MAX_SPEED_MILLIMETRES_PER_MINUTE))
                .when(settings).getSetting(Setting.Key.RIG_MAX_SPEED.getKey());
        doReturn(Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, RigLimits.DEFAULT_SUCTION_LOAD_MARGIN_NEWTON))
                .when(settings).getSetting(Setting.Key.RIG_SUCTION_LOAD_MARGIN.getKey());

        doAnswer(invocation -> {
            if ("/topic/logs".equals(invocation.getArgument(0))) {
                synchronized (broadcastLog) {
                    broadcastLog.add(String.valueOf((Object) invocation.getArgument(1)));
                    broadcastLog.notifyAll();
                }
            }
            return invocation.callRealMethod();
        }).when(template).convertAndSend(anyString(), any(Object.class));
    }

    /** The values every scenario starts from; a scenario may tighten one before its run starts. */
    void restoreRecoveryDefaults() {
        recoveryProperties.setReconnectWindowMillis(1000);
        recoveryProperties.setBackoffMillis(List.of(100L));
        recoveryProperties.setSafeHoldTimeoutMillis(60_000);
        recoveryProperties.setMaxHoldForResumeMillis(30_000);
        recoveryProperties.setPlausibilityGateMillis(20);
        // Wide: a cyclic run keeps moving for the 250 ms the watchdog needs to trip, and the drift
        // that adds must not refuse the resume a scenario is about.
        recoveryProperties.setDriftFraction(0.5);
        recoveryProperties.setMaxLossesPerRun(2);
        recoveryProperties.setMinEnvelopeNewton(1000);
        recoveryProperties.setResumeEnabled(true);
        RecoveryProperties defaults = new RecoveryProperties();
        recoveryProperties.setPlausibilityGateMinSamples(defaults.getPlausibilityGateMinSamples());
        recoveryProperties.setMaxSampleIntervalMillis(defaults.getMaxSampleIntervalMillis());
        recoveryProperties.setMaxFrozenMillis(defaults.getMaxFrozenMillis());
    }

    @AfterEach
    void releaseBench() {
        motorSafety.reset();
        for (Run run : runs) {
            run.forceEnd();
        }
        runs.clear();
        faults.clearAll();
        BootDeEnergizeGate.resetForTests();
        // A wedge set to never end parks a handle's I/O thread with stops queued behind it: release
        // it first, so those stops land now rather than in the middle of the next scenario.
        bench.endWedge();
        // A tier-3 scenario leaves the plant energized: de-energize it through a handle of its own,
        // outside the recorder, so the next scenario mounts a fresh specimen on a standing motor.
        Drive direct = simulatedDrive.open();
        try {
            direct.setControlParameters(false, false, null, null, null);
            direct.setSpeedReferenceValueAsRpm(0);
            awaitCondition(() -> direct.getMotorSpeedValueAsRpm() == 0, 5000, "the simulated motor to stand still");
        } finally {
            direct.close();
        }
    }

    // ---- runs -------------------------------------------------------------------------------

    /** What to run; forces in kN, speed in mm/min, as the cms stores them. */
    record RunSpec(TestType type, int speed, Double lowerShutOff, Double upperShutOff, Double lowerTurn,
                   Double upperTurn, Integer cycleCount, Double startRamp, Double stopRamp) {

        static RunSpec destructive(int speed, double lowerShutOff, double upperShutOff) {
            return new RunSpec(TestType.DESTRUCTIVE, speed, lowerShutOff, upperShutOff, null, null, null, null, null);
        }

        static RunSpec cyclic(int speed, double lowerTurn, double upperTurn, double ceiling, int cycles) {
            return new RunSpec(TestType.CYCLIC, speed, null, ceiling, lowerTurn, upperTurn, cycles, null, null);
        }

        static RunSpec timeCyclic(int speed, double lowerTurn, double upperTurn, double ceiling, int cycles) {
            return new RunSpec(TestType.TIME_CYCLIC, speed, null, ceiling, lowerTurn, upperTurn, cycles, null, null);
        }

        RunSpec withRamps(double start, double stop) {
            return new RunSpec(type, speed, lowerShutOff, upperShutOff, lowerTurn, upperTurn, cycleCount, start, stop);
        }
    }

    TestResult persist(RunSpec spec) {
        return transactionTemplate.execute(status -> {
            Project project = new Project();
            project.name = "run harness";
            project.customer = entityManager.getReference(Customer.class, 1L);
            entityManager.persist(project);

            Sample sample = new Sample();
            sample.project = project;
            sample.name = "run harness sample";
            sample.yearOfManufacture = 2024;
            sample.materials = List.of();
            entityManager.persist(sample);

            TestParameter parameter = new TestParameter();
            parameter.type = spec.type();
            parameter.speed = spec.speed();
            parameter.lowerShutOffThreshold = spec.lowerShutOff();
            parameter.upperShutOffThreshold = spec.upperShutOff();
            parameter.lowerTurnForce = spec.lowerTurn();
            parameter.upperTurnForce = spec.upperTurn();
            parameter.cycleCount = spec.cycleCount();
            parameter.startRampSeconds = spec.startRamp();
            parameter.stopRampSeconds = spec.stopRamp();
            entityManager.persist(parameter);

            TestResult result = new TestResult();
            result.sample = sample;
            result.testParameter = parameter;
            entityManager.persist(result);
            return result;
        });
    }

    /** A fresh runner, as {@code TestRunnerService} builds one, started on a fresh result. */
    Run start(RunSpec spec) {
        return start(testRunnerFactory.createTestRunnerThread(), persist(spec));
    }

    Run start(TestRunnerThread runner, TestResult result) {
        runner.startThread(result);
        Run run = new Run(runner, result);
        runs.add(run);
        return run;
    }

    /** One started run and the event waits a scenario synchronises on. */
    final class Run {
        final TestRunnerThread runner;
        final TestResult result;
        final TestStateMachine machine;
        final Thread thread;
        private final Object monitor = new Object();

        private Run(TestRunnerThread runner, TestResult result) {
            this.runner = runner;
            this.result = result;
            this.machine = field(runner, "stateMachine");
            this.thread = field(runner, "thread");
            // Wakes the waits below; the history itself is read from the machine, so a transition
            // committed before this listener was added is still counted.
            machine.addListener(record -> {
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            });
        }

        long count(TestState state) {
            return machine.history().stream().filter(r -> r.to() == state).count();
        }

        /** Waits for the {@code occurrence}-th transition into {@code state}. */
        void awaitState(TestState state, int occurrence) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STATE_TIMEOUT_MS);
            synchronized (monitor) {
                while (count(state) < occurrence) {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (left <= 0) {
                        fail("no transition #" + occurrence + " into " + state + " within " + STATE_TIMEOUT_MS
                                + " ms; history " + renderHistory(machine.history()));
                    }
                    try {
                        monitor.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fail("interrupted while waiting for " + state);
                    }
                }
            }
        }

        void awaitState(TestState state) {
            awaitState(state, 1);
        }

        /** Joins the runner thread: the run has torn down and released its scope. */
        void awaitEnd() {
            try {
                thread.join(STATE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(thread.isAlive()).as("runner thread still alive; history %s",
                    renderHistory(machine.history())).isFalse();
            assertThat(runner.isRunning()).isFalse();
        }

        TestState state() {
            return machine.current();
        }

        private void forceEnd() {
            if (thread.isAlive()) {
                runner.stopThread();
            }
        }

        Path directory() {
            return RESULTS.resolve("simulated").resolve(Long.toString(result.getId()));
        }

        Path file(String suffix) {
            try (Stream<Path> files = Files.list(directory())) {
                List<Path> matches = files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
                assertThat(matches).as("files ending %s in %s", suffix, directory()).hasSizeLessThanOrEqualTo(1);
                return matches.isEmpty() ? null : matches.getFirst();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TestRunnerThread has no field " + name
                    + "; the harness reads it to reach the run's state machine and runner thread", e);
        }
    }

    // ---- waits on run-log lines --------------------------------------------------------------

    /** Waits for the {@code occurrence}-th broadcast run-log line starting with {@code prefix}. */
    void awaitLog(String prefix, int occurrence) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STATE_TIMEOUT_MS);
        synchronized (broadcastLog) {
            while (broadcastLog.stream().filter(line -> line.startsWith(prefix)).count() < occurrence) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    fail("no run-log line #" + occurrence + " starting '" + prefix + "' within "
                            + STATE_TIMEOUT_MS + " ms; log " + broadcastLog);
                }
                try {
                    broadcastLog.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted while waiting for log line " + prefix);
                }
            }
        }
    }

    /**
     * For the one wait that has no event to hang on: the plant coasting to zero between scenarios.
     */
    static void awaitCondition(BooleanSupplier condition, long timeoutMs, String what) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("timed out waiting for " + what);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    // ---- drive-call predicates for hooks -----------------------------------------------------

    static Predicate<RecordingDriveProvider.DriveCall> onRunner(String method, String args) {
        return call -> call.thread().equals("TestRunnerThread") && call.method().equals(method)
                && call.args().equals(args);
    }

    /**
     * The run loop's energize: since D3 (OQ-64 step 7) one control word carrying start and general
     * enable together, whatever the direction and ramp.
     */
    static boolean isEnergizeOnRunner(RecordingDriveProvider.DriveCall call) {
        return call.thread().equals("TestRunnerThread") && call.method().equals("setControlParameters")
                && call.args().startsWith("true,true,");
    }

    /**
     * A de-energize: the safe stop's {@code Drive#stop()}, or the verification's re-sent one-write
     * de-energize.
     */
    static boolean isDeEnergize(RecordingDriveProvider.DriveCall call) {
        return call.method().equals("stop")
                || (call.method().equals("setControlParameters") && call.args().equals("false,false,null,null,null"));
    }

    /** Matches the {@code n}-th call (counting from 1) the predicate accepts. */
    static Predicate<RecordingDriveProvider.DriveCall> nth(int n, Predicate<RecordingDriveProvider.DriveCall> base) {
        int[] seen = {0};
        return call -> base.test(call) && ++seen[0] == n;
    }

    // ---- assertions over a finished run ------------------------------------------------------

    /**
     * The force CSV holds only delivered samples, in delivery order, each once; with
     * {@code contiguous}, also every delivered sample between its first and last row - the run
     * dropped nothing it was handed (a hold drops by design).
     */
    void assertForceCsv(Run run, boolean contiguous) {
        Path csv = run.file("_force.csv");
        assertThat(csv).as("force CSV of run %s", run.result.getId()).isNotNull();
        List<String> rows;
        try {
            rows = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(rows).as("force CSV rows").isNotEmpty();

        List<String> delivered = stream.delivered().stream()
                .map(d -> d.measurement().timestamp() + "," + d.measurement().force())
                .toList();
        int cursor = 0;
        int first = -1;
        int last = -1;
        for (String row : rows) {
            int found = -1;
            for (int i = cursor; i < delivered.size(); i++) {
                if (delivered.get(i).equals(row)) {
                    found = i;
                    break;
                }
            }
            assertThat(found).as("CSV row '%s' is no delivered sample after the previous row", row)
                    .isGreaterThanOrEqualTo(0);
            if (first < 0) {
                first = found;
            }
            last = found;
            cursor = found + 1;
        }
        if (contiguous) {
            assertThat(rows).as("CSV rows against the delivered window").hasSize(last - first + 1);
        }
    }

    /** I10: the first energize came after the loop had processed a delivered sample. */
    void assertEnergizedOnlyAfterFirstSample(Run run) {
        RecordingDriveProvider.DriveCall energize = drive.calls().stream()
                .filter(c -> c.method().equals("setActionInCaseOfCommunicationError"))
                .findFirst().orElse(null);
        if (energize == null) {
            return;
        }
        List<RecordingLoadCellStreamProvider.Delivered> delivered = stream.delivered();
        Path csv = run.file("_force.csv");
        assertThat(csv).isNotNull();
        String firstRow;
        try (Stream<String> lines = Files.lines(csv)) {
            firstRow = lines.findFirst().orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        long firstSampleNanos = delivered.stream()
                .filter(d -> (d.measurement().timestamp() + "," + d.measurement().force()).equals(firstRow))
                .mapToLong(RecordingLoadCellStreamProvider.Delivered::nanos)
                .findFirst().orElseThrow();
        assertThat(energize.nanos()).as("energize after the first CSV sample was delivered")
                .isGreaterThan(firstSampleNanos);
    }

    /**
     * I3: the incident's de-energize write reached the drive no later than the {@code n}-th
     * SENSOR_LOST was recorded, so the stop came before the bookkeeping.
     */
    void assertStopBeforeIncident(Run run, int n) {
        TestStateMachine.TransitionRecord lost = run.machine.history().stream()
                .filter(r -> r.to() == TestState.SENSOR_LOST).skip(n - 1).findFirst().orElseThrow();
        boolean stopFirst = drive.calls().stream()
                .anyMatch(c -> isDeEnergize(c)
                        && c.epochMillis() <= lost.atEpochMillis()
                        && c.epochMillis() >= previousIncidentFloor(run, n));
        assertThat(stopFirst).as("a de-energize write at or before SENSOR_LOST #%s", n).isTrue();
    }

    private static long previousIncidentFloor(Run run, int n) {
        // Bounded below by the transition before this incident, so a stop from an earlier one
        // cannot stand in for this one's.
        List<TestStateMachine.TransitionRecord> history = run.machine.history();
        TestStateMachine.TransitionRecord lost = history.stream()
                .filter(r -> r.to() == TestState.SENSOR_LOST).skip(n - 1).findFirst().orElseThrow();
        int index = history.indexOf(lost);
        return index == 0 ? 0 : history.get(index - 1).atEpochMillis();
    }

    /** The plant's control word, read through a handle of its own outside the recorder. */
    Map<String, Boolean> benchControlWord() {
        Drive direct = simulatedDrive.open();
        try {
            return new HashMap<>(direct.getControlParameters());
        } finally {
            direct.close();
        }
    }

    void assertBenchDeEnergized() {
        Map<String, Boolean> word = benchControlWord();
        assertThat(word.get("generalEnable")).as("general enable").isFalse();
        assertThat(word.get("start")).as("start").isFalse();
    }

    // ---- goldens ------------------------------------------------------------------------------

    private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+(E-?\\d+)?");
    private static final Pattern RE_SENT = Pattern.compile("\\d+ re-sent");
    private static final Pattern RPM = Pattern.compile("-?\\d+ rpm");
    private static final Pattern MILLIS_TIME = Pattern.compile("time: \\d+ ms");
    /** Configured values, logged at run start; kept so a golden shows what the run was given. */
    private static final List<String> CONFIGURATION_LINES = List.of("upperShutOffThreshold ",
            "lowerShutOffThreshold ", "upperTurnForce ", "lowerTurnForce ");

    static String normalise(String line) {
        if (line == null) {
            return "null";
        }
        String out = line;
        if (CONFIGURATION_LINES.stream().noneMatch(line::startsWith)) {
            out = DECIMAL.matcher(out).replaceAll("#.#");
        }
        out = RE_SENT.matcher(out).replaceAll("# re-sent");
        out = RPM.matcher(out).replaceAll("# rpm");
        out = MILLIS_TIME.matcher(out).replaceAll("time: # ms");
        return out;
    }

    static String renderHistory(List<TestStateMachine.TransitionRecord> history) {
        StringBuilder out = new StringBuilder();
        for (TestStateMachine.TransitionRecord record : history) {
            out.append(record.from()).append(" -> ").append(record.to()).append(" | ")
                    .append(normalise(record.reason()));
            if (record.actor() != null) {
                out.append(" | by ").append(record.actor());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * The scenario's observable record. {@code excludeLog} drops run-log lines whose position
     * against the teardown is decided by the scheduler, never by the code path, named in the golden.
     */
    String record(Run run, Predicate<String> excludeLog, String excludeNote) {
        StringBuilder out = new StringBuilder();
        out.append("[history]\n").append(renderHistory(run.machine.history()));

        out.append("[log]\n");
        if (excludeNote != null) {
            out.append("# excluded: ").append(excludeNote).append('\n');
        }
        Path log = run.file("_test.log");
        if (log != null) {
            try {
                for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                    if (excludeLog == null || !excludeLog.test(line)) {
                        out.append(normalise(line)).append('\n');
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        out.append("[gaps]\n");
        Path gaps = run.file("_gaps.json");
        if (gaps == null) {
            out.append("none\n");
        } else {
            JsonNode document = objectMapper.readTree(gaps.toFile());
            for (JsonNode gap : document.get("gaps")) {
                out.append(gap.get("index").asInt()).append(' ').append(gap.get("outcome").asString())
                        .append(" stopTier=").append(text(gap.get("stopTier")))
                        .append(" stopVerified=").append(text(gap.get("stopVerified")))
                        .append(" | ").append(normalise(text(gap.get("reason")))).append('\n');
            }
            out.append("runOutcome=").append(text(document.get("runOutcome"))).append('\n');
        }

        out.append("[drive]\n");
        for (String line : drive.golden()) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "null" : node.asString();
    }

    String record(Run run) {
        return record(run, null, null);
    }

    /** Compares against, or with {@code DECK_GOLDENS_UPDATE=1} rewrites, {@code goldens/<name>.txt}. */
    void assertGolden(String name, String actual) {
        String header = "# " + name + " - recorded by RunScenarioTest (OQ-64 step 1). "
                + "Regenerate with DECK_GOLDENS_UPDATE=1; a step that changes it on purpose says so.\n";
        Path source = Path.of(GOLDEN_RESOURCE_DIR).resolve(name + ".txt");
        if ("1".equals(System.getenv("DECK_GOLDENS_UPDATE"))) {
            try {
                Files.createDirectories(source.getParent());
                Files.writeString(source, header + actual, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return;
        }

        String expected;
        try (var in = SimulatedRunHarness.class.getResourceAsStream("goldens/" + name + ".txt")) {
            if (in == null) {
                writeActual(name, actual);
                fail("no golden goldens/" + name + ".txt; record it with DECK_GOLDENS_UPDATE=1");
                return;
            }
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String stripped = stripComments(expected);
        if (!stripped.equals(stripComments(actual))) {
            writeActual(name, actual);
        }
        assertThat(stripComments(actual)).as("golden %s", name).isEqualTo(stripped);
    }

    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (!line.startsWith("#")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static void writeActual(String name, String actual) {
        try {
            Path target = Path.of("build", "run-goldens-actual", name + ".txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, actual, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Only a diagnostic.
        }
    }
}
