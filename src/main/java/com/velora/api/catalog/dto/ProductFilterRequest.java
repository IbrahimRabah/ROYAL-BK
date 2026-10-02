package com.velora.api.catalog.dto;

import com.velora.api.catalog.domain.FulfillmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * Every field is optional. A null filter contributes nothing to the query, which
 * is what makes the Specification composition work.
 */
@Schema(description = "Catalog search and filter criteria")
public record ProductFilterRequest(

        @Schema(example = "ساعه ذهبي", description = "Arabic-normalized full-text search")
        String q,

        @Schema(example = "4")
        Long categoryId,

        @Schema(description = "Include products of any of these brands")
        List<Long> brandIds,

        @Schema(example = "500")
        BigDecimal minPrice,

        @Schema(example = "5000")
        BigDecimal maxPrice,

        @Schema(description = "Attribute value ids, e.g. colour=gold and colour=silver")
        List<Long> attributeValueIds,

        @Schema(example = "true")
        Boolean inStockOnly,

        Boolean featured,

        Boolean newArrival,

        @Schema(example = "READY_MADE", description = "How the product is sold")
        FulfillmentType fulfillmentType,

        @Schema(example = "newest",
                allowableValues = {"newest", "price_asc", "price_desc", "name"})
        String sort
) {
    public String sortOrDefault() {
        return sort == null || sort.isBlank() ? "newest" : sort;
    }
}
