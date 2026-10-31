package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "What a bulk publish or unpublish did, product by product")
public record ProductBulkStatusResponse(
        String action,
        @Schema(description = "How many distinct products were asked for") int requested,
        @Schema(description = "The products that changed") List<Long> succeeded,
        @Schema(description = "The products left as they were, each with the reason") List<Skipped> skipped,
        @Schema(description = """
                Products that DID go live but have no image (code NO_IMAGE). A warning, not a refusal: a
                made-to-order product may legitimately have no photo yet. Always empty for UNPUBLISH.""")
        List<Warned> warnings
) {

    @Schema(description = "A product that changed, with something worth knowing")
    public record Warned(Long id, @Schema(description = "The product's Arabic name") String name,
                         @Schema(description = "NO_IMAGE") String code) {
    }

    @Schema(description = "A product that was not changed")
    public record Skipped(
            Long id,
            @Schema(description = "The product's Arabic name, null when it was not found") String name,
            @Schema(description = """
                    Why: NOT_DRAFT (publish asks for a draft), NOT_ACTIVE (unpublish asks for a live product),
                    PRODUCT_NOT_FOUND, PRODUCT_HAS_NO_VARIANTS (a ready-made product needs a variant before it can
                    go live) or PRODUCT_MISSING_ARABIC_NAME
                    """)
            String reason
    ) {
    }
}
