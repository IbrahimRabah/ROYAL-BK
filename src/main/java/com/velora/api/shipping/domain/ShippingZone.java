package com.velora.api.shipping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A group of governorates that share a price and a delivery estimate.
 *
 * <p>Six zones exist even though only two prices are in use today. Zones are cheap
 * to keep and expensive to introduce later: the day Alexandria needs its own rate,
 * it is a price change rather than a data migration.
 */
@Entity
@Table(name = "shipping_zone")
@Getter
@Setter
@NoArgsConstructor
public class ShippingZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GREATER_CAIRO, DELTA, UPPER_EGYPT ... */
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name_ar", nullable = false, length = 100)
    private String nameAr;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Ceiling on the shipping charge for an order to this zone. When the per-unit
     * total is higher, this is charged instead. Null means no ceiling.
     */
    @Column(name = "max_shipping_cost", precision = 19, scale = 4)
    private BigDecimal maxShippingCost;

    public String nameFor(String locale) {
        return "en".equalsIgnoreCase(locale) ? nameEn : nameAr;
    }
}
