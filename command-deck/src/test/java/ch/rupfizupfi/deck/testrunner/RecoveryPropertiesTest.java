package ch.rupfizupfi.deck.testrunner;

import jakarta.validation.constraints.AssertTrue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryPropertiesTest {

    private final RecoveryProperties properties = new RecoveryProperties();

    @Test
    void snapshotCarriesEachPropertyIntoItsNamedGate() {
        // Pairwise-distinct values pin each property to its named gate even between same-typed
        // fields; the reflection mirror test below only proves a component exists per property.
        properties.setReconnectWindowMillis(11_000);
        properties.setBackoffMillis(List.of(101L, 202L));
        properties.setSafeHoldTimeoutMillis(33_000);
        properties.setMaxHoldForResumeMillis(22_000);
        properties.setPlausibilityGateMillis(444);
        properties.setDriftFraction(0.05);
        properties.setMaxLossesPerRun(7);
        properties.setMinEnvelopeNewton(1_234.5);
        properties.setResumeEnabled(false);

        RecoveryGates gates = properties.snapshot();

        assertThat(gates.reconnectWindowMillis()).isEqualTo(11_000);
        assertThat(gates.backoffMillis()).containsExactly(101L, 202L);
        assertThat(gates.safeHoldTimeoutMillis()).isEqualTo(33_000);
        assertThat(gates.maxHoldForResumeMillis()).isEqualTo(22_000);
        assertThat(gates.plausibilityGateMillis()).isEqualTo(444);
        assertThat(gates.driftFraction()).isEqualTo(0.05);
        assertThat(gates.maxLossesPerRun()).isEqualTo(7);
        assertThat(gates.minEnvelopeNewton()).isEqualTo(1_234.5);
        assertThat(gates.resumeEnabled()).isFalse();
    }

    @Test
    void snapshotDefensivelyCopiesBackoffMillis() {
        List<Long> source = new ArrayList<>(List.of(500L, 1_000L));
        properties.setBackoffMillis(source);

        RecoveryGates gates = properties.snapshot();
        source.set(0, 1L);
        source.add(99_999L);

        assertThat(gates.backoffMillis()).containsExactly(500L, 1_000L);
    }

    @Test
    void snapshotTurnsANullBackoffListIntoAnEmptyOne() {
        properties.setBackoffMillis(null);
        assertThat(properties.snapshot().backoffMillis()).isEmpty();
    }

    @Test
    void resumeWindowLongerThanTheHoldIsInvalid() {
        properties.setSafeHoldTimeoutMillis(60_000);
        properties.setMaxHoldForResumeMillis(60_001);
        assertThat(properties.isResumeWindowInsideHold()).isFalse();
    }

    @Test
    void resumeWindowEqualToTheHoldIsValid() {
        properties.setSafeHoldTimeoutMillis(60_000);
        properties.setMaxHoldForResumeMillis(60_000);
        assertThat(properties.isResumeWindowInsideHold()).isTrue();
    }

    @Test
    void backoffWithANonPositiveEntryIsInvalid() {
        properties.setBackoffMillis(List.of(0L, 1_000L));
        assertThat(properties.isBackoffInsideWindow()).isFalse();

        properties.setBackoffMillis(List.of(1_000L, -1L));
        assertThat(properties.isBackoffInsideWindow()).isFalse();

        properties.setBackoffMillis(Arrays.asList(1_000L, null));
        assertThat(properties.isBackoffInsideWindow()).isFalse();
    }

    @Test
    void firstBackoffAtOrBeyondTheReconnectWindowIsInvalid() {
        properties.setReconnectWindowMillis(1_000);
        properties.setBackoffMillis(List.of(1_000L));
        assertThat(properties.isBackoffInsideWindow()).isFalse();

        properties.setBackoffMillis(List.of(999L));
        assertThat(properties.isBackoffInsideWindow()).isTrue();
    }

    @Test
    void emptyOrNullBackoffListPassesTheWindowCheck() {
        // Deliberate: an empty list is @NotEmpty's finding, and the operator gets one message, not two.
        properties.setBackoffMillis(List.of());
        assertThat(properties.isBackoffInsideWindow()).isTrue();

        properties.setBackoffMillis(null);
        assertThat(properties.isBackoffInsideWindow()).isTrue();
    }

    /**
     * snapshot() completeness guard: a RecoveryProperties field without a RecoveryGates component
     * cannot be snapshotted, so the audit record would silently claim gates the run never used.
     */
    @Test
    void everyPropertyGetterHasAMatchingGatesRecordComponent() {
        Set<String> components = Arrays.stream(RecoveryGates.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        for (Method method : RecoveryProperties.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            // @AssertTrue methods are validation predicates, not configuration getters.
            if (method.isAnnotationPresent(AssertTrue.class)) {
                continue;
            }
            String property;
            if (method.getName().startsWith("get")) {
                property = decapitalize(method.getName().substring(3));
            } else if (method.getName().startsWith("is")) {
                property = decapitalize(method.getName().substring(2));
            } else {
                continue;
            }
            if (!components.contains(property)) {
                missing.add(property);
            }
        }

        assertThat(missing)
                .as("RecoveryProperties getters without a RecoveryGates record component")
                .isEmpty();
    }

    private static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
