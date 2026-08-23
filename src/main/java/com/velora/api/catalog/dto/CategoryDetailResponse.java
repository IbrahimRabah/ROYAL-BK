package com.velora.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Category landing page")
public record CategoryDetailResponse(
        Long id,
        String slug,
        String name,
        String description,
        String imageUrl,
        String bannerUrl,
        @Schema(description = "Live products in this category or its direct children")
        int productCount,
        List<CategoryTreeResponse> children,
        List<ProductDetailResponse.CategoryRefResponse> breadcrumb,
        String metaTitle,
        String metaDescription
) {
}
