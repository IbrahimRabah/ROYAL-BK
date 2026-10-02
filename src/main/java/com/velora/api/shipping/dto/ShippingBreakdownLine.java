package com.velora.api.shipping.dto;

import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Shipping for all the units of one size class in an order")
public record ShippingBreakdownLine(
        ShippingSizeClass sizeClass,
        int quantity,
        @Schema(description = "Cost of one unit of this size in the destination zone")
        BigDecimal unitCost,
        @Schema(description = "unitCost x quantity")
        BigDecimal lineCost
) {
}
