package com.velora.api.catalog.review.dto;

import com.velora.api.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

@Schema(description = "The approved reviews of one product, with the average and the star breakdown")
public record ProductReviewsResponse(
        @Schema(description = "Average stars, absent when there are no reviews") BigDecimal average,
        int count,
        @Schema(description = "How many reviews gave 5, 4, 3, 2 and 1 stars; every key is present")
        Map<Integer, Integer> breakdown,
        PageResponse<PublicReviewResponse> reviews
) {
}
