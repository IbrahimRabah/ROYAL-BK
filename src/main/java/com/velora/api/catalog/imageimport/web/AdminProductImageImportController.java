package com.velora.api.catalog.imageimport.web;

import com.velora.api.catalog.imageimport.dto.ImageBulkResponse;
import com.velora.api.catalog.imageimport.dto.ImageImportMode;
import com.velora.api.catalog.imageimport.dto.ImageMatchRequest;
import com.velora.api.catalog.imageimport.dto.ImageMatchResponse;
import com.velora.api.catalog.imageimport.service.ProductImageImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin — Product image import",
        description = "Bulk image upload, matched to products by file name = SKU. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/product-images")
public class AdminProductImageImportController {

    private final ProductImageImportService importService;

    public AdminProductImageImportController(ProductImageImportService importService) {
        this.importService = importService;
    }

    @Operation(summary = "Which file belongs to which product, before uploading anything",
            description = """
                    Step 1 of 2. Send only the file NAMES. `SKU.ext` is a product's main image, `SKU_2.ext`,
                    `SKU_3.ext` … its extra images; the extension does not matter. The answer lists the
                    products that would get images (with `upload` per file), files that clash, files that match
                    nothing (with a suggested SKU for near misses), unsupported formats such as HEIC, and the
                    products that would still have no image. Nothing is written.
                    """)
    @PostMapping("/match")
    public ImageMatchResponse match(@Valid @RequestBody ImageMatchRequest request) {
        return importService.match(request);
    }

    @Operation(summary = "Attach a few pictures to their products",
            description = """
                    Step 2 of 2, called repeatedly with at most 12 files each (the request limit is 10 MB).
                    The file's own name is the SKU rule above; its bytes are checked, never its extension.
                    Every file gets its own result and its own transaction: one bad file does not stop the
                    others. `runId` is a UUID the screen makes once and sends with every request of the run;
                    it tells this run's images from the ones the product already had. `mode`: SKIP (default)
                    leaves a product that already had images alone, ADD appends, REPLACE removes the images
                    it had before the run.
                    """)
    @PostMapping(value = "/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageBulkResponse bulk(
            @Parameter(description = "A UUID, the same for every request of one run") @RequestParam String runId,
            @RequestParam(defaultValue = "SKIP") ImageImportMode mode,
            @RequestPart("files") List<MultipartFile> files) {
        return importService.upload(runId, mode, files);
    }
}
