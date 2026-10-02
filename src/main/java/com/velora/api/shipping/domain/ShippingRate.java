package com.velora.api.shipping.domain;

import jakarta.persistence.Column;
import com.velora.api.catalog.domain.ShippingSizeClass;
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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a zone costs to ship to.
 *
 * <p>One row per (zone, size class): {@link #baseCost} is the cost of shipping ONE
 * unit of that size to the zone. An order pays the sum over its lines, capped at
 * {@link ShippingZone#getMaxShippingCost()}.
 *
 * <p>{@code maxWeightGrams}, {@code costPerExtraKg} and {@code freeShippingOver} are
 * no longer read by the calculation; the columns remain in the table.
 */
@Entity
@Table(name = "shipping_rate")
@Getter
@Setter
@NoArgsConstructor
public class ShippingRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zone_id", nullable = false)
    private ShippingZone zone;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_class", nullable = false, length = 10)
    private ShippingSizeClass sizeClass;

    /** Cost of ONE unit of {@link #sizeClass} shipped to the zone. */
    @Column(name = "base_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseCost;

    /** Unused — see the class comment. */
    @Column(name = "max_weight_grams")
    private Integer maxWeightGrams;

    @Column(name = "cost_per_extra_kg", nullable = false, precision = 19, scale = 4)
    private BigDecimal costPerExtraKg = BigDecimal.ZERO;

    /** Unused — see the class comment. */
    @Column(name = "free_shipping_over", precision = 19, scale = 4)
    private BigDecimal freeShippingOver;

    /** Extra charge for collecting cash. Zero today. */
    @Column(name = "cod_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal codFee = BigDecimal.ZERO;

    @Column(name = "delivery_days_min", nullable = false)
    private short deliveryDaysMin = 2;

    @Column(name = "delivery_days_max", nullable = false)
    private short deliveryDaysMax = 5;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

    public boolean hasFreeThreshold() {
        return freeShippingOver != null;
    }

    public boolean isWeightBased() {
        return maxWeightGrams != null
                && costPerExtraKg.compareTo(BigDecimal.ZERO) > 0;
    }
}
