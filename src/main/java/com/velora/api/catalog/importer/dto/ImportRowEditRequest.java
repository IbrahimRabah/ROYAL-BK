package com.velora.api.catalog.importer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Schema(description = "Corrections to one row of a preview")
public record ImportRowEditRequest(
        @Schema(description = """
                The values to replace, by field key (the `values` of the row, and the `field` of its
                findings): sku, category, name_ar, name_en, short_ar, desc_ar, desc_en, meta_title_ar,
                meta_title_en, meta_desc_ar, meta_desc_en, slug, price, compare_at, stock, and
                spec_<attribute code in lowercase> for each specification, e.g. spec_width_cm.
                Keys not sent keep their value. Send an empty text to clear a value. Everything is text,
                so a mistyped number comes back as a finding instead of a rejected request.
                """, example = "{\"price\": \"2650\", \"category\": \"كراسي البار\"}")
        @NotNull(message = "values is required")
        Map<String, String> values
) {
}
