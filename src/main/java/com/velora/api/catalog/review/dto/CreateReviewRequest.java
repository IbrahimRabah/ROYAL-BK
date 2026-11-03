package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A review of a product the customer received")
public record CreateReviewRequest(
        @NotNull(message = "productId is required") Long productId,
        @NotNull(message = "rating is required") @Min(value = 1, message = "rating is 1 to 5")
        @Max(value = 5, message = "rating is 1 to 5") Integer rating,
        @Schema(description = "Optional, up to 2000 characters, Arabic or English")
        @Size(max = 2000, message = "The comment is at most 2000 characters") String comment
) {
}
