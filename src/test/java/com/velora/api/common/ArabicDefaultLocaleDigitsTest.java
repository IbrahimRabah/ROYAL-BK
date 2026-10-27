package com.velora.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;

import com.velora.api.common.config.BrandingProperties;
import com.velora.api.export.service.AccountingExcelWriter;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.service.OrderNumberGenerator;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A server whose JVM runs with an Arabic locale makes every {@code %d} print Arabic-Indic digits.
 * Numbers that identify a document, or sit in an export, must not depend on where it runs.
 */
class ArabicDefaultLocaleDigitsTest {

    private static final String INDIC = ".*[\\u0660-\\u0669\\u06F0-\\u06F9].*";

    private Locale before;

    @BeforeEach
    void useAnArabicServer() {
        before = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
    }

    @AfterEach
    void restore() {
        Locale.setDefault(Locale.Category.FORMAT, before);
    }

    @Test
    @DisplayName("Precondition: under ar-EG a plain %d really does print Arabic-Indic digits on this JDK")
    void thePrecondition() {
        assumeThat(String.format("%d", 5)).matches(INDIC);
    }

    @Test
    @DisplayName("The accounting sheet's totals label keeps Latin digits")
    void excelLabel() throws Exception {
        CustomerOrder order = new CustomerOrder();
        order.setGrandTotal(new BigDecimal("100.00"));
        byte[] bytes = new AccountingExcelWriter().write(List.of(order, new CustomerOrder()), "t");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            String label = sheet.getRow(sheet.getLastRowNum()).getCell(0).getStringCellValue();
            assertThat(label).contains("(2 ").doesNotMatch(INDIC);
        }
    }

    @Test
    @DisplayName("Order numbers keep Latin digits")
    void orderNumber() {
        BrandingProperties branding = new BrandingProperties();
        OrderNumberGenerator generator = new OrderNumberGenerator(mock(OrderRepository.class), branding);

        assertThat(generator.generate()).doesNotMatch(INDIC).matches(".*\\d{4}$");
    }
}
