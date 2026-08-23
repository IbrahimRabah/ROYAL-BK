package com.velora.api.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "One entry in the append-only stock ledger")
public record StockMovementResponse(
        Long id,
        Long variantId,
        String sku,
        String movementType,
        int quantityDelta,
        int qtyAfter,
        String referenceType,
        String referenceId,
        String reason,
        Long actorId,
        @Schema(description = "Copied at write time — the account may be renamed later")
        String actorName,
        OffsetDateTime createdAt
) {
}
