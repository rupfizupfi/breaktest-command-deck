package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.TestRunnerFactory;
import org.springframework.stereotype.Component;

@Component
final class DestructiveProgramFactory implements TestProgramFactory {
    private final SettingRepository settings;
    private final TestRunnerFactory runners;

    DestructiveProgramFactory(SettingRepository settings, TestRunnerFactory runners) {
        this.settings = settings;
        this.runners = runners;
    }

    @Override
    public TestType type() {
        return TestType.DESTRUCTIVE;
    }

    @Override
    public TestProgram create(TestResult result, RigLimits rig) {
        return new DestructiveProgram(result.testParameter, rig.suctionLoadMarginNewton(), settings,
                seconds -> runners.createSuckJob(seconds).start());
    }
}
