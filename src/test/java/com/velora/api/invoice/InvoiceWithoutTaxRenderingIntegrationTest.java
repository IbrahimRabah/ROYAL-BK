package com.velora.api.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.export.service.TemplateRenderer;
import com.velora.api.invoice.dto.InvoiceView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The invoice template with and without tax. While the company is not VAT-registered every rate
 * is zero and the invoice must read as a plain total; an invoice issued at 14% before that must
 * still show its tax when it is rendered again.
 */
@SpringBootTest
class InvoiceWithoutTaxRenderingIntegrationTest {

    @Autowired private TemplateRenderer templateRenderer;

    @Test
    @DisplayName("Zero tax: no tax or net rows or columns, no tax wording, no 0.00 - and the seller address prints")
    void zeroTaxReadsAsAPlainTotal() {
        String html = render(view(new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("1000.00"),
                BigDecimal.ZERO));

        assertThat(html).doesNotContain("قيمة الضريبة", "الصافي", "شاملة ضريبة");
        // The line's tax column header and its rate cell.
        assertThat(html).doesNotContain(">الضريبة<").doesNotContainPattern("(?<![\\d.])0%");
        // A zero amount is never printed. "1,000.00" must not trip this, hence the lookbehind.
        assertThat(html).doesNotContainPattern("(?<![\\d,])0\\.00");

        assertThat(html).contains("شارع الصناعة، العاشر من رمضان");
        assertThat(html).contains("1,000.00");
    }

    @Test
    @DisplayName("The title describes the document: tax invoice with tax, plain invoice without")
    void titleFollowsTheDocument() {
        String plain = render(view(new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("1000.00"),
                BigDecimal.ZERO));
        String taxed = render(view(new BigDecimal("877.19"), new BigDecimal("122.81"), new BigDecimal("1000.00"),
                new BigDecimal("14")));

        assertThat(plain).contains(">فاتورة</div>").doesNotContain("فاتورة ضريبية");
        assertThat(taxed).contains(">فاتورة ضريبية</div>");
    }

    @Test
    @DisplayName("Free shipping prints مجاني, not 0.00; a charged shipping prints its amount")
    void freeShippingIsSaidInWords() {
        String free = render(view(new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("1000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO));
        String charged = render(view(new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("1000.00"),
                BigDecimal.ZERO, new BigDecimal("50.00")));

        assertThat(free).contains("مجاني").doesNotContainPattern("(?<![\\d,])0\\.00");
        assertThat(charged).contains("50.00").doesNotContain("مجاني");
    }

    @Test
    @DisplayName("The tax number and the commercial register are gone from the template, even if the data has them")
    void noTaxNumberOrRegister() {
        String html = render(view(new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("1000.00"),
                BigDecimal.ZERO));

        assertThat(html).doesNotContain("الرقم الضريبي", "السجل التجاري", "123-456-789", "CR-55555");
    }

    @Test
    @DisplayName("An invoice that carries tax still shows its net, tax and breakdown")
    void taxedInvoiceKeepsItsBreakdown() {
        String html = render(view(new BigDecimal("877.19"), new BigDecimal("122.81"), new BigDecimal("1000.00"),
                new BigDecimal("14")));

        assertThat(html).contains("الصافي قبل الضريبة", "قيمة الضريبة", "شاملة ضريبة", "122.81", "877.19", "14%");
    }

    /** As rendered, untouched: the digits are pinned in {@link DocumentDigitsIntegrationTest}. */
    private String render(InvoiceView view) {
        return templateRenderer.render("invoice", "view", view);
    }

    private static InvoiceView view(BigDecimal net, BigDecimal tax, BigDecimal gross, BigDecimal ratePercent) {
        return view(net, tax, gross, ratePercent, new BigDecimal("50.00"));
    }

    private static InvoiceView view(BigDecimal net, BigDecimal tax, BigDecimal gross, BigDecimal ratePercent,
                                    BigDecimal shipping) {
        return new InvoiceView(
                "RYL-INV-2026-000001", "2026-10-27", "RYL-261027-0001", "2026-10-27", false,
                "رويال آيرون", "شارع الصناعة، العاشر من رمضان", "01012345678", "info@example.com",
                "123-456-789", "CR-55555", null,
                "عميل", "01099999999", "القاهرة",
                List.of(new InvoiceView.Line("SKU-1", "ترابيزة", null, 1, gross, BigDecimal.ZERO,
                        net, ratePercent, tax, gross)),
                gross, BigDecimal.ZERO, shipping, BigDecimal.ZERO, BigDecimal.ZERO,
                net, tax, gross.add(shipping),
                "الدفع عند الاستلام", "EGP", "رويال آيرون");
    }
}
