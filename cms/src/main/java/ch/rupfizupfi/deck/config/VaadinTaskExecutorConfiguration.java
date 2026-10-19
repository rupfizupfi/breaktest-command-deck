package ch.rupfizupfi.deck.config;

import com.vaadin.flow.spring.annotation.VaadinTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * The STOMP broker contributes three {@link TaskExecutor} beans, so Vaadin cannot pick one for
 * its async work and fails startup. The reserved {@link VaadinTaskExecutor#NAME} bean resolves it.
 */
@Configuration
public class VaadinTaskExecutorConfiguration {

    @Bean(VaadinTaskExecutor.NAME)
    public TaskExecutor vaadinTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("vaadin-task-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
