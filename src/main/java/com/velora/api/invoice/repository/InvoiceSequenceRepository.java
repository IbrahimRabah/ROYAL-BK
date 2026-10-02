package com.velora.api.invoice.repository;

import com.velora.api.invoice.domain.InvoiceSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Integer> {

    /**
     * Makes sure the year's counter row exists, safely under concurrency.
     *
     * <p>Without this, two invoices issued first thing in a new year both find no row,
     * both create one, and one of them fails on the primary key — an invoice that fails
     * to issue. The insert is attempted only when the row is absent, and if another
     * transaction wins the race the primary key refuses the duplicate and that error is
     * swallowed: the unique constraint is the guarantee, not a lock hint. (An
     * {@code UPDLOCK, HOLDLOCK} existence check was tried first and still failed under
     * concurrent load.) A new row is seeded from the highest number already used that year,
     * so a row removed by hand cannot cause a duplicate.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            BEGIN TRY
                INSERT INTO invoice_sequence (fiscal_year, last_number)
                SELECT :year, (SELECT COALESCE(MAX(sequence_number), 0)
                               FROM invoice WHERE fiscal_year = :year)
                WHERE NOT EXISTS (SELECT 1 FROM invoice_sequence WHERE fiscal_year = :year);
            END TRY
            BEGIN CATCH
                -- Lost the race to another transaction that inserted the same year. The
                -- primary key refused the duplicate, which is all we needed to know: the
                -- row exists now, and the caller locks it next.
                IF ERROR_NUMBER() NOT IN (2601, 2627) THROW;
            END CATCH
            """, nativeQuery = true)
    void ensureYear(@Param("year") int year);

    /**
     * Loads the year's counter under a write lock.
     *
     * <p>The lock is the mechanism that makes the sequence gapless: two invoices
     * issued at the same instant queue here instead of both reading the same number.
     * Held only for the length of the transaction that writes the invoice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InvoiceSequence s where s.fiscalYear = :year")
    Optional<InvoiceSequence> lockForYear(@Param("year") Integer year);
}
