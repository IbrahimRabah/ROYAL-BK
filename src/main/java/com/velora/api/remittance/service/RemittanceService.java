package com.velora.api.remittance.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.PaymentMethod;
import com.velora.api.order.domain.PaymentStatus;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.remittance.domain.CodRemittance;
import com.velora.api.remittance.domain.CodRemittanceItem;
import com.velora.api.remittance.domain.RemittanceStatus;
import com.velora.api.remittance.dto.CreateRemittanceRequest;
import com.velora.api.remittance.dto.RemittanceResponse;
import com.velora.api.remittance.dto.UnsettledOrderResponse;
import com.velora.api.remittance.repository.CodRemittanceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "delivered" into "paid".
 *
 * <p>A cash-on-delivery order reaches DELIVERED while the money is still in a
 * driver's bag. The gap between those two facts is real money and often several days
 * wide, and nothing else in the system closes it.
 *
 * <p>Recording a batch does three things at once: marks the covered orders paid,
 * makes any shortfall visible while the courier still remembers the week, and leaves
 * a document both sides can point at.
 */
@Service
public class RemittanceService {

    private static final Logger log = LoggerFactory.getLogger(RemittanceService.class);

    private final CodRemittanceRepository remittanceRepository;
    private final OrderRepository orderRepository;
    private final AuditService auditService;

    public RemittanceService(CodRemittanceRepository remittanceRepository,
                             OrderRepository orderRepository,
                             AuditService auditService) {
        this.remittanceRepository = remittanceRepository;
        this.orderRepository = orderRepository;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------- outstanding

    /**
     * Delivered orders whose cash has not arrived.
     *
     * <p>The working list: these are handed to the courier as "here is what you owe
     * me". Sorted oldest first, because age is what makes a balance worth chasing.
     */
    @Transactional(readOnly = true)
    public List<UnsettledOrderResponse> unsettled() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return orderRepository.findUnsettledCodOrders().stream()
                .map(order -> new UnsettledOrderResponse(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getContactName(),
                        order.getShipGovernorateName(),
                        order.getGrandTotal(),
                        order.getDeliveredAt(),
                        order.getDeliveredAt() == null ? 0
                                : (int) ChronoUnit.DAYS.between(order.getDeliveredAt(), now)))
                .toList();
    }

    @Transactional(readOnly = true)
    public BigDecimal outstandingTotal() {
        return orderRepository.findUnsettledCodOrders().stream()
                .map(CustomerOrder::getGrandTotal)
                .reduce(MoneyUtils.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- record

    /**
     * Records a batch and marks its orders paid.
     *
     * <p>Every order is validated before anything is written. A batch that half
     * applies is worse than one that fails: the courier's paperwork and yours would
     * disagree, and neither side would know which orders were counted.
     */
    @Transactional
    public RemittanceResponse record(CreateRemittanceRequest request, Long actorId) {
        if (request.orderIds() == null || request.orderIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Select at least one order for this remittance");
        }

        CodRemittance remittance = new CodRemittance();
        remittance.setReference(nextReference());
        remittance.setCourierName(request.courierName());
        remittance.setCourierReference(request.courierReference());
        remittance.setSettlementDate(request.settlementDate() == null
                ? LocalDate.now() : request.settlementDate());
        remittance.setRecordedBy(actorId);

        BigDecimal expected = MoneyUtils.ZERO;
        List<CustomerOrder> orders = new ArrayList<>();

        for (Long orderId : request.orderIds()) {
            CustomerOrder order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                            "Order not found: " + orderId));

            validateSettleable(order);

            orders.add(order);
            expected = expected.add(order.getGrandTotal());

            CodRemittanceItem item = new CodRemittanceItem();
            item.setOrder(order);
            item.setOrderNumber(order.getOrderNumber());
            // Copied, not referenced: a later refund changes the order total, and this
            // batch must still show what was reconciled on the day.
            item.setAmount(order.getGrandTotal());
            remittance.addItem(item);
        }

        BigDecimal received = request.receivedAmount();
        BigDecimal difference = MoneyUtils.round(received.subtract(expected));

        // A mismatch always needs a human explanation. Recording one silently means
        // nobody ever reconciles it.
        if (difference.compareTo(BigDecimal.ZERO) != 0
                && (request.note() == null || request.note().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    ("The amounts do not match: expected %s, received %s. "
                            + "A note explaining the difference is required.")
                            .formatted(expected, received));
        }

        remittance.setExpectedAmount(MoneyUtils.round(expected));
        remittance.setReceivedAmount(MoneyUtils.round(received));
        remittance.setDifference(difference);
        remittance.setOrderCount(orders.size());
        remittance.setNote(request.note());
        remittance.setStatus(difference.compareTo(BigDecimal.ZERO) < 0
                ? RemittanceStatus.SHORT : RemittanceStatus.SETTLED);

        CodRemittance saved = remittanceRepository.save(remittance);

        /*
         * The orders are marked paid even on a shortfall.
         *
         * The alternative — leaving them pending until the courier makes up the
         * difference — leaves the same orders on next week's outstanding list and they
         * get counted twice. The shortfall belongs to the batch, not to individual
         * orders, and that is where it is recorded and chased.
         */
        for (CustomerOrder order : orders) {
            order.setPaymentStatus(PaymentStatus.PAID);
            order.touch();
        }
        orderRepository.saveAll(orders);

        auditService.record(AuditAction.PAYMENT_ADJUSTED, "COD_REMITTANCE",
                saved.getId(), saved.getReference(),
                expected, received,
                difference.compareTo(BigDecimal.ZERO) == 0
                        ? "%d order(s) settled".formatted(orders.size())
                        : "%d order(s) settled with a difference of %s: %s"
                                .formatted(orders.size(), difference, request.note()),
                actorId);

        if (saved.isShort()) {
            log.warn("Remittance {} is short by {}: {}",
                    saved.getReference(), difference.abs(), request.note());
        } else {
            log.info("Remittance {} recorded: {} order(s), {}",
                    saved.getReference(), orders.size(), received);
        }

        return toResponse(saved);
    }

