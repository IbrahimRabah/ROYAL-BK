package com.velora.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.importer.service.ImportSheetReader;
import com.velora.api.catalog.importer.service.ImportSheetReader.RawRow;
import com.velora.api.catalog.importer.service.ImportTemplate;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The upload accepts only a file shaped like the downloaded template. No database: the real template
 * resource is damaged in the ways a person would damage it.
 */
class ImportSheetReaderTest {

    private final ImportSheetReader reader = new ImportSheetReader();

    private BusinessException rejected(byte[] file) {
        return org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () -> reader.read(file));
    }

    private static XSSFSheet main(org.apache.poi.xssf.usermodel.XSSFWorkbook wb) {
        return wb.getSheet(ImportTemplate.SHEET_MAIN);
    }

    private static XSSFSheet seo(org.apache.poi.xssf.usermodel.XSSFWorkbook wb) {
        return wb.getSheet(ImportTemplate.SHEET_SEO);
    }

    @Test
    @DisplayName("The downloaded template has headers only, so as downloaded it is refused as empty")
    void theBareTemplateIsEmpty() {
        assertThat(rejected(ImportWorkbooks.template()).getErrorCode()).isEqualTo(ErrorCode.IMPORT_EMPTY);
    }

    @Test
    @DisplayName("A filled template is read from both sheets, joined by SKU, with the cell of each row kept")
    void readsBothSheets() {
        List<RawRow> rows = reader.read(ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, ImportWorkbooks.row("sku", "CH135", "category", "كراسي بار",
                    "slug", "bar-stool-ch135", "meta_title_en", "Iron Bar Stool | Royal Iron",
                    "price", "2650", "spec_width_cm", "50", "name_ar", "كرسي بار CH135"));
            ImportWorkbooks.put(wb, 2, ImportWorkbooks.row("sku", "CH136", "category", "كراسي"));
        }));

        assertThat(rows).hasSize(2);
        RawRow first = rows.get(0);
        assertThat(first.rowNo()).isEqualTo(2);
        assertThat(first.seoRowNo()).isEqualTo(2);
        assertThat(first.get("sku")).isEqualTo("CH135");
        // From the SEO sheet, joined by SKU.
        assertThat(first.get("category")).isEqualTo("كراسي بار");
        assertThat(first.get("slug")).isEqualTo("bar-stool-ch135");
        assertThat(first.get("meta_title_en")).isEqualTo("Iron Bar Stool | Royal Iron");
        // From the main sheet.
        assertThat(first.get("price")).isEqualTo("2650");
        assertThat(first.get("spec_width_cm")).isEqualTo("50");
        assertThat(rows.get(1).get("category")).isEqualTo("كراسي");
        assertThat(first.get(ImportSheetReader.SEO_MISSING)).isNull();
    }

    @Test
    @DisplayName("A renamed header in the main sheet is refused, and the message names the sheet and the column")
    void renamedMainHeader() {
        BusinessException e = rejected(ImportWorkbooks.empty(wb ->
                main(wb).getRow(0).getCell(1).setCellValue("الاسم")));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
        assertThat(e.getMessage()).contains("«" + ImportTemplate.SHEET_MAIN + "»").contains("column B")
                .contains("الاسم");
    }

    @Test
    @DisplayName("A changed header in the SEO sheet is refused too")
    void renamedSeoHeader() {
        BusinessException e = rejected(ImportWorkbooks.empty(wb ->
                seo(wb).getRow(0).getCell(19).setCellValue("Slug")));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
        assertThat(e.getMessage()).contains("«SEO»").contains("column T");
    }

    @Test
    @DisplayName("Two columns swapped is refused: order is part of the template")
    void swappedColumns() {
        BusinessException e = rejected(ImportWorkbooks.empty(wb -> {
            Row header = main(wb).getRow(0);
            String a = header.getCell(4).getStringCellValue();
            header.getCell(4).setCellValue(header.getCell(5).getStringCellValue());
            header.getCell(5).setCellValue(a);
        }));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
    }

    @Test
    @DisplayName("A deleted column is refused")
    void deletedColumn() {
        BusinessException e = rejected(ImportWorkbooks.empty(wb ->
                main(wb).shiftColumns(4, ImportTemplate.headers(ImportTemplate.Sheet.MAIN).length, -1)));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
    }

    @Test
    @DisplayName("An added column is refused")
    void addedColumn() {
        BusinessException e = rejected(ImportWorkbooks.empty(wb ->
                main(wb).getRow(0).createCell(ImportTemplate.headers(ImportTemplate.Sheet.MAIN).length)
                        .setCellValue("عمود جديد")));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
        assertThat(e.getMessage()).contains("is not part of the template");
    }

    @Test
    @DisplayName("A header that only differs by stray spaces still matches")
    void whitespaceIsTolerated() {
        List<RawRow> rows = reader.read(ImportWorkbooks.empty(wb -> {
            main(wb).getRow(0).getCell(0).setCellValue("  رقم   الموديل ");
            ImportWorkbooks.put(wb, 1, ImportWorkbooks.row("sku", "A1", "category", "كراسي"));
        }));

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("A workbook without the SEO sheet, or without the main sheet, is refused")
    void missingSheets() {
        BusinessException noSeo = rejected(ImportWorkbooks.empty(wb ->
                wb.removeSheetAt(wb.getSheetIndex(ImportTemplate.SHEET_SEO))));
        BusinessException noMain = rejected(ImportWorkbooks.empty(wb ->
                wb.removeSheetAt(wb.getSheetIndex(ImportTemplate.SHEET_MAIN))));

        assertThat(noSeo.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
        assertThat(noMain.getErrorCode()).isEqualTo(ErrorCode.IMPORT_TEMPLATE_MISMATCH);
    }

    @Test
    @DisplayName("Sheets the template does not use, and extra sheets of the original workbook, are ignored")
    void otherSheetsAreIgnored() {
        List<RawRow> rows = reader.read(ImportWorkbooks.empty(wb -> {
            wb.createSheet("Eng");
            ImportWorkbooks.put(wb, 1, ImportWorkbooks.row("sku", "A1", "category", "كراسي"));
        }));

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("Something that is not an xlsx is refused as the wrong file type")
    void notAWorkbook() {
        assertThat(rejected("a,b,c\n1,2,3".getBytes()).getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
        assertThat(rejected(new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("A row whose SKU has no SEO row is flagged, and blank rows between products are skipped")
    void joinBySku() {
        List<RawRow> rows = reader.read(ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, ImportWorkbooks.row("sku", "A1", "name_ar", "أ", "price", "10",
                    "category", "كراسي"));
            // No SEO row for this SKU: written to the main sheet only.
            main(wb).createRow(4).createCell(0).setCellValue("B2");
        }));

        assertThat(rows).extracting(RawRow::rowNo).containsExactly(2, 5);
        assertThat(rows.get(0).get(ImportSheetReader.SEO_MISSING)).isNull();
        assertThat(rows.get(1).get(ImportSheetReader.SEO_MISSING)).isEqualTo("1");
    }

    @Test
    @DisplayName("An empty title, description or price falls back to the other sheet's copy of it")
    void fallbacks() {
        List<RawRow> rows = reader.read(ImportWorkbooks.empty(wb ->
                ImportWorkbooks.put(wb, 1, ImportWorkbooks.row("sku", "A1", "category", "كراسي",
                        "name_ar_seo", "عنوان من السيو", "price_seo", "777", "desc_en_seo", "From SEO"))));

        RawRow row = rows.get(0);
        assertThat(row.get("name_ar")).isEqualTo("عنوان من السيو");
        assertThat(row.get("price")).isEqualTo("777");
        assertThat(row.get("desc_en")).isEqualTo("From SEO");
    }
}
