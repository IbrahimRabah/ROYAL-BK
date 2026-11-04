package com.velora.api.seo.dto;

import java.time.OffsetDateTime;

public record SeoPageResponse(
        String pageKey,
        String titleAr,
        String descriptionAr,
        String titleEn,
        String descriptionEn,
        boolean noindex,
        OffsetDateTime updatedAt
) {
}
