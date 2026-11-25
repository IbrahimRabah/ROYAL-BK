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
import com.velora.api.order.domain.FulfillmentStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
                .and(ProductSpecifications.onSale(filter.onSale()))
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
                null, null, null, null, null, null, true, true, null, null, null, "newest");
        return search(filter, pageable, locale);
    }

    public PageResponse<ProductSummaryResponse> findNewArrivals(Pageable pageable, String locale) {
        ProductFilterRequest filter = new ProductFilterRequest(
                null, null, null, null, null, null, true, null, true, null, null, "newest");
        return search(filter, pageable, locale);
    }

    /**
     * Order statuses that do NOT count as a sale: the goods never stayed sold.
     * Everything else counts, including PENDING and PARTIALLY_RETURNED (net of returned units).
     */
    static final Set<FulfillmentStatus> NOT_A_SALE = Set.of(
            FulfillmentStatus.CANCELLED, FulfillmentStatus.RETURNED,
            FulfillmentStatus.RETURNED_TO_SELLER, FulfillmentStatus.REFUSED_ON_DELIVERY);

    /**
     * Best sellers: products ranked by net units sold (see {@link #NOT_A_SALE}), ties newest first.
     * Products with sales come first; the rest of the page (and every later page) is filled with the
     * remaining visible products, featured first then newest, so the strip is never empty.
     * Ranking is ONE aggregate query; the fill is one paged query.
     */
    public PageResponse<ProductSummaryResponse> findBestSellers(Pageable pageable, String locale) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        int page = pageable.getPageNumber();
        long offset = (long) page * size;

        List<Long> rankedIds = productRepository.rankBestSellers(NOT_A_SALE).stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();
        int rankedCount = rankedIds.size();

        int from = (int) Math.min(offset, rankedCount);
        int to = Math.min(rankedCount, from + size);
        List<Long> pageRanked = rankedIds.subList(from, to);

        Map<Long, Product> byId = new HashMap<>();
        productRepository.findAllById(pageRanked).forEach(p -> byId.put(p.getId(), p));
        List<Product> products = new ArrayList<>();
        for (Long id : pageRanked) {
            Product p = byId.get(id);
            if (p != null) {
                products.add(p);
            }
        }

        Specification<Product> fillSpec = Specification.where(ProductSpecifications.isVisible());
        if (!rankedIds.isEmpty()) {
            fillSpec = fillSpec.and((root, q, cb) -> cb.not(root.get("id").in(rankedIds)));
        }
        int skip = (int) Math.max(0, offset - rankedCount);
        int needed = size - products.size();
        Sort fillSort = Sort.by(Sort.Order.desc("featured"), Sort.Order.desc("createdAt"),
                Sort.Order.desc("id"));
        Page<Product> fill = productRepository.findAll(fillSpec,
                PageRequest.of(0, Math.max(1, skip + needed), fillSort));
        if (needed > 0 && skip < fill.getContent().size()) {
            products.addAll(fill.getContent().subList(skip,
                    Math.min(fill.getContent().size(), skip + needed)));
        }

        long total = rankedCount + fill.getTotalElements();
        List<ProductSummaryResponse> content = products.stream()
                .map(p -> mapper.toSummary(p, locale)).toList();
        return PageResponse.from(new PageImpl<>(content, PageRequest.of(page, size), total));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Maps the API's sort keys onto entity properties.
     *
     * <p>{@code price_asc} sorts on {@code effectiveMinPrice}, which is a {@code @Formula}
     * field. That is why the formula exists: a Java-side calculation could not be
     * used in an ORDER BY, and sorting a single page in memory would give the wrong
     * order across pages.
     */
    private Pageable applySort(Pageable pageable, String sortKey) {
        Sort sort = switch (sortKey) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "effectiveMinPrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "effectiveMinPrice");
            case "name" -> Sort.by(Sort.Direction.ASC, "slug");
            // Best sellers are served by GET /products/best-sellers (findBestSellers), not a sort key.
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
