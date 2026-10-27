package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "Accepting a quote means accepting THIS price")
public record AcceptQuoteRequest(
        @Schema(description = "The amount the customer saw when they pressed accept (the quotedAmount of "
                + "the response they were looking at). If staff revised the price since, the call is "
                + "refused with QUOTE_CHANGED instead of accepting a price the customer never saw.",
                example = "4500.00")
        @NotNull(message = "quotedAmount is required: the price the customer saw")
        @DecimalMin(value = "0.0001", message = "quotedAmount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal quotedAmount
) {
}
