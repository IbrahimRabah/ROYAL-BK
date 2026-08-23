package com.velora.api.catalog.spec;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductAttributeValue;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantAttributeValue;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.common.util.ArabicNormalizer;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for the product catalog.
 *
 * <p>Why the Specification pattern and not repository methods: product search has
 * eight optional filters. As derived query methods that would be
 * {@code findByCategoryAndBrandInAndPriceBetweenAndStatus...} multiplied by every
 * combination — hundreds of methods, or hand-concatenated JPQL. Specifications
 * compose the same filters into one query at runtime.
 *
 * <p>Note every inactive filter returns {@link #alwaysTrue()} rather than
 * {@code null}: Spring Data JPA 4.x rejects a null argument to {@code and()}.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
        // utility class
    }

    /**
     * A no-op predicate. Spring Data JPA 4.x throws on {@code and(null)}, unlike
     * earlier versions, so an inactive filter must contribute an always-true
     * condition instead of nothing.
     */
    private static Specification<Product> alwaysTrue() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Active and not archived. Apply to every public query. */
    public static Specification<Product> isVisible() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), ProductStatus.ACTIVE),
                cb.isNull(root.get("archivedAt")));
    }

    /**
     * Matches the category itself and its direct children, so browsing "Watches"
     * shows everything in "Men Watches" too.
     */
    public static Specification<Product> inCategory(Long categoryId) {
        if (categoryId == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> {
            var category = root.join("category", JoinType.INNER);
            return cb.or(
                    cb.equal(category.get("id"), categoryId),
                    cb.equal(category.get("parent").get("id"), categoryId));
        };
    }

    public static Specification<Product> inBrands(Collection<Long> brandIds) {
        if (brandIds == null || brandIds.isEmpty()) {
            return alwaysTrue();
        }
        return (root, query, cb) -> root.join("brand", JoinType.INNER).get("id").in(brandIds);
    }

    public static Specification<Product> isFeatured(Boolean featured) {
        if (featured == null || !featured) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.isTrue(root.get("featured"));
    }

    public static Specification<Product> isNewArrival(Boolean newArrival) {
        if (newArrival == null || !newArrival) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.isTrue(root.get("newArrival"));
    }

    /**
     * Filters on the {@code minPrice} / {@code maxPrice} formula fields, so no join
     * or DISTINCT is needed and the page count stays correct.
     */
    public static Specification<Product> priceBetween(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> {
            if (min != null && max != null) {
                // A product matches if any of its variants falls in the range.
                return cb.and(
                        cb.lessThanOrEqualTo(root.get("minPrice"), max),
                        cb.greaterThanOrEqualTo(root.get("maxPrice"), min));
            }
            if (min != null) {
                return cb.greaterThanOrEqualTo(root.get("maxPrice"), min);
            }
            return cb.lessThanOrEqualTo(root.get("minPrice"), max);
        };
    }

    /**
     * Products having at least one variant carrying any of the given attribute
     * values — colour = gold OR silver — OR having the value set directly as a
     * specification, for attributes that are not variant-defining.
     *
     * <p>An attribute is only ever linked one of two ways, never both:
     * {@link com.velora.api.catalog.domain.VariantAttributeValue} for variant-defining
     * attributes (colour, size — each value is its own SKU), or
     * {@link ProductAttributeValue} for specification-only attributes (strap
     * material, movement — informational, never creates a SKU). A filter checking
     * only the variant table silently returns zero products for every
     * specification-only value, even when the product genuinely has it set.
     *
     * <p>Uses EXISTS subqueries rather than joins, which keeps the row count
     * unchanged and avoids the DISTINCT that would otherwise break pagination.
     */
    public static Specification<Product> hasAnyAttributeValue(Collection<Long> attributeValueIds) {
        if (attributeValueIds == null || attributeValueIds.isEmpty()) {
            return alwaysTrue();
        }
        return (root, query, cb) -> {
            Subquery<Long> variantMatch = query.subquery(Long.class);
            Root<ProductVariant> variant = variantMatch.from(ProductVariant.class);
            Join<ProductVariant, VariantAttributeValue> vav = variant.join("attributeValues");

            variantMatch.select(variant.get("id"))
                    .where(cb.and(
                            cb.equal(variant.get("product").get("id"), root.get("id")),
                            cb.equal(variant.get("status"), VariantStatus.ACTIVE),
                            cb.isNull(variant.get("archivedAt")),
                            vav.get("attributeValue").get("id").in(attributeValueIds)));

            Subquery<Long> specMatch = query.subquery(Long.class);
            Root<ProductAttributeValue> pav = specMatch.from(ProductAttributeValue.class);

            specMatch.select(pav.get("product").get("id"))
                    .where(cb.and(
                            cb.equal(pav.get("product").get("id"), root.get("id")),
                            pav.get("attributeValue").get("id").in(attributeValueIds)));

            return cb.or(cb.exists(variantMatch), cb.exists(specMatch));
        };
    }

    /** Only products with at least one unit actually available. */
    public static Specification<Product> inStock(Boolean inStockOnly) {
        if (inStockOnly == null || !inStockOnly) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.greaterThan(root.get("availableQty"), 0);
    }

    /**
     * Arabic-aware search across EVERY translation, not just the current interface
     * language.
     *
     * <p>This is deliberate. Restricting the search to the active locale means a
     * customer browsing in English who types {@code ساعة ذهبية} finds nothing — and
     * in this market that is a very common combination. What the shopper types and
     * what language the buttons are in are two different things.
     *
     * <p>The query is normalized with the SAME function used when writing
     * {@code search_text}, so {@code ساعه ذهبى} matches a product stored as
     * {@code ساعة ذهبية}. Applying it on one side only would silently break search.
     *
     * <p>Uses EXISTS rather than a join: a product has two translation rows, and a
     * join would return it twice, forcing DISTINCT and corrupting the page count.
     */
    public static Specification<Product> matches(String rawQuery, String locale) {
        String normalized = ArabicNormalizer.normalize(rawQuery);
        if (normalized == null) {
            return alwaysTrue();
        }
        String pattern = "%" + normalized.replace(" ", "%") + "%";

        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<ProductTranslation> tr = sub.from(ProductTranslation.class);

            sub.select(cb.literal(1L))
                    .where(cb.and(
                            cb.equal(tr.get("product").get("id"), root.get("id")),
                            cb.or(
                                    cb.like(cb.lower(tr.get("searchText")), pattern),
                                    cb.like(cb.lower(tr.get("name")), pattern))));

            return cb.or(
                    cb.exists(sub),
                    // The slug is Latin, so a Latin query still matches it.
                    cb.like(cb.lower(root.get("slug")), pattern));
        };
    }
}