package com.velora.api.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Stops every Spring test context from starting if the configured datasource is not a test
 * database. Looks at the URL only, so it fires before a single connection is opened.
 *
 * <p>Registered in {@code src/test/resources/META-INF/spring.factories}, so it covers every
 * {@code @SpringBootTest} in the project without each one having to ask for it.
 */
public class TestDatabaseGuardInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String url = context.getEnvironment().getProperty("spring.datasource.url");
        if (url == null) {
            return;     // a context with no datasource has nothing to protect
        }
        String database = TestDatabaseGuard.databaseNameOf(url);
        if (!TestDatabaseGuard.isTestDatabase(database)) {
            throw new IllegalStateException(TestDatabaseGuard.refusal("the configured database", database));
        }
    }
}
