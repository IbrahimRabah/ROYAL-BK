package com.velora.api.portfolio.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Replace a portfolio item content. Send the whole form back: the optional "
        + "fields below are CLEARED when omitted. Publishing and archiving are separate calls.")
public record PortfolioUpdateRequest(

        @Schema(description = "Omit to keep the current slug. Changing it breaks links already "
                + "shared; there is no redirect.")
        @Size(max = 150) String slug,

        @NotBlank(message = "The Arabic title is required") @Size(max = 255) String titleAr,
        @Size(max = 255) String titleEn,
        String descriptionAr,
        String descriptionEn,
        Long categoryId,
        LocalDate completedAt,
        @Schema(description = "Omit to keep the current order") Integer displayOrder
) {
}
