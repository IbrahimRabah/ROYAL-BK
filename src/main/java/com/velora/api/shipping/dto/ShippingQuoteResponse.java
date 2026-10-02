package com.velora.api.shipping.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * What shipping costs and when it arrives.
 *
 * <p>The delivery estimate matters more than it looks: showing it before checkout
 * removes more support messages than almost any other single field.
 */
@Schema(description = "Shipping cost and delivery estimate")
public record ShippingQuoteResponse(
        Long governorateId,
        String governorateName,
        String zoneName,

        @Schema(example = "1500.00", description = "What the customer pays for delivery, "
                + "after the zone cap")
        BigDecimal shippingCost,

        @Schema(description = "True when the per-unit total exceeded the zone's cap and "
                + "the cap was charged instead")
        boolean shippingCapApplied,

        @Schema(example = "2950.00", description = "The per-unit total before the cap. "
                + "Equals shippingCost when the cap did not apply.")
        BigDecimal uncappedCost,

        @Schema(description = "How the cost was built, largest size first")
        List<ShippingBreakdownLine> breakdown,

        @Schema(description = "Extra charge for cash collection. Zero today.")
        BigDecimal codFee,

        @Schema(description = "Assembly for the pieces that need it (fee x quantity), tax-inclusive. "
                + "Zero when nothing in the cart is assembled.")
        BigDecimal assemblyTotal,

        @Schema(description = "True when shippingCost is zero (e.g. Greater Cairo)")
        boolean freeShippingApplied,

        @Schema(description = "No longer offered — always null")
        BigDecimal freeShippingThreshold,

        @Schema(description = "No longer offered — always null")
        BigDecimal amountToFreeShipping,

        int deliveryDaysMin,
        int deliveryDaysMax,

        @Schema(description = "Cart total used in the calculation")
        BigDecimal orderSubtotal,

        @Schema(description = "Cart weight in grams. Informational — it does not affect the price")
        int totalWeightGrams,

        @Schema(description = "subtotal + assembly + shipping + COD fee")
        BigDecimal estimatedTotal
) {
}
