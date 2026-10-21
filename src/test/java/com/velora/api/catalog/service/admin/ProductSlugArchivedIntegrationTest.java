package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.ProductUpdateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The slug check must see archived products, because the unique index does. */
@SpringBootTest
class ProductSlugArchivedIntegrationTest {

    @Autowired private ProductAdminService products;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Category category = new Category();
        category.setSlug("slug-arch-cat-" + UUID.randomUUID().toString().substring(0, 8));
        category.setActive(false);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("Updating to a slug held by an ARCHIVED product gets a suffix, not a 409")
    void archivedSlugIsStillTaken() {
        String slug = "archived-slug-" + UUID.randomUUID().toString().substring(0, 8);
        Long archived = create(slug);
        products.archive(archived);
        Long other = create("other-" + UUID.randomUUID().toString().substring(0, 8));

        ProductAdminResponse updated = products.update(other, new ProductUpdateRequest(
                categoryId, null, slug, translations(), false, false, null, null, null));

        assertThat(updated.slug()).isEqualTo(slug + "-2");
    }

    @Test
    @DisplayName("Re-saving a product with its own slug keeps it; a live clash is still suffixed")
    void ownSlugKeptLiveClashSuffixed() {
        String slug = "live-slug-" + UUID.randomUUID().toString().substring(0, 8);
        Long first = create(slug);
        Long second = create("second-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(products.update(first, new ProductUpdateRequest(categoryId, null, slug,
                translations(), false, false, null, null, null)).slug()).isEqualTo(slug);
        assertThat(products.update(second, new ProductUpdateRequest(categoryId, null, slug,
                translations(), false, false, null, null, null)).slug()).isEqualTo(slug + "-2");
    }

    private Long create(String slug) {
        Long id = products.create(new ProductCreateRequest(categoryId, null, slug, translations(),
                false, false, null, null, ShippingSizeClass.SMALL)).id();
        created.add(id);
        return id;
    }

    private static List<TranslationRequest> translations() {
        return List.of(new TranslationRequest("ar", "منتج اختبار", null, null, null, null));
    }
}
