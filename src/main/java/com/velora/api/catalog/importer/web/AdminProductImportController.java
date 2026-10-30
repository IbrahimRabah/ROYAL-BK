package com.velora.api.catalog.importer.web;

import com.velora.api.catalog.importer.domain.ImportRowStatus;
import com.velora.api.catalog.importer.dto.ImportBatchResponse;
import com.velora.api.catalog.importer.dto.ImportCommitResponse;
import com.velora.api.catalog.importer.dto.ImportRowEditRequest;
import com.velora.api.catalog.importer.dto.ImportSkuCheckResponse;
import com.velora.api.catalog.importer.dto.ImportRowResponse;
import com.velora.api.catalog.importer.dto.ImportRowUpdateResponse;
import com.velora.api.catalog.importer.service.ProductImportService;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.identity.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin — Product import", description = "Bulk product upload from Excel. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/product-imports")
public class AdminProductImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ProductImportService importService;

    public AdminProductImportController(ProductImportService importService) {
        this.importService = importService;
    }

    @Operation(summary = "Download the empty product import template",
            description = """
                    The only file format the upload accepts. Fill the first sheet, one row per product.
                    The category dropdown lists the shop's current product categories, so download a
                    fresh copy when categories change.
                    """)
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("product-import-template.xlsx").build().toString())
                .body(importService.template());
    }

    @Operation(summary = "Upload a filled template for review",
            description = """
                    Step 1 of 2. The sheet is checked against the template (any difference in the
                    header row rejects the whole file), then every row is validated. NOTHING is
                    saved to the catalogue. Review the rows with GET /{id}/rows, then submit with
                    POST /{id}/commit.
                    """)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportBatchResponse> upload(@RequestPart("file") MultipartFile file,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Only .xlsx files made from the product import template are accepted");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED, "The uploaded file could not be read");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importService.preview(name, content, principal == null ? null : principal.id()));
    }

    @Operation(summary = "Recent imports, newest first")
    @GetMapping
    public PageResponse<ImportBatchResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return importService.list(pageable);
    }

    @Operation(summary = "One import's totals",
            description = "`canSubmit` is true only while the batch is a preview with no error rows.")
    @GetMapping("/{id}")
    public ImportBatchResponse get(@PathVariable Long id) {
        return importService.get(id);
    }

    @Operation(summary = "The rows of an import, for the review screen",
            description = "Each row carries the parsed values, the specifications and every finding. "
                    + "Pass status=ERROR to see only the rows that block the submit.")
    @GetMapping("/{id}/rows")
    public PageResponse<ImportRowResponse> rows(
            @PathVariable Long id,
            @Parameter(description = "VALID or ERROR; omit for all rows")
            @RequestParam(required = false) ImportRowStatus status,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return importService.rows(id, status, pageable);
    }

    @Operation(summary = "Fix one row of a preview",
            description = """
                    Replaces the values sent (by field key; see `values` of the row) and checks the row again
                    exactly as an uploaded one is checked. Returns the row and the batch with its new counts: an
                    edit can clear errors, or create new ones, also on other rows that shared a SKU or slug.
                    Only while the batch is a preview. The uploaded file itself is never changed.
                    """)
    @PutMapping("/{id}/rows/{rowNo}")
    public ImportRowUpdateResponse editRow(@PathVariable Long id, @PathVariable int rowNo,
                                           @Valid @RequestBody ImportRowEditRequest request) {
        return importService.editRow(id, rowNo, request);
    }

    @Operation(summary = "Is this SKU free?",
            description = """
                    For the row editor, before the row is saved. `status`: AVAILABLE (unique), DUPLICATE_IN_FILE
                    (another row of this sheet has it; `rowNos` says which, an error), IN_CATALOG (the shop
                    already sells it, so the row would update that product; `productId`, `productName`) or EMPTY.
                    Pass `rowNo` of the row being edited so it does not clash with itself.
                    """)
    @GetMapping("/{id}/sku-check")
    public ImportSkuCheckResponse checkSku(@PathVariable Long id, @RequestParam String sku,
                                           @RequestParam(required = false) Integer rowNo) {
        return importService.checkSku(id, sku, rowNo);
    }

    @Operation(summary = "Submit the reviewed sheet",
            description = """
                    Step 2 of 2. Allowed only when every row is valid (409 IMPORT_HAS_ERRORS
                    otherwise). All products are written in one transaction: either all of them
                    are saved or none. New products are DRAFT.
                    """)
    @PostMapping("/{id}/commit")
    public ImportCommitResponse commit(@PathVariable Long id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return importService.commit(id, principal == null ? null : principal.id());
    }

    @Operation(summary = "Discard a preview", description = "The rows are kept for the record.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable Long id) {
        importService.discard(id);
        return ResponseEntity.noContent().build();
    }
}
