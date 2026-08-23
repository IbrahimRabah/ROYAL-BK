package com.velora.api.order.service;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.inventory.service.ReservationService;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.OrderItem;
import com.velora.api.order.domain.OrderStatusHistory;
import com.velora.api.order.domain.PaymentStatus;
import com.velora.api.order.domain.StatusKind;
import com.velora.api.order.dto.OrderItemResponse;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.OrderSummaryResponse;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.repository.OrderStatusHistoryRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading orders and moving them through their lifecycle.
 *
 * <p>Status changes go through {@link OrderStatusMachine}, and every one of them
 * writes a history row. The order's own status column is a cache of the latest
 * entry; the history is the record.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderStatusMachine statusMachine;
    private final ReservationService reservationService;
    private final InvoiceService invoiceService;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository historyRepository,
                        OrderStatusMachine statusMachine,
                        ReservationService reservationService,
                        InvoiceService invoiceService) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.statusMachine = statusMachine;
        this.reservationService = reservationService;
        this.invoiceService = invoiceService;
    }

    // ------------------------------------------------------------- customer view

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listForCustomer(Long customerId,
                                                              Pageable pageable,
                                                              String locale) {
        Page<CustomerOrder> page =
                orderRepository.findByCustomerIdOrderByPlacedAtDesc(customerId, pageable);
        return PageResponse.from(page, order -> toSummary(order, locale));
    }

    /**
     * One customer's order. Scoped by customer id, not just order number.
     *
     * <p>Fetching by number alone would let anyone read anyone's order — which is
     * also why order numbers carry a random suffix rather than being sequential.
     */
    @Transactional(readOnly = true)
    public OrderResponse getForCustomer(Long customerId, String orderNumber, String locale) {
        CustomerOrder order = orderRepository
                .findByOrderNumberAndCustomerId(orderNumber, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return toResponse(order, locale);
    }

    @Transactional(readOnly = true)
    public OrderResponse getForAdmin(Long orderId, String locale) {
        CustomerOrder order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return toResponse(order, locale);
    }

    // ------------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listForAdmin(String status, String phone,
                                                           Pageable pageable, String locale) {
        Page<CustomerOrder> page;

        if (phone != null && !phone.isBlank()) {
            String normalized = PhoneNormalizer.toE164(phone);
            page = orderRepository.findByContactPhoneOrderByPlacedAtDesc(
                    normalized == null ? phone : normalized, pageable);
        } else if (status != null && !status.isBlank()) {
            page = orderRepository.findByFulfillmentStatusOrderByPlacedAtDesc(
                    parseFulfillment(status), pageable);
        } else {
            page = orderRepository.findAll(pageable);
        }
        return PageResponse.from(page, order -> toSummary(order, locale));
    }

    // -------------------------------------------------------- status transitions

    /**
     * Moves an order forward. The machine decides what is legal; a note is required
     * for the outcomes a human will need explained later.
     */
    @Transactional
    public OrderResponse changeFulfillmentStatus(Long orderId, String rawStatus,
                                                 String note, Long actorId, String locale) {
        CustomerOrder order = load(orderId);
        FulfillmentStatus from = order.getFulfillmentStatus();
        FulfillmentStatus to = parseFulfillment(rawStatus);

        statusMachine.requireTransition(from, to);

        if (requiresNote(to) && (note == null || note.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required when marking an order as %s".formatted(to));
        }

        order.setFulfillmentStatus(to);
        order.touch();
        applyTimestamps(order, to);

        // Stock effects of the move.
        switch (to) {
            case SHIPPED ->
                // The hold becomes a real reduction. On hand and reserved both drop.
                    reservationService.commitForOrder(order.getId());
            case CANCELLED, RETURNED_TO_SELLER ->
                // Not yet shipped, or came straight back — return the units.
                    releaseIfStillHeld(order);
            default -> { }
        }

        // The order row must exist with its new status before the invoice snapshots
        // it, so this flush comes before issuing.
        orderRepository.saveAndFlush(order);

        if (to == FulfillmentStatus.DELIVERED) {
            /*
             * Invoices are issued on DELIVERY, not on confirmation.
             *
             * With cash on delivery, refusal and failed delivery are common. An
             * invoice raised for a sale that never happened needs a credit note to
             * undo it — accounting complexity with no upside, and it would put gaps
             * of meaning (though not of number) in the sequence.
             *
             * Inside the same transaction on purpose: if numbering fails, the
             * delivery is not recorded either, and the two stay consistent.
             */
            invoiceService.issueForOrder(order.getId());
        }

        historyRepository.save(OrderStatusHistory.of(order, StatusKind.FULFILLMENT,
                from.name(), to.name(), note, actorId));

        log.info("Order {} moved {} -> {}{}", order.getOrderNumber(), from, to,
                note == null ? "" : " (" + note + ")");

        /*
         * Re-read before building the response.
         *
         * The stock operations above run native UPDATEs with clearAutomatically, which
         * empties the persistence context so later reads see the new quantities. The
         * side effect is that `order` and its items are now detached, and any lazy
         * association they still hold can no longer be initialised.
         *
         * The session is still open, so a fresh fetch comes back fully managed. One
         * extra query, and far more robust than trying to remember which associations
         * were touched before the clear.
         */
        return toResponse(load(orderId), locale);
    }

    @Transactional
    public OrderResponse changePaymentStatus(Long orderId, PaymentStatus to,
                                             String note, Long actorId, String locale) {
        CustomerOrder order = load(orderId);
        PaymentStatus from = order.getPaymentStatus();

        statusMachine.requireTransition(from, to);

        order.setPaymentStatus(to);
        order.touch();

        historyRepository.save(OrderStatusHistory.of(order, StatusKind.PAYMENT,
                from.name(), to.name(), note, actorId));

        orderRepository.save(order);
        log.info("Order {} payment {} -> {}", order.getOrderNumber(), from, to);

        return toResponse(load(orderId), locale);
    }

    /** COD phone confirmation. Records who confirmed and when. */
    @Transactional
    public OrderResponse confirm(Long orderId, String note, Long actorId, String locale) {
        return changeFulfillmentStatus(orderId, FulfillmentStatus.CONFIRMED.name(),
                note == null ? "Confirmed by phone" : note, actorId, locale);
    }

    /**
     * Customer-initiated cancellation.
     *
     * <p>Ownership is checked here rather than in the controller: an order id from
     * someone else's account must behave exactly like one that does not exist.
     */
    @Transactional
    public OrderResponse cancelByCustomer(Long customerId, String orderNumber,
                                          String reason, String locale) {
        CustomerOrder order = orderRepository
                .findByOrderNumberAndCustomerId(orderNumber, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.isCancellable()) {
            throw new BusinessException(ErrorCode.ORDER_CANNOT_BE_CANCELLED,
                    "The order is already %s".formatted(order.getFulfillmentStatus()));
        }

        order.setCancelReason(reason);
        return changeFulfillmentStatus(order.getId(), FulfillmentStatus.CANCELLED.name(),
                reason == null ? "Cancelled by customer" : reason, customerId, locale);
    }

    @Transactional
    public OrderResponse cancelByStaff(Long orderId, String reason, Long actorId,
                                       String locale) {
        CustomerOrder order = load(orderId);
        order.setCancelReason(reason);
        return changeFulfillmentStatus(orderId, FulfillmentStatus.CANCELLED.name(),
                reason == null ? "Cancelled by staff" : reason, actorId, locale);
    }

    // ------------------------------------------------------------------ internal

    /**
     * These outcomes get read months later by someone trying to understand what
     * happened, so they cannot be recorded without an explanation.
     */
    private boolean requiresNote(FulfillmentStatus status) {
        return status == FulfillmentStatus.DELIVERY_FAILED
                || status == FulfillmentStatus.REFUSED_ON_DELIVERY
                || status == FulfillmentStatus.CANCELLED
                || status == FulfillmentStatus.RETURNED_TO_SELLER;
    }

    private void releaseIfStillHeld(CustomerOrder order) {
        // After shipment the hold is already committed, so there is nothing to give
        // back — the goods physically left. Returned stock re-enters through the
        // returns flow, after inspection.
        if (!order.getFulfillmentStatus().isDispatched()
                || order.getShippedAt() == null) {
            reservationService.releaseForOrder(order.getId());
        }
    }

    private void applyTimestamps(CustomerOrder order, FulfillmentStatus to) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        switch (to) {
            case CONFIRMED -> order.setConfirmedAt(now);
            case SHIPPED -> order.setShippedAt(now);
            case DELIVERED -> order.setDeliveredAt(now);
            case CANCELLED -> order.setCancelledAt(now);
            default -> { }
        }
    }

    private FulfillmentStatus parseFulfillment(String raw) {
        try {
            return FulfillmentStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "Unknown order status: " + raw);
        }
    }

    private CustomerOrder load(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    // ------------------------------------------------------------------ mapping

    private OrderSummaryResponse toSummary(CustomerOrder order, String locale) {
        String thumbnail = order.getItems().isEmpty()
                ? null : order.getItems().get(0).getImageUrl();

        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name(),
                order.getPaymentMethod().name(),
                order.getGrandTotal(),
                order.getItems().size(),
                order.totalQuantity(),
                order.getContactName(),
                PhoneNormalizer.toLocalFormat(order.getContactPhone()),
                order.getShipGovernorateName(),
                thumbnail,
                order.getPlacedAt(),
                invoiceService.findInvoiceNumberForOrder(order.getId()));
    }

    private OrderResponse toResponse(CustomerOrder order, String locale) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> toItemResponse(item, locale))
                .toList();

        List<OrderResponse.TimelineEntry> timeline = historyRepository
                .findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
                .map(entry -> new OrderResponse.TimelineEntry(
                        entry.getStatusKind().name(),
                        entry.getFromStatus(),
                        entry.getToStatus(),
                        entry.getNote(),
                        entry.getCreatedAt()))
                .toList();

        OrderResponse.AddressSnapshot address = new OrderResponse.AddressSnapshot(
                order.getShipGovernorateName(),
                order.getShipCityName(),
                order.getShipArea(),
                order.getShipStreetAddress(),
                order.getShipBuilding(),
                order.getShipFloor(),
                order.getShipApartment(),
                order.getShipLandmark(),
                order.formattedAddress());

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name(),
                order.getPaymentMethod().name(),
                order.getCurrency(),
                order.getSubtotalGross(),
                order.getDiscountTotal(),
                order.getShippingCost(),
                order.getCodFee(),
                order.getGrandTotal(),
                order.getTaxTotal(),
                order.getNetTotal(),
                order.getContactName(),
                PhoneNormalizer.toLocalFormat(order.getContactPhone()),
                PhoneNormalizer.toLocalFormat(order.getContactAltPhone()),
                order.getContactEmail(),
                address,
                order.getShippingZoneName(),
                order.getDeliveryDaysMin() == null ? null : (int) order.getDeliveryDaysMin(),
                order.getDeliveryDaysMax() == null ? null : (int) order.getDeliveryDaysMax(),
                order.getCustomerNote(),
                invoiceService.findInvoiceNumberForOrder(order.getId()),
                items,
                order.totalQuantity(),
                timeline,
                order.isCancellable(),
                order.getPlacedAt(),
                order.getConfirmedAt(),
                order.getShippedAt(),
                order.getDeliveredAt(),
                order.getCancelledAt(),
                order.getCancelReason());
    }

    private OrderItemResponse toItemResponse(OrderItem item, String locale) {
        return new OrderItemResponse(
                item.getId(),
                item.getVariant() == null ? null : item.getVariant().getId(),
                item.getProduct() == null ? null : item.getProduct().getId(),
                item.getProduct() == null ? null : item.getProduct().getSlug(),
                // From the SNAPSHOT, never from the live product.
                item.nameFor(locale),
                item.getSku(),
                item.getVariantSummary(),
                item.getImageUrl(),
                item.getUnitPriceGross(),
                item.getQuantity(),
                item.getLineDiscount(),
                item.getAllocatedCartDiscount(),
                item.getLineTotalGross(),
                item.getLineTaxAmount(),
                item.getQuantityReturned(),
                item.returnableQuantity());
    }
}