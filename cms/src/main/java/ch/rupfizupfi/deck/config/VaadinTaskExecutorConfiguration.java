package ch.rupfizupfi.deck.config;

import com.vaadin.flow.spring.annotation.VaadinTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Vaadin 25 resolves a {@link TaskExecutor} for its asynchronous work (Signals) during
 * servlet init. Enabling the STOMP broker via {@code @EnableWebSocketMessageBroker}
 * contributes three executor beans (clientInbound/clientOutbound/brokerChannel), which
 * leaves Vaadin unable to pick one and fails startup.
 * <p>
 * Declaring an executor under the reserved {@link VaadinTaskExecutor#NAME} bean name makes
 * Vaadin use this one and ignore the messaging executors.
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
