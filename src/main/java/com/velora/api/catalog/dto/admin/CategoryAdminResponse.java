package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The admin view of a category — unlike the storefront tree
 * ({@code CategoryTreeResponse}), this carries every locale's full content and the
 * fields staff need to edit or hide a category, not just what a customer sees.
 *
 * <p>{@code translations[]} exists for the same reason it was added to
 * {@code ProductAdminResponse}: {@code PUT /admin/categories/{id}} replaces a
 * category's translations wholesale. A response that only exposed the current
 * locale's name would round-trip as every other locale's content — and the
 * description and SEO meta for the locale being edited too — being cleared.
 */
@Schema(description = "Category as seen by staff")
public record CategoryAdminResponse(
        Long id,
        String slug,
        @Schema(description = "Null for a top-level category") Long parentId,
        List<CategoryTranslationResponse> translations,
        String imageUrl,
        String bannerUrl,
        int displayOrder,
        @Schema(description = "Inactive categories are hidden from the storefront tree "
                + "but still returned here, so staff can find and reactivate them")
        boolean active,
        @Schema(description = "Live products in this category or its direct children")
        int productCount,
        List<CategoryAdminResponse> children
) {
}
