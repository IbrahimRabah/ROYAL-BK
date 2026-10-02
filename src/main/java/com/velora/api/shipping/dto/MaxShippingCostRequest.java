package com.velora.api.shipping.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Schema(description = "Set or clear a zone's shipping cap")
public record MaxShippingCostRequest(
        @Schema(example = "1500.00", description = "Null removes the cap")
        @DecimalMin(value = "0.0", message = "The cap cannot be negative")
        BigDecimal maxShippingCost
) {
}
