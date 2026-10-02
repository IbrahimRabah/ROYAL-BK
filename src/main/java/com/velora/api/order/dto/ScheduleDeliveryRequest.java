package com.velora.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

@Schema(description = "Set or move the delivery appointment agreed with the customer")
public record ScheduleDeliveryRequest(

        @Schema(example = "2026-10-12T10:00:00+03:00",
                description = "Must be in the future. Send it with its UTC offset.")
        @NotNull(message = "The delivery date and time are required")
        OffsetDateTime scheduledDeliveryAt,

        @Schema(description = "Optional. Recorded in the audit log, e.g. why it moved.")
        @Size(max = 500) String note
) {
}
