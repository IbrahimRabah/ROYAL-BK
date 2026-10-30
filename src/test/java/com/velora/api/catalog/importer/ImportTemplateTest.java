package com.velora.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.importer.service.ImportTemplate;
import com.velora.api.catalog.importer.service.ImportTemplate.Sheet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The downloadable template resource and the code that reads uploads must describe the same workbook. */
class ImportTemplateTest {

    private final DataFormatter formatter = new DataFormatter();

    @Test
    @DisplayName("The template resource carries exactly the header rows the reader expects, in both sheets")
    void resourceMatchesDefinition() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbooks.template()))) {
            for (Sheet sheet : Sheet.values()) {
                String[] expected = ImportTemplate.headers(sheet);
                Row header = wb.getSheet(ImportTemplate.sheetName(sheet)).getRow(0);

                assertThat((int) header.getLastCellNum()).as(sheet + " header width").isEqualTo(expected.length);
                for (int i = 0; i < expected.length; i++) {
                    assertThat(ImportTemplate.normalizeHeader(formatter.formatCellValue(header.getCell(i))))
                            .as(sheet + " column " + ImportTemplate.columnLetter(i))
                            .isEqualTo(ImportTemplate.normalizeHeader(expected[i]));
                }
            }
        }
    }

    @Test
    @DisplayName("The template carries no product data, and keeps the dropdown-list sheet its cells validate against")
    void resourceHasNoDataButHasLists() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbooks.template()))) {
            assertThat(wb.getSheet(ImportTemplate.SHEET_MAIN).getLastRowNum()).isZero();
            assertThat(wb.getSheet(ImportTemplate.SHEET_SEO).getLastRowNum()).isZero();
            assertThat(wb.getSheet("Dropdawon")).isNotNull();
            assertThat(wb.getSheet("Eng")).isNull();
        }
    }

    @Test
    @DisplayName("Every column key is unique and points inside its sheet")
    void columnsAreConsistent() {
        Set<String> keys = new HashSet<>();
        ImportTemplate.COLUMNS.forEach(c -> {
            assertThat(keys.add(c.key())).as("duplicate key " + c.key()).isTrue();
            assertThat(c.index()).as(c.key()).isLessThan(ImportTemplate.headers(c.sheet()).length);
        });
    }
}
