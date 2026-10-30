package com.velora.api.catalog.importer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Whether a SKU typed in the row editor is unique")
public record ImportSkuCheckResponse(
        @Schema(description = "The SKU as it will be stored (trimmed, upper-case)") String sku,
        @Schema(allowableValues = {"AVAILABLE", "DUPLICATE_IN_FILE", "IN_CATALOG", "EMPTY"}) String status,
        @Schema(description = "DUPLICATE_IN_FILE: the other rows of this sheet that have it") List<Integer> rowNos,
        @Schema(description = "IN_CATALOG: the product that already sells it") Long productId,
        String productName
) {
}
