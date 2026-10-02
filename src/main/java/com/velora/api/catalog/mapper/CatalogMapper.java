package com.velora.api.catalog.mapper;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.Brand;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantAttributeValue;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.AttributeValueResponse;
import com.velora.api.catalog.dto.BrandResponse;
import com.velora.api.catalog.dto.CategoryTreeResponse;
import com.velora.api.catalog.dto.ImageResponse;
import com.velora.api.catalog.dto.ProductDetailResponse;
import com.velora.api.catalog.dto.ProductSummaryResponse;
import com.velora.api.catalog.dto.SpecificationResponse;
import com.velora.api.catalog.dto.VariantOptionResponse;
import com.velora.api.catalog.dto.VariantResponse;
import com.velora.api.common.storage.StorageService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Entity to DTO for the storefront.
 *
 * <p>Written by hand rather than generated: the mapping is locale-aware, the variant
 * matrix has to be assembled from several relations, and — critically —
 * {@code costPrice} must never leak into a public response. That last point is
 * clearer when the mapping is explicit.
 */
@Component
public class CatalogMapper {

    private final StorageService storageService;

    public CatalogMapper(StorageService storageService) {
        this.storageService = storageService;
    }

    // ------------------------------------------------------------------ product

    public ProductSummaryResponse toSummary(Product product, String locale) {
        ProductImage main = product.mainImage();
        ProductImage hover = product.hoverImage();
        ProductVariant cheapest = cheapestVariant(product);

        return new ProductSummaryResponse(
                product.getId(),
                product.getSlug(),
                product.nameFor(locale),
                shortDescription(product, locale),
                product.getBrand() == null ? null : product.getBrand().nameFor(locale),
                product.getCategory() == null ? null : product.getCategory().getSlug(),
                main == null ? null : storageService.urlFor(main.getUrl()),
                main == null ? null : main.altFor(locale),
                hover == null ? null : storageService.urlFor(hover.getUrl()),
                product.getMinPrice(),
                product.getMaxPrice(),
                cheapest == null ? null : cheapest.getCompareAtPrice(),
                cheapest == null ? null : cheapest.discountPercent(),
                product.isInStock(),
                product.getAvailableQty(),
                product.isFeatured(),
                product.isNewArrival(),
                product.getFulfillmentType(),
                product.getShippingSizeClass(),
                product.isRequiresAssembly(),
                product.effectiveAssemblyFee());
    }

    public ProductDetailResponse toDetail(Product product,
                                          List<ProductVariant> variants,
                                          String locale) {
        ProductTranslation translation = product.translationFor(locale);

        return new ProductDetailResponse(
                product.getId(),
                product.getSlug(),
                product.nameFor(locale),
                translation == null ? null : translation.getShortDescription(),
                translation == null ? null : translation.getDescription(),
                toBrand(product.getBrand(), locale),
                buildBreadcrumb(product.getCategory(), locale),
                new ProductDetailResponse.PriceRangeResponse(
                        product.getMinPrice(), product.getMaxPrice()),
                buildVariantOptions(variants, locale),
                variants.stream().map(v -> toVariant(v, product, locale)).toList(),
                buildSpecifications(product, locale),
                // ProductRepository.findBySlugAndArchivedAtIsNull fetch-joins "images"
                // alongside "translations" and "category.translations" — two Maps and a
                // bag in one query multiplies every image row by 2x2. distinct() collapses
                // it back to the same managed ProductImage instances. Same issue as
                // AttributeRepository / ProductVariantRepository (see variantSummary()).
                product.getImages().stream().distinct().map(i -> toImage(i, locale)).toList(),
                product.isInStock(),
                product.isFeatured(),
                product.isNewArrival(),
                product.getFulfillmentType(),
                product.getShippingSizeClass(),
                product.isRequiresAssembly(),
                product.effectiveAssemblyFee(),
                new ProductDetailResponse.SeoResponse(
                        translation == null ? null : translation.getMetaTitle(),
                        translation == null ? null : translation.getMetaDescription(),
                        "/p/" + product.getSlug()));
    }

    // ------------------------------------------------------------------ variant

    public VariantResponse toVariant(ProductVariant variant, Product product, String locale) {
        // See variantSummary() for why distinct() is required here.
        List<Long> valueIds = variant.getAttributeValues().stream()
                .distinct()
                .map(vav -> vav.getAttributeValue().getId())
                .toList();

        List<ImageResponse> variantImages = product.getImages().stream()
                .distinct()
                .filter(img -> img.getVariant() != null
                        && img.getVariant().getId().equals(variant.getId()))
                .map(img -> toImage(img, locale))
                .toList();

        return new VariantResponse(
                variant.getId(),
                variant.getSku(),
                variantSummary(variant, locale),
                variant.getPrice(),
                variant.getCompareAtPrice(),
                variant.discountPercent(),
                valueIds,
                variant.getAvailable(),
                variant.isInStock(),
                variantImages);
        // Note: getCostPrice() is deliberately absent. It is owner-only.
    }

