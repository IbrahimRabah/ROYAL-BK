package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record ReviewModerationRequest(
        @Schema(description = "Optional note, shown to the customer on a rejected review")
        @Size(max = 500, message = "The note is at most 500 characters") String note
) {
}
