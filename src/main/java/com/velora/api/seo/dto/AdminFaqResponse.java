package com.velora.api.seo.dto;

import java.time.OffsetDateTime;

public record AdminFaqResponse(
        Long id,
        String scope,
        Long targetId,
        String targetName,
        String topic,
        String questionAr,
        String answerAr,
        String questionEn,
        String answerEn,
        int position,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
