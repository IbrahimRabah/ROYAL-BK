package com.velora.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Filtered unique indexes (one non-cancelled invoice per order, one active settlement per order)
 * are only writable by sessions whose SET options match what the index requires, otherwise every
 * INSERT fails with error 1934. This builds a scratch table the same way the migration builds
 * its indexes and then writes to it through the application's own pooled connection.
 */
@SpringBootTest
class SessionOptionsIntegrationTest {

    private static final String TABLE = "#tmp_filtered_index_probe";

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("The application's connection can write to a table with a filtered unique index")
    void appConnectionCanWriteToFilteredIndex() throws Exception {
        // The test login may not create real tables, so the probe is a temp table on ONE pooled
        // connection: record the session's default options, build the index the way the
        // migration does (every option stated), put the defaults back, then write as the app does.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT SESSIONPROPERTY('QUOTED_IDENTIFIER'), SESSIONPROPERTY('ANSI_NULLS'), "
                    + "SESSIONPROPERTY('ANSI_WARNINGS'), SESSIONPROPERTY('ARITHABORT'), "
                    + "SESSIONPROPERTY('CONCAT_NULL_YIELDS_NULL'), SESSIONPROPERTY('NUMERIC_ROUNDABORT')");
            rs.next();
            int qi = rs.getInt(1), nulls = rs.getInt(2), warn = rs.getInt(3), abort = rs.getInt(4),
                    concat = rs.getInt(5), round = rs.getInt(6);
            System.out.println("PROBE app session defaults: QUOTED_IDENTIFIER=" + qi + " ANSI_NULLS=" + nulls
                    + " ANSI_WARNINGS=" + warn + " ARITHABORT=" + abort + " CONCAT_NULL_YIELDS_NULL=" + concat
                    + " NUMERIC_ROUNDABORT=" + round);

            try {
                s.execute("SET QUOTED_IDENTIFIER ON; SET ANSI_NULLS ON; SET ANSI_WARNINGS ON; "
                        + "SET ARITHABORT ON; SET CONCAT_NULL_YIELDS_NULL ON; SET NUMERIC_ROUNDABORT OFF;");
                s.execute("CREATE TABLE " + TABLE + " (id INT IDENTITY PRIMARY KEY, order_id BIGINT NOT NULL, "
                        + "status VARCHAR(20) NOT NULL)");
                s.execute("CREATE UNIQUE NONCLUSTERED INDEX uq_probe ON " + TABLE
                        + "(order_id) WHERE status <> 'CANCELLED'");
            } finally {
                s.execute("SET QUOTED_IDENTIFIER " + onOff(qi) + "; SET ANSI_NULLS " + onOff(nulls)
                        + "; SET ANSI_WARNINGS " + onOff(warn) + "; SET ARITHABORT " + onOff(abort)
                        + "; SET CONCAT_NULL_YIELDS_NULL " + onOff(concat)
                        + "; SET NUMERIC_ROUNDABORT " + onOff(round) + ";");
            }

            // Error 1934 here would mean the app is locked out of every table with such an index.
            s.execute("INSERT INTO " + TABLE + " (order_id, status) VALUES (1, 'ISSUED')");
            s.execute("INSERT INTO " + TABLE + " (order_id, status) VALUES (1, 'CANCELLED')");
            s.execute("INSERT INTO " + TABLE + " (order_id, status) VALUES (1, 'CANCELLED')");

            assertThatThrownBy(() -> s.execute(
                    "INSERT INTO " + TABLE + " (order_id, status) VALUES (1, 'ISSUED')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getErrorCode())
                            .as("a duplicate active row is a uniqueness error (2601), not a SET-option error (1934)")
                            .isEqualTo(2601));
            s.execute("DROP TABLE " + TABLE);
        }
    }

    private static String onOff(int value) {
        return value == 1 ? "ON" : "OFF";
    }
}
