package com.velora.api.catalog.importer.service;

import com.velora.api.catalog.importer.service.ImportTemplate.Column;
import com.velora.api.catalog.importer.service.ImportTemplate.Sheet;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Reads an uploaded workbook and lets through ONLY a file shaped like the downloaded template.
 *
 * <p>The template is the furniture workbook itself: the main sheet «كرسي البار» and the «SEO» sheet,
 * each with its header row. Both header rows have to match {@link ImportTemplate}, cell for cell and in
 * order. A renamed, moved, missing or extra column fails the whole file with
 * {@link ErrorCode#IMPORT_TEMPLATE_MISMATCH} and a message naming each difference. Nothing is guessed
 * or repaired. Any other sheet in the workbook (the English mirror, the dropdown lists, review notes)
 * is not looked at.
 *
 * <p>One product is one row of the main sheet, joined to its row of the SEO sheet by SKU. The result
 * keeps the cell texts by column key, with the fallbacks already applied (see {@link #FALLBACKS}).
 */
@Component
public class ImportSheetReader {

    /** What the reader returns for one product row: the cell texts by column key. */
    public record RawRow(int rowNo, Integer seoRowNo, Map<String, String> values) {

        public String get(String key) {
            return values.get(key);
        }
    }

    /** Set on a row whose SKU has no row in the SEO sheet, which holds the category and the slug. */
    public static final String SEO_MISSING = "seo_missing";

    /**
     * Where a value comes from when its own column is empty, in order. The sheet repeats several values
     * (titles and descriptions sit in both sheets, the price in three columns), and which one is filled
     * depends on how the workbook was worked on.
     */
    private static final Map<String, List<String>> FALLBACKS = Map.of(
            "name_ar", List.of("name_ar_seo"),
            "name_en", List.of("name_en_seo"),
            "desc_ar", List.of("desc_ar_seo"),
            "desc_en", List.of("desc_en_seo", "desc_en_alt"),
            "price", List.of("price_seo", "price_ag"));

    private static final int MAX_PROBLEMS_SHOWN = 8;
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    private final DataFormatter formatter = new DataFormatter();

    public List<RawRow> read(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        if (!startsWithZipMagic(content)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Only .xlsx files made from the product import template are accepted");
        }

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            org.apache.poi.ss.usermodel.Sheet main = workbook.getSheet(ImportTemplate.SHEET_MAIN);
            org.apache.poi.ss.usermodel.Sheet seo = workbook.getSheet(ImportTemplate.SHEET_SEO);
            if (main == null || seo == null) {
                throw mismatch("The sheet «" + (main == null ? ImportTemplate.SHEET_MAIN : ImportTemplate.SHEET_SEO)
                        + "» is missing");
            }
            checkHeader(main, Sheet.MAIN);
            checkHeader(seo, Sheet.SEO);
            return readRows(main, seo);
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "The file could not be read as an Excel workbook (.xlsx)");
        }
    }

    // ----------------------------------------------------------------------- header

    private void checkHeader(org.apache.poi.ss.usermodel.Sheet sheet, Sheet which) {
        String[] expected = ImportTemplate.headers(which);
        Row header = sheet.getRow(0);
        List<String> problems = new ArrayList<>();

        int found = header == null ? 0 : Math.max(header.getLastCellNum(), 0);
        int width = Math.max(expected.length, found);

        for (int i = 0; i < width; i++) {
            String want = i < expected.length ? ImportTemplate.normalizeHeader(expected[i]) : "";
            String got = header == null ? "" : ImportTemplate.normalizeHeader(text(header.getCell(i)));
            if (want.equals(got)) {
                continue;
            }
            String letter = ImportTemplate.columnLetter(i);
            if (got.isEmpty()) {
                problems.add("column " + letter + " is empty, expected «" + want + "»");
            } else if (want.isEmpty()) {
                problems.add("column " + letter + " «" + got + "» is not part of the template");
            } else {
                problems.add("column " + letter + " is «" + got + "», expected «" + want + "»");
            }
        }

        if (!problems.isEmpty()) {
            String shown = String.join("; ", problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS_SHOWN)));
            String more = problems.size() > MAX_PROBLEMS_SHOWN
                    ? " (and " + (problems.size() - MAX_PROBLEMS_SHOWN) + " more)" : "";
            throw mismatch("In the sheet «" + ImportTemplate.sheetName(which) + "» the header row differs from the "
                    + "template: " + shown + more);
        }
    }

    // ------------------------------------------------------------------------- rows

    private List<RawRow> readRows(org.apache.poi.ss.usermodel.Sheet main, org.apache.poi.ss.usermodel.Sheet seo) {
        Map<String, Row> seoBySku = new HashMap<>();
        for (int r = 1; r <= seo.getLastRowNum(); r++) {
            Row row = seo.getRow(r);
            String sku = row == null ? "" : text(row.getCell(ImportTemplate.SEO_SKU_INDEX));
            if (!sku.isEmpty()) {
                seoBySku.putIfAbsent(sku.toUpperCase(Locale.ROOT), row);
            }
        }

        List<Column> mainColumns = ImportTemplate.columns(Sheet.MAIN);
        List<Column> seoColumns = ImportTemplate.columns(Sheet.SEO);
        int mainWidth = ImportTemplate.headers(Sheet.MAIN).length;
        List<RawRow> rows = new ArrayList<>();

        for (int r = 1; r <= main.getLastRowNum(); r++) {
            Row row = main.getRow(r);
            if (row == null || isBlank(row, mainWidth)) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Column c : mainColumns) {
                values.put(c.key(), text(row.getCell(c.index())));
            }

            String sku = values.get("sku").toUpperCase(Locale.ROOT);
            Row seoRow = sku.isEmpty() ? null : seoBySku.get(sku);
            for (Column c : seoColumns) {
                values.put(c.key(), seoRow == null ? "" : text(seoRow.getCell(c.index())));
            }
            if (seoRow == null) {
                values.put(SEO_MISSING, "1");
            }
            applyFallbacks(values);

            if (rows.size() >= ImportTemplate.MAX_ROWS) {
                throw new BusinessException(ErrorCode.IMPORT_TOO_MANY_ROWS,
                        "One import accepts at most " + ImportTemplate.MAX_ROWS + " products");
            }
            rows.add(new RawRow(r + 1, seoRow == null ? null : seoRow.getRowNum() + 1, values));
        }

        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_EMPTY);
        }
        return rows;
    }

    private void applyFallbacks(Map<String, String> values) {
        FALLBACKS.forEach((key, sources) -> {
            if (!values.getOrDefault(key, "").isEmpty()) {
                return;
            }
            for (String source : sources) {
                String v = values.getOrDefault(source, "");
                if (!v.isEmpty()) {
                    values.put(key, v);
                    return;
                }
            }
        });
    }

    private boolean isBlank(Row row, int width) {
        for (int c = 0; c < width; c++) {
            if (!text(row.getCell(c)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** A cell as the person sees it: trimmed text, numbers without a trailing ".0". */
    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? formatter.formatCellValue(cell)
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> "";
        };
        return value == null ? "" : value.replace(' ', ' ').trim();
    }

    // ---------------------------------------------------------------------- helpers

    private BusinessException mismatch(String detail) {
        return new BusinessException(ErrorCode.IMPORT_TEMPLATE_MISMATCH,
                ErrorCode.IMPORT_TEMPLATE_MISMATCH.getDefaultMessage() + ". " + detail);
    }

    private static boolean startsWithZipMagic(byte[] content) {
        if (content.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (content[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
