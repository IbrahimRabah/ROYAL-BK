package com.velora.api.shipping.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "A zone with its rates and the governorates it covers")
public record ShippingZoneResponse(
        Long zoneId,
        String code,
        String nameAr,
        String nameEn,
        @Schema(description = "Cost of ONE unit per size class") List<SizeRateResponse> rates,
        @Schema(description = "Shipping for an order never exceeds this. Null means no cap.")
        BigDecimal maxShippingCost,
        BigDecimal codFee,
        int deliveryDaysMin,
        int deliveryDaysMax,
        boolean active,
        List<String> governorates
) {
}
