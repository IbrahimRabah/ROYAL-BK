package com.velora.api.catalog.importer.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.velora.api.catalog.importer.service.ImportTemplate;
import com.velora.api.catalog.importer.service.ImportTemplate.Column;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One finding about one cell or row. The client branches on {@code code}, never on the message
 * text, like every other error in this API.
 *
 * <p>{@code column}, {@code field} and {@code cell} say WHERE: the header of the column, the key of
 * the value in the edit form, and the spreadsheet cell it came from. They let the review screen show
 * "column AN, cell AN5" and jump straight to the input that fixes it.
 */
@Schema(description = "A validation finding for a sheet row")
public record ImportMessage(
        @Schema(description = "The header of the column concerned, null for a whole-row finding")
        String column,
        @Schema(description = "The key of the value in the row editor (see ImportRowEditRequest), null for a "
                + "whole-row finding", example = "price")
        String field,
        @Schema(description = "The cell in the uploaded workbook, e.g. AN5 or SEO!T7. Null when it has no cell",
                example = "AN5")
        String cell,
        @Schema(allowableValues = {"ERROR", "WARNING"}) String severity,
        @Schema(example = "REQUIRED") String code,
        String message
) {

    public static final String ERROR = "ERROR";
    public static final String WARNING = "WARNING";

    /** A finding about the value {@code field}; its cell is filled in once the row's position is known. */
    public static ImportMessage error(String field, String code, String message) {
        return of(ERROR, field, code, message);
    }

    public static ImportMessage warning(String field, String code, String message) {
        return of(WARNING, field, code, message);
    }

    private static ImportMessage of(String severity, String field, String code, String message) {
        Column column = field == null ? null : ImportTemplate.column(field);
        return new ImportMessage(column == null ? null : column.header(), field, null, severity, code, message);
    }

    /** This finding with the cell it points at: the main sheet's row, or the SEO sheet's row for SEO columns. */
    public ImportMessage locatedAt(int rowNo, Integer seoRowNo) {
        Column c = field == null ? null : ImportTemplate.column(field);
        if (c == null) {
            return this;
        }
        String ref;
        if (c.sheet() == ImportTemplate.Sheet.MAIN) {
            ref = ImportTemplate.columnLetter(c.index()) + rowNo;
        } else if (seoRowNo != null) {
            ref = "SEO!" + ImportTemplate.columnLetter(c.index()) + seoRowNo;
        } else {
            return this;
        }
        return new ImportMessage(column, field, ref, severity, code, message);
    }

    @JsonIgnore
    public boolean isError() {
        return ERROR.equals(severity);
    }
}