    /**
     * Builds "ذهبي / 42 مم" from the variant's defining attribute values.
     *
     * <p>{@code ProductVariantRepository.findByProductIdAndArchivedAtIsNullOrderByPositionAsc}
     * fetch-joins {@code attributeValues} alongside {@code attributeValue.translations}
     * in one query. Fetch-joining two bags together is a cartesian product at the row
     * level: a value with 2 translations (ar + en) makes Hibernate hydrate the SAME
     * {@code VariantAttributeValue} row twice, so it would otherwise render as
     * "ذهبي / ذهبي". {@code distinct()} collapses it back — the duplicates are the
     * same managed entity instance (Hibernate's first-level cache), so reference
     * equality is enough. Same issue as {@code AttributeRepository}.
     */
    public String variantSummary(ProductVariant variant, String locale) {
        if (variant.getAttributeValues().isEmpty()) {
            return null;
        }
        return variant.getAttributeValues().stream()
                .distinct()
                .sorted(Comparator.comparing(vav -> vav.getAttribute().getDisplayOrder()))
                .map(vav -> vav.getAttributeValue().nameFor(locale))
                .reduce((a, b) -> a + " / " + b)
                .orElse(null);
    }

    /**
     * Collects the distinct variant-defining attributes and their values across all
     * variants, preserving attribute display order. This is what renders the colour
     * and size selectors.
     */
    public List<VariantOptionResponse> buildVariantOptions(List<ProductVariant> variants,
                                                           String locale) {
        Map<Long, Attribute> attributes = new LinkedHashMap<>();
        Map<Long, Map<Long, AttributeValue>> valuesByAttribute = new LinkedHashMap<>();

        for (ProductVariant variant : variants) {
            if (variant.getStatus() != VariantStatus.ACTIVE) {
                continue;
            }
            for (VariantAttributeValue vav : variant.getAttributeValues()) {
                Attribute attribute = vav.getAttribute();
                attributes.putIfAbsent(attribute.getId(), attribute);
                valuesByAttribute
                        .computeIfAbsent(attribute.getId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(vav.getAttributeValue().getId(), vav.getAttributeValue());
            }
        }

        List<VariantOptionResponse> options = new ArrayList<>();
        attributes.values().stream()
                .sorted(Comparator.comparing(Attribute::getDisplayOrder))
                .forEach(attribute -> {
                    List<AttributeValueResponse> values = valuesByAttribute
                            .getOrDefault(attribute.getId(), Map.of())
                            .values().stream()
                            .sorted(Comparator.comparing(AttributeValue::getDisplayOrder))
                            .map(v -> AttributeValueResponse.of(
                                    v.getId(), v.getCode(), v.nameFor(locale), v.getHexColor()))
                            .toList();
                    options.add(new VariantOptionResponse(
                            attribute.getId(), attribute.getCode(),
                            attribute.nameFor(locale), values));
                });
        return options;
    }

    // ------------------------------------------------------------------- others

    public List<SpecificationResponse> buildSpecifications(Product product, String locale) {
        return product.getSpecifications().stream()
                .sorted(Comparator.comparing(pav -> pav.getAttribute().getDisplayOrder()))
                .map(pav -> new SpecificationResponse(
                        pav.getAttribute().getCode(),
                        pav.getAttribute().nameFor(locale),
                        pav.displayValue(locale)))
                .filter(spec -> spec.value() != null)
                .toList();
    }

    public List<ProductDetailResponse.CategoryRefResponse> buildBreadcrumb(Category category,
                                                                          String locale) {
        List<ProductDetailResponse.CategoryRefResponse> path = new ArrayList<>();
        Category current = category;
        int guard = 0;
        while (current != null && guard++ < 10) {
            path.add(0, new ProductDetailResponse.CategoryRefResponse(
                    current.getId(), current.getSlug(), current.nameFor(locale)));
            current = current.getParent();
        }
        return path;
    }

    public ImageResponse toImage(ProductImage image, String locale) {
        return new ImageResponse(
                image.getId(),
                storageService.urlFor(image.getUrl()),
                storageService.urlFor(image.getThumbUrl()),
                image.altFor(locale),
                image.isMain());
    }

    public BrandResponse toBrand(Brand brand, String locale) {
        if (brand == null) {
            return null;
        }
        return new BrandResponse(
                brand.getId(), brand.getSlug(), brand.nameFor(locale), brand.getLogoUrl());
    }

    public CategoryTreeResponse toTreeNode(Category category, String locale,
                                           List<CategoryTreeResponse> children) {
        return new CategoryTreeResponse(
                category.getId(),
                category.getSlug(),
                category.nameFor(locale),
                storageService.urlFor(category.getImageUrl()),
                storageService.urlFor(category.getBannerUrl()),
                category.getDisplayOrder(),
                category.getProductCount() == null ? 0 : category.getProductCount(),
                children);
    }

    // ------------------------------------------------------------------ helpers

    private String shortDescription(Product product, String locale) {
        ProductTranslation t = product.translationFor(locale);
        return t == null ? null : t.getShortDescription();
    }

    private ProductVariant cheapestVariant(Product product) {
        return product.getVariants().stream()
                .filter(v -> v.getStatus() == VariantStatus.ACTIVE && v.getArchivedAt() == null)
                .min(Comparator.comparing(ProductVariant::getPrice,
                        Comparator.nullsLast(BigDecimal::compareTo)))
                .orElse(null);
    }
}
