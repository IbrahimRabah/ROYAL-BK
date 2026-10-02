package com.velora.api.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "A portfolio item with all its images")
public record PortfolioDetailResponse(
        Long id,
        String slug,
        String title,
        String description,
        String categorySlug,
        String categoryName,
        LocalDate completedAt,
        List<PortfolioImageResponse> images
) {

    @Schema(description = "One picture, in display order")
    public record PortfolioImageResponse(
            String url,
            String alt,
            boolean main,
            int displayOrder
    ) {
    }
}
