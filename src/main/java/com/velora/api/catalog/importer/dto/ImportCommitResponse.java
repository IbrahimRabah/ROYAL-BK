package com.velora.api.catalog.importer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The result of submitting a reviewed sheet")
public record ImportCommitResponse(
        Long batchId,
        @Schema(description = "Products created, all as DRAFT") int created,
        @Schema(description = "Imported products are drafts. Publish them from the product list.")
        String note
) {
}
