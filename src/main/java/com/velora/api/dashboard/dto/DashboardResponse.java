package com.velora.api.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the owner needs to see on opening the admin.
 *
 * <p>Organised around questions rather than tables: what sold, what needs doing, what
 * is about to go wrong, and how much money is actually mine. A screen of counts with
 * no answer attached is a report, not a dashboard.
 */
@Schema(description = "Store overview")
public record DashboardResponse(

        @Schema(description = "Sales figures. NOT cash in hand — see codPosition.")
        Sales sales,

        @Schema(description = "Cash the courier is holding")
        CodPosition codPosition,

        @Schema(description = "Orders waiting on someone to act")
        List<StatusCount> actionQueues,

        @Schema(description = "Custom requests waiting on someone to act. Separate from "
                + "actionQueues: different objects, different screen.")
        CustomRequests customRequests,

        @Schema(description = "Orders stuck in one status too long")
        List<StaleOrder> staleOrders,

        @Schema(description = "About to run out")
        List<LowStockItem> lowStock,

        @Schema(description = "Stock frozen by orders that never shipped")
        int stuckReservations,

        @Schema(description = "Best sellers this month, by units")
        List<TopProduct> topProducts,

        DeliveryHealth deliveryHealth,
        CustomerStats customers,
        CatalogStats catalog,

        @Schema(description = "Things that need a person, in priority order")
        List<Alert> alerts,

        OffsetDateTime generatedAt
) {

    @Schema(description = "Revenue and order counts across three windows")
    public record Sales(
            BigDecimal revenueToday,
            int ordersToday,
            BigDecimal revenueThisWeek,
            int ordersThisWeek,
            BigDecimal revenueThisMonth,
            int ordersThisMonth,
            @Schema(description = "Average order value this month")
            BigDecimal averageOrderValue
    ) {
    }

    @Schema(description = "Delivered cash-on-delivery orders not yet remitted")
    public record CodPosition(
            int orderCount,
            @Schema(description = "Money the courier holds, not you")
            BigDecimal amount,
            @Schema(description = "Days since the oldest one was delivered")
            int oldestDays
    ) {
    }

    public record StatusCount(
            String status,
            String label,
            int count,
            @Schema(description = "Where to go in the admin; same field as on customRequests queues")
            String actionPath
    ) {
    }

    @Schema(description = "Custom requests that need action; statuses with none are omitted")
    public record CustomRequests(
            @Schema(description = "Requests needing action, all statuses together")
            int totalCount,
            @Schema(description = "Days the longest-waiting of them has been in its current status")
            int oldestDays,
            List<CustomRequestQueue> queues
    ) {
    }

    @Schema(description = "Same status/label/count as an actionQueues entry, plus age and link")
    public record CustomRequestQueue(
            String status,
            String label,
            int count,
            @Schema(description = "Days the oldest request here has been in this status")
            int oldestDays,
            @Schema(description = "Where to go in the admin")
            String actionPath
    ) {
    }

    @Schema(description = "An order that has not moved")
    public record StaleOrder(
            Long orderId,
            String orderNumber,
            String status,
            String statusLabel,
            String customerName,
            BigDecimal grandTotal,
            int hoursWaiting
    ) {
    }

    public record LowStockItem(
            Long variantId,
            String sku,
            String productName,
            int qtyOnHand,
            int qtyReserved,
            int available,
            int minStockLevel
    ) {
    }

    public record TopProduct(
            String sku,
            String productName,
            int unitsSold,
            BigDecimal revenue
    ) {
    }

    @Schema(description = "How deliveries are actually going")
    public record DeliveryHealth(
            int delivered,
            int failed,
            @Schema(description = "Customer declined at the door — watch this with COD")
            int refused,
            int returnedToSeller,
            int cancelled,
            @Schema(example = "87.5", description = "Delivered as a percentage of attempts")
            BigDecimal successRatePercent
    ) {
    }

    public record CustomerStats(
            int total,
            int newThisMonth,
            @Schema(description = "Orders placed without an account this month")
            int guestOrdersThisMonth,
            @Schema(description = "Customers who bought more than once")
            int repeatCustomers
    ) {
    }

    public record CatalogStats(
            int activeProducts,
            int draftProducts,
            int activeVariants,
            int outOfStockVariants
    ) {
    }

    /**
     * Something that needs a person.
     *
     * <p>Ordered by severity so the front end can render the list as-is. An alert
     * always names what to do, not just what is true — "12 orders awaiting
     * confirmation" is a fact; "confirm 12 orders" is an instruction.
     */
    @Schema(description = "Something needing attention")
    public record Alert(
            @Schema(example = "HIGH", allowableValues = {"HIGH", "MEDIUM", "LOW"})
            String severity,
            @Schema(example = "STOCK") String category,
            String message,
            @Schema(description = "Where to go in the admin")
            String actionPath
    ) {
    }
}
