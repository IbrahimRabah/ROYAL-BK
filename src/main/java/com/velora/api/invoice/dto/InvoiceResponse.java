package com.velora.api.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "An issued invoice")
public record InvoiceResponse(
        Long id,
        @Schema(example = "VLR-INV-2026-000001") String invoiceNumber,
        String status,
        Long orderId,
        String orderNumber,
        String buyerName,
        String buyerPhone,
        BigDecimal grandTotal,
        BigDecimal taxTotal,
        BigDecimal netTotal,
        String currency,
        @Schema(description = "Path of the admin PDF endpoint (GET, needs the admin bearer token — fetch it as a blob; it cannot be opened as a plain link).")
        String pdfUrl,
        OffsetDateTime issuedAt,
        String cancelReason,
        @Schema(description = "Number of the cancelled invoice this one replaces. Absent when "
                + "it replaces none.")
        String replacesInvoiceNumber
) {
}
