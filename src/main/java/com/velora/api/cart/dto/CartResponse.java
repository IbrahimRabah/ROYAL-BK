package com.velora.api.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * The cart, recalculated server-side on every read.
 *
 * <p>Totals are never taken from what the client last saw. Prices change, stock
 * moves, and products get archived; the only trustworthy figures are the ones
 * computed from the database at this moment.
 */
@Schema(description = "Cart contents and totals")
public record CartResponse(
        Long cartId,
        List<CartItemResponse> items,
        int itemCount,
        int totalQuantity,

        @Schema(description = "Sum of line totals, tax-inclusive")
        BigDecimal subtotal,

        @Schema(description = "Coupon discount. Zero until the promotion module lands.")
        BigDecimal discountTotal,

        @Schema(description = "Assembly for the pieces that need it (fee x quantity), tax-inclusive. "
                + "Zero when nothing in the cart is assembled.")
        BigDecimal assemblyTotal,

        @Schema(description = "subtotal - discount + assembly. Shipping is added at checkout.")
        BigDecimal estimatedTotal,

        @Schema(description = "Tax contained in the subtotal — extracted, not added")
        BigDecimal taxIncluded,

        String couponCode,

        @Schema(description = "Changes since items were added. Show these before paying.")
        List<CartWarning> warnings,

        @Schema(description = "False when a warning blocks checkout")
        boolean checkoutReady
) {
}
