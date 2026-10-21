package com.velora.api.invoice.web;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.invoice.dto.CancelInvoiceRequest;
import com.velora.api.invoice.dto.InvoiceResponse;
import com.velora.api.invoice.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.velora.api.identity.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Invoices", description = "Tax invoices. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/invoices")
public class AdminInvoiceController {

    private final InvoiceService invoiceService;

    public AdminInvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @Operation(summary = "List invoices, newest first",
            description = "With orderId, only that order's invoices, cancelled ones included "
                    + "(each shows its status and replacesInvoiceNumber).")
    @GetMapping
    public PageResponse<InvoiceResponse> list(
            @RequestParam(required = false) Long orderId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return invoiceService.list(orderId, pageable);
    }

    @Operation(summary = "One invoice")
    @GetMapping("/{invoiceId}")
    public InvoiceResponse get(@PathVariable Long invoiceId) {
        return invoiceService.get(invoiceId);
    }

    @Operation(summary = "Download the PDF",
            description = "Rendered fresh from the stored snapshot, so it is identical "
                    + "every time even if prices or the store address have changed since.")
    @GetMapping("/{invoiceId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long invoiceId) {
        InvoiceResponse invoice = invoiceService.get(invoiceId);
        byte[] body = invoiceService.renderPdf(invoiceId);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(invoice.invoiceNumber() + ".pdf").build().toString())
                .body(body);
    }

    @Operation(summary = "Issue an invoice manually",
            description = """
                    Invoices are issued automatically on delivery. This exists for the
                    orders that slipped through — delivered before this module existed,
                    or fixed up by hand in the database.

                    Idempotent: an order that already has a live (non-cancelled) invoice
                    returns it rather than consuming another number. If the order's invoice was
                    cancelled, this issues a NEW invoice with the next number, linked to the
                    cancelled one through replacesInvoiceNumber.
                    """)
    @PostMapping("/issue/{orderId}")
    public InvoiceResponse issue(@PathVariable Long orderId,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        return invoiceService.get(invoiceService.issueForOrder(orderId, principal.id()).getId());
    }

    @Operation(summary = "Cancel an invoice",
            description = """
                    The number stays consumed and the row stays in place. A cancelled
                    invoice still has to be explainable to an auditor, and deleting one
                    would leave a gap in the sequence.
                    """)
    @PostMapping("/{invoiceId}/cancel")
    public InvoiceResponse cancel(@PathVariable Long invoiceId,
                                  @Valid @RequestBody CancelInvoiceRequest request,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        return invoiceService.get(
                invoiceService.cancel(invoiceId, request.reason(), principal.id()).getId());
    }

    @Operation(summary = "Delivered orders with no invoice",
            description = """
                    A reconciliation check that should always return an empty list.
                    Anything here is an order that was delivered without being invoiced —
                    issue it with POST /issue/{orderId}.
                    """)
    @GetMapping("/reconciliation/uninvoiced")
    public Map<String, Object> uninvoiced() {
        List<Long> orderIds = invoiceService.findUninvoicedDeliveredOrders();
        return Map.of("count", orderIds.size(), "orderIds", orderIds);
    }
}
