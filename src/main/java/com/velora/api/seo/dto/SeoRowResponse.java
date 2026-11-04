package com.velora.api.seo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A product or category with its SEO text")
public record SeoRowResponse(
        Long id,
        String slug,
        String status,
        String name,
        String metaTitleAr,
        String metaDescriptionAr,
        String metaTitleEn,
        String metaDescriptionEn,
        @Schema(description = "Published FAQs of this product or category") long faqCount,
        @Schema(description = "Another product has the same Arabic meta title") boolean duplicateTitle
) {
}
