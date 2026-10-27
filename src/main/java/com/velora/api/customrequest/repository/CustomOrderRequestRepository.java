package com.velora.api.customrequest.repository;

import com.velora.api.customrequest.domain.CustomOrderRequest;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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

    // ---- the customer's own requests. Always scoped by customer_id: a request is never matched to
    // ---- an account by phone number or email, and a guest's (customer_id NULL) never matches.

    @EntityGraph(attributePaths = {"product"})
    Page<CustomOrderRequest> findByCustomerId(Long customerId, Pageable pageable);

    @EntityGraph(attributePaths = {"product"})
    Optional<CustomOrderRequest> findByRequestNumberAndCustomerId(String requestNumber,
                                                                  Long customerId);

    /** The write-locked variant for accepting or rejecting: the same lock the admin paths take. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CustomOrderRequest r "
            + "where r.requestNumber = :requestNumber and r.customerId = :customerId")
    Optional<CustomOrderRequest> lockByRequestNumberAndCustomerId(
            @Param("requestNumber") String requestNumber, @Param("customerId") Long customerId);

    long countByCustomerIdAndStatus(Long customerId, CustomRequestStatus status);

    @Query("select coalesce(max(r.sequenceNumber), 0) from CustomOrderRequest r "
            + "where r.fiscalYear = :year")
    int highestSequenceFor(@Param("year") int year);
}
