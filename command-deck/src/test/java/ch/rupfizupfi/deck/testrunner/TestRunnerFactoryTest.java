package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The constructor lookup {@code TestRunnerFactory#createTestRunner} performs by reflection. */
class TestRunnerFactoryTest {

    private ApplicationContext applicationContext;
    private TestRunnerFactory factory;
    private TestResult testResult;
    private TestLogger testLogger;

    @BeforeEach
    void wireFactory() {
        applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(DeviceService.class)).thenReturn(mock(DeviceService.class));
        when(applicationContext.getBean(MotorSafetyController.class))
                .thenReturn(mock(MotorSafetyController.class));
        factory = new TestRunnerFactory(applicationContext);
        testResult = new TestResult();
        testResult.setId(11L);
        testLogger = mock(TestLogger.class);
    }

    @Test
    void theSolePublicConstructorIsResolvedByTypeOutOfTheContext() {
        DestructiveTest test = factory.createTestRunner(DestructiveTest.class, testResult, testLogger);

        assertThat(test).isNotNull();
    }

    @Test
    void aSecondPublicConstructorIsRefusedInsteadOfPickedAtRandom() {
        assertThatThrownBy(() -> factory.createTestRunner(TwoConstructors.class, testResult, testLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one public constructor")
                .hasMessageContaining("2");
    }

    @Test
    void anUnresolvableParameterIsNamedWithItsIndexAndType() {
        when(applicationContext.getBean(Random.class))
                .thenThrow(new NoSuchBeanDefinitionException(Random.class));

        assertThatThrownBy(() -> factory.createTestRunner(UnregisteredParameter.class, testResult, testLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("constructor parameter 5")
                .hasMessageContaining(Random.class.getName())
                .hasMessageContaining(UnregisteredParameter.class.getName())
                .hasCauseInstanceOf(NoSuchBeanDefinitionException.class);
    }

    public static class TwoConstructors extends AbstractTest {
        public TwoConstructors(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory,
                               DeviceService deviceService, MotorSafetyController motorSafety) {
            super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
        }

        public TwoConstructors(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory,
                               DeviceService deviceService, MotorSafetyController motorSafety, Random random) {
            super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
        }

        @Override
        void setup() {
        }

        @Override
        public void handleSignal(int signal) {
        }
    }

    public static class UnregisteredParameter extends AbstractTest {
        public UnregisteredParameter(TestResult testResult, TestLogger testLogger,
                                     TestRunnerFactory testRunnerFactory, DeviceService deviceService,
                                     MotorSafetyController motorSafety, Random random) {
            super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
        }

        @Override
        void setup() {
        }

        @Override
        public void handleSignal(int signal) {
        }
    }
}
