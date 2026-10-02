package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "One row of the admin request list")
public record CustomRequestSummaryResponse(
        Long id,
        String requestNumber,
        String type,
        String status,
        String contactName,
        @Schema(description = "Local format, for display") String phone,
        String governorateName,
        int quantity,
        BigDecimal quotedAmount,
        int attachmentCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
