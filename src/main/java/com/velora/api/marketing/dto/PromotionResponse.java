package com.velora.api.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "A discount as staff see it")
public record PromotionResponse(
        Long id,
        String nameAr,
        String nameEn,
        String scope,
        Long targetId,
        @Schema(description = "The category or product it applies to, in Arabic") String targetName,
        BigDecimal percent,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        @Schema(description = "What staff decided: DRAFT, ACTIVE or PAUSED") String status,
        @Schema(description = "Status and clock together: DRAFT, PAUSED, SCHEDULED, RUNNING or ENDED. Only RUNNING changes a price")
        String state,
        String headlineAr,
        String headlineEn,
        String bannerUrl,
        String bannerMobileUrl,
        @Schema(description = "Some order was placed under it: it can be ended but not deleted") boolean usedByOrders,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
