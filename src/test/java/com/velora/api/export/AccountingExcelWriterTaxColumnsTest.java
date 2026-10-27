package com.velora.api.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.export.service.AccountingExcelWriter;
import com.velora.api.order.domain.CustomerOrder;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The accounting sheet leaves out the tax and net columns when nothing in it carries tax. */
class AccountingExcelWriterTaxColumnsTest {

    private static final int HEADER_ROW = 2;
    private final AccountingExcelWriter writer = new AccountingExcelWriter();

    @Test
    @DisplayName("Orders without tax: no tax or net column, and every later column moves up")
    void zeroTaxLeavesTheColumnsOut() throws Exception {
        Sheet sheet = sheet(List.of(order("0.0000", "1000.0000"), order("0.0000", "500.0000")));

        List<String> headers = headers(sheet);
        assertThat(headers).doesNotContain("منه ضريبة", "الصافي");
        assertThat(headers).hasSize(17).endsWith("طريقة الدفع", "حالة الدفع", "حالة الطلب");

        Row totals = sheet.getRow(sheet.getLastRowNum());
        assertThat(totals.getCell(headers.indexOf("الإجمالي")).getNumericCellValue()).isEqualTo(1500.0);
        assertThat(totals.getLastCellNum()).as("the totals row is as wide as the header").isEqualTo((short) 17);
    }

    @Test
    @DisplayName("A range that includes an order taxed earlier keeps both columns, with its tax")
    void aTaxedOrderKeepsTheColumns() throws Exception {
        Sheet sheet = sheet(List.of(order("0.0000", "1000.0000"), order("140.0000", "1140.0000")));

        List<String> headers = headers(sheet);
        assertThat(headers).hasSize(19).contains("منه ضريبة", "الصافي");
        Row totals = sheet.getRow(sheet.getLastRowNum());
        assertThat(totals.getCell(headers.indexOf("منه ضريبة")).getNumericCellValue()).isEqualTo(140.0);
    }

    private Sheet sheet(List<CustomerOrder> orders) throws Exception {
        byte[] bytes = writer.write(orders, "أرشيف");
        return new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
    }

    private static List<String> headers(Sheet sheet) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : sheet.getRow(HEADER_ROW)) {
            headers.add(cell.getStringCellValue());
        }
        return headers;
    }

    private static CustomerOrder order(String tax, String grand) {
        CustomerOrder order = new CustomerOrder();
        order.setTaxTotal(new BigDecimal(tax));
        order.setGrandTotal(new BigDecimal(grand));
        order.setNetTotal(new BigDecimal(grand).subtract(new BigDecimal(tax)));
        return order;
    }
}
