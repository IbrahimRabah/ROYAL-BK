package com.velora.api.catalog.review.repository;

import com.velora.api.catalog.review.domain.ProductReview;
import com.velora.api.catalog.review.domain.ReviewStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductReviewRepository
        extends JpaRepository<ProductReview, Long>, JpaSpecificationExecutor<ProductReview> {

    boolean existsByCustomerIdAndProductId(Long customerId, Long productId);

    /** The customer's own review, or nothing: another customer's id is indistinguishable from a missing one. */
    Optional<ProductReview> findByIdAndCustomerId(Long id, Long customerId);

    Page<ProductReview> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    Page<ProductReview> findByProductIdAndStatusOrderByCreatedAtDesc(Long productId, ReviewStatus status,
                                                                      Pageable pageable);

    /** [rating, how many] over the APPROVED reviews of one product. */
    @Query("select r.rating, count(r) from ProductReview r "
            + "where r.product.id = :productId and r.status = com.velora.api.catalog.review.domain.ReviewStatus.APPROVED "
            + "group by r.rating")
    List<Object[]> breakdown(@Param("productId") Long productId);

    /** [status, how many] over every review, for the tabs of the admin screen. */
    @Query("select r.status, count(r) from ProductReview r group by r.status")
    List<Object[]> countByStatus();

    /**
     * What the customer can still review: products on a DELIVERED order of theirs with no review of theirs yet.
     * [productId, orderNumber] (the latest delivered order that carried the product).
     */
    @Query("""
            select oi.product.id, max(o.orderNumber)
            from OrderItem oi join oi.order o
            where o.customer.id = :customerId
              and o.fulfillmentStatus = com.velora.api.order.domain.FulfillmentStatus.DELIVERED
              and oi.product is not null
              and not exists (select 1 from ProductReview r
                              where r.customerId = :customerId and r.product.id = oi.product.id)
            group by oi.product.id
            """)
    List<Object[]> eligible(@Param("customerId") Long customerId);

    /** [orderItemId, orderNumber] of the delivered lines that carried this product for this customer, oldest first. */
    @Query("""
            select oi.id, o.orderNumber
            from OrderItem oi join oi.order o
            where o.customer.id = :customerId and oi.product.id = :productId
              and o.fulfillmentStatus = com.velora.api.order.domain.FulfillmentStatus.DELIVERED
            order by oi.id
            """)
    List<Object[]> proof(@Param("customerId") Long customerId, @Param("productId") Long productId);
}
