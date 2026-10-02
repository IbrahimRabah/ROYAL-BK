package com.velora.api.testsupport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one definition of "a database the tests may use": its name ends in {@code _test}.
 *
 * <p>A test that passes while writing to the real database is worse than one that fails, so
 * this is enforced twice, by {@link TestDatabaseGuardInitializer} (the configured URL, before
 * any connection is opened) and {@link TestDatabaseGuardCheck} (the database the connection
 * actually landed in). And underneath both, the test login has no access to any other
 * database at all — see scripts/db/create-test-db.ps1.
 */
public final class TestDatabaseGuard {

    private static final Pattern DATABASE_NAME = Pattern.compile("databaseName=([^;]+)", Pattern.CASE_INSENSITIVE);

    private TestDatabaseGuard() {
        // utility class
    }

    /** @return the database a JDBC URL selects, or null if it names none */
    public static String databaseNameOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        Matcher matcher = DATABASE_NAME.matcher(jdbcUrl);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    public static boolean isTestDatabase(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith("_test");
    }

    static String refusal(String what, String name) {
        return ("REFUSING TO RUN THE TESTS: " + what + " is '" + name + "', which is not a test database "
                + "(its name must end in '_test'). The tests create, change and delete rows; they must "
                + "never run against a real database. Point src/test/resources/application.yml at "
                + "royal_test - see scripts/db/create-test-db.ps1.");
    }
}
