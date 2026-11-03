package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "A review as staff see it")
public record AdminReviewResponse(
        Long id,
        Long productId,
        String productSlug,
        String productName,
        Long customerId,
        @Schema(description = "The account's full name; the page shows reviewerName instead") String customerName,
        String reviewerName,
        String orderNumber,
        int rating,
        String comment,
        String status,
        Long moderatedBy,
        OffsetDateTime moderatedAt,
        String moderationNote,
        OffsetDateTime createdAt
) {
}
