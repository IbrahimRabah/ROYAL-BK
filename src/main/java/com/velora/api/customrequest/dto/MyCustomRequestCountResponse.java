package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single number for a badge on the signed-in customer's custom requests")
public record MyCustomRequestCountResponse(
        @Schema(description = "How many of their requests have a quote waiting for their answer. "
                + "It stays until they answer; it is not 'new since your last visit'")
        long awaitingResponse
) {
}
