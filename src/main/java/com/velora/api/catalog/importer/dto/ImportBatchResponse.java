package com.velora.api.catalog.importer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "An uploaded product sheet and what the checks found")
public record ImportBatchResponse(
        Long id,
        String fileName,
        @Schema(allowableValues = {"PREVIEW", "COMMITTED", "DISCARDED"}) String status,
        int totalRows,
        int validRows,
        int errorRows,
        @Schema(description = "Rows that are valid but carry a warning") int warningRows,
        @Schema(description = "True only when the batch is still a preview and has no error rows")
        boolean canSubmit,
        OffsetDateTime createdAt,
        OffsetDateTime committedAt
) {
}
