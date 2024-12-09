package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.FileSystemCheck;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;

@Service
public class TestRunnerFactory {
    private final ApplicationContext applicationContext;

    public TestRunnerFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public TestRunnerThread createTestRunnerThread() {
        return new TestRunnerThread(this);
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractTest> T createTestRunner(Class<T> testRunnerClass, TestResult testResult, Logger logger) {
        try {
            // Get the constructor of the testRunnerClass
            Constructor<T> constructor;
            Constructor<?> firstConstructor = testRunnerClass.getConstructors()[0];
            if (firstConstructor.getDeclaringClass().equals(testRunnerClass)) {
                constructor = (Constructor<T>) firstConstructor;
            } else {
                throw new RuntimeException("Failed to found constructor for " + testRunnerClass.getName());
            }

            // Get the parameter types of the constructor
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] parameters = new Object[parameterTypes.length];

            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(TestResult.class)) {
                    parameters[i] = testResult;
                } else if (parameterTypes[i].equals(TestRunnerFactory.class)) {
                    parameters[i] = this;
                } else if (parameterTypes[i].equals(Logger.class)) {
                    parameters[i] = logger;
                } else {
                    parameters[i] = applicationContext.getBean(parameterTypes[i]);
                }
            }

            return constructor.newInstance(parameters);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test runner instance", e);
        }
    }

    public LoadCellThread createLoadCellThread(TestContext testContext, LoadCellDevice loadCellDevice) {
        return new LoadCellThread(testContext, loadCellDevice, applicationContext.getBean(CSVStoreService.class));
    }

    public Logger createLogger(TestResult testResult) {
        return new Logger(testResult, applicationContext.getBean(SimpMessagingTemplate.class), applicationContext.getBean(StorageLocationService.class));
    }

    public AbstractCheck[] getStartupChecks() {
        return new AbstractCheck[] {
            new FileSystemCheck(applicationContext.getBean(StorageLocationService.class))
        };
    }
}
