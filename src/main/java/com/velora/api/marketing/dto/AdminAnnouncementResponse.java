package com.velora.api.marketing.dto;

import java.time.OffsetDateTime;

public record AdminAnnouncementResponse(
        Long id,
        String textAr,
        String textEn,
        String linkUrl,
        String linkLabelAr,
        String linkLabelEn,
        String animation,
        String theme,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        boolean active,
        int position,
        boolean dismissible,
        boolean showCountdown,
        Long promotionId,
        String promotionName,
        String promotionState,
        boolean showingNow,
        OffsetDateTime updatedAt
) {
}
