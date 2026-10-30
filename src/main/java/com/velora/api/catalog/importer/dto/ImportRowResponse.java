package com.velora.api.catalog.importer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "One sheet row, for the review screen")
public record ImportRowResponse(
        @Schema(description = "The row number in the sheet, header = 1") int rowNo,
        String sku,
        @Schema(allowableValues = {"CREATE", "UPDATE"}, description = "Null when the row could not be read")
        String action,
        @Schema(allowableValues = {"VALID", "ERROR"}) String status,
        ImportRowData data,
        @Schema(description = "The editable values by field key, exactly what PUT .../rows/{rowNo} takes back")
        Map<String, String> values,
        @Schema(description = "Every specification the sheet can carry, filled or not, so the editor can show all")
        List<Specification> specifications,
        List<ImportMessage> messages,
        @Schema(description = "The product this row became, once the batch is committed")
        Long productId
) {

    /**
     * A specification the sheet can carry. {@code key} is its field key in the editor, {@code kind} is
     * TEXT, NUMBER or BOOLEAN (yes/no written as «نعم» / «لا»), {@code value} is null when not filled.
     */
    public record Specification(String code, String key, String label, String kind, String value) {
    }
}
