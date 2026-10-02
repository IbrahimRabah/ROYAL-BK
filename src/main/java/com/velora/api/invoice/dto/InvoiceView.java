package com.velora.api.invoice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The model the invoice template renders.
 *
 * <p>Flat and fully resolved so the template never triggers a lazy load — with
 * {@code open-in-view: false} that would throw halfway through rendering a PDF.
 */
public record InvoiceView(
        String invoiceNumber,
        String issuedDate,
        String orderNumber,
        String orderDate,
        boolean cancelled,

        String sellerName,
        String sellerAddress,
        String sellerPhone,
        String sellerEmail,
        String sellerTaxNumber,
        String sellerCommercialRegister,
        String footerNote,

        String buyerName,
        String buyerPhone,
        String buyerAddress,

        List<Line> lines,
        BigDecimal subtotalGross,
        BigDecimal discountTotal,
        BigDecimal shippingCost,
        BigDecimal codFee,
        BigDecimal assemblyTotal,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String paymentMethod,
        String currency
) {

    /**
     * One invoice line, broken into net / tax / gross.
     *
     * <p>Prices are tax-INCLUSIVE, so the net is derived by extraction. Showing all
     * three columns is what makes the arithmetic checkable by an accountant.
     */
    public record Line(
            String sku,
            String name,
            String variant,
            int quantity,
            BigDecimal unitPriceGross,
            BigDecimal discount,
            BigDecimal netAmount,
            BigDecimal taxRatePercent,
            BigDecimal taxAmount,
            BigDecimal grossAmount
    ) {
    }
}
