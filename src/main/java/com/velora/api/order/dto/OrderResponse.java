package com.velora.api.order.dto;

import com.velora.api.shipping.dto.ShippingBreakdownLine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A full order.
 *
 * <p>Note the two status fields. Angular must render them as two separate badges:
 * a delivered COD order with {@code paymentStatus: PENDING} is correct until the
 * courier remits the cash, and showing one combined status makes that look broken.
 */
@Schema(description = "Order detail")
public record OrderResponse(
        Long id,
        String orderNumber,

        @Schema(example = "OUT_FOR_DELIVERY")
        String fulfillmentStatus,

        @Schema(example = "PENDING",
                description = "PENDING on a delivered COD order is CORRECT, not an error")
        String paymentStatus,

        String paymentMethod,

        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal shippingCost,
        @Schema(description = "True when the zone's shipping cap was charged instead of the "
                + "per-unit total. False on orders placed before the cap existed.")
        boolean shippingCapApplied,
        @Schema(description = "What shipping would have cost without the cap. Null on orders "
                + "placed before shipping was priced per size class.")
        BigDecimal shippingUncappedCost,
        @Schema(description = "How shippingCost was built. Null on orders placed before "
                + "shipping was priced per size class — use shippingCost alone for those.")
        List<ShippingBreakdownLine> shippingBreakdown,
        BigDecimal codFee,
        @Schema(description = "What the courier collects") BigDecimal grandTotal,
        @Schema(description = "Extracted from the gross totals") BigDecimal taxTotal,
        BigDecimal netTotal,

        String contactName,
        String contactPhone,
        String contactAltPhone,
        String contactEmail,

        AddressSnapshot shippingAddress,
        String shippingZoneName,
        Integer deliveryDaysMin,
        Integer deliveryDaysMax,

        String customerNote,

        @Schema(description = "Null until the order is DELIVERED and the invoice is issued. "
                + "Use it to build GET /me/invoices/{invoiceNumber}/pdf")
        String invoiceNumber,

        List<OrderItemResponse> items,
        int totalQuantity,

        List<TimelineEntry> timeline,

        @Schema(description = "False once the parcel has been dispatched")
        boolean cancellable,

        OffsetDateTime placedAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime shippedAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime cancelledAt,
        String cancelReason
) {

    @Schema(description = "The delivery address as it was at purchase")
    public record AddressSnapshot(
            String governorateName,
            String cityName,
            String area,
            String streetAddress,
            String building,
            String floor,
            String apartment,
            String landmark,
            @Schema(description = "One line, for courier labels") String formatted
    ) {
    }

    @Schema(description = "One status change")
    public record TimelineEntry(
            String kind,
            String from,
            String to,
            String note,
            OffsetDateTime at
    ) {
    }
}
