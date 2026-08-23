package com.velora.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A row in an order list. Deliberately small. */
@Schema(description = "Order list row")
public record OrderSummaryResponse(
        Long id,
        String orderNumber,
        String fulfillmentStatus,
        String paymentStatus,
        String paymentMethod,
        BigDecimal grandTotal,
        int itemCount,
        int totalQuantity,
        String contactName,
        String contactPhone,
        String governorateName,
        @Schema(description = "First item's image, for the list thumbnail")
        String thumbnailUrl,
        OffsetDateTime placedAt,
        @Schema(description = "Null until the order is DELIVERED and the invoice is issued")
        String invoiceNumber
) {
}
