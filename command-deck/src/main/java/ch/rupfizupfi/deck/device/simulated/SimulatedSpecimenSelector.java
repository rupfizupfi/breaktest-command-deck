package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.device.BenchPreparation;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Picks each simulated run's specimen from the run itself: the profile from the sample's materials,
 * the pretension from the test parameters.
 * <p>
 * Profile, in order: {@code deck.simulated.profile} when set; else the first of the sample's
 * materials, by name, that {@code deck.simulated.material-profiles} maps; else
 * {@link SpecimenProfile#DEFAULT}. Sample name and description are not read — in the recorded runs
 * they did not tell weathered webbing that kept its strength from webbing that lost two thirds.
 * <p>
 * Pretension: a destructive run ends on its first sample below the lower shut-off threshold, so
 * the operator tensions above it. When the profile's median pretension is not at least
 * {@link #PRETENSION_OVER_LOWER_THRESHOLD} times that threshold, the specimen is mounted there
 * instead. Cyclic runs start by releasing, so their turn forces need no adjustment.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedSpecimenSelector implements BenchPreparation {

    /** Margin over the lower shut-off threshold; recorded runs sat at a median 1.85 kN against 0.5–1 kN. */
    static final double PRETENSION_OVER_LOWER_THRESHOLD = 1.5;

    /** A threshold set near the break must still leave a curve to climb. */
    private static final double MAX_PRETENSION_OF_BREAK = 0.8;

    private final SimulatedBench bench;
    private final EntityManager entityManager;
    private final SpecimenProfile forcedProfile;
    private final Map<String, SpecimenProfile> materialProfiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public SimulatedSpecimenSelector(SimulatedBench bench, EntityManager entityManager,
                                     SimulatedBenchProperties properties) {
        this.bench = bench;
        this.entityManager = entityManager;
        String forced = properties.getProfile();
        this.forcedProfile = forced == null || forced.isBlank() ? null : SpecimenProfile.resolve(forced);
        // Resolved here so a mistyped mapping fails startup rather than the first run on that material.
        properties.getMaterialProfiles().forEach((material, key) ->
                materialProfiles.put(material.trim(), SpecimenProfile.resolve(key)));
    }

    @Override
    public String prepare(TestResult testResult) {
        Selection selection = select(materialNames(testResult), testResult.testParameter);
        bench.prepareRun(selection.profile(), selection.pretensionNewton(), "test result " + testResult.getId());
        String speed = testResult.testParameter == null ? "" : ", pulled at " + testResult.testParameter.speed + " mm/min";
        return "simulated specimen: profile " + selection.profile().key() + " (" + selection.reason() + "), pretension "
                + Math.round(selection.pretensionNewton()) + " N, breaks at "
                + Math.round(bench.specimen().breakForceNewton()) + " N" + speed;
    }

    record Selection(SpecimenProfile profile, String reason, double pretensionNewton) {
    }

    Selection select(List<String> materialNames, TestParameter parameter) {
        SpecimenProfile profile;
        String reason;
        if (forcedProfile != null) {
            profile = forcedProfile;
            reason = "forced by deck.simulated.profile";
        } else {
            String material = materialNames.stream()
                    .filter(materialProfiles::containsKey)
                    .min(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
                    .orElse(null);
            if (material != null) {
                profile = materialProfiles.get(material);
                reason = "material " + material;
            } else {
                profile = SpecimenProfile.DEFAULT;
                reason = materialNames.isEmpty() ? "sample has no material, default"
                        : "no profile for material " + String.join(", ", materialNames) + ", default";
            }
        }

        double pretension = profile.pretensionNewton();
        if (parameter != null && parameter.type == TestType.DESTRUCTIVE && parameter.lowerShutOffThreshold != null) {
            double floor = parameter.lowerShutOffThreshold * 1000 * PRETENSION_OVER_LOWER_THRESHOLD;
            if (pretension < floor) {
                pretension = Math.min(floor, MAX_PRETENSION_OF_BREAK * profile.breakForceNewton());
                reason += ", pretension raised over the lower shut-off threshold";
            }
        }
        return new Selection(profile, reason, pretension);
    }

    /**
     * Queried rather than read off the entity: {@code Sample.materials} is lazy and the runner
     * thread holds no persistence context.
     */
    private List<String> materialNames(TestResult testResult) {
        if (testResult.sample == null || testResult.sample.getId() == null) {
            return List.of();
        }
        return entityManager.createQuery(
                        "select m.name from Sample s join s.materials m where s.id = :id", String.class)
                .setParameter("id", testResult.sample.getId())
                // A list, not a stream: outside a transaction the shared EntityManager closes the
                // result set before a stream is consumed.
                .getResultList().stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
