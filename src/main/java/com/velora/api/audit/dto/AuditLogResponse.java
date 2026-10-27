package com.velora.api.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "One recorded change")
public record AuditLogResponse(
        Long id,
        @Schema(example = "PRICE_CHANGED") String action,
        @Schema(example = "PRODUCT_VARIANT") String entityType,
        String entityId,
        @Schema(description = "Human label, stored so the entry reads without joins")
        String entityLabel,
        String oldValue,
        String newValue,
        String reason,
        Long actorId,
        @Schema(description = "Copied at write time — the account may be renamed later")
        String actorName,
        @Schema(description = "Who KIND of actor this was: CUSTOMER (a customer acting on their "
                + "own account, e.g. accepting their own quote), SYSTEM (no person) or STAFF. "
                + "actorName alone cannot tell a customer from staff - render this next to it.",
                allowableValues = {"STAFF", "CUSTOMER", "SYSTEM"})
        String actorKind,
        OffsetDateTime createdAt
) {
}
