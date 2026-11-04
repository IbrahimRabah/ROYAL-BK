package com.velora.api.seo.web;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.seo.dto.SeoMetaRequest;
import com.velora.api.seo.dto.SeoOverviewResponse;
import com.velora.api.seo.dto.SeoPageRequest;
import com.velora.api.seo.dto.SeoPageResponse;
import com.velora.api.seo.dto.SeoRowResponse;
import com.velora.api.seo.service.SeoAdminService;
import com.velora.api.seo.service.SeoAdminService.Filter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — SEO", description = "Meta titles and descriptions. ROLE_ADMIN or ROLE_SEO_EDITOR.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/seo")
public class AdminSeoController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SeoAdminService seoService;

    public AdminSeoController(SeoAdminService seoService) {
        this.seoService = seoService;
    }

    @Operation(summary = "SEO health: what is missing, too long or duplicated",
            description = "Measured on the Arabic text of products on sale, active categories and the static pages.")
    @GetMapping("/overview")
    public SeoOverviewResponse overview() {
        return seoService.overview();
    }

    @Operation(summary = "Products with their SEO text",
            description = "filter: ALL, MISSING_TITLE, MISSING_DESCRIPTION, TOO_LONG, DUPLICATE, NO_FAQ. "
                    + "status: ACTIVE or DRAFT (archived products are never listed). q searches name and slug.")
    @GetMapping("/products")
    public PageResponse<SeoRowResponse> products(
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "ALL") Filter filter,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return seoService.products(query, filter, status, pageOf(page, size));
    }

    @Operation(summary = "Change a product's four SEO texts",
            description = "Only the meta titles and descriptions; a blank value clears it. The English text is "
                    + "ignored when the product has no English translation.")
    @PutMapping("/products/{id}")
    public SeoRowResponse updateProduct(@PathVariable Long id, @Valid @RequestBody SeoMetaRequest request,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return seoService.updateProduct(id, request, principal.id());
    }

    @Operation(summary = "Product categories with their SEO text")
    @GetMapping("/categories")
    public PageResponse<SeoRowResponse> categories(
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "ALL") Filter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return seoService.categories(query, filter, pageOf(page, size));
    }

    @Operation(summary = "Change a category's four SEO texts")
    @PutMapping("/categories/{id}")
    public SeoRowResponse updateCategory(@PathVariable Long id, @Valid @RequestBody SeoMetaRequest request,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return seoService.updateCategory(id, request, principal.id());
    }

    @Operation(summary = "The static pages and their SEO text")
    @GetMapping("/pages")
    public List<SeoPageResponse> pages() {
        return seoService.pages();
    }

    @Operation(summary = "Change a static page's SEO text and its hide-from-search flag",
            description = "Keys: HOME, PRODUCTS, ABOUT, CONTACT, PORTFOLIO, CUSTOM_REQUEST, FAQ.")
    @PutMapping("/pages/{key}")
    public SeoPageResponse updatePage(@PathVariable String key, @Valid @RequestBody SeoPageRequest request,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return seoService.updatePage(key, request, principal.id());
    }

    private static Pageable pageOf(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }
}
