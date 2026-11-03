package com.velora.api.catalog.review.dto;

import java.time.OffsetDateTime;

/** What the product page shows: the first name and an initial, never anything that identifies the account. */
public record PublicReviewResponse(Long id, String reviewerName, int rating, String comment, OffsetDateTime createdAt) {
}
