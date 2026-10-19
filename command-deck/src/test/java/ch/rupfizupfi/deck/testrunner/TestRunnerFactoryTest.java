package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.api.services.SuckService;
import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.program.TestProgram;
import ch.rupfizupfi.deck.testrunner.program.TestProgramRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What {@code TestRunnerFactory} hands a run: its program, its rig-limit snapshot and its vacuum job. */
class TestRunnerFactoryTest {

    private ApplicationContext applicationContext;
    private TestRunnerFactory factory;
    private TestResult testResult;

    @BeforeEach
    void wireFactory() {
        applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(DeviceService.class)).thenReturn(mock(DeviceService.class));
        when(applicationContext.getBean(MotorSafetyController.class))
                .thenReturn(mock(MotorSafetyController.class));
        factory = new TestRunnerFactory(applicationContext);
        testResult = new TestResult();
        testResult.setId(11L);
    }

    /** OQ-64 step 7: the program comes from the registry, by type; no reflection is left. */
    @Test
    void theProgramComesFromTheRegistry() {
        TestProgramRegistry registry = mock(TestProgramRegistry.class);
        TestProgram program = mock(TestProgram.class);
        when(applicationContext.getBean(TestProgramRegistry.class)).thenReturn(registry);
        when(registry.create(testResult, RigLimits.DEFAULTS)).thenReturn(program);

        assertThat(factory.createProgram(testResult, RigLimits.DEFAULTS)).isSameAs(program);
    }

    @Test
    void rigLimitsAreReadFromTheSettingsEachTimeSoEveryRunGetsAFreshSnapshot() {
        SettingRepository settings = mock(SettingRepository.class);
        when(applicationContext.getBean(SettingRepository.class)).thenReturn(settings);
        doReturn(Setting.create(Setting.Key.RIG_RATED_CAPACITY, 50), Setting.create(Setting.Key.RIG_RATED_CAPACITY, 150)).when(settings).getSetting(Setting.Key.RIG_RATED_CAPACITY.getKey());
        doReturn(Setting.create(Setting.Key.RIG_MAX_SPEED, 200)).when(settings).getSetting(Setting.Key.RIG_MAX_SPEED.getKey());
        doReturn(Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 500)).when(settings).getSetting(Setting.Key.RIG_SUCTION_LOAD_MARGIN.getKey());

        assertThat(factory.rigLimits()).isEqualTo(new RigLimits(50, 200));
        assertThat(factory.rigLimits()).isEqualTo(new RigLimits(150, 200));
    }

    @Test
    void anUnusableRigLimitSettingRefusesTheRunInsteadOfFallingBack() {
        SettingRepository settings = mock(SettingRepository.class);
        when(applicationContext.getBean(SettingRepository.class)).thenReturn(settings);
        doReturn(Setting.create(Setting.Key.RIG_RATED_CAPACITY, -1)).when(settings).getSetting(Setting.Key.RIG_RATED_CAPACITY.getKey());
        doReturn(Setting.create(Setting.Key.RIG_MAX_SPEED, 200)).when(settings).getSetting(Setting.Key.RIG_MAX_SPEED.getKey());
        doReturn(Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 500)).when(settings).getSetting(Setting.Key.RIG_SUCTION_LOAD_MARGIN.getKey());

        assertThatThrownBy(() -> factory.rigLimits())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rated capacity");
    }

    /** BL-108: the vacuum job's alarm reaches the dashboard's log topic, not just a sink in a test. */
    @Test
    @Timeout(10)
    void aVacuumRelayThatStaysOnIsAnnouncedOnTheLogTopic() {
        SuckService suckService = mock(SuckService.class);
        when(suckService.enable()).thenReturn(true);
        when(suckService.disable()).thenReturn(false);
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        when(applicationContext.getBean(SuckService.class)).thenReturn(suckService);
        when(applicationContext.getBean(SimpMessagingTemplate.class)).thenReturn(template);

        factory.createSuckJob(0).suck();

        verify(template).convertAndSend("/topic/logs", SuckJob.offRefusedAlarm(SuckJob.MAX_OFF_ATTEMPTS));
    }
}
