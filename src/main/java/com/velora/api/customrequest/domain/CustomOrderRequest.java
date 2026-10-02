package com.velora.api.customrequest.domain;

import com.velora.api.catalog.domain.Product;
import com.velora.api.common.audit.BaseAuditEntity;
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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer's request for a size, a made-to-order piece, or custom work.
 *
 * <p>This is not a sale and never touches inventory: nothing is reserved when a request
 * is created, and nothing is priced until staff quote it and the customer accepts.
 *
 * <p>Never deleted — a rejected request is {@code REJECTED}, not removed. The
 * {@code requestNumber} has no gaps (see {@link CustomOrderRequestSequence}), and a
 * deleted row would be a gap.
 */
@Entity
@Table(name = "custom_order_request")
@Getter
@Setter
@NoArgsConstructor
public class CustomOrderRequest extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code REQ-2026-000001}. Unique forever. */
    @Column(name = "request_number", nullable = false, length = 30)
    private String requestNumber;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private CustomRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomRequestStatus status = CustomRequestStatus.NEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Null for a guest. */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "contact_name", nullable = false, length = 150)
    private String contactName;

    /** E.164. */
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "alt_phone", length = 20)
    private String altPhone;

    @Column(name = "email", length = 255)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "governorate_id", nullable = false)
    private Governorate governorate;

    @Column(name = "area", length = 150)
    private String area;

    @Column(name = "street_address", length = 255)
    private String streetAddress;

    @Column(name = "width_cm", precision = 8, scale = 2)
    private BigDecimal widthCm;

    @Column(name = "height_cm", precision = 8, scale = 2)
    private BigDecimal heightCm;

    @Column(name = "depth_cm", precision = 8, scale = 2)
    private BigDecimal depthCm;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    @Column(name = "notes", length = 1000)
    private String notes;

    /** Tax-inclusive total for the whole request, all units. Null until quoted. */
    @Column(name = "quoted_amount", precision = 19, scale = 4)
    private BigDecimal quotedAmount;

    /** The latest staff note. Earlier ones live in the audit log. */
    @Column(name = "admin_note", length = 1000)
    private String adminNote;

    /** Set when the request becomes an order. Nothing sets it yet. */
    @Column(name = "converted_order_id")
    private Long convertedOrderId;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<CustomRequestAttachment> attachments = new ArrayList<>();

    public void addAttachment(CustomRequestAttachment attachment) {
        attachment.setRequest(this);
        attachments.add(attachment);
    }
}
