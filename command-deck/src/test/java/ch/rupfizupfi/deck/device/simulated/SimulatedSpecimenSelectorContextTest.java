package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.Application;
import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.Material;
import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.testrunner.TestRunnerFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selector against the real schema, called the way the runner calls it: through
 * {@link TestRunnerFactory#prepareBench} with a detached {@link TestResult}, outside any
 * transaction, so the lazy {@code Sample.materials} cannot be read off the entity.
 * <p>
 * Same configuration as {@code ApplicationContextTest}, so the cached context is reused.
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:deck-context-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
class SimulatedSpecimenSelectorContextTest {

    @Autowired
    TestRunnerFactory testRunnerFactory;

    @Autowired
    SimulatedBench bench;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void aPolyamideSampleMountsThePolyamideProfile() {
        TestResult result = persistRun(List.of("Polyamid"), TestType.DESTRUCTIVE, 1.0);

        String logLine = testRunnerFactory.prepareBench(result).orElseThrow();

        assertThat(bench.specimen().profile()).isEqualTo(SpecimenProfile.POLYAMIDE_WEBBING);
        assertThat(logLine).contains("polyamide-webbing", "material Polyamid");
    }

    @Test
    void aSampleWithoutAMappedMaterialMountsTheDefault() {
        TestResult result = persistRun(List.of("Hemp"), TestType.DESTRUCTIVE, 1.0);

        String logLine = testRunnerFactory.prepareBench(result).orElseThrow();

        assertThat(bench.specimen().profile()).isEqualTo(SpecimenProfile.DEFAULT);
        assertThat(logLine).contains("no profile for material Hemp");
    }

    @Test
    void aHighLowerThresholdRaisesThePretensionAboveIt() {
        TestResult result = persistRun(List.of("Polyester"), TestType.DESTRUCTIVE, 3.0);

        testRunnerFactory.prepareBench(result);

        Specimen specimen = bench.specimen();
        assertThat(specimen.profile()).isEqualTo(SpecimenProfile.STIFF);
        assertThat(specimen.forceAt(specimen.mountPositionMm())).isGreaterThan(3000);
    }

    private TestResult persistRun(List<String> materialNames, TestType type, double lowerShutOffKiloNewton) {
        return transactionTemplate.execute(status -> {
            Project project = new Project();
            project.name = "simulated specimen selection";
            project.customer = entityManager.getReference(Customer.class, 1L);
            entityManager.persist(project);

            Sample sample = new Sample();
            sample.project = project;
            sample.name = "selector sample";
            sample.yearOfManufacture = 2024;
            sample.materials = entityManager
                    .createQuery("select m from Material m where m.name in :names", Material.class)
                    .setParameter("names", materialNames)
                    .getResultList();
            entityManager.persist(sample);

            TestParameter parameter = new TestParameter();
            parameter.type = type;
            parameter.speed = 350;
            parameter.upperShutOffThreshold = 45.0;
            parameter.lowerShutOffThreshold = lowerShutOffKiloNewton;
            entityManager.persist(parameter);

            TestResult result = new TestResult();
            result.sample = sample;
            result.testParameter = parameter;
            entityManager.persist(result);
            return result;
        });
    }
}
