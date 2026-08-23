package com.velora.api.inventory.spec;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.common.util.ArabicNormalizer;
import com.velora.api.inventory.domain.Inventory;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for the admin inventory list — same Specification pattern as
 * {@code ProductSpecifications}, for the same reason: several independent optional
 * filters compose into one query at runtime instead of a derived-method explosion.
 */
public final class InventorySpecifications {

    private InventorySpecifications() {
        // utility class
    }

    private static Specification<Inventory> alwaysTrue() {
        return (root, query, cb) -> cb.conjunction();
    }

    /**
     * Matches the SKU (raw, case-insensitive) or the product name in ANY locale.
     *
     * <p>The product name lives on {@code ProductTranslation} — a product has one
     * row per locale, so joining it directly would return this inventory row twice
     * per match and corrupt the page count. An EXISTS subquery keeps the row count
     * at one, same reasoning as {@code ProductSpecifications.matches()}.
     *
     * <p>The name side is matched against {@code searchText}, which is
     * Arabic-normalized the same way the query is normalized here — same rule as
     * everywhere else in the catalog: normalize on write AND on read, with the same
     * function, or the two silently drift apart.
     */
    public static Specification<Inventory> matchesSkuOrProductName(String q) {
        if (q == null || q.isBlank()) {
            return alwaysTrue();
        }
        String skuPattern = "%" + q.trim().toLowerCase(Locale.ENGLISH) + "%";
        String normalized = ArabicNormalizer.normalize(q);
        String namePattern = normalized == null ? null : "%" + normalized.replace(" ", "%") + "%";

        return (root, query, cb) -> {
            Join<Inventory, ProductVariant> variant = variantJoin(root);
            var skuMatch = cb.like(cb.lower(variant.get("sku")), skuPattern);
            if (namePattern == null) {
                return skuMatch;
            }

            Subquery<Long> sub = query.subquery(Long.class);
            Root<ProductTranslation> tr = sub.from(ProductTranslation.class);
            sub.select(cb.literal(1L))
                    .where(cb.and(
                            cb.equal(tr.get("product").get("id"), variant.get("product").get("id")),
                            cb.like(cb.lower(tr.get("searchText")), namePattern)));

            return cb.or(skuMatch, cb.exists(sub));
        };
    }

    /** At or below the variant's own threshold — same test as {@code Inventory.isLowStock()}. */
    public static Specification<Inventory> isLowStock(Boolean lowStockOnly) {
        if (lowStockOnly == null || !lowStockOnly) {
            return alwaysTrue();
        }
        return (root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("availableQty"), root.get("minStockLevel"));
    }

    public static Specification<Inventory> isOutOfStock(Boolean outOfStockOnly) {
        if (outOfStockOnly == null || !outOfStockOnly) {
            return alwaysTrue();
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("availableQty"), 0);
    }

    /**
     * Matches the category itself and its direct children — same "self or direct
     * child" reach as {@code ProductSpecifications.inCategory}. Every step from
     * {@code Inventory} up to {@code Category} is many-to-one/one-to-one, so unlike
     * the translation lookup above, a plain join here can never multiply rows.
     */
    public static Specification<Inventory> inCategory(Long categoryId) {
        if (categoryId == null) {
            return alwaysTrue();
        }
        return (root, query, cb) -> {
            Join<ProductVariant, Product> product =
                    productJoin(variantJoin(root));
            Join<Product, Category> category = product.join("category", JoinType.INNER);
            return cb.or(
                    cb.equal(category.get("id"), categoryId),
                    cb.equal(category.get("parent").get("id"), categoryId));
        };
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Reuses the {@code variant} join if an earlier predicate on the same query
     * already created one, rather than adding a second redundant join to the same
     * table.
     */
    @SuppressWarnings("unchecked")
    private static Join<Inventory, ProductVariant> variantJoin(Root<Inventory> root) {
        return root.getJoins().stream()
                .filter(j -> j.getAttribute().getName().equals("variant"))
                .map(j -> (Join<Inventory, ProductVariant>) j)
                .findFirst()
                .orElseGet(() -> root.join("variant", JoinType.INNER));
    }

    @SuppressWarnings("unchecked")
    private static Join<ProductVariant, Product> productJoin(Join<Inventory, ProductVariant> variant) {
        return variant.getJoins().stream()
                .filter(j -> j.getAttribute().getName().equals("product"))
                .map(j -> (Join<ProductVariant, Product>) j)
                .findFirst()
                .orElseGet(() -> variant.join("product", JoinType.INNER));
    }
}
