package com.velora.api.customrequest.dto;

import com.velora.api.customrequest.dto.MyCustomRequestSummaryResponse.ProductRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One request in full, for its owner: what they submitted, read back to them, and the quote. Their
 * own inputs only. NOT here, on purpose: the staff note (one box holds both pricing notes and the
 * mandatory reason for a rejection), phone, email, address, attachments, customer id.
 */
@Schema(description = "One of the signed-in customer's own custom requests, in full")
public record MyCustomRequestResponse(
        @Schema(example = "REQ-2026-000014") String requestNumber,
        String type,
        String status,
        OffsetDateTime submittedAt,
        ProductRef product,
        @Schema(description = "What the customer wrote, read back to them") String description,
        @Schema(description = "How many units. The quote below is for ALL of them") int quantity,
        BigDecimal widthCm,
        BigDecimal heightCm,
        BigDecimal depthCm,
        @Schema(description = "Tax-inclusive total for the whole request, all units. Absent until quoted")
        BigDecimal quotedAmount,
        @Schema(description = "When this amount was quoted. Absent until quoted") OffsetDateTime quotedAt,
        @Schema(description = "Decided by the SERVER; do not derive it from the status") boolean canAccept,
        @Schema(description = "Decided by the SERVER; do not derive it from the status") boolean canReject
) {
}
