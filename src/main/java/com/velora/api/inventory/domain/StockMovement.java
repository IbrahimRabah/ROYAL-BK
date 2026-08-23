package com.velora.api.inventory.domain;

import com.velora.api.catalog.domain.ProductVariant;
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
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * APPEND-ONLY ledger of stock changes. Never UPDATE or DELETE a row here.
 *
 * <p>{@code qtyAfter} stores the running balance so the ledger can be reconciled
 * against {@code inventory.qty_on_hand}. A mismatch means something wrote to
 * inventory without recording why — which is the bug you want to find.
 */
@Entity
@Table(name = "stock_movement")
@Getter
@Setter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    /** Signed: +10 received, -2 sold. */
    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "qty_after", nullable = false)
    private int qtyAfter;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id", length = 60)
    private String referenceId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "actor_id")
    private Long actorId;

    /** Copied, because the account may be renamed or removed later. */
    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
