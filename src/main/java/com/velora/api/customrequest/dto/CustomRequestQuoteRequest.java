package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Give or change the quote")
public record CustomRequestQuoteRequest(

        @Schema(example = "4500.00", description = "Tax-inclusive total for the whole request, "
                + "all units — what the customer would pay")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "A quote must be greater than zero")
        @DecimalMax(value = "100000000", message = "That amount is not plausible")
        BigDecimal amount,

        @Schema(description = "Optional. Replaces the request's latest note when given.")
        @Size(max = 1000)
        String note
) {
}
