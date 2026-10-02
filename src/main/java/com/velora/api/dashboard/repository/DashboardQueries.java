package com.velora.api.dashboard.repository;

import com.velora.api.dashboard.dto.DashboardResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reporting queries for the dashboard.
 *
 * <p>Plain SQL rather than JPA repositories, on purpose. These are aggregates across
 * several tables that map to no entity — forcing them through the domain repositories
 * would mean adding reporting methods to interfaces that exist to serve business
 * operations, and inventing projection types for shapes nothing else uses.
 *
 * <p>Reporting reads differently from transactional code, and keeping that boundary
 * visible is worth more than uniformity.
 */
@Repository
public class DashboardQueries {

    private final JdbcTemplate jdbc;

    public DashboardQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- revenue

    /**
     * Revenue since a date, excluding cancelled orders.
     *
     * <p>Counts orders that have been PLACED, which is the commercial figure. It is
     * NOT cash in hand — see {@link #unsettledCod()} for the difference, which with
     * cash on delivery is usually large.
     */
    public BigDecimal revenueSince(LocalDate from) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(grand_total), 0)
                FROM customer_order
                WHERE placed_at >= ?
                  AND fulfillment_status <> 'CANCELLED'
                """, BigDecimal.class, from.atStartOfDay());
        return total == null ? BigDecimal.ZERO : total;
    }

    public int orderCountSince(LocalDate from) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM customer_order
                WHERE placed_at >= ?
                  AND fulfillment_status <> 'CANCELLED'
                """, Integer.class, from.atStartOfDay());
        return count == null ? 0 : count;
    }

    /**
     * Delivered orders whose cash the courier has not remitted.
     *
     * <p>The number that keeps a cash-on-delivery business honest with itself. Until
     * an order appears in a remittance the money has not arrived, and a revenue figure
     * that ignores this overstates available cash by exactly this amount.
     */
    public DashboardResponse.CodPosition unsettledCod() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS order_count,
                       COALESCE(SUM(grand_total), 0) AS total
                FROM customer_order
                WHERE payment_method = 'COD'
                  AND fulfillment_status = 'DELIVERED'
                  AND payment_status = 'PENDING'
                """);

        Integer oldestDays = jdbc.queryForObject("""
                SELECT COALESCE(MAX(DATEDIFF(day, delivered_at, SYSDATETIMEOFFSET())), 0)
                FROM customer_order
                WHERE payment_method = 'COD'
                  AND fulfillment_status = 'DELIVERED'
                  AND payment_status = 'PENDING'
                """, Integer.class);

        return new DashboardResponse.CodPosition(
                ((Number) row.get("order_count")).intValue(),
                (BigDecimal) row.get("total"),
                oldestDays == null ? 0 : oldestDays);
    }

    // ----------------------------------------------------------------- queues

    /** Orders waiting on someone to do something, by status. */
    public List<DashboardResponse.StatusCount> actionQueues() {
        return jdbc.query("""
                SELECT fulfillment_status, COUNT(*) AS order_count
                FROM customer_order
                WHERE fulfillment_status IN
                      ('PENDING', 'CONFIRMED', 'AWAITING_SCHEDULE', 'PROCESSING', 'SHIPPED',
                       'OUT_FOR_DELIVERY', 'DELIVERY_FAILED')
                GROUP BY fulfillment_status
                """,
                (rs, rowNum) -> new DashboardResponse.StatusCount(
                        rs.getString("fulfillment_status"),
                        arabicStatus(rs.getString("fulfillment_status")),
                        rs.getInt("order_count")));
    }

    /**
     * Orders sitting in one status too long.
     *
     * <p>A count alone hides the problem: ten orders awaiting confirmation is normal,
     * one awaiting confirmation since Tuesday is not.
     *
     * <p>An order in AWAITING_SCHEDULE counts only while NO delivery date has been set: then
     * the wait is on staff, who have not agreed one. Once a date is set the order is waiting
     * for that date, and an alert about it would be a false alarm — which teaches people to
     * ignore every alert on this screen.
     */
    public List<DashboardResponse.StaleOrder> staleOrders(int olderThanHours) {
        return jdbc.query("""
                SELECT TOP 20
                       id, order_number, fulfillment_status, contact_name, grand_total,
                       DATEDIFF(hour, updated_at, SYSDATETIMEOFFSET()) AS hours_waiting
                FROM customer_order
                WHERE (fulfillment_status IN ('PENDING', 'CONFIRMED', 'PROCESSING')
                       OR (fulfillment_status = 'AWAITING_SCHEDULE'
                           AND scheduled_delivery_at IS NULL))
                  AND DATEDIFF(hour, updated_at, SYSDATETIMEOFFSET()) >= ?
                ORDER BY updated_at ASC
                """,
                (rs, rowNum) -> new DashboardResponse.StaleOrder(
                        rs.getLong("id"),
                        rs.getString("order_number"),
                        rs.getString("fulfillment_status"),
                        arabicStatus(rs.getString("fulfillment_status")),
                        rs.getString("contact_name"),
                        rs.getBigDecimal("grand_total"),
                        rs.getInt("hours_waiting")),
                olderThanHours);
    }

    // ------------------------------------------------------------------ stock

    public List<DashboardResponse.LowStockItem> lowStock() {
        return jdbc.query("""
                SELECT TOP 20
                       v.id AS variant_id, v.sku,
                       COALESCE(pt.name, p.slug) AS product_name,
                       i.qty_on_hand, i.qty_reserved,
                       (i.qty_on_hand - i.qty_reserved) AS available,
                       i.min_stock_level
                FROM inventory i
                JOIN product_variant v ON v.id = i.variant_id
                JOIN product p ON p.id = v.product_id
                LEFT JOIN product_translation pt
                       ON pt.product_id = p.id AND pt.locale = 'ar'
                WHERE (i.qty_on_hand - i.qty_reserved) <= i.min_stock_level
                  AND v.archived_at IS NULL
                  AND p.archived_at IS NULL
                ORDER BY (i.qty_on_hand - i.qty_reserved) ASC
                """,
                (rs, rowNum) -> new DashboardResponse.LowStockItem(
                        rs.getLong("variant_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getInt("qty_on_hand"),
                        rs.getInt("qty_reserved"),
                        rs.getInt("available"),
                        rs.getInt("min_stock_level")));
    }

    /**
     * Stock held for orders that never shipped.
     *
     * <p>Order-backed holds never expire by design, which is correct — but one sitting
     * for days means an order is stuck in fulfilment, and the stock is frozen with it.
     */
    public int stuckReservations(int olderThanHours) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM stock_reservation
                WHERE status = 'HELD'
                  AND order_id IS NOT NULL
                  AND DATEDIFF(hour, created_at, SYSDATETIMEOFFSET()) >= ?
                """, Integer.class, olderThanHours);
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- selling

    /** Best sellers by units, from the ORDER LINES — what was actually bought. */
    public List<DashboardResponse.TopProduct> topProducts(LocalDate from, int limit) {
        return jdbc.query("""
                SELECT TOP (?)
                       oi.sku,
                       MAX(oi.product_name_ar) AS product_name,
                       SUM(oi.quantity) AS units_sold,
                       SUM(oi.line_total_gross - oi.allocated_cart_discount
                           - oi.line_discount) AS revenue
                FROM order_item oi
                JOIN customer_order o ON o.id = oi.order_id
                WHERE o.placed_at >= ?
                  AND o.fulfillment_status <> 'CANCELLED'
                GROUP BY oi.sku
                ORDER BY SUM(oi.quantity) DESC
                """,
                (rs, rowNum) -> new DashboardResponse.TopProduct(
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getInt("units_sold"),
                        rs.getBigDecimal("revenue")),
                limit, from.atStartOfDay());
    }

    /**
     * Deliveries that did not complete.
     *
     * <p>Watched closely with cash on delivery: a rising refusal rate is the earliest
     * signal that something upstream is wrong — unclear photos, a courier problem, or
     * orders being confirmed without a phone call.
     */
    public DashboardResponse.DeliveryHealth deliveryHealth(LocalDate from) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    SUM(CASE WHEN fulfillment_status = 'DELIVERED' THEN 1 ELSE 0 END)
                        AS delivered,
                    SUM(CASE WHEN fulfillment_status = 'DELIVERY_FAILED' THEN 1 ELSE 0 END)
                        AS failed,
                    SUM(CASE WHEN fulfillment_status = 'REFUSED_ON_DELIVERY' THEN 1 ELSE 0 END)
                        AS refused,
                    SUM(CASE WHEN fulfillment_status = 'RETURNED_TO_SELLER' THEN 1 ELSE 0 END)
                        AS returned,
                    SUM(CASE WHEN fulfillment_status = 'CANCELLED' THEN 1 ELSE 0 END)
                        AS cancelled
                FROM customer_order
                WHERE placed_at >= ?
                """, from.atStartOfDay());

        int delivered = intOf(row.get("delivered"));
        int failed = intOf(row.get("failed"));
        int refused = intOf(row.get("refused"));
        int returned = intOf(row.get("returned"));
        int cancelled = intOf(row.get("cancelled"));

        int attempted = delivered + refused + returned;
        BigDecimal successRate = attempted == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(delivered)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(attempted), 1, java.math.RoundingMode.HALF_UP);

        return new DashboardResponse.DeliveryHealth(
                delivered, failed, refused, returned, cancelled, successRate);
    }

    // -------------------------------------------------------------- customers

    public DashboardResponse.CustomerStats customerStats(LocalDate from) {
        // There is no deleted_at column: a removed account is a status, which keeps
        // the row (and its order history) intact. Staff accounts are excluded because
        // this is a CUSTOMER count.
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_user
                WHERE status <> 'DELETED' AND is_staff = 0
                """, Integer.class);

        Integer newCustomers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_user
                WHERE created_at >= ? AND status <> 'DELETED' AND is_staff = 0
                """, Integer.class, from.atStartOfDay());

        // A guest order has no customer_id. Worth seeing: a high share means people
        // are buying without an account, and repeat purchases cannot be tracked.
        Integer guestOrders = jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_order
                WHERE customer_id IS NULL AND placed_at >= ?
                """, Integer.class, from.atStartOfDay());

        Integer repeatCustomers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT customer_id
                    FROM customer_order
                    WHERE customer_id IS NOT NULL
                      AND fulfillment_status <> 'CANCELLED'
                    GROUP BY customer_id
                    HAVING COUNT(*) > 1
                ) repeat_buyers
                """, Integer.class);

        return new DashboardResponse.CustomerStats(
                total == null ? 0 : total,
                newCustomers == null ? 0 : newCustomers,
                guestOrders == null ? 0 : guestOrders,
                repeatCustomers == null ? 0 : repeatCustomers);
    }

    // ------------------------------------------------------------------ catalog

    public DashboardResponse.CatalogStats catalogStats() {
        Integer activeProducts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE status = 'ACTIVE' AND archived_at IS NULL",
                Integer.class);
        Integer draftProducts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE status = 'DRAFT'", Integer.class);
        Integer variants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_variant WHERE archived_at IS NULL",
                Integer.class);
        Integer outOfStock = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM inventory i
                JOIN product_variant v ON v.id = i.variant_id
                WHERE (i.qty_on_hand - i.qty_reserved) <= 0 AND v.archived_at IS NULL
                """, Integer.class);

        return new DashboardResponse.CatalogStats(
                activeProducts == null ? 0 : activeProducts,
                draftProducts == null ? 0 : draftProducts,
                variants == null ? 0 : variants,
                outOfStock == null ? 0 : outOfStock);
    }

    // ------------------------------------------------------------------ helpers

    private int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private String arabicStatus(String status) {
        return switch (status) {
            case "PENDING" -> "بانتظار التأكيد";
            case "CONFIRMED" -> "مؤكد — جاهز للتجهيز";
            case "AWAITING_SCHEDULE" -> "بانتظار تحديد الموعد";
            case "PROCESSING" -> "قيد التجهيز";
            case "SHIPPED" -> "تم الشحن";
            case "OUT_FOR_DELIVERY" -> "خرج للتوصيل";
            case "DELIVERY_FAILED" -> "فشل التوصيل";
            default -> status;
        };
    }
}
