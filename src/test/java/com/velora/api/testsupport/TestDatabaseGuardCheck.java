package com.velora.api.testsupport;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The second lock: asks the database the connection actually reached which one it is. The
 * URL guard trusts configuration; this trusts only the server.
 *
 * <p>Picked up by component scanning of the test classpath, so it is part of every Spring
 * test context that has a datasource.
 */
@Component
public class TestDatabaseGuardCheck implements SmartInitializingSingleton {

    private final DataSource dataSource;

    public TestDatabaseGuardCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String actual = new JdbcTemplate(dataSource).queryForObject("SELECT DB_NAME()", String.class);
        if (!TestDatabaseGuard.isTestDatabase(actual)) {
            throw new IllegalStateException(TestDatabaseGuard.refusal("the connected database", actual));
        }
    }
}
