package com.velora.api.catalog.importer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A row after an edit, and the batch totals it changed")
public record ImportRowUpdateResponse(
        @Schema(description = "The batch with its new counts: an edit can clear errors, or make new ones, "
                + "including on other rows that shared a SKU or slug with this one")
        ImportBatchResponse batch,
        ImportRowResponse row,
        @Schema(description = "OTHER rows whose result changed because of this edit (a duplicate SKU or slug "
                + "cleared or appeared), so the screen can update them in place")
        java.util.List<ImportRowResponse> affected
) {
}
