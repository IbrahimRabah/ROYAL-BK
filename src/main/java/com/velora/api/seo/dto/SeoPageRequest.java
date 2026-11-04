package com.velora.api.seo.dto;

import jakarta.validation.constraints.Size;

public record SeoPageRequest(
        @Size(max = 255) String titleAr,
        @Size(max = 500) String descriptionAr,
        @Size(max = 255) String titleEn,
        @Size(max = 500) String descriptionEn,
        boolean noindex
) {
}
