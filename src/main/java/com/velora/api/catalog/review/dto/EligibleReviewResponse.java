package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A product the customer received and has not reviewed yet")
public record EligibleReviewResponse(Long productId, String slug, String name, String imageUrl, String orderNumber) {
}
