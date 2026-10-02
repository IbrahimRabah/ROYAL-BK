package com.velora.api.catalog.dto;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** The product card in a grid. Deliberately small — a category page loads 24 of these. */
@Schema(description = "Product card")
public record ProductSummaryResponse(
        Long id,
        String slug,
        String name,
        String shortDescription,
        String brandName,
        String categorySlug,
        String imageUrl,
        String imageAlt,
        @Schema(description = "Second product image by displayOrder, for hover-swap. "
                + "Null when the product has only one image.")
        String hoverImageUrl,
        @Schema(description = "Lowest active variant price, tax-inclusive")
        BigDecimal minPrice,
        BigDecimal maxPrice,
        @Schema(description = "Struck-through price when discounted")
        BigDecimal compareAtPrice,
        Integer discountPercent,
        boolean inStock,
        @Schema(description = "Shown as 'only 2 left' when low")
        Integer availableQty,
        boolean featured,
        boolean newArrival,
        FulfillmentType fulfillmentType,
        ShippingSizeClass shippingSizeClass
) {
}
