package com.velora.api.catalog.importer;

import com.velora.api.catalog.importer.service.ImportTemplate;
import com.velora.api.catalog.importer.service.ImportTemplate.Column;
import com.velora.api.catalog.importer.service.ImportTemplate.Sheet;
import com.velora.api.catalog.importer.service.ImportTemplateProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds import workbooks for the tests from the real downloadable template: the three sample rows are
 * cleared, then rows are written by column key into the main sheet and the SEO sheet.
 */
final class ImportWorkbooks {

    private ImportWorkbooks() {
    }

    static byte[] template() {
        return new ImportTemplateProvider().template();
    }

    /** The template with no product rows, then changed by {@code change}. */
    static byte[] empty(Consumer<XSSFWorkbook> change) {
        return edit(template(), wb -> {
            clearRows(wb.getSheet(ImportTemplate.SHEET_MAIN));
            clearRows(wb.getSheet(ImportTemplate.SHEET_SEO));
            change.accept(wb);
        });
    }

    /** The template as downloaded (sample rows included), then changed by {@code change}. */
    static byte[] sample(Consumer<XSSFWorkbook> change) {
        return edit(template(), change);
    }

    static byte[] edit(byte[] bytes, Consumer<XSSFWorkbook> change) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            change.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void clearRows(XSSFSheet sheet) {
        for (int r = sheet.getLastRowNum(); r >= 1; r--) {
            Row row = sheet.getRow(r);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    /**
     * Writes one product at {@code rowIndex} (zero-based, so 1 is the first data row) in both sheets: a
     * value goes to the sheet its column key belongs to, and the SKU is repeated in the SEO sheet.
     */
    static void put(XSSFWorkbook wb, int rowIndex, Map<String, String> values) {
        XSSFSheet main = wb.getSheet(ImportTemplate.SHEET_MAIN);
        XSSFSheet seo = wb.getSheet(ImportTemplate.SHEET_SEO);
        Row mainRow = main.createRow(rowIndex);
        Row seoRow = seo.createRow(rowIndex);

        String sku = values.get("sku");
        if (sku != null) {
            seoRow.createCell(ImportTemplate.SEO_SKU_INDEX).setCellValue(sku);
        }
        values.forEach((key, value) -> {
            Column column = ImportTemplate.column(key);
            if (column == null) {
                throw new IllegalArgumentException("No template column " + key);
            }
            Row row = column.sheet() == Sheet.MAIN ? mainRow : seoRow;
            if (value.matches("[0-9]+(\\.[0-9]+)?") && isNumberColumn(column)) {
                row.createCell(column.index()).setCellValue(Double.parseDouble(value));
            } else {
                row.createCell(column.index()).setCellValue(value);
            }
        });
    }

    private static boolean isNumberColumn(Column column) {
        return column.kind() == ImportTemplate.Kind.MONEY || column.kind() == ImportTemplate.Kind.INTEGER;
    }

    static Map<String, String> row(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
