package com.velora.api.catalog.review.dto;

public record ReviewCountsResponse(long pending, long approved, long rejected, long total) {
}
