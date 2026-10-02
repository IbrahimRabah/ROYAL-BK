package com.velora.api.portfolio.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Create a portfolio item. It starts unpublished; add images, then publish.")
public record PortfolioCreateRequest(

        @Schema(description = "Leave empty to generate from the Arabic title. Latin letters, digits "
                + "and dashes. An explicit slug that is already taken is refused with 409.")
        @Size(max = 150) String slug,

        @NotBlank(message = "The Arabic title is required") @Size(max = 255) String titleAr,
        @Size(max = 255) String titleEn,
        String descriptionAr,
        String descriptionEn,
        Long categoryId,
        LocalDate completedAt,
        @Schema(description = "Lower first. Defaults to 0.") Integer displayOrder
) {
}
