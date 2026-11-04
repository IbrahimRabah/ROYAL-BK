package com.velora.api.seo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "The four SEO texts of a product or category. A blank value clears it. Nothing else of the item changes")
public record SeoMetaRequest(
        @Size(max = 255) String metaTitleAr,
        @Size(max = 500) String metaDescriptionAr,
        @Size(max = 255) String metaTitleEn,
        @Size(max = 500) String metaDescriptionEn
) {
}
