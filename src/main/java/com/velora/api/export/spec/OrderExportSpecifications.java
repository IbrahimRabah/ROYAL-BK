package com.velora.api.export.spec;

import com.velora.api.common.util.EnumParam;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.PaymentStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.data.jpa.domain.Specification;

/** Composable filters for the export queries. */
public final class OrderExportSpecifications {

    private OrderExportSpecifications() {
        // utility class
    }

    private static Specification<CustomerOrder> alwaysTrue() {
        return (root, query, cb) -> cb.conjunction();
    }

    public static Specification<CustomerOrder> placedFrom(LocalDate from) {
        if (from == null) {
            return alwaysTrue();
        }
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("placedAt"), start);
    }

    /** Inclusive: a date of 31 August includes orders placed at 23:59 that day. */
    public static Specification<CustomerOrder> placedUntil(LocalDate to) {
        if (to == null) {
            return alwaysTrue();
        }
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return (root, query, cb) -> cb.lessThan(root.get("placedAt"), end);
    }

    public static Specification<CustomerOrder> hasFulfillmentStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return alwaysTrue();
        }
        FulfillmentStatus status = EnumParam.parse(FulfillmentStatus.class, raw, "fulfillmentStatus");
        return (root, query, cb) -> cb.equal(root.get("fulfillmentStatus"), status);
    }

    public static Specification<CustomerOrder> hasPaymentStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return alwaysTrue();
        }
        PaymentStatus status = EnumParam.parse(PaymentStatus.class, raw, "paymentStatus");
        return (root, query, cb) -> cb.equal(root.get("paymentStatus"), status);
    }

    public static Specification<CustomerOrder> inGovernorate(Long governorateId) {
        if (governorateId == null) {
            return alwaysTrue();
        }
        return (root, query, cb) ->
                cb.equal(root.get("shipGovernorate").get("id"), governorateId);
    }

    /**
     * Cancelled orders skew every total on an accounting sheet, so they are excluded
     * by default rather than quietly included.
     */
    public static Specification<CustomerOrder> excludeCancelled(boolean exclude) {
        if (!exclude) {
            return alwaysTrue();
        }
        return (root, query, cb) ->
                cb.notEqual(root.get("fulfillmentStatus"), FulfillmentStatus.CANCELLED);
    }
}
