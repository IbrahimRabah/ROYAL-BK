package com.velora.api.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "What a discount would do, before it is switched on")
public record PromotionImpactResponse(
        int affectedProducts,
        int affectedVariants,
        @Schema(description = "Variants whose sale price would be below their cost price. Activation is refused while any exist")
        int belowCost,
        @Schema(description = "True above 70 percent") boolean highDiscount,
        List<Example> examples,
        List<Example> belowCostExamples,
        @Schema(description = "Other discounts that overlap in time and scope: the customer gets the biggest, never both")
        List<Overlap> overlaps
) {

    public record Example(String sku, String productName, BigDecimal listPrice, BigDecimal salePrice, BigDecimal costPrice) {
    }

    public record Overlap(Long id, String nameAr, BigDecimal percent, String scope, String state) {
    }
}
