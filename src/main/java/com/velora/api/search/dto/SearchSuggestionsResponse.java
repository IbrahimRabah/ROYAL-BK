package com.velora.api.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Type-ahead suggestions for the storefront search box. Both lists are always "
        + "present, empty when nothing matches.")
public record SearchSuggestionsResponse(
        @Schema(description = "At most 6, newest first - the same products, in the same order, that "
                + "GET /products?q= would list first")
        List<ProductSuggestion> products,
        @Schema(description = "At most 4, in the shop menu's order. PRODUCT categories only")
        List<CategorySuggestion> categories
) {

    public static final SearchSuggestionsResponse EMPTY =
            new SearchSuggestionsResponse(List.of(), List.of());

    @Schema(description = "One product row of the suggestion list")
    public record ProductSuggestion(
            String name,
            @Schema(description = "Open the product page with this") String slug,
            @Schema(description = "Main image; absent when the product has none") String imageUrl,
            @Schema(description = "The lowest price among its active variants (a 'from' price), "
                    + "tax-inclusive. Absent when no variant is on sale")
            BigDecimal price
    ) {
    }

    @Schema(description = "One category row of the suggestion list")
    public record CategorySuggestion(
            String name,
            @Schema(description = "Open the category page with this") String slug
    ) {
    }
}
