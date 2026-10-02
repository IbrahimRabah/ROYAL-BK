package com.velora.api.portfolio.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Update image metadata. Omitted fields are left unchanged.")
public record PortfolioImageUpdateRequest(
        @Size(max = 255) String altTextAr,
        @Size(max = 255) String altTextEn,
        @Schema(description = "true makes this the main image; there is always exactly one")
        Boolean main,
        Integer displayOrder
) {
}
