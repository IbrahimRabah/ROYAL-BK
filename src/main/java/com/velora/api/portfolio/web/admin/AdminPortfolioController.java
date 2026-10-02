package com.velora.api.portfolio.web.admin;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.portfolio.dto.admin.PortfolioAdminResponse;
import com.velora.api.portfolio.dto.admin.PortfolioAdminResponse.PortfolioImageAdminResponse;
import com.velora.api.portfolio.dto.admin.PortfolioCreateRequest;
import com.velora.api.portfolio.dto.admin.PortfolioImageUpdateRequest;
import com.velora.api.portfolio.dto.admin.PortfolioPublishRequest;
import com.velora.api.portfolio.dto.admin.PortfolioUpdateRequest;
import com.velora.api.portfolio.service.PortfolioAdminService;
import com.velora.api.portfolio.service.PortfolioImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin — Portfolio", description = "Portfolio management. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/portfolio")
public class AdminPortfolioController {

    private final PortfolioAdminService service;
    private final PortfolioImageService imageService;

    public AdminPortfolioController(PortfolioAdminService service,
                                    PortfolioImageService imageService) {
        this.service = service;
        this.imageService = imageService;
    }

    @Operation(summary = "List portfolio items, drafts included",
            description = "Archived items are left out unless includeArchived=true.")
    @GetMapping
    public PageResponse<PortfolioAdminResponse> list(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) Long categoryId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return service.list(includeArchived, categoryId, pageable);
    }

    @Operation(summary = "Get one item, archived or not")
    @GetMapping("/{id}")
    public PortfolioAdminResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Create", description = "Created unpublished. Slug generated from the "
            + "Arabic title unless one is given.")
    @PostMapping
    public ResponseEntity<PortfolioAdminResponse> create(
            @Valid @RequestBody PortfolioCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "Replace the content of an item")
    @PutMapping("/{id}")
    public PortfolioAdminResponse update(@PathVariable Long id,
                                         @Valid @RequestBody PortfolioUpdateRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Publish or hide",
            description = "Body {published: true|false}. Refused with 409 for an archived item.")
    @PatchMapping("/{id}/publish")
    public PortfolioAdminResponse publish(@PathVariable Long id,
                                          @Valid @RequestBody PortfolioPublishRequest request) {
        return service.setPublished(id, request.published());
    }

    @Operation(summary = "Archive",
            description = "Soft delete: hidden everywhere public and forced unpublished. The row "
                    + "and its images are kept. Repeating it is harmless.")
    @DeleteMapping("/{id}")
    public PortfolioAdminResponse archive(@PathVariable Long id) {
        return service.archive(id);
    }

    @Operation(summary = "Restore from the archive",
            description = "Comes back as an unpublished draft; publish it as a separate step.")
    @PatchMapping("/{id}/restore")
    public PortfolioAdminResponse restore(@PathVariable Long id) {
        return service.restore(id);
    }

    // -------------------------------------------------------------------- images

    @Operation(summary = "List images")
    @GetMapping("/{id}/images")
    public List<PortfolioImageAdminResponse> listImages(@PathVariable Long id) {
        return imageService.list(id);
    }

    @Operation(summary = "Upload an image",
            description = "JPEG, PNG, WebP or AVIF, up to 5 MB, checked by its real bytes. "
                    + "At most 20 per item; the first becomes the main image.")
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PortfolioImageAdminResponse> uploadImage(
            @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(imageService.upload(id, file));
    }

    @Operation(summary = "Update image alt text, order or main flag")
    @PatchMapping("/{id}/images/{imageId}")
    public PortfolioImageAdminResponse updateImage(
            @PathVariable Long id, @PathVariable Long imageId,
            @Valid @RequestBody PortfolioImageUpdateRequest request) {
        return imageService.update(id, imageId, request);
    }

    @Operation(summary = "Delete one image", description = "Removes the file too.")
    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<Void> deleteImage(@PathVariable Long id, @PathVariable Long imageId) {
        imageService.delete(id, imageId);
        return ResponseEntity.noContent().build();
    }
}
