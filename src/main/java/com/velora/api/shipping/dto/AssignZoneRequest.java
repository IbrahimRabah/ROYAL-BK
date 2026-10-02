package com.velora.api.shipping.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Put a governorate into a shipping zone — opening it for delivery, or "
        + "moving it between zones")
public record AssignZoneRequest(
        @NotNull(message = "Zone is required")
        Long zoneId
) {
}
