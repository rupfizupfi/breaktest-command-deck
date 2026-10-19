package ch.rupfizupfi.deck;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structure lock of the OQ-64 redesign (step 10): the dependency rule of
 * {@code doc/dev/backend/testrunner-architecture.md#the-dependency-rule} as a test.
 * Dependencies point down - {@code run} above {@code program} and {@code measure}, both above the
 * {@code testrunner} root and {@code device} - and each crossing is an explicit allow-list, so a new
 * edge fails here and has to be argued into the list rather than slipping in.
 * <p>
 * Production classes only: tests and jars are not imported. The rules name this module's packages;
 * the module-wide ones also hold for whatever cms classes the test classpath carries.
 */
class ArchitectureTest {

    private static final String DECK = "ch.rupfizupfi.deck";
    private static final String ROOT = DECK + ".testrunner";
    private static final String RUN = ROOT + ".run";
    private static final String PROGRAM = ROOT + ".program";
    private static final String MEASURE = ROOT + ".measure";

    private static JavaClasses deck;

    @BeforeAll
    static void importProductionClasses() {
        deck = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(DECK);
        // An importer that cannot read this JDK's class files imports nothing, and every rule below
        // would then pass on an empty set.
        assertThat(deck.contain(RUN + ".RunLoop")).as("RunLoop imported").isTrue();
        assertThat(deck.contain(MEASURE + ".MeasurementLoop")).as("MeasurementLoop imported").isTrue();
        assertThat(deck.contain(PROGRAM + ".TestProgramRegistry")).as("TestProgramRegistry imported").isTrue();
    }

    /** Exactly the named classes of {@code pkg}, their nested classes included ({@code RunControl$Envelope}). */
    private static DescribedPredicate<JavaClass> oneOf(String pkg, String... simpleNames) {
        Set<String> names = Set.of(simpleNames);
        return DescribedPredicate.describe(pkg + "." + names, c -> {
            if (!c.getPackageName().equals(pkg)) {
                return false;
            }
            String top = c.getName().substring(pkg.length() + 1);
            int nested = top.indexOf('$');
            return names.contains(nested < 0 ? top : top.substring(0, nested));
        });
    }

    private static DescribedPredicate<JavaClass> inPackage(String pkg) {
        return DescribedPredicate.describe("in " + pkg, c -> c.getPackageName().equals(pkg));
    }

    private static void check(ArchRule rule) {
        rule.check(deck);
    }

    // ---- program: the test types, written against RunControl only ---------------------------

    @Test
    void programsSeeOfTheRunOnlyTheControlSurfaceAndItsValueTypes() {
        // MotorStop (2026-10-13): how the finish stop went, the loop's mapping of SafeStopResult, so
        // the destructive program can withhold the suction from an unconfirmed stop.
        check(noClasses().that().resideInAPackage(PROGRAM)
                .should().dependOnClassesThat(inPackage(RUN).and(DescribedPredicate.not(oneOf(RUN,
                        "RunControl", "Verdict", "Limit", "Direction", "FinishCause", "RunEvent", "MotorStop")))));
    }

    @Test
    void programsSeeOfTheMeasurementSideOnlyTheEventSink() {
        check(noClasses().that().resideInAPackage(PROGRAM)
                .should().dependOnClassesThat(inPackage(MEASURE)
                        .and(DescribedPredicate.not(oneOf(MEASURE, "RunEventSink")))));
    }

    @Test
    void programsSeeOfTheDriveContractOnlyDrive() {
        check(noClasses().that().resideInAPackage(PROGRAM)
                .should().dependOnClassesThat(resideInAPackage(DECK + ".device..")
                        .and(DescribedPredicate.not(oneOf(DECK + ".device.api", "Drive")))));
    }

    @Test
    void programsSeeOfTheRunnerOnlyTheParameterConversion() {
        // TestRunnerFactory and SuckJob are the suction job; only the destructive factory - a Spring
        // wiring point, not program logic - may reach for it.
        check(noClasses().that().resideInAPackage(PROGRAM)
                .and().doNotHaveSimpleName("DestructiveProgramFactory")
                .should().dependOnClassesThat(inPackage(ROOT)
                        .and(DescribedPredicate.not(oneOf(ROOT, "RunParameters")))));
        check(noClasses().that().haveSimpleName("DestructiveProgramFactory")
                .should().dependOnClassesThat(inPackage(ROOT)
                        .and(DescribedPredicate.not(oneOf(ROOT, "RunParameters", "TestRunnerFactory", "SuckJob")))));
    }

    @Test
    void programStateHasOneWriterSoNoFieldIsVolatile() {
        check(fields().that().areDeclaredInClassesThat().resideInAPackage(PROGRAM)
                .should().notHaveModifier(JavaModifier.VOLATILE));
    }

    @Test
    void noProgramMethodIsSynchronized() {
        check(methods().that().areDeclaredInClassesThat().resideInAPackage(PROGRAM)
                .should().notHaveModifier(JavaModifier.SYNCHRONIZED));
    }

    /** A {@code synchronized} block leaves no modifier for the importer to see, so the source is read. */
    @Test
    void noProgramSourceTakesAMonitor() throws IOException {
        Path sources = Path.of("src/main/java", PROGRAM.replace('.', '/'));
        assertThat(sources).isDirectory();
        try (Stream<Path> files = Files.list(sources)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(ArchitectureTest::mentionsSynchronizedOutsideComments)
                    .toList();
            assertThat(offenders).as("program sources using synchronized").isEmpty();
        }
    }

    private static boolean mentionsSynchronizedOutsideComments(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("/*") && !line.startsWith("//"))
                    .anyMatch(line -> line.matches(".*\\bsynchronized\\b.*"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- measure: detects, de-energizes through EmergencyStop, reports through RunEventSink --

    @Test
    void theMeasurementSideSeesOfTheRunOnlyItsEventVocabulary() {
        check(noClasses().that().resideInAPackage(MEASURE)
                .should().dependOnClassesThat(inPackage(RUN)
                        .and(DescribedPredicate.not(oneOf(RUN, "RunEvent", "Limit", "Direction")))));
    }

    @Test
    void theMeasurementSideSeesOfTheRunnerOnlyTheStopResultTheGatesAndTheGapRecord() {
        // Never MotorSafetyController: the stop arrives as an EmergencyStop.
        check(noClasses().that().resideInAPackage(MEASURE)
                .should().dependOnClassesThat(inPackage(ROOT).and(DescribedPredicate.not(
                        oneOf(ROOT, "SafeStopResult", "RecoveryGates", "GapRecorder")))));
    }

    @Test
    void theMeasurementSideNeverSeesAProgram() {
        check(noClasses().that().resideInAPackage(MEASURE)
                .should().dependOnClassesThat().resideInAPackage(PROGRAM));
    }

    // ---- run: the loop, above both --------------------------------------------------------

    @Test
    void theRunLoopSeesOfTheProgramsOnlyTheirContract() {
        // ResumePolicy lives in program and is the loop's to apply (step 8); the concrete programs
        // and phases are reached only through TestProgram.
        check(noClasses().that().resideInAPackage(RUN)
                .should().dependOnClassesThat(inPackage(PROGRAM).and(DescribedPredicate.not(oneOf(PROGRAM,
                        "TestProgram", "DriveSetup", "Limits", "Ramp", "ResumePolicy")))));
    }

    @Test
    void theRunLoopReachesTheMeasurementLoopOnlyThroughItsDevicesSeam() {
        check(noClasses().that().resideInAPackage(RUN)
                .should().dependOnClassesThat(oneOf(MEASURE, "MeasurementLoop", "LimitEdgeDetector", "TraceWriter")
                        .and(DescribedPredicate.describe("other than the gate verdict",
                                c -> !c.getName().endsWith("MeasurementLoop$GateResult")))));
    }

    @Test
    void theRunLoopSeesOfTheRunnerOnlyTheStateMachineAndTheStopValues() {
        check(noClasses().that().resideInAPackage(RUN)
                .should().dependOnClassesThat(inPackage(ROOT).and(DescribedPredicate.not(oneOf(ROOT,
                        "TestState", "TestStateMachine", "SafeStopResult", "RecoveryGates")))));
    }

    // ---- across the module -----------------------------------------------------------------

    @Test
    void nothingBelowTheApiLayerPointsUpIntoIt() {
        check(noClasses().that().resideInAnyPackage(RUN, PROGRAM, MEASURE, DECK + ".device..")
                .should().dependOnClassesThat().resideInAPackage(DECK + ".api.."));
    }

    @Test
    void theDevicesNeverSeeTheTestRunner() {
        check(noClasses().that().resideInAPackage(DECK + ".device..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".."));
    }

    @Test
    void noClassNamesTheVendorDrive() {
        // Cfw11 is final and lives in the licence-restricted driver jar; the deck sees Drive only.
        check(noClasses().should().dependOnClassesThat().haveSimpleNameContaining("Cfw11"));
        check(noClasses().should().haveSimpleNameContaining("Cfw11"));
    }

    @Test
    void noDriveHandleIsOpenedOrKeptOutsideItsAccessors() {
        // BL-121: the deck holds no lock around a Drive call, so the device's own handle is reached
        // only through FrequencyInverterDevice's accessors, and only the safe stop's tier 2 opens a
        // handle of its own. Nobody else opens one or keeps one in a field.
        String[] owners = {DECK + ".device.frequencyinverter", DECK + ".device.simulated", DECK + ".device.api.."};
        check(noClasses().that().resideOutsideOfPackages(owners)
                .and().doNotHaveFullyQualifiedName(ROOT + ".MotorSafetyController")
                .should().callMethod(DriveProvider.class, "open"));
        check(noFields().that().haveRawType(Drive.class)
                .should().beDeclaredInClassesThat().resideOutsideOfPackages(owners));
    }

    @Test
    void noTestRunnerCodeCreatesObjectsByReflection() {
        // The reflective TestRunnerFactory#createTestRunner went in step 7; the registry replaced it.
        check(noClasses().that().resideInAPackage(ROOT + "..")
                .should().callMethod(Class.class, "getConstructors")
                .orShould().callMethod(Class.class, "getDeclaredConstructors")
                .orShould().callMethodWhere(DescribedPredicate.describe("Class#getConstructor/getDeclaredConstructor",
                        call -> call.getTargetOwner().isEquivalentTo(Class.class)
                                && (call.getName().equals("getConstructor") || call.getName().equals("getDeclaredConstructor"))))
                .orShould().callMethodWhere(DescribedPredicate.describe("newInstance",
                        call -> (call.getTargetOwner().isEquivalentTo(Class.class)
                                || call.getTargetOwner().isEquivalentTo(Constructor.class))
                                && call.getName().equals("newInstance"))));
    }

    @Test
    void noRunControlFlowIsAnException() {
        // FinishTestException ended a run by unwinding the stack (deleted in step 5); a run now ends
        // on a Verdict or an event. The three run packages declare no throwable of their own.
        check(noClasses().that().resideInAnyPackage(RUN, PROGRAM, MEASURE)
                .should().beAssignableTo(Throwable.class));
        check(noClasses().should().haveSimpleName("FinishTestException")
                .orShould().haveSimpleName("SignalListener")
                .orShould().haveSimpleName("AbstractTest")
                .orShould().haveSimpleName("TestContext"));
    }

    @Test
    void everyRunEventIsDeclaredInTheSealedVocabulary() {
        // One file names every event, so a switch over RunEvent without a default stays exhaustive.
        check(classes().that().implement(RUN + ".RunEvent")
                .should(ArchCondition.from(DescribedPredicate.describe("be nested in RunEvent",
                        (JavaClass c) -> c.getEnclosingClass()
                                .map(e -> e.getName().equals(RUN + ".RunEvent")).orElse(false)))));
    }
}
