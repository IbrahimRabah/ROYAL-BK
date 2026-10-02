package com.velora.api.shipping.dto;

import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "What one unit of a size class costs to ship to a zone")
public record SizeRateResponse(
        ShippingSizeClass sizeClass,
        @Schema(example = "150.00") BigDecimal unitCost
) {
}
