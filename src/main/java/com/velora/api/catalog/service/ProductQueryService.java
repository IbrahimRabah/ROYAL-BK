package com.velora.api.catalog.service;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.dto.ProductDetailResponse;
import com.velora.api.catalog.dto.ProductFilterRequest;
import com.velora.api.catalog.dto.ProductSummaryResponse;
import com.velora.api.catalog.mapper.CatalogMapper;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.spec.ProductSpecifications;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only catalog queries for the storefront.
 *
 * <p>Everything here is {@code @Transactional(readOnly = true)}: Hibernate skips
 * dirty checking, which is a real saving on list pages that load hundreds of
 * entities and never modify one.
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private static final int MAX_PAGE_SIZE = 60;
    private static final int RELATED_LIMIT = 8;

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CatalogMapper mapper;

    public ProductQueryService(ProductRepository productRepository,
                               ProductVariantRepository variantRepository,
                               CatalogMapper mapper) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.mapper = mapper;
    }

    /**
     * The category page and search results. Every filter is optional and composes
     * into a single query.
     */
    public PageResponse<ProductSummaryResponse> search(ProductFilterRequest filter,
                                                       Pageable pageable,
                                                       String locale) {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.isVisible())
                .and(ProductSpecifications.inCategory(filter.categoryId()))
                .and(ProductSpecifications.inBrands(filter.brandIds()))
                .and(ProductSpecifications.priceBetween(filter.minPrice(), filter.maxPrice()))
                .and(ProductSpecifications.hasAnyAttributeValue(filter.attributeValueIds()))
                .and(ProductSpecifications.inStock(filter.inStockOnly()))
                .and(ProductSpecifications.isFeatured(filter.featured()))
                .and(ProductSpecifications.isNewArrival(filter.newArrival()))
                .and(ProductSpecifications.hasFulfillmentType(filter.fulfillmentType()))
                .and(ProductSpecifications.matches(filter.q(), locale));

        Pageable effective = applySort(pageable, filter.sortOrDefault());
        Page<Product> page = productRepository.findAll(spec, effective);

        return PageResponse.from(page, product -> mapper.toSummary(product, locale));
    }

    /** The product detail page. One call returns the whole variant matrix. */
    public ProductDetailResponse findBySlug(String slug, String locale) {
        Product product = productRepository.findBySlugAndArchivedAtIsNull(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (!isVisible(product)) {
            // A draft product must look identical to one that does not exist.
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        List<ProductVariant> variants = variantRepository
                .findByProductIdAndArchivedAtIsNullOrderByPositionAsc(product.getId());

        return mapper.toDetail(product, variants, locale);
    }

    public List<ProductSummaryResponse> findRelated(Long productId, String locale) {
        Product product = productRepository.findByIdAndArchivedAtIsNull(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        return productRepository
                .findRelated(product.getCategory().getId(), productId,
                        PageRequest.of(0, RELATED_LIMIT))
                .stream()
                .map(p -> mapper.toSummary(p, locale))
                .toList();
    }

    public PageResponse<ProductSummaryResponse> findFeatured(Pageable pageable, String locale) {
        ProductFilterRequest filter = new ProductFilterRequest(
                null, null, null, null, null, null, true, true, null, null, "newest");
        return search(filter, pageable, locale);
    }

    public PageResponse<ProductSummaryResponse> findNewArrivals(Pageable pageable, String locale) {
        ProductFilterRequest filter = new ProductFilterRequest(
                null, null, null, null, null, null, true, null, true, null, "newest");
        return search(filter, pageable, locale);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Maps the API's sort keys onto entity properties.
     *
     * <p>{@code price_asc} sorts on {@code minPrice}, which is a {@code @Formula}
     * field. That is why the formula exists: a Java-side calculation could not be
     * used in an ORDER BY, and sorting a single page in memory would give the wrong
     * order across pages.
     */
    private Pageable applySort(Pageable pageable, String sortKey) {
        Sort sort = switch (sortKey) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "minPrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "minPrice");
            case "name" -> Sort.by(Sort.Direction.ASC, "slug");
            // TODO: "best_selling" needs order data — add when the order module lands.
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };

        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }

    private boolean isVisible(Product product) {
        return product.getStatus() == com.velora.api.catalog.domain.ProductStatus.ACTIVE
                && product.getArchivedAt() == null;
    }
}
