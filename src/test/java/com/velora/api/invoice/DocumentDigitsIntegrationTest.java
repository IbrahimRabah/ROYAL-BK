package com.velora.api.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.export.dto.PickingListView;
import com.velora.api.export.service.TemplateRenderer;
import com.velora.api.invoice.dto.InvoiceView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Commercial documents in Egypt use Latin digits, "1,000.00": an invoice reading ١٬٠٠٠٫٠٠ looks
 * foreign to the customer and has to be retyped into an accounting system. The documents stay
 * Arabic in language and direction; only the digits are pinned here.
 *
 * <p>These tests read the rendered output as it is - no digit is translated before comparing -
 * because a test that translates Arabic-Indic digits first passes with or without the defect.
 */
@SpringBootTest
class DocumentDigitsIntegrationTest {

    /** Arabic-Indic and extended Arabic-Indic digits, and their separators. */
    private static final String INDIC = "[\\u0660-\\u0669\\u06F0-\\u06F9\\u066B\\u066C]";

    @Autowired private TemplateRenderer templateRenderer;

    @Test
    @DisplayName("Invoice amounts print as 1,000.00: Latin digits, comma for thousands, dot for decimals")
    void invoiceAmountsUseLatinDigits() {
        String html = templateRenderer.render("invoice", "view", invoice());

        assertThat(html).contains("1,000.00", "877.19", "122.81", "1,050.00", "14%");
        assertThat(html).doesNotContainPattern(INDIC);
    }

    @Test
    @DisplayName("The invoice dates print as Gregorian yyyy-MM-dd in Latin digits, and the page stays Arabic and RTL")
    void invoiceDatesAndLanguage() {
        String html = templateRenderer.render("invoice", "view", invoice());

        assertThat(html).contains("2026-10-27").doesNotContainPattern(INDIC);
        assertThat(html).contains("lang=\"ar\"").contains("dir=\"rtl\"").contains("رقم الفاتورة");
    }

    @Test
    @DisplayName("The picking list prints its COD amounts in Latin digits too")
    void pickingListAmountsUseLatinDigits() {
        PickingListView view = new PickingListView("قائمة التحضير والشحن", "2026-10-27 10:30", 1, 2,
                new BigDecimal("2500.50"),
                List.of(new PickingListView.OrderBlock("RYL-261027-0001", "2026-10-27 09:00", "عميل",
                        "01012345678", null, "القاهرة", "شارع", null, null, "COD",
                        new BigDecimal("2500.50"), 2,
                        List.of(new PickingListView.LineItem("SKU-1", "ترابيزة", null, 2,
                                new BigDecimal("1250.25"), new BigDecimal("2500.50"))))));

        String html = templateRenderer.render("picking-list", "view", view);

        assertThat(html).contains("2,500.50");
        assertThat(html).doesNotContainPattern(INDIC);
        assertThat(html).contains("قائمة التحضير والشحن");
    }

    private static InvoiceView invoice() {
        BigDecimal gross = new BigDecimal("1000.00");
        BigDecimal net = new BigDecimal("877.19");
        BigDecimal tax = new BigDecimal("122.81");
        return new InvoiceView(
                "RYL-INV-2026-000001", "2026-10-27", "RYL-261027-0001", "2026-10-27", false,
                "رويال آيرون", "شارع الصناعة، العاشر من رمضان", "01012345678", "info@example.com",
                null, null, null,
                "عميل", "01099999999", "القاهرة",
                List.of(new InvoiceView.Line("SKU-1", "ترابيزة", null, 1, gross, BigDecimal.ZERO,
                        net, new BigDecimal("14"), tax, gross)),
                gross, BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                net, tax, new BigDecimal("1050.00"),
                "الدفع عند الاستلام", "EGP", "رويال آيرون");
    }
}
