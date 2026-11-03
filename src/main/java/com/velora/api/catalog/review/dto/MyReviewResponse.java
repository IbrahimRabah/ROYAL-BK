package com.velora.api.catalog.review.dto;

import java.time.OffsetDateTime;

public record MyReviewResponse(
        Long id,
        Long productId,
        String productSlug,
        String productName,
        String orderNumber,
        int rating,
        String comment,
        String status,
        String moderationNote,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
