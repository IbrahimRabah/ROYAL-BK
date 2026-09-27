package com.velora.api.catalog.web.admin;

import com.velora.api.catalog.domain.CategoryImageType;
import com.velora.api.catalog.dto.admin.AttributeAdminResponse;
import com.velora.api.catalog.dto.admin.AttributeSaveRequest;
import com.velora.api.catalog.dto.admin.BrandAdminResponse;
import com.velora.api.catalog.dto.admin.BrandSaveRequest;
import com.velora.api.catalog.dto.admin.CategoryAdminResponse;
import com.velora.api.catalog.dto.admin.CategorySaveRequest;
import com.velora.api.catalog.service.admin.TaxonomyAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@Tag(name = "Admin — Taxonomy",
        description = "Categories, brands and attributes. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTaxonomyController {

    private final TaxonomyAdminService taxonomyService;

    public AdminTaxonomyController(TaxonomyAdminService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @Operation(summary = "Full category tree",
            description = "Includes inactive categories, so staff can find and "
                    + "reactivate what they turned off. Every node carries the full "
                    + "translations[] and active flag — load a node straight into the "
                    + "edit form and send it back whole.")
    @GetMapping("/categories")
    public List<CategoryAdminResponse> listCategories() {
        return taxonomyService.getCategoryTree();
    }

    @Operation(summary = "Create a category")
    @PostMapping("/categories")
    public ResponseEntity<Map<String, Long>> createCategory(
            @Valid @RequestBody CategorySaveRequest request) {
        Long id = taxonomyService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @Operation(summary = "Update a category")
    @PutMapping("/categories/{id}")
    public Map<String, Long> updateCategory(@PathVariable Long id,
                                            @Valid @RequestBody CategorySaveRequest request) {
        return Map.of("id", taxonomyService.updateCategory(id, request));
    }

    @Operation(summary = "Upload a category image",
            description = "JPEG, PNG, WebP or AVIF, up to 5 MB. CARD is the card image "
                    + "shown on the homepage and category listings; BANNER is the "
                    + "category page header. Each type holds exactly one image — "
                    + "uploading again replaces it. Returns the full category, so the "
                    + "UI can show the new image without a second call.")
    @PostMapping(value = "/categories/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CategoryAdminResponse uploadCategoryImage(@PathVariable Long id,
                                                     @RequestPart("file") MultipartFile file,
                                                     @RequestParam CategoryImageType imageType) {
        return taxonomyService.uploadCategoryImage(id, imageType, file);
    }

    @Operation(summary = "Delete a category image")
    @DeleteMapping("/categories/{id}/images/{imageType}")
    public ResponseEntity<Void> deleteCategoryImage(@PathVariable Long id,
                                                    @PathVariable CategoryImageType imageType) {
        taxonomyService.deleteCategoryImage(id, imageType);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List all brands",
            description = "Includes inactive brands, so staff can find and "
                    + "reactivate what they turned off. Carries nameAr/nameEn and "
                    + "active — load a brand straight into the edit form and send it "
                    + "back whole.")
    @GetMapping("/brands")
    public List<BrandAdminResponse> listBrands() {
        return taxonomyService.listBrands();
    }

    @Operation(summary = "Create a brand")
    @PostMapping("/brands")
    public ResponseEntity<Map<String, Long>> createBrand(
            @Valid @RequestBody BrandSaveRequest request) {
        Long id = taxonomyService.saveBrand(null, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @Operation(summary = "Update a brand")
    @PutMapping("/brands/{id}")
    public Map<String, Long> updateBrand(@PathVariable Long id,
                                         @Valid @RequestBody BrandSaveRequest request) {
        return Map.of("id", taxonomyService.saveBrand(id, request));
    }

    @Operation(summary = "List all attributes",
            description = """
                    Every attribute, with its values and translations. Not paginated —
                    the catalog only ever has a handful of attributes.

                    Unlike the storefront filter facets, this includes attributes that
                    are not `filterable` — specification-only attributes (movement,
                    water resistance) still need to be visible to staff.

                    Each attribute's `id` and each value's `id` can be sent straight to
                    `POST /admin/products/{productId}/variants/preview` as
                    `attributeId` / `valueIds`.
                    """)
    @GetMapping("/attributes")
    public List<AttributeAdminResponse> listAttributes(
            @RequestParam(required = false) Boolean variantDefining) {
        return taxonomyService.listAttributes(variantDefining);
    }

    @Operation(summary = "Create an attribute",
            description = """
                    Set `variantDefining` carefully:
                    TRUE generates SKUs (colour, size) — FALSE is specification only
                    (movement, water resistance, fragrance notes).

                    It cannot be changed once variants depend on the attribute.
                    """)
    @PostMapping("/attributes")
    public ResponseEntity<Map<String, Long>> createAttribute(
            @Valid @RequestBody AttributeSaveRequest request) {
        Long id = taxonomyService.saveAttribute(null, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @Operation(summary = "Update an attribute or add values")
    @PutMapping("/attributes/{id}")
    public Map<String, Long> updateAttribute(@PathVariable Long id,
                                             @Valid @RequestBody AttributeSaveRequest request) {
        return Map.of("id", taxonomyService.saveAttribute(id, request));
    }
}
