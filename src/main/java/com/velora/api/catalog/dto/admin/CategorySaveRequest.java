package com.velora.api.catalog.dto.admin;

import com.velora.api.catalog.domain.CategoryScope;
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

        Boolean active,

        @Schema(description = "PRODUCT or PORTFOLIO. On create: omitted means PRODUCT (or the "
                + "parent's scope when a parent is given). On update: omitted means NO CHANGE - "
                + "the one field that is not replaced wholesale, because resetting it would "
                + "silently move a portfolio category into the shop navigation. Changing it is "
                + "refused (CATEGORY_SCOPE_IN_USE) while the category has children, products "
                + "or portfolio items.",
                allowableValues = {"PRODUCT", "PORTFOLIO"})
        CategoryScope scope
) {

    /** A request that says nothing about scope: no change on update, PRODUCT (or the parent's) on create. */
    public CategorySaveRequest(Long parentId, String slug, List<TranslationRequest> translations,
                               Integer displayOrder, Boolean active) {
        this(parentId, slug, translations, displayOrder, active, null);
    }
}
