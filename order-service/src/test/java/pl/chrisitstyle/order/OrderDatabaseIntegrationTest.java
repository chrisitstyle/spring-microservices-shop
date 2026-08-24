package pl.chrisitstyle.order;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class OrderDatabaseIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldUsePostgreSql17AndApplyFlywayMigrations() {

        String postgresVersion =
                jdbcTemplate.queryForObject(
                        "SELECT current_setting('server_version')",
                        String.class
                );

        Integer successfulMigrationCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM flyway_schema_history
                        WHERE success = true
                        """,
                        Integer.class
                );

        assertThat(postgresVersion)
                .startsWith("17.");

        assertThat(successfulMigrationCount)
                .isPositive();
    }

    @Test
    void shouldCreateSagaRecoveryAndFencingColumns() {

        List<String> columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'order_creation_sagas'
                        """,
                        String.class
                );

        assertThat(columns)
                .contains(
                        "recovery_owner",
                        "recovery_lease_until",
                        "recovery_fence"
                );
    }
}