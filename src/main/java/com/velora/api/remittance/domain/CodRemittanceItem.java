package com.velora.api.remittance.domain;

import com.velora.api.order.domain.CustomerOrder;
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
 * One order inside a remittance batch.
 *
 * <p>The amount is copied rather than read from the order. An order total can change
 * through a later refund, and a remittance must always show what was reconciled at
 * the time — otherwise last month's settlement quietly stops balancing.
 */
@Entity
@Table(name = "cod_remittance_item")
@Getter
@Setter
@NoArgsConstructor
public class CodRemittanceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "remittance_id", nullable = false)
    private CodRemittance remittance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    /** Snapshot of the order number, so the row reads without a join. */
    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    /**
     * False once the settlement is cancelled. The row stays as history, but only an active item
     * counts toward "an order is in at most one settlement" (filtered unique index).
     */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Snapshot of the amount collected for this order. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
}
