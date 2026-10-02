package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "A request was received")
public record CustomRequestCreatedResponse(
        Long id,
        @Schema(example = "REQ-2026-000001", description = "Tell the customer this number")
        String requestNumber,
        String status,
        @Schema(description = "Send it back as X-Request-Token to attach images to THIS "
                + "request. Shown once, and not recoverable — it is the only proof the "
                + "caller created the request.")
        String attachmentToken,
        OffsetDateTime attachmentTokenExpiresAt,
        OffsetDateTime createdAt
) {
}
