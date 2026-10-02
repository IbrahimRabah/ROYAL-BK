package com.velora.api.shipping.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "An Egyptian governorate, with its shipping terms")
public record GovernorateResponse(
        Long id,
        String code,
        String name,
        @Schema(description = "Zone name, for display only") String zoneName,
        @Schema(description = "Cost of ONE unit per size class. Empty when not served.")
        List<SizeRateResponse> shippingRates,
        @Schema(description = "Shipping for an order never exceeds this. Null when uncapped "
                + "or not served.")
        BigDecimal maxShippingCost,
        Integer deliveryDaysMin,
        Integer deliveryDaysMax,
        @Schema(description = "False when we do not deliver there yet") boolean served
) {
}
