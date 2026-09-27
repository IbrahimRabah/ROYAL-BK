package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@Schema(description = "Create or update a category. Images are not set here — "
        + "upload them with POST /admin/categories/{id}/images.")
public record CategorySaveRequest(

        Long parentId,

        String slug,

        @NotEmpty(message = "At least one translation is required")
        @Valid
        List<TranslationRequest> translations,

        Integer displayOrder,

        Boolean active
) {
}
