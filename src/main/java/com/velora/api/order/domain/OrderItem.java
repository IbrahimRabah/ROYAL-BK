package com.velora.api.order.domain;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One purchased line, FROZEN at the moment of purchase.
 *
 * <p>The variant and product references exist for reporting and reorder only.
 * <b>Never read a price or a name from them when displaying a historical order.</b>
 * If order lines resolved their values live, editing a price next week would
 * silently rewrite every invoice that ever contained that product — including ones
 * already filed with an accountant.
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    /** Reference only. Nullable because a variant may eventually be purged. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    // ------------------------------------------------ SNAPSHOT: authoritative

    @Column(name = "product_name_ar", nullable = false, length = 255)
    private String productNameAr;

    @Column(name = "product_name_en", nullable = false, length = 255)
    private String productNameEn;

    @Column(name = "sku", nullable = false, length = 60)
    private String sku;

    /** "ذهبي / 42 مم" */
    @Column(name = "variant_summary", length = 255)
    private String variantSummary;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Tax-inclusive, as charged. */
    @Column(name = "unit_price_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceGross;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** A discount that applied to this line specifically. */
    @Column(name = "line_discount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineDiscount = BigDecimal.ZERO;

    /**
     * This line's share of a cart-wide coupon.
     *
     * <p>THE field that makes partial returns possible. A three-item order with a
     * 20% cart coupon, one item returned: the refundable amount is not that item's
     * list price. Allocate at order creation or the question has no correct answer,
     * and it cannot be reconstructed afterwards.
     */
    @Column(name = "allocated_cart_discount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedCartDiscount = BigDecimal.ZERO;

    /** The rate in force at purchase. Rates change; invoices must not. */
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "line_total_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotalGross;

    @Column(name = "line_tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTaxAmount;

    /** Assembly fee PER PIECE at purchase, tax-inclusive. Zero when the piece needs none. */
    @Column(name = "assembly_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal assemblyFee = BigDecimal.ZERO;

    @Column(name = "quantity_returned", nullable = false)
    private int quantityReturned;

    // ------------------------------------------------------------------ helpers

    /** The assembly charged for this whole line: per-piece fee x quantity. */
    public BigDecimal assemblyTotal() {
        return com.velora.api.common.util.MoneyUtils.lineTotal(assemblyFee, quantity);
    }

    /** Tax inside {@link #assemblyTotal()}, at this line's own rate. */
    public BigDecimal assemblyTax() {
        return com.velora.api.common.util.MoneyUtils.taxFromGross(assemblyTotal(), taxRate);
    }

    public int returnableQuantity() {
        return quantity - quantityReturned;
    }

    /**
     * What one unit of this line is actually worth back to the customer, after its
     * share of every discount. This is the number a refund uses.
     */
    public BigDecimal refundableUnitValue() {
        BigDecimal totalDiscount = lineDiscount.add(allocatedCartDiscount);
        BigDecimal netLine = lineTotalGross.subtract(totalDiscount);
        return netLine.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP);
    }

    public String nameFor(String locale) {
        return "en".equalsIgnoreCase(locale) ? productNameEn : productNameAr;
    }
}
