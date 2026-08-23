package com.velora.api.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.dto.CategoryDetailResponse;
import com.velora.api.catalog.dto.CategoryTreeResponse;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code CategoryTreeResponse}/{@code CategoryDetailResponse} used to carry no
 * product count at all, so the frontend called {@code GET /products?categoryId=...}
 * once per category just to read {@code totalElements} — one extra round trip per
 * row in the category tree.
 *
 * <p>{@code Category.productCount} follows the same "self or direct child" reach
 * every other category-scoped query in this codebase already uses
 * ({@code ProductSpecifications.inCategory}, {@code AttributeRepository.findFacetsForCategory}):
 * browsing "Watches" counts everything in "Men Watches" too.
 */
@SpringBootTest
class CategoryProductCountIntegrationTest {

    @Autowired private CategoryService categoryService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private String unique;
    private Long parentCategoryId;
    private Long childCategoryId;
    private Long parentProductId;
    private Long childProductId;
    private Long draftProductId;

    @BeforeEach
    void seedTwoLevelCategoryWithProducts() {
        unique = UUID.randomUUID().toString().substring(0, 8);

        transactionTemplate.executeWithoutResult(status -> {
            Category parent = new Category();
            parent.setSlug("count-parent-" + unique);
            parent.setActive(true);
            parentCategoryId = categoryRepository.save(parent).getId();

            Category child = new Category();
            child.setParent(categoryRepository.findById(parentCategoryId).orElseThrow());
            child.setSlug("count-child-" + unique);
            child.setActive(true);
            childCategoryId = categoryRepository.save(child).getId();

            // One product directly under the parent...
            Product inParent = new Product();
            inParent.setCategory(categoryRepository.findById(parentCategoryId).orElseThrow());
            inParent.setSlug("count-product-parent-" + unique);
            inParent.setStatus(ProductStatus.ACTIVE);
            parentProductId = productRepository.save(inParent).getId();

            // ...one under the child, which must still count toward the parent...
            Product inChild = new Product();
            inChild.setCategory(categoryRepository.findById(childCategoryId).orElseThrow());
            inChild.setSlug("count-product-child-" + unique);
            inChild.setStatus(ProductStatus.ACTIVE);
            childProductId = productRepository.save(inChild).getId();

            // ...and one DRAFT product, which must NOT be counted anywhere.
            Product draft = new Product();
            draft.setCategory(categoryRepository.findById(parentCategoryId).orElseThrow());
            draft.setSlug("count-product-draft-" + unique);
            draft.setStatus(ProductStatus.DRAFT);
            draftProductId = productRepository.save(draft).getId();
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?)",
                parentProductId, childProductId, draftProductId);
        jdbc.update("DELETE FROM category WHERE id = ?", childCategoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", parentCategoryId);
    }

    @Test
    @DisplayName("Tree: parent counts its own products plus its direct child's; drafts are excluded")
    void getTree_countsSelfAndDirectChildrenExcludingDrafts() {
        List<CategoryTreeResponse> tree = categoryService.getTree("ar");

        CategoryTreeResponse parentNode = tree.stream()
                .filter(n -> n.id().equals(parentCategoryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seeded parent category missing from tree"));

        assertThat(parentNode.productCount())
                .as("1 ACTIVE product directly in the parent + 1 in the child = 2; "
                        + "the DRAFT product must not be counted")
                .isEqualTo(2);

        CategoryTreeResponse childNode = parentNode.children().stream()
                .filter(n -> n.id().equals(childCategoryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seeded child category missing from tree"));

        assertThat(childNode.productCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Detail: the category landing page carries the same count")
    void findBySlug_carriesTheSameCount() {
        CategoryDetailResponse detail = categoryService.findBySlug(
                "count-parent-" + unique, "ar");

        assertThat(detail.productCount()).isEqualTo(2);
    }
}
