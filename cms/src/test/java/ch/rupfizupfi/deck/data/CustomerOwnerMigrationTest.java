package ch.rupfizupfi.deck.data;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code V4__customer_owner} backfills each customer's owner from its projects: the unique owner when
 * every referencing project has the same non-null one, otherwise null (shared), and null for a
 * customer no project references. Seeds at V3, migrates, and reads the result — on PostgreSQL (the
 * production database; skips without Docker) and on H2 in MODE=PostgreSQL (dev).
 */
class CustomerOwnerMigrationTest {

    @Test
    void backfillsCustomerOwnersOnPostgres() throws SQLException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            assertBackfill(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @Test
    void backfillsCustomerOwnersOnH2() throws SQLException {
        assertBackfill("jdbc:h2:mem:customer-owner-migration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH", "sa", "");
    }

    private static void assertBackfill(String url, String user, String password) throws SQLException {
        flyway(url, user, password).target("3").load().migrate();

        Map<String, Long> customers = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            long alice = insert(connection, "insert into application_user (version, username, name) values (0, 'alice', 'Alice')");
            long bob = insert(connection, "insert into application_user (version, username, name) values (0, 'bob', 'Bob')");

            for (String name : new String[]{"aliceOnly", "aliceAndBob", "aliceAndShared", "sharedOnly", "unreferenced"}) {
                customers.put(name, insert(connection, "insert into customer (version, organization) values (0, '" + name + "')"));
            }
            project(connection, customers.get("aliceOnly"), alice);
            project(connection, customers.get("aliceOnly"), alice);
            project(connection, customers.get("aliceAndBob"), alice);
            project(connection, customers.get("aliceAndBob"), bob);
            project(connection, customers.get("aliceAndShared"), alice);
            project(connection, customers.get("aliceAndShared"), null);
            project(connection, customers.get("sharedOnly"), null);

            flyway(url, user, password).load().migrate();

            assertThat(ownerOf(connection, customers.get("aliceOnly"))).isEqualTo(alice);
            assertThat(ownerOf(connection, customers.get("aliceAndBob"))).isNull();
            assertThat(ownerOf(connection, customers.get("aliceAndShared"))).isNull();
            assertThat(ownerOf(connection, customers.get("sharedOnly"))).isNull();
            assertThat(ownerOf(connection, customers.get("unreferenced"))).isNull();
            // V2's demo customer has no project, so it stays shared.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select owner_id from customer where organization = 'Climbing Club'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
            }
        }
    }

    private static FluentConfiguration flyway(String url, String user, String password) {
        return Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration");
    }

    private static void project(Connection connection, long customerId, Long ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into project (version, name, customer_id, owner_id) values (0, 'p', ?, ?)")) {
            statement.setLong(1, customerId);
            if (ownerId == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, ownerId);
            }
            statement.executeUpdate();
        }
    }

    private static long insert(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                // PostgreSQL returns every column, H2 the identity: read it by name.
                return keys.getLong("id");
            }
        }
    }

    private static Long ownerOf(Connection connection, long customerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select owner_id from customer where id = ?")) {
            statement.setLong(1, customerId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                long owner = rs.getLong(1);
                return rs.wasNull() ? null : owner;
            }
        }
    }
}
