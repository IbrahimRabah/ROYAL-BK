package com.velora.api.catalog.review.domain;

import com.velora.api.catalog.domain.Product;
import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One customer's review of one product. It belongs to the PRODUCT (every colour shares the reviews) and is
 * proven by a delivered order line. The reviewer's display name is copied at write time, like an order's
 * delivery address: a later change to the account does not rewrite what the page shows.
 */
@Entity
@Table(name = "product_review")
@Getter
@Setter
@NoArgsConstructor
public class ProductReview extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    /** «Ahmed M.»: first name and the initial of the last, never the full name. */
    @Column(name = "reviewer_name", nullable = false, length = 100)
    private String reviewerName;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "comment", length = 2000)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "moderated_by")
    private Long moderatedBy;

    @Column(name = "moderated_at")
    private OffsetDateTime moderatedAt;

    @Column(name = "moderation_note", length = 500)
    private String moderationNote;
}
