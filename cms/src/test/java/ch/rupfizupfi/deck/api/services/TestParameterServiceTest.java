package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestParameterRepository;
import ch.rupfizupfi.deck.data.TestType;
import com.vaadin.hilla.exception.EndpointException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A save the bench could not run is refused against the rig limits as they stand, with a message
 * the browser can show, before ownership or the repository are reached.
 */
class TestParameterServiceTest {

    private TestParameterRepository repository;
    private SettingRepository settings;
    private ScopedTestParameterService service;

    private static class ScopedTestParameterService extends TestParameterService {
        private final TestParameterRepository repository;

        ScopedTestParameterService(SettingRepository settings, TestParameterRepository repository) {
            super(settings);
            this.repository = repository;
        }

        @Override
        protected TestParameterRepository getRepository() {
            return repository;
        }
    }

    @BeforeEach
    void createService() {
        repository = mock(TestParameterRepository.class);
        settings = mock(SettingRepository.class);
        rigLimits(150, 500);
        service = new ScopedTestParameterService(settings, repository);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void rigLimits(Object capacityKn, Object maxSpeed) {
        doReturn(Setting.create(Setting.Key.RIG_RATED_CAPACITY, capacityKn))
                .when(settings).getSetting(Setting.Key.RIG_RATED_CAPACITY.getKey());
        doReturn(Setting.create(Setting.Key.RIG_MAX_SPEED, maxSpeed))
                .when(settings).getSetting(Setting.Key.RIG_MAX_SPEED.getKey());
        doReturn(Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 500))
                .when(settings).getSetting(Setting.Key.RIG_SUCTION_LOAD_MARGIN.getKey());
    }

    @Test
    void aShutOffAboveTheRatedCapacityIsRefusedWithAReadableMessage() {
        TestParameter parameter = destructive(151.0, 10);

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.save(parameter))
                .withMessageContaining("Cannot save the test parameter")
                .withMessageContaining("rated capacity");
        verify(repository, never()).save(any());
    }

    @Test
    void aSpeedAboveTheRigsMaximumIsRefused() {
        TestParameter parameter = destructive(100.0, 501);

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.save(parameter))
                .withMessageContaining("maximum crosshead speed");
        verify(repository, never()).save(any());
    }

    @Test
    void theLimitsAreReadAtSaveTimeSoAnEditedSettingApplies() {
        rigLimits(80, 500);

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.save(destructive(100.0, 10)))
                .withMessageContaining("(0, 80.0] kN");
    }

    @Test
    void anUnusableRigLimitSettingRefusesTheSave() {
        rigLimits("lots", 500);

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.save(destructive(100.0, 10)))
                .withMessageContaining(Setting.Key.RIG_RATED_CAPACITY.getKey());
        verify(repository, never()).save(any());
    }

    @Test
    void aCyclicParameterWithoutACycleCountIsRefusedNamingTheField() {
        TestParameter parameter = new TestParameter();
        parameter.type = TestType.CYCLIC;
        parameter.speed = 10;
        parameter.lowerTurnForce = 10.0;
        parameter.upperTurnForce = 50.0;
        parameter.upperShutOffThreshold = 80.0;

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.save(parameter))
                .withMessageContaining("Cannot save the test parameter")
                .withMessageContaining("cycle count");
        verify(repository, never()).save(any());
    }

    @Test
    void saveAllRefusesTheBatchOnItsFirstInvalidParameter() {
        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.saveAll(List.of(destructive(200.0, 10))));
        verify(repository, never()).save(any());
    }

    @Test
    void aValidParameterReachesTheRepository() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        TestParameter parameter = destructive(150.0, 500);
        when(repository.save(parameter)).thenReturn(parameter);

        assertThat(service.save(parameter)).isSameAs(parameter);
        verify(repository).save(parameter);
    }

    @Test
    void anUntypedParameterIsStoredUnchecked() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        TestParameter parameter = new TestParameter();
        when(repository.save(parameter)).thenReturn(parameter);

        assertThat(service.save(parameter)).isSameAs(parameter);
    }

    private static TestParameter destructive(double upperShutOff, int speed) {
        TestParameter parameter = new TestParameter();
        parameter.type = TestType.DESTRUCTIVE;
        parameter.speed = speed;
        parameter.upperShutOffThreshold = upperShutOff;
        parameter.lowerShutOffThreshold = 1.0;
        return parameter;
    }
}
