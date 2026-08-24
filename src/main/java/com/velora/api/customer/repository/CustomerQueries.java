package com.velora.api.customer.repository;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.customer.dto.CustomerDetailResponse;
import com.velora.api.customer.dto.CustomerSummaryResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Customer list and purchase aggregates.
 *
 * <p>Plain SQL because these join users to orders and group — a shape that belongs to
 * no entity. Expressing it through JPA would mean either a projection interface per
 * query or loading every order into memory to count them.
 *
 * <p>Every DATETIMEOFFSET column is read with {@code rs.getObject(column,
 * OffsetDateTime.class)}, never the untyped {@code rs.getObject(column)}: the driver's
 * untyped return is its own {@code microsoft.sql.DateTimeOffset}, not
 * {@code java.time.OffsetDateTime}, and a previous {@code toString()}-and-parse
 * conversion of that type broke on every non-null row (its format has a space before
 * the offset too, so a blind {@code replace(" ", "T")} produced an unparseable
 * string) — every customer with a placed order 500'd on both the list and detail
 * endpoint. The typed overload asks the driver to do the conversion itself.
 */
@Repository
public class CustomerQueries {

    private final JdbcTemplate jdbc;

    public CustomerQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Customers with their order counts and lifetime value.
     *
     * <p>Cancelled orders are excluded from both figures. A customer who placed six
     * orders and cancelled five has not spent anything, and a list that says otherwise
     * misleads whoever is deciding how to treat them.
     */
    public PageResponse<CustomerSummaryResponse> findCustomers(String search, String sort,
                                                               Pageable pageable) {
        String orderBy = switch (sort == null ? "" : sort) {
            case "spent_desc" -> "total_spent DESC";
            case "orders_desc" -> "order_count DESC";
            case "recent_order" -> "last_order_at DESC";
            case "name" -> "u.first_name ASC";
            default -> "u.created_at DESC";
        };

        // No deleted_at column — a removed account is a status, so the row and its
        // order history survive. Staff are excluded: this is the customer list.
        StringBuilder where = new StringBuilder(
                " WHERE u.status <> 'DELETED' AND u.is_staff = 0 ");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            where.append("""
                    AND (u.phone_e164 LIKE ?
                         OR u.email LIKE ?
                         OR (u.first_name + ' ' + ISNULL(u.last_name, '')) LIKE ?)
                    """);
            String like = "%" + search + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user u" + where, Integer.class, params.toArray());

        // The aggregate runs as a correlated subquery rather than a join+group so the
        // customer row stays one row even before any order exists.
        String sql = """
                SELECT u.id, u.first_name, u.last_name, u.phone_e164, u.email,
                       u.phone_verified_at, u.status, u.created_at,
                       (SELECT COUNT(*) FROM customer_order o
                        WHERE o.customer_id = u.id
                          AND o.fulfillment_status <> 'CANCELLED') AS order_count,
                       (SELECT COALESCE(SUM(o.grand_total), 0) FROM customer_order o
                        WHERE o.customer_id = u.id
                          AND o.fulfillment_status <> 'CANCELLED') AS total_spent,
                       (SELECT MAX(o.placed_at) FROM customer_order o
                        WHERE o.customer_id = u.id) AS last_order_at
                FROM app_user u
                """ + where + """
                ORDER BY """ + " " + orderBy + """
                
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;

        List<Object> pagedParams = new ArrayList<>(params);
        pagedParams.add(pageable.getOffset());
        pagedParams.add(pageable.getPageSize());

        List<CustomerSummaryResponse> rows = jdbc.query(sql,
                (rs, rowNum) -> new CustomerSummaryResponse(
                        rs.getLong("id"),
                        fullName(rs.getString("first_name"), rs.getString("last_name")),
                        PhoneNormalizer.toLocalFormat(rs.getString("phone_e164")),
                        rs.getString("email"),
                        // Verification is stored as a timestamp, not a flag: knowing
                        // WHEN a number was confirmed matters when a login is disputed.
                        rs.getObject("phone_verified_at") != null,
                        rs.getInt("order_count"),
                        rs.getBigDecimal("total_spent"),
                        rs.getObject("last_order_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("status")),
                pagedParams.toArray());

        // Wrapped in a Page so the existing PageResponse.from mapping applies —
        // the pagination shape stays identical to every other list endpoint.
        return PageResponse.from(
                new PageImpl<>(rows, pageable, total == null ? 0 : total),
                row -> row);
    }

    /** One customer's totals, broken down by outcome. */
    public PurchaseTotals purchaseTotals(Long customerId) {
        return jdbc.queryForObject("""
                SELECT
                    COUNT(*) AS total_orders,
                    SUM(CASE WHEN fulfillment_status = 'DELIVERED' THEN 1 ELSE 0 END)
                        AS delivered_orders,
                    SUM(CASE WHEN fulfillment_status IN
                        ('DELIVERY_FAILED', 'REFUSED_ON_DELIVERY', 'RETURNED_TO_SELLER')
                        THEN 1 ELSE 0 END) AS failed_orders,
                    SUM(CASE WHEN fulfillment_status = 'CANCELLED' THEN 1 ELSE 0 END)
                        AS cancelled_orders,
                    COALESCE(SUM(CASE WHEN fulfillment_status <> 'CANCELLED'
                        THEN grand_total ELSE 0 END), 0) AS total_spent,
                    MIN(placed_at) AS first_order_at,
                    MAX(placed_at) AS last_order_at
                FROM customer_order
                WHERE customer_id = ?
                """,
                (rs, rowNum) -> new PurchaseTotals(
                        rs.getInt("total_orders"),
                        rs.getInt("delivered_orders"),
                        rs.getInt("failed_orders"),
                        rs.getInt("cancelled_orders"),
                        rs.getBigDecimal("total_spent"),
                        rs.getObject("first_order_at", OffsetDateTime.class),
                        rs.getObject("last_order_at", OffsetDateTime.class)),
                customerId);
    }

    public List<CustomerDetailResponse.OrderLine> recentOrders(Long customerId, int limit) {
        return jdbc.query("""
                SELECT TOP (?)
                       o.id, o.order_number, o.fulfillment_status, o.payment_status,
                       o.grand_total, o.placed_at,
                       (SELECT COUNT(*) FROM order_item oi WHERE oi.order_id = o.id)
                           AS item_count
                FROM customer_order o
                WHERE o.customer_id = ?
                ORDER BY o.placed_at DESC
                """,
                (rs, rowNum) -> new CustomerDetailResponse.OrderLine(
                        rs.getLong("id"),
                        rs.getString("order_number"),
                        rs.getString("fulfillment_status"),
                        rs.getString("payment_status"),
                        rs.getBigDecimal("grand_total"),
                        rs.getInt("item_count"),
                        rs.getObject("placed_at", OffsetDateTime.class)),
                limit, customerId);
    }

    // ------------------------------------------------------------------ helpers

    private String fullName(String first, String last) {
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? "—" : name;
    }

    /** One customer's purchase history, aggregated. */
    public record PurchaseTotals(
            int totalOrders,
            int deliveredOrders,
            int failedOrders,
            int cancelledOrders,
            BigDecimal totalSpent,
            OffsetDateTime firstOrderAt,
            OffsetDateTime lastOrderAt
    ) {
    }
}
