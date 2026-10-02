package com.velora.api.portfolio.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "A portfolio item as seen by staff: both languages, status and archive state")
public record PortfolioAdminResponse(
        Long id,
        String slug,
        String titleAr,
        String titleEn,
        String descriptionAr,
        String descriptionEn,
        Long categoryId,
        String categoryName,
        LocalDate completedAt,
        int displayOrder,
        boolean published,
        @Schema(description = "Null while the item is live or a draft; set when archived")
        OffsetDateTime archivedAt,
        List<PortfolioImageAdminResponse> images,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    @Schema(description = "Uploaded portfolio image")
    public record PortfolioImageAdminResponse(
            Long id,
            @Schema(description = "Storage key, what is stored in the database")
            String key,
            String url,
            String altTextAr,
            String altTextEn,
            boolean main,
            int displayOrder
    ) {
    }
}
