package ch.rupfizupfi.deck;

import ch.rupfizupfi.deck.data.UserRepository;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * The entry point of the Spring Boot application.
 * <p>
 * Use the @PWA annotation make the application installable on phones, tablets
 * and some desktop browsers.
 */
@SpringBootApplication
@Theme("breaktest-command-deck")
@ColorScheme(ColorScheme.Value.DARK)
// Vaadin 25 loads all Lumo modules automatically except the utility classes, which the
// theme previously requested via the now-unsupported "lumoImports" in theme.json.
@StyleSheet(Lumo.UTILITY_STYLESHEET)
// Spring Boot 4 registers SqlInitializationProperties from DataSourceInitializationAutoConfiguration,
// which backs off because the initializer below is an ApplicationScriptDatabaseInitializer. Registering
// the properties here keeps them injectable.
@EnableConfigurationProperties(SqlInitializationProperties.class)
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    ApplicationDataSourceScriptDatabaseInitializer dataSourceScriptDatabaseInitializer(DataSource dataSource, SqlInitializationProperties properties, @Lazy UserRepository repository) {
        // This bean ensures the database is only initialized when empty
        return new ApplicationDataSourceScriptDatabaseInitializer(dataSource, properties) {
            @Override
            public boolean initializeDatabase() {
                if (repository.count() == 0L) {
                    return super.initializeDatabase();
                }
                return false;
            }
        };
    }
}
