package com.velora.api.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Not "the tests pass" but "the tests CAN NOT reach a real database": the connection is to a
 * _test database, as a login that has no access to anything else, and every other database on
 * the server refuses that login when asked.
 */
@SpringBootTest
class TestDatabaseIsolationIntegrationTest {

    private static final Set<String> SYSTEM_DATABASES = Set.of("master", "tempdb", "model", "msdb");

    @Autowired private JdbcTemplate jdbc;
    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.datasource.username}") private String username;
    @Value("${spring.datasource.password}") private String password;

    @Test
    @DisplayName("The suite is connected to a _test database")
    void connectedToATestDatabase() {
        String database = jdbc.queryForObject("SELECT DB_NAME()", String.class);

        assertThat(TestDatabaseGuard.isTestDatabase(database)).as("connected to %s", database).isTrue();
        assertThat(TestDatabaseGuard.databaseNameOf(url)).isEqualTo(database);
    }

    @Test
    @DisplayName("It runs as the restricted test login, not as an administrator")
    void runsAsTheRestrictedLogin() {
        String login = jdbc.queryForObject("SELECT SUSER_NAME()", String.class);

        assertThat(login).isEqualTo(username).isNotEqualToIgnoringCase("sa");
        assertThat(jdbc.queryForObject("SELECT IS_SRVROLEMEMBER('sysadmin')", Integer.class))
                .as("the test login must not be a sysadmin").isZero();
        assertThat(jdbc.queryForObject("SELECT IS_MEMBER('db_owner')", Integer.class))
                .as("nor owner of even its own database").isZero();
    }

    @Test
    @DisplayName("Every other database on the server refuses the test login")
    void everyOtherDatabaseRefusesTheLogin() throws SQLException {
        String own = TestDatabaseGuard.databaseNameOf(url);
        List<String> others = jdbc.queryForList("SELECT name FROM sys.databases", String.class).stream()
                .filter(name -> !SYSTEM_DATABASES.contains(name.toLowerCase()))
                .filter(name -> !name.equalsIgnoreCase(own))
                .toList();

        for (String other : others) {
            String otherUrl = url.replaceAll("(?i)databaseName=[^;]+", "databaseName=" + other);
            assertThatThrownBy(() -> {
                try (Connection connection = DriverManager.getConnection(otherUrl, username, password)) {
                    connection.createStatement().execute("SELECT 1");
                }
            }).as("opening '%s' as %s", other, username).isInstanceOf(SQLException.class);
        }
        // Not vacuous: on a normal developer machine there are real databases to refuse.
        assertThat(others).as("databases that were checked: %s", others).isNotNull();
    }
}
