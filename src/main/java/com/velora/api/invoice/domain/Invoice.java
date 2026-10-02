package com.velora.api.invoice.domain;

import com.velora.api.order.domain.CustomerOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A tax invoice. A legal document, not a view of an order.
 *
 * <p>Both parties are COPIED onto it — the seller as well as the buyer. The store's
 * legal address will change, and an invoice must always show what was printed on it,
 * not what the store profile says today.
 *
 * <p>Never deleted, never renumbered. Cancellation is a status.
 */
@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** VLR-INV-2026-000001 — sequential and gapless within the year. */
    @Column(name = "invoice_number", nullable = false, length = 40)
    private String invoiceNumber;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    // -------------------------------------------------------- seller SNAPSHOT

    @Column(name = "seller_name", nullable = false, length = 200)
    private String sellerName;

    @Column(name = "seller_address", length = 500)
    private String sellerAddress;

    @Column(name = "seller_phone", length = 30)
    private String sellerPhone;

    @Column(name = "seller_email", length = 255)
    private String sellerEmail;

    @Column(name = "seller_tax_number", length = 30)
    private String sellerTaxNumber;

    @Column(name = "seller_commercial_register", length = 30)
    private String sellerCommercialRegister;

    // --------------------------------------------------------- buyer SNAPSHOT

    @Column(name = "buyer_name", nullable = false, length = 150)
    private String buyerName;

    @Column(name = "buyer_phone", nullable = false, length = 20)
    private String buyerPhone;

    @Column(name = "buyer_address", length = 800)
    private String buyerAddress;

    // -------------------------------------------------------- money SNAPSHOT

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "EGP";

    @Column(name = "subtotal_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotalGross;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "shipping_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal shippingCost = BigDecimal.ZERO;

    /** Cash-on-delivery fee, frozen from the order. Shown only when above zero. */
    @Column(name = "cod_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal codFee = BigDecimal.ZERO;

    /** Assembly charged, frozen from the order. Shown only when above zero. */
    @Column(name = "assembly_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal assemblyTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal netTotal;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    // ------------------------------------------------------------------- meta

    /** Storage key, not a URL. Built into a URL at read time. */
    @Column(name = "pdf_key", length = 500)
    private String pdfKey;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt = OffsetDateTime.now(ZoneOffset.UTC);

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    public boolean isCancelled() {
        return status == InvoiceStatus.CANCELLED;
    }
}
