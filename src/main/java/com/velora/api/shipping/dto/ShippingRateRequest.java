package com.velora.api.shipping.dto;

import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "Set the price of one size class in a zone")
public record ShippingRateRequest(

        @NotNull(message = "Zone is required")
        Long zoneId,

        @NotNull(message = "Size class is required")
        ShippingSizeClass sizeClass,

        @Schema(example = "150.00", description = "Cost of ONE unit of this size. Zero is "
                + "allowed (free delivery).")
        @NotNull(message = "Base cost is required")
        @DecimalMin(value = "0.0", message = "Cost cannot be negative")
        BigDecimal baseCost,

        @Schema(description = "Cash-on-delivery handling fee. Applies to the whole zone — "
                + "written to every size row. Null leaves it unchanged.")
        BigDecimal codFee,

        @Schema(description = "Whole zone. Null leaves it unchanged.")
        @Min(0) Integer deliveryDaysMin,

        @Schema(description = "Whole zone. Null leaves it unchanged.")
        @Min(0) Integer deliveryDaysMax
) {
}
