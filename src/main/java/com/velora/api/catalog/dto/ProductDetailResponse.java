package com.velora.api.catalog.dto;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the product page needs, in ONE call.
 *
 * <p>The full variant matrix is included deliberately: the customer changing colour
 * must not wait for a round trip. The page has all combinations, prices, stock and
 * images up front and switches locally.
 */
@Schema(description = "Full product detail")
public record ProductDetailResponse(
        Long id,
        String slug,
        String name,
        String shortDescription,
        String description,
        BrandResponse brand,
        List<CategoryRefResponse> categoryPath,
        PriceRangeResponse priceRange,
        @Schema(description = "Selector controls: colour, size — the variant-defining attributes")
        List<VariantOptionResponse> variantOptions,
        @Schema(description = "Every sellable combination with its own price, stock and images")
        List<VariantResponse> variants,
        @Schema(description = "The specification table — movement, water resistance, notes")
        List<SpecificationResponse> specifications,
        List<ImageResponse> images,
        boolean inStock,
        boolean featured,
        boolean newArrival,
        FulfillmentType fulfillmentType,
        ShippingSizeClass shippingSizeClass,
        SeoResponse seo
) {

    @Schema(description = "Price range across active variants")
    public record PriceRangeResponse(BigDecimal min, BigDecimal max) {
    }

    @Schema(description = "Meta tags for the storefront head")
    public record SeoResponse(
            String metaTitle,
            String metaDescription,
            String canonicalPath
    ) {
    }

    @Schema(description = "Category breadcrumb entry")
    public record CategoryRefResponse(Long id, String slug, String name) {
    }
}
