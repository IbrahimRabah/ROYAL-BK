package com.velora.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.util.ArabicNormalizer;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.dto.InventoryAdminResponse;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.inventory.service.InventoryAdminService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /admin/inventory} — the full, paginated, searchable stock list. Without
 * it the admin screen had to load every product and then every product's variants
 * separately to build one table (search and sort done in the browser instead of the
 * database), which does not scale past a handful of products.
 *
 * <p>Runs against the real database because the point being verified is the query
 * shape itself: sorting on a {@code @Formula} field, filtering across a many-to-one
 * chain (inventory → variant → product → category) alongside an EXISTS subquery for
 * the product-name search — exactly the kind of query that silently duplicates rows
 * or breaks pagination if built the wrong way, which a mocked repository would not
 * catch.
 */
@SpringBootTest
class InventoryAdminListIntegrationTest {

    @Autowired private InventoryAdminService inventoryAdminService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private String unique;
    private Long parentCategoryId;
    private Long childCategoryId;

    private Long productAId;
    private Long productBId;
    private Long productCId;
    private String skuA;
    private String skuB;
    private String skuC;
    private String productAName;

    /**
     * A: plenty of stock. B: low (available &lt;= minStockLevel, but &gt; 0).
     * C: out of stock (available == 0), in the CHILD category — must still surface
     * when filtering by the PARENT, same "self or direct child" reach used
     * everywhere else a category filters something in this codebase.
     */
    @BeforeEach
    void seedThreeVariantsAcrossParentAndChildCategory() {
        unique = UUID.randomUUID().toString().substring(0, 8);
        skuA = "INV-TEST-A-" + unique.toUpperCase();
        skuB = "INV-TEST-B-" + unique.toUpperCase();
        skuC = "INV-TEST-C-" + unique.toUpperCase();
        productAName = "ساعة اختبار المخزون " + unique;

        transactionTemplate.executeWithoutResult(status -> {
            Category parent = new Category();
            parent.setSlug("inv-test-parent-" + unique);
            parent.setActive(true);
            parentCategoryId = categoryRepository.save(parent).getId();

            Category child = new Category();
            child.setParent(categoryRepository.findById(parentCategoryId).orElseThrow());
            child.setSlug("inv-test-child-" + unique);
            child.setActive(true);
            childCategoryId = categoryRepository.save(child).getId();

            productAId = seedProductWithInventory(parentCategoryId, skuA, productAName,
                    10, 0, 3);
            productBId = seedProductWithInventory(parentCategoryId, skuB,
                    "منتج ب اختبار المخزون " + unique, 2, 0, 3);
            productCId = seedProductWithInventory(childCategoryId, skuC,
                    "منتج ج اختبار المخزون " + unique, 0, 0, 3);
        });
    }

    private Long seedProductWithInventory(Long categoryId, String sku, String name,
                                          int qtyOnHand, int qtyReserved, int minStockLevel) {
        Product product = new Product();
        product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        product.setSlug("inv-test-" + sku.toLowerCase());
        product.setStatus(ProductStatus.ACTIVE);
        Long productId = productRepository.save(product).getId();

        Product persisted = productRepository.findById(productId).orElseThrow();
        ProductTranslation translation = new ProductTranslation();
        translation.attachTo(persisted, "ar");
        translation.setName(name);
        translation.setSearchText(ArabicNormalizer.normalize(name));
        persisted.getTranslations().put("ar", translation);
        productRepository.save(persisted);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(productRepository.findById(productId).orElseThrow());
        variant.setSku(sku);
        variant.setPrice(new BigDecimal("1000.0000"));
        variant.setTaxRate(new BigDecimal("0.1400"));
        variant.setWeightGrams(100);
        variant.setStatus(VariantStatus.ACTIVE);
        Long variantId = variantRepository.save(variant).getId();

        Inventory inventory = new Inventory();
        inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
        inventory.setQtyOnHand(qtyOnHand);
        inventory.setQtyReserved(qtyReserved);
        inventory.setMinStockLevel(minStockLevel);
        inventoryRepository.save(inventory);

        return productId;
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM inventory WHERE variant_id IN "
                + "(SELECT id FROM product_variant WHERE sku IN (?, ?, ?))", skuA, skuB, skuC);
        jdbc.update("DELETE FROM product_variant WHERE sku IN (?, ?, ?)", skuA, skuB, skuC);
        jdbc.update("DELETE FROM product_translation WHERE product_id IN (?, ?, ?)",
                productAId, productBId, productCId);
        jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?)",
                productAId, productBId, productCId);
        jdbc.update("DELETE FROM category WHERE id = ?", childCategoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", parentCategoryId);
    }

    @Test
    @DisplayName("categoryId includes the direct child; default sort is available ascending")
    void list_scopedByCategory_sortsAvailableAscendingByDefault() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                null, null, null, parentCategoryId, null, PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content())
                .extracting(InventoryAdminResponse::sku)
                .as("C (0 available) must sort before B (2) before A (10)")
                .containsExactly(skuC, skuB, skuA);
    }

    @Test
    @DisplayName("sort=available_desc reverses the order")
    void list_sortAvailableDesc() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                null, null, null, parentCategoryId, "available_desc", PageRequest.of(0, 20));

        assertThat(page.content())
                .extracting(InventoryAdminResponse::sku)
                .containsExactly(skuA, skuB, skuC);
    }

    @Test
    @DisplayName("q matches the SKU")
    void list_qMatchesSku() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                skuA, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.content()).extracting(InventoryAdminResponse::sku)
                .containsExactly(skuA);
    }

    @Test
    @DisplayName("q matches the product name, not just the SKU")
    void list_qMatchesProductName() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                productAName, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.content()).extracting(InventoryAdminResponse::sku)
                .containsExactly(skuA);
    }

    @Test
    @DisplayName("lowStockOnly excludes A (plenty of stock) but keeps B and C")
    void list_lowStockOnly() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                null, true, null, parentCategoryId, null, PageRequest.of(0, 20));

        assertThat(page.content()).extracting(InventoryAdminResponse::sku)
                .containsExactlyInAnyOrder(skuB, skuC);
    }

    @Test
    @DisplayName("outOfStockOnly keeps only C")
    void list_outOfStockOnly() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                null, null, true, parentCategoryId, null, PageRequest.of(0, 20));

        assertThat(page.content()).extracting(InventoryAdminResponse::sku)
                .containsExactly(skuC);
    }

    @Test
    @DisplayName("Pagination: size=1 returns one row but the correct total count")
    void list_paginates() {
        PageResponse<InventoryAdminResponse> page = inventoryAdminService.list(
                null, null, null, parentCategoryId, null, PageRequest.of(0, 1));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).sku()).isEqualTo(skuC);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
    }
}
