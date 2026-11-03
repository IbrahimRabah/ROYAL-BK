package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Edits the customer's own review. It goes back to PENDING and is approved again")
public record UpdateReviewRequest(
        @NotNull(message = "rating is required") @Min(value = 1, message = "rating is 1 to 5")
        @Max(value = 5, message = "rating is 1 to 5") Integer rating,
        @Size(max = 2000, message = "The comment is at most 2000 characters") String comment
) {
}
