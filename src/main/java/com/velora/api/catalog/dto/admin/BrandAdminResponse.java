package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The admin view of a brand — the read-side counterpart of {@link BrandSaveRequest}:
 * same fields, so the edit form can load a brand and send the same shape back.
 *
 * <p>{@code nameAr}/{@code nameEn} rather than a {@code translations[]} array:
 * {@code Brand} has no separate translation table — the two names are plain columns
 * on the row itself, exactly as {@code BrandSaveRequest} already requires both.
 */
@Schema(description = "Brand as seen by staff")
public record BrandAdminResponse(
        Long id,
        String slug,
        String nameAr,
        String nameEn,
        String logoUrl,
        @Schema(description = "Inactive brands are hidden from the storefront list "
                + "but still returned here, so staff can find and reactivate them")
        boolean active
) {
}
