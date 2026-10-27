package com.velora.api.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A portfolio category, for the filter on the portfolio page")
public record PortfolioCategoryResponse(
        Long id,
        String slug,
        String name,
        @Schema(description = "Card image; absent when none was uploaded") String imageUrl,
        int displayOrder
) {
}
