package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "A reference image attached to a request")
public record CustomRequestAttachmentResponse(
        Long id,
        String url,
        String contentType,
        long sizeBytes,
        OffsetDateTime createdAt
) {
}
