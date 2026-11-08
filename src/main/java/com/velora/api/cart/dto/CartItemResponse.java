package com.velora.api.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "One cart line, priced from the CURRENT variant price")
public record CartItemResponse(
        Long itemId,
        Long variantId,
        Long productId,
        String slug,
        String name,
        @Schema(example = "ذهبي / 42 مم") String variantSummary,
        String sku,
        String imageUrl,

        @Schema(description = "Current price, tax-inclusive. This is what will be charged.")
        BigDecimal unitPrice,

        @Schema(description = "Price when the item was added — for comparison only")
        BigDecimal priceAtAdd,

        boolean priceChanged,

        int quantity,

        @Schema(description = "How many can actually be bought right now")
        int qtyAvailable,

        boolean inStock,

        BigDecimal lineTotal,

        @Schema(description = "The regular price per piece when a promotion lowers it, otherwise absent")
        BigDecimal listPrice,

        @Schema(description = "Whole-number percentage off the regular price, when a promotion applies")
        Integer salePercent
) {
}
