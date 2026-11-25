package com.velora.api.shipping.dto;

import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "What delivery to one governorate costs and how long it takes, "
        + "for the product page. Needs no cart.")
public record DeliveryOptionResponse(
        Long id,
        String code,
        String name,
        @Schema(description = "Zone name, for display only") String zoneName,
        @Schema(description = "The size class the cost is for; null when the request gave none "
                + "(shippingCost is then the cheapest size)")
        ShippingSizeClass sizeClass,
        @Schema(description = "Delivery fee for ONE unit of sizeClass (or the cheapest size when "
                + "sizeClass is null), capped at maxShippingCost. Null only when the zone has "
                + "no rate for the requested size.", example = "200.00")
        BigDecimal shippingCost,
        @Schema(description = "True exactly when shippingCost is 0") boolean free,
        @Schema(description = "Cost of ONE unit per size class")
        List<SizeRateResponse> shippingRates,
        @Schema(description = "Shipping for an order never exceeds this. Null when uncapped.")
        BigDecimal maxShippingCost,
        int deliveryDaysMin,
        int deliveryDaysMax
) {
}
