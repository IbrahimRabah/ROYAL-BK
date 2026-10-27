package com.velora.api.catalog.domain;

import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

/**
 * THE SELLABLE UNIT.
 *
 * <p>Cart lines, order lines, stock movements, reservations and price rules all
 * reference this — never {@link Product}. A product with exactly one variant is
 * normal and expected, not a special case to optimize away.
 *
 * <p>{@code price} is TAX-INCLUSIVE: it is the final amount the customer pays.
 * Use {@code MoneyUtils.taxFromGross()} to extract the tax for the invoice.
 */
@Entity
@Table(name = "product_variant")
@Getter
@Setter
@NoArgsConstructor
public class ProductVariant extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Never reused, even after the variant is archived. */
    @Column(name = "sku", nullable = false, length = 60)
    private String sku;

    @Column(name = "barcode", length = 60)
    private String barcode;

    /** TAX-INCLUSIVE final price. */
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    /** The struck-through "was" price. Null when there is no discount. */
    @Column(name = "compare_at_price", precision = 19, scale = 4)
    private BigDecimal compareAtPrice;

    /** Owner-only. NEVER expose this in a public DTO. */
    @Column(name = "cost_price", precision = 19, scale = 4)
    private BigDecimal costPrice;

    /**
     * The rate a variant gets when none is given: zero while the company is not registered for
     * VAT. If it registers, set this back to {@code 0.1400} (and the {@code df_var_tax} default
     * with it) and give the variants their rate in the admin. There is deliberately no
     * application setting for it: the rate that matters is each variant's own {@code taxRate},
     * copied onto every order line when it is bought.
     */
    public static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("0.0000");

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate = DEFAULT_TAX_RATE;

    /** Drives weight-based shipping rates. */
    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "position", nullable = false)
    private short position;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VariantStatus status = VariantStatus.ACTIVE;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /** The attribute combination that defines this variant: Colour=Gold, Size=42mm. */
    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VariantAttributeValue> attributeValues = new ArrayList<>();

    /** on_hand minus reserved. Derived — never stored. */
    @Formula("(select coalesce(i.qty_on_hand - i.qty_reserved, 0) from inventory i "
            + "where i.variant_id = id)")
    private Integer availableQty;

    public int getAvailable() {
        return availableQty == null ? 0 : availableQty;
    }

    public boolean isInStock() {
        return getAvailable() > 0;
    }

    public boolean isSellable() {
        return status == VariantStatus.ACTIVE && archivedAt == null && isInStock();
    }

    public boolean hasDiscount() {
        return compareAtPrice != null && compareAtPrice.compareTo(price) > 0;
    }

    /** Percentage off, for the badge on the product card. */
    public Integer discountPercent() {
        if (!hasDiscount()) {
            return null;
        }
        BigDecimal off = compareAtPrice.subtract(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(compareAtPrice, 0, java.math.RoundingMode.HALF_UP);
        return off.intValue();
    }
}
