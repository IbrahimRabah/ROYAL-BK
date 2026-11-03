package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ReviewBulkResponse(String action, int requested, List<Long> succeeded, List<Skipped> skipped) {

    @Schema(description = "A review left as it was. reason: REVIEW_NOT_FOUND, ALREADY_APPROVED or ALREADY_REJECTED")
    public record Skipped(Long id, String reason) {
    }
}
