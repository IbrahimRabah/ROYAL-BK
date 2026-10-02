package com.velora.api.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "A portfolio card in the grid")
public record PortfolioSummaryResponse(
        Long id,
        @Schema(description = "The public URL segment: /portfolio/{slug}")
        String slug,
        String title,
        String imageUrl,
        String imageAlt,
        String categorySlug,
        String categoryName,
        LocalDate completedAt
) {
}
