package com.velora.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One node of the category tree. The mega-menu renders this recursively. */
@Schema(description = "Category tree node")
public record CategoryTreeResponse(
        Long id,
        String slug,
        String name,
        String imageUrl,
        String bannerUrl,
        int displayOrder,
        @Schema(description = "Live products in this category or its direct children")
        int productCount,
        List<CategoryTreeResponse> children
) {
}
