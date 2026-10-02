package com.velora.api.order.domain;

import com.velora.api.identity.domain.AppUser;
import com.velora.api.shipping.domain.Governorate;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An order is a financial and legal record, not a view of the catalog.
 *
 * <p>Everything commercial is COPIED at purchase time. The address is not a foreign
 * key to {@code customer_address} because customers edit and delete addresses, and a
 * delivered order must always show where it actually went. The same reasoning
 * applies to prices, names and the shipping zone.
 *
 * <p>Two status columns, never one. Fulfilment and payment move independently, and
 * {@code DELIVERED} + {@code PENDING} is the correct state for a COD parcel in the
 * courier's hands.
 *
 * <p>Orders are never deleted. Cancellation and return are states.
 */
@Entity
@Table(name = "customer_order")
@Getter
@Setter
@NoArgsConstructor
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable, e.g. VLR-260809-4821. Unique forever. */
    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    /** Null for guest checkout. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private AppUser customer;

    // ------------------------------------------------- two independent machines

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 30)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    // ------------------------------------------------ money, all tax-INCLUSIVE

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "EGP";

    @Column(name = "subtotal_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotalGross;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "shipping_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal shippingCost = BigDecimal.ZERO;

    @Column(name = "cod_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal codFee = BigDecimal.ZERO;

    /** What the courier collects. */
    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal;

    /** Extracted from the gross amounts, not added on top. */
    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal netTotal;

    // ------------------------------------------------------- contact SNAPSHOT

    @Column(name = "contact_name", nullable = false, length = 150)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Column(name = "contact_alt_phone", length = 20)
    private String contactAltPhone;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    // ------------------------------------------------------- address SNAPSHOT

    /** Kept for reporting by governorate. The NAME below is the record of truth. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ship_governorate_id")
    private Governorate shipGovernorate;

    @Column(name = "ship_governorate_name", nullable = false, length = 100)
    private String shipGovernorateName;

    @Column(name = "ship_city_name", nullable = false, length = 120)
    private String shipCityName;

    @Column(name = "ship_area", length = 150)
    private String shipArea;

    @Column(name = "ship_street_address", nullable = false, length = 255)
    private String shipStreetAddress;

    @Column(name = "ship_building", length = 50)
    private String shipBuilding;

    @Column(name = "ship_floor", length = 20)
    private String shipFloor;

    @Column(name = "ship_apartment", length = 20)
    private String shipApartment;

    @Column(name = "ship_landmark", length = 255)
    private String shipLandmark;

    // ------------------------------------------------------ shipping SNAPSHOT

    @Column(name = "shipping_zone_name", length = 100)
    private String shippingZoneName;

    /**
     * How {@code shippingCost} was built: JSON array of
     * {@code {sizeClass, quantity, unitCost, lineCost}}. Null on orders placed before
     * shipping was priced per size class — those only have the final figure.
     */
    @Column(name = "shipping_breakdown", length = 500)
    private String shippingBreakdown;

    /** Null on orders placed before the cap existed; read that as "not applied". */
    @Column(name = "shipping_cap_applied")
    private Boolean shippingCapApplied;

    /** What shipping would have cost without the cap. Null on older orders. */
    @Column(name = "shipping_uncapped_cost", precision = 19, scale = 4)
    private BigDecimal shippingUncappedCost;

    @Column(name = "delivery_days_min")
    private Short deliveryDaysMin;

    @Column(name = "delivery_days_max")
    private Short deliveryDaysMax;

    // -------------------------------------------------------------- meta

    @Column(name = "customer_note", length = 500)
    private String customerNote;

    @Column(name = "internal_note", length = 1000)
    private String internalNote;

    @Column(name = "locale", nullable = false, length = 5)
    private String locale = "ar";

    @Column(name = "placed_at", nullable = false, updatable = false)
    private OffsetDateTime placedAt = OffsetDateTime.now(ZoneOffset.UTC);

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "shipped_at")
    private OffsetDateTime shippedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    // ------------------------------------------------------------------ helpers

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public boolean isGuestOrder() {
        return customer == null;
    }

    /** Cancellation is allowed until the parcel leaves. */
    public boolean isCancellable() {
        return !fulfillmentStatus.isDispatched()
                && fulfillmentStatus != FulfillmentStatus.CANCELLED;
    }

    /** The full delivery address on one line, for courier labels. */
    public String formattedAddress() {
        StringBuilder sb = new StringBuilder(shipStreetAddress);
        if (shipBuilding != null) {
            sb.append(", building ").append(shipBuilding);
        }
        if (shipFloor != null) {
            sb.append(", floor ").append(shipFloor);
        }
        if (shipApartment != null) {
            sb.append(", apt ").append(shipApartment);
        }
        if (shipArea != null) {
            sb.append(", ").append(shipArea);
        }
        sb.append(", ").append(shipCityName).append(", ").append(shipGovernorateName);
        if (shipLandmark != null) {
            sb.append(" (").append(shipLandmark).append(')');
        }
        return sb.toString();
    }

    public int totalQuantity() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }
}
