package com.velora.api.export.web;

import com.velora.api.export.dto.OrderExportFilter;
import com.velora.api.export.service.OrderExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Exports", description = "Downloadable order sheets. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/exports")
public class AdminExportController {

    private static final String EXCEL_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OrderExportService exportService;

    public AdminExportController(OrderExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "Accounting archive (Excel)",
            description = """
                    One row per order with a totals row: customer, governorate, subtotal,
                    discount, shipping, grand total, tax and net. The tax and net columns are
                    left out when none of the exported orders carries tax.

                    Money is written as real numbers, not formatted text, so the accountant
                    can sum a column and pivot without retyping anything. The sheet is
                    right-to-left.

                    Cancelled orders are excluded by default — they skew every total.
                    Capped at 5,000 rows; narrow the date range if you hit it.
                    """)
    @ApiResponse(responseCode = "200", description = "An .xlsx file")
    @ApiResponse(responseCode = "404", description = "No orders match the filters")
    @GetMapping("/orders/accounting")
    public ResponseEntity<byte[]> accountingExcel(@ParameterObject OrderExportFilter filter) {
        byte[] body = exportService.exportAccountingExcel(filter);
        String filename = "velora-orders-" + exportService.fileSuffix(filter) + ".xlsx";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(EXCEL_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .body(body);
    }

    @Operation(summary = "Picking and shipping list (PDF)",
            description = """
                    Grouped by order: customer, phone, full address, landmark, the products
                    to pick with a tick box for each, and the amount the courier must
                    collect.

                    Defaults to CONFIRMED orders — the ones ready to pack. Without that
                    default it would print delivered and cancelled orders too, which is
                    worse than useless in a warehouse.

                    The collect-on-delivery amount is printed ONLY for unpaid COD orders,
                    so a courier cannot double-charge a prepaid customer.
                    """)
    @ApiResponse(responseCode = "200", description = "A print-ready PDF")
    @GetMapping("/orders/picking-list")
    public ResponseEntity<byte[]> pickingListPdf(@ParameterObject OrderExportFilter filter) {
        byte[] body = exportService.exportPickingListPdf(filter);
        String filename = "velora-picking-" + exportService.fileSuffix(filter) + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .body(body);
    }

    private String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename).build().toString();
    }
}
