package com.velora.api.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** The rule that keeps the test suite away from a real database. No database involved. */
class TestDatabaseGuardTest {

    @Test
    @DisplayName("Only a name ending in _test is a test database")
    void onlyTestDatabasesPass() {
        assertThat(TestDatabaseGuard.isTestDatabase("royal_test")).isTrue();
        assertThat(TestDatabaseGuard.isTestDatabase("ROYAL_TEST")).isTrue();
        assertThat(TestDatabaseGuard.isTestDatabase("shop_test")).isTrue();

        assertThat(TestDatabaseGuard.isTestDatabase("royal")).isFalse();
        assertThat(TestDatabaseGuard.isTestDatabase("velora")).isFalse();
        assertThat(TestDatabaseGuard.isTestDatabase("royal_test_backup")).isFalse();
        assertThat(TestDatabaseGuard.isTestDatabase("test_royal")).isFalse();
        assertThat(TestDatabaseGuard.isTestDatabase("master")).isFalse();
        assertThat(TestDatabaseGuard.isTestDatabase("")).isFalse();
        assertThat(TestDatabaseGuard.isTestDatabase(null)).isFalse();
    }

    @Test
    @DisplayName("The database is read from the JDBC URL wherever it sits in it")
    void databaseNameIsParsedFromTheUrl() {
        assertThat(TestDatabaseGuard.databaseNameOf(
                "jdbc:sqlserver://localhost:1433;databaseName=royal_test;encrypt=true")).isEqualTo("royal_test");
        assertThat(TestDatabaseGuard.databaseNameOf(
                "jdbc:sqlserver://localhost:1433;encrypt=true;DatabaseName=royal")).isEqualTo("royal");
        assertThat(TestDatabaseGuard.databaseNameOf("jdbc:sqlserver://localhost:1433")).isNull();
        assertThat(TestDatabaseGuard.databaseNameOf(null)).isNull();
    }

    @Test
    @DisplayName("A URL with no database at all is refused: it would land in the login's default database")
    void aUrlWithoutADatabaseIsRefused() {
        assertThat(TestDatabaseGuard.isTestDatabase(TestDatabaseGuard.databaseNameOf(
                "jdbc:sqlserver://localhost:1433;encrypt=true"))).isFalse();
    }

    @Test
    @DisplayName("The initializer stops a context pointed at royal or velora, and lets royal_test through")
    void initializerRefusesRealDatabases() {
        for (String real : new String[] {"royal", "velora", "master"}) {
            assertThatThrownBy(() -> start("jdbc:sqlserver://localhost:1433;databaseName=" + real + ";encrypt=true"))
                    .as("databaseName=%s", real)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REFUSING TO RUN THE TESTS")
                    .hasMessageContaining("'" + real + "'");
        }
        assertThatThrownBy(() -> start("jdbc:sqlserver://localhost:1433;encrypt=true"))
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> start("jdbc:sqlserver://localhost:1433;databaseName=royal_test;encrypt=true"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A context with no datasource configured is left alone")
    void noDatasourceMeansNothingToProtect() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment());

        assertThatCode(() -> new TestDatabaseGuardInitializer().initialize(context)).doesNotThrowAnyException();
    }

    private static void start(String url) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url", url));
        new TestDatabaseGuardInitializer().initialize(context);
    }
}
