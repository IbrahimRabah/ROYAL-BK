package com.velora.api.customrequest.repository;

import com.velora.api.customrequest.domain.CustomOrderRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomOrderRequestRepository
        extends JpaRepository<CustomOrderRequest, Long>,
                JpaSpecificationExecutor<CustomOrderRequest> {

    /**
     * Loads one request under a write lock.
     *
     * <p>Used wherever a decision depends on the row's current state — the attachment
     * count, the status — so two concurrent callers cannot both pass the same check.
     * Held only for the length of the calling transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CustomOrderRequest r where r.id = :id")
    Optional<CustomOrderRequest> lockById(@Param("id") Long id);

    @Query("select coalesce(max(r.sequenceNumber), 0) from CustomOrderRequest r "
            + "where r.fiscalYear = :year")
    int highestSequenceFor(@Param("year") int year);
}
