package com.velora.api.seo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How healthy the SEO text is, measured on the Arabic version (the indexed one)")
public record SeoOverviewResponse(Products products, Categories categories, Pages pages, Faqs faqs) {

    @Schema(description = "Products on sale")
    public record Products(
            long total,
            long missingTitle,
            long missingDescription,
            @Schema(description = "Meta title over 60 characters: Google cuts it off") long titleTooLong,
            @Schema(description = "Meta description over 155 characters: Google cuts it off") long descriptionTooLong,
            @Schema(description = "Products whose meta title is used by another product too") long duplicateTitle,
            long withoutFaq
    ) {
    }

    public record Categories(long total, long missingTitle, long missingDescription, long withoutFaq) {
    }

    public record Pages(long total, long missingTitle, long missingDescription) {
    }

    public record Faqs(long published, long drafts) {
    }
}