    /**
     * Voids a batch and returns its orders to unpaid.
     *
     * <p>Used when a batch was recorded against the wrong orders. The row stays —
     * a cancelled settlement is still part of the reconciliation history.
     */
    @Transactional
    public RemittanceResponse cancel(Long remittanceId, String reason, Long actorId) {
        CodRemittance remittance = remittanceRepository.findWithItemsById(remittanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Remittance not found"));

        if (!remittance.getStatus().canBeCancelled()) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "This remittance is already cancelled");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required to cancel a remittance");
        }

        for (CodRemittanceItem item : remittance.getItems()) {
            CustomerOrder order = item.getOrder();
            order.setPaymentStatus(PaymentStatus.PENDING);
            order.touch();
            orderRepository.save(order);
            // The item stays on record, but the order is free to enter a new settlement.
            item.setActive(false);
        }

        remittance.setStatus(RemittanceStatus.CANCELLED);
        remittance.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC));
        remittance.setNote(remittance.getNote() == null
                ? reason : remittance.getNote() + " | CANCELLED: " + reason);

        auditService.record(AuditAction.PAYMENT_ADJUSTED, "COD_REMITTANCE",
                remittance.getId(), remittance.getReference(),
                "SETTLED", "CANCELLED", reason, actorId);

        log.warn("Cancelled remittance {}: {} — {} order(s) back to unpaid",
                remittance.getReference(), reason, remittance.getItems().size());

        return toResponse(remittanceRepository.save(remittance));
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public PageResponse<RemittanceResponse> list(Pageable pageable) {
        return PageResponse.from(
                remittanceRepository.findAllByOrderBySettlementDateDesc(pageable),
                this::toResponse);
    }

    @Transactional(readOnly = true)
    public RemittanceResponse get(Long remittanceId) {
        return toResponse(remittanceRepository.findWithItemsById(remittanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Remittance not found")));
    }

    // ------------------------------------------------------------------ internal

    private void validateSettleable(CustomerOrder order) {
        if (order.getPaymentMethod() != PaymentMethod.COD) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Order %s is not cash on delivery".formatted(order.getOrderNumber()));
        }
        if (order.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Order %s is %s — only delivered orders can be settled"
                            .formatted(order.getOrderNumber(), order.getFulfillmentStatus()));
        }
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            // The guard against counting the same cash twice.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Order %s is already %s and cannot be settled again"
                            .formatted(order.getOrderNumber(), order.getPaymentStatus()));
        }
    }

    /** REM-2026-0001. Sequential within the year; a gap here costs nothing. */
    private String nextReference() {
        String prefix = "REM-%d-".formatted(LocalDate.now().getYear());
        int next = remittanceRepository.highestSequenceFor(prefix) + 1;
        return "%s%04d".formatted(prefix, next);
    }

    private RemittanceResponse toResponse(CodRemittance remittance) {
        List<RemittanceResponse.SettledOrder> orders = remittance.getItems().stream()
                .map(item -> new RemittanceResponse.SettledOrder(
                        item.getOrder().getId(),
                        item.getOrderNumber(),
                        item.getAmount()))
                .toList();

        return new RemittanceResponse(
                remittance.getId(),
                remittance.getReference(),
                remittance.getCourierName(),
                remittance.getCourierReference(),
                remittance.getSettlementDate(),
                remittance.getStatus().name(),
                remittance.getExpectedAmount(),
                remittance.getReceivedAmount(),
                remittance.getDifference(),
                remittance.getOrderCount(),
                remittance.getNote(),
                orders,
                remittance.getCreatedAt());
    }
}
