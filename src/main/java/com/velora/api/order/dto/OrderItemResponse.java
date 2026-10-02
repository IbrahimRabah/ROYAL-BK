package com.velora.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * A purchased line, exactly as it was at purchase.
 *
 * <p>Everything here comes from the snapshot columns. Nothing is resolved live from
 * the catalog, which is why an old order still shows the price the customer paid.
 */
@Schema(description = "One line of an order")
public record OrderItemResponse(
        Long id,
        Long variantId,
        Long productId,
        @Schema(description = "For linking back to the product page") String productSlug,
        String name,
        String sku,
        String variantSummary,
        String imageUrl,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineDiscount,
        @Schema(description = "This line's share of a cart-wide coupon")
        BigDecimal allocatedCartDiscount,
        BigDecimal lineTotal,
        BigDecimal taxAmount,
        @Schema(description = "Assembly fee PER PIECE at purchase. Zero when the piece needs none.")
        BigDecimal assemblyFee,
        @Schema(description = "assemblyFee x quantity — what assembling this line cost")
        BigDecimal assemblyTotal,
        int quantityReturned,
        int returnableQuantity
) {
}
