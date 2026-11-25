package com.velora.api.catalog.web;

import com.velora.api.catalog.dto.ProductDetailResponse;
import com.velora.api.catalog.dto.ProductFilterRequest;
import com.velora.api.catalog.dto.ProductSummaryResponse;
import com.velora.api.catalog.service.ProductQueryService;
import com.velora.api.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Products", description = "Public catalog browsing")
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @Operation(summary = "Search and filter products",
            description = """
                    Every filter is optional and they compose. Used by the category page,
                    search results and the filter sidebar.

                    Search is Arabic-normalized: `ساعه ذهبى` matches `ساعة ذهبي`.
                    """,
            security = {})
    @ApiResponse(responseCode = "200", description = "Paged product cards")
    @GetMapping
    public PageResponse<ProductSummaryResponse> search(
            @ParameterObject ProductFilterRequest filter,
            @ParameterObject @PageableDefault(size = 24) Pageable pageable,
            HttpServletRequest request) {

        return productQueryService.search(filter, pageable, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Product detail by slug",
            description = """
                    Returns the FULL variant matrix in one call, so changing colour on the
                    product page needs no round trip.
                    """,
            security = {})
    @ApiResponse(responseCode = "404", description = "No active product with this slug")
    @GetMapping("/{slug}")
    public ProductDetailResponse getBySlug(
            @Parameter(example = "classic-gold-watch") @PathVariable String slug,
            HttpServletRequest request) {

        return productQueryService.findBySlug(slug, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Related products", description = "Same category, excludes itself",
            security = {})
    @GetMapping("/{id}/related")
    public List<ProductSummaryResponse> getRelated(@PathVariable Long id,
                                                   HttpServletRequest request) {
        return productQueryService.findRelated(id, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Featured products", description = "Homepage merchandising",
            security = {})
    @GetMapping("/featured")
    public PageResponse<ProductSummaryResponse> getFeatured(
            @ParameterObject @PageableDefault(size = 12) Pageable pageable,
            HttpServletRequest request) {

        return productQueryService.findFeatured(pageable, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Best sellers",
            description = """
                    Ranked by net units sold (quantity minus returned units) on orders that are not
                    CANCELLED, RETURNED, RETURNED_TO_SELLER or REFUSED_ON_DELIVERY. ACTIVE,
                    non-archived products only; ties go to the newest product. If fewer products than
                    the page size have sales, the page is filled with the remaining products
                    (featured first, then newest) so the strip is never empty.
                    """,
            security = {})
    @GetMapping("/best-sellers")
    public PageResponse<ProductSummaryResponse> getBestSellers(
            @ParameterObject @PageableDefault(size = 12) Pageable pageable,
            HttpServletRequest request) {

        return productQueryService.findBestSellers(pageable, LocaleResolver.resolve(request));
    }

    @Operation(summary = "New arrivals", security = {})
    @GetMapping("/new-arrivals")
    public PageResponse<ProductSummaryResponse> getNewArrivals(
            @ParameterObject @PageableDefault(size = 12) Pageable pageable,
            HttpServletRequest request) {

        return productQueryService.findNewArrivals(pageable, LocaleResolver.resolve(request));
    }
}
