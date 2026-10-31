package com.velora.api.catalog.imageimport.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "What happened to each file of one upload request")
public record ImageBulkResponse(
        String runId,
        String mode,
        Counts counts,
        List<FileResult> results
) {

    public record Counts(int attached, int skipped, int rejected, int failed) {
    }

    public record FileResult(
            String name,
            @Schema(description = "ATTACHED, SKIPPED, REJECTED (the file is wrong) or FAILED (the server could not save it; retry)")
            String status,
            @Schema(description = """
                    SKIPPED: HAS_IMAGES (mode SKIP), DUPLICATE_IMAGE (this picture is already on the product).
                    REJECTED: NO_MATCH, AMBIGUOUS_NAME, UNSUPPORTED_FORMAT (HEIC and the like), NOT_AN_IMAGE,
                    TOO_LARGE, EMPTY_FILE, PRODUCT_ARCHIVED, LIMIT_REACHED (20 images), DUPLICATE_POSITION.
                    FAILED: SERVER_ERROR. Null when ATTACHED.""")
            String code,
            String sku,
            Long productId,
            Long imageId,
            @Schema(description = "Public URL of the stored image, when ATTACHED") String url
    ) {
    }
}
