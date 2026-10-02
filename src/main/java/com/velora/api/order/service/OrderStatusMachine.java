package com.velora.api.order.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.PaymentStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which status changes are legal.
 *
 * <p>The rules live here, in the service layer — not in an admin dropdown. A UI that
 * only shows valid options is a convenience; a server that only accepts them is the
 * actual constraint. Anyone can send whatever they like to the API.
 */
@Component
public class OrderStatusMachine {

    private static final Map<FulfillmentStatus, Set<FulfillmentStatus>> FULFILLMENT =
            new EnumMap<>(FulfillmentStatus.class);

    private static final Map<PaymentStatus, Set<PaymentStatus>> PAYMENT =
            new EnumMap<>(PaymentStatus.class);

    static {
        FULFILLMENT.put(FulfillmentStatus.PENDING, EnumSet.of(
                FulfillmentStatus.CONFIRMED,
                FulfillmentStatus.CANCELLED));

        // AWAITING_SCHEDULE is optional: a delivery date can be agreed here, or staff can go
        // straight to PROCESSING without one.
        FULFILLMENT.put(FulfillmentStatus.CONFIRMED, EnumSet.of(
                FulfillmentStatus.AWAITING_SCHEDULE,
                FulfillmentStatus.PROCESSING,
                FulfillmentStatus.CANCELLED));

        // Leaving for PROCESSING additionally needs scheduledDeliveryAt (OrderService checks
        // it, with its own error code, so staff are told what to do rather than "not allowed").
        FULFILLMENT.put(FulfillmentStatus.AWAITING_SCHEDULE, EnumSet.of(
                FulfillmentStatus.PROCESSING,
                FulfillmentStatus.CANCELLED));

        FULFILLMENT.put(FulfillmentStatus.PROCESSING, EnumSet.of(
                FulfillmentStatus.SHIPPED,
                FulfillmentStatus.CANCELLED));

        // Once shipped, cancellation is no longer available — the parcel is gone.
        FULFILLMENT.put(FulfillmentStatus.SHIPPED, EnumSet.of(
                FulfillmentStatus.OUT_FOR_DELIVERY,
                FulfillmentStatus.DELIVERY_FAILED,
                FulfillmentStatus.RETURNED_TO_SELLER));

        FULFILLMENT.put(FulfillmentStatus.OUT_FOR_DELIVERY, EnumSet.of(
                FulfillmentStatus.DELIVERED,
                FulfillmentStatus.DELIVERY_FAILED,
                FulfillmentStatus.REFUSED_ON_DELIVERY));

        // A failed attempt goes back out for another try — usually three before the
        // courier gives up. This loop is the normal case, not an edge case.
        FULFILLMENT.put(FulfillmentStatus.DELIVERY_FAILED, EnumSet.of(
                FulfillmentStatus.OUT_FOR_DELIVERY,
                FulfillmentStatus.REFUSED_ON_DELIVERY,
                FulfillmentStatus.RETURNED_TO_SELLER));

        FULFILLMENT.put(FulfillmentStatus.REFUSED_ON_DELIVERY, EnumSet.of(
                FulfillmentStatus.RETURNED_TO_SELLER));

        FULFILLMENT.put(FulfillmentStatus.DELIVERED, EnumSet.of(
                FulfillmentStatus.RETURNED,
                FulfillmentStatus.PARTIALLY_RETURNED));

        FULFILLMENT.put(FulfillmentStatus.PARTIALLY_RETURNED, EnumSet.of(
                FulfillmentStatus.RETURNED));

        FULFILLMENT.put(FulfillmentStatus.CANCELLED, EnumSet.noneOf(FulfillmentStatus.class));
        FULFILLMENT.put(FulfillmentStatus.RETURNED, EnumSet.noneOf(FulfillmentStatus.class));
        FULFILLMENT.put(FulfillmentStatus.RETURNED_TO_SELLER,
                EnumSet.noneOf(FulfillmentStatus.class));

        // ---------------------------------------------------------------- payment

        PAYMENT.put(PaymentStatus.PENDING, EnumSet.of(
                PaymentStatus.AUTHORIZED,
                PaymentStatus.PAID,
                PaymentStatus.FAILED,
                PaymentStatus.EXPIRED));

        PAYMENT.put(PaymentStatus.AUTHORIZED, EnumSet.of(
                PaymentStatus.PAID,
                PaymentStatus.FAILED,
                PaymentStatus.EXPIRED));

        PAYMENT.put(PaymentStatus.PAID, EnumSet.of(
                PaymentStatus.PARTIALLY_REFUNDED,
                PaymentStatus.REFUNDED));

        PAYMENT.put(PaymentStatus.PARTIALLY_REFUNDED, EnumSet.of(
                PaymentStatus.REFUNDED));

        // A failed payment can be retried — the customer fixes their card.
        PAYMENT.put(PaymentStatus.FAILED, EnumSet.of(PaymentStatus.PENDING));
        PAYMENT.put(PaymentStatus.EXPIRED, EnumSet.of(PaymentStatus.PENDING));
        PAYMENT.put(PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransition(FulfillmentStatus from, FulfillmentStatus to) {
        return FULFILLMENT.getOrDefault(from, EnumSet.noneOf(FulfillmentStatus.class))
                .contains(to);
    }

    public boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return PAYMENT.getOrDefault(from, EnumSet.noneOf(PaymentStatus.class)).contains(to);
    }

    public void requireTransition(FulfillmentStatus from, FulfillmentStatus to) {
        if (from == to) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "The order is already %s".formatted(to));
        }
        if (!canTransition(from, to)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot move from %s to %s. Allowed: %s".formatted(
                            from, to, allowedFrom(from)));
        }
    }

    public void requireTransition(PaymentStatus from, PaymentStatus to) {
        if (from == to) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Payment is already %s".formatted(to));
        }
        if (!canTransition(from, to)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot move payment from %s to %s. Allowed: %s".formatted(
                            from, to, PAYMENT.getOrDefault(from,
                                    EnumSet.noneOf(PaymentStatus.class))));
        }
    }

    public Set<FulfillmentStatus> allowedFrom(FulfillmentStatus from) {
        return FULFILLMENT.getOrDefault(from, EnumSet.noneOf(FulfillmentStatus.class));
    }

    public Set<PaymentStatus> allowedFrom(PaymentStatus from) {
        return PAYMENT.getOrDefault(from, EnumSet.noneOf(PaymentStatus.class));
    }
}
