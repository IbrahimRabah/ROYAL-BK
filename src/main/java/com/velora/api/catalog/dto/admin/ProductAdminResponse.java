package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The admin view — includes drafts, archived products and cost price, none of
 * which ever appear in the public DTOs.
 */
@Schema(description = "Product as seen by staff")
public record ProductAdminResponse(
        Long id,
        String slug,
        String status,
        String nameAr,
        String nameEn,
        @Schema(description = "Full translated content per locale — name, descriptions and SEO "
                + "meta. Load this into the edit form and send it back whole; a response "
                + "missing a field here would round-trip as that field being cleared.")
        List<TranslationResponse> translations,
        Long categoryId,
        String categoryName,
        Long brandId,
        String brandName,
        boolean featured,
        boolean newArrival,
        @Schema(description = "Informational specifications — movement, water resistance, "
                + "strap material. Load this into the edit form and send it back whole; a "
                + "response missing this field would round-trip as the specifications being "
                + "cleared, exactly like translations.")
        List<SpecificationAdminResponse> specifications,
        int variantCount,
        int imageCount,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Integer availableQty,
        OffsetDateTime publishedAt,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> warnings
) {
}
