package com.velora.api.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first invoices of a new year arrive when there is no counter row yet. Two issued at
 * the same instant used to both insert one; the second hit the primary key and that
 * invoice failed to issue. The row is now created by one atomic statement.
 *
 * <p>Uses a year far in the future, so it can never touch a real counter, and removes it
 * afterwards. {@code InvoiceNumberingTest} covers the counter's own arithmetic.
 */
@SpringBootTest
class InvoiceSequenceConcurrencyIntegrationTest {

    private static final int YEAR = 2099;

    @Autowired private InvoiceService invoiceService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void removeTheCounterRow() {
        jdbc.update("DELETE FROM invoice_sequence WHERE fiscal_year = ?", YEAR);
    }

    @Test
    @DisplayName("Eight invoices racing for a year with no counter row all get a number, with no gaps or repeats")
    void firstInvoicesOfANewYearDoNotCollide() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return allocate();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            List<Integer> numbers = new ArrayList<>();
            for (Future<Integer> f : futures) {
                numbers.add(f.get(60, TimeUnit.SECONDS));   // throws if any invoice failed
            }
            List<Integer> sorted = numbers.stream().sorted().toList();

            assertThat(sorted).doesNotHaveDuplicates();
            assertThat(sorted).as("consecutive from 1").containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A rollback returns the number, and finding the row already there changes nothing")
    void rollbackReturnsTheNumberAndTheRowIsNotRecreated() {
        assertThat(allocate()).isEqualTo(1);
        assertThat(allocate()).isEqualTo(2);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(invoiceService.allocateNumber(YEAR)).isEqualTo(3);
            status.setRollbackOnly();
        });

        assertThat(allocate())
                .as("the number the rolled-back invoice held is reused, not skipped")
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_sequence WHERE fiscal_year = ?",
                Integer.class, YEAR)).isEqualTo(1);
    }

    /** One invoice's worth of numbering: allocated inside its own transaction, as issuing does. */
    private int allocate() {
        Integer number = transactionTemplate.execute(status -> invoiceService.allocateNumber(YEAR));
        return number;
    }
}
