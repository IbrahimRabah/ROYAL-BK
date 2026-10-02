package com.velora.api.customrequest.repository;

import com.velora.api.customrequest.domain.CustomOrderRequestSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomOrderRequestSequenceRepository
        extends JpaRepository<CustomOrderRequestSequence, Integer> {

    /**
     * Makes sure the year's counter row exists, safely under concurrency.
     *
     * <p>Without this, two requests arriving first thing in a new year both find no row,
     * both create one, and one of them fails on the primary key. The insert is attempted only when the row is absent, and if another
     * transaction wins the race the primary key refuses the duplicate and that error is
     * swallowed: the unique constraint is the guarantee, not a lock hint. (An
     * {@code UPDLOCK, HOLDLOCK} existence check was tried first and still failed under
     * concurrent load.) A new row is seeded from
     * the highest number already used that year, so a row removed by hand cannot cause a
     * duplicate.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            BEGIN TRY
                INSERT INTO custom_order_request_sequence (fiscal_year, last_number)
                SELECT :year, (SELECT COALESCE(MAX(sequence_number), 0)
                               FROM custom_order_request WHERE fiscal_year = :year)
                WHERE NOT EXISTS (SELECT 1 FROM custom_order_request_sequence WHERE fiscal_year = :year);
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
     * Loads the year's counter under a write lock — the lock is what makes the sequence
     * gapless: two requests created at the same instant queue here instead of both
     * reading the same number. Released when the transaction ends.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CustomOrderRequestSequence s where s.fiscalYear = :year")
    Optional<CustomOrderRequestSequence> lockForYear(@Param("year") Integer year);
}
