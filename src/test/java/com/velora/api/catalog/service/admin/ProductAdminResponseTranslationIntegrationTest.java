package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.ProductUpdateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.dto.admin.TranslationResponse;
import com.velora.api.catalog.repository.CategoryRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ProductAdminResponse} used to expose only {@code nameAr}/{@code nameEn}.
 * {@code ProductUpdateRequest.translations} overwrites every field of a translation
 * unconditionally ({@code ProductAdminService.applyTranslations} — no merge), so an
 * admin UI that round-trips {@code get()} straight back into {@code update()} would
 * silently blank {@code shortDescription}, {@code description} and both meta fields
 * the moment it only had {@code name} to work with.
 *
 * <p>{@code translations[]} now carries the full content, so the round trip is safe.
 * Runs against the real database because the point being proven is the save path,
 * not just that the DTO has the right fields.
 */
@SpringBootTest
class ProductAdminResponseTranslationIntegrationTest {

    @Autowired private ProductAdminService productAdminService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;

    @BeforeEach
    void createCategory() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Category category = new Category();
        category.setSlug("admin-resp-cat-" + unique);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void removeTestData() {
        if (productId != null) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("get() exposes shortDescription, description and both meta fields, not just the name")
    void get_exposesFullTranslationContent() {
        ProductAdminResponse created = productAdminService.create(new ProductCreateRequest(
                categoryId, null, null,
                List.of(new TranslationRequest("ar", "ساعة كلاسيك ذهبية",
                        "وصف قصير للساعة", "وصف تفصيلي طويل للساعة الذهبية الكلاسيكية",
                        "ساعة كلاسيك ذهبية - فيلورا", "أفضل ساعة كلاسيك ذهبية في مصر")),
                false, false, null, null, ShippingSizeClass.MEDIUM));
        productId = created.id();

        ProductAdminResponse fetched = productAdminService.get(productId);

        assertThat(fetched.translations()).hasSize(1);
        TranslationResponse ar = fetched.translations().get(0);
        assertThat(ar.locale()).isEqualTo("ar");
        assertThat(ar.name()).isEqualTo("ساعة كلاسيك ذهبية");
        assertThat(ar.shortDescription()).isEqualTo("وصف قصير للساعة");
        assertThat(ar.description()).isEqualTo("وصف تفصيلي طويل للساعة الذهبية الكلاسيكية");
        assertThat(ar.metaTitle()).isEqualTo("ساعة كلاسيك ذهبية - فيلورا");
        assertThat(ar.metaDescription()).isEqualTo("أفضل ساعة كلاسيك ذهبية في مصر");
    }

    @Test
    @DisplayName("Round-tripping get() translations through update() does not wipe description or SEO")
    void updateRoundTrip_preservesDescriptionAndSeo() {
        ProductAdminResponse created = productAdminService.create(new ProductCreateRequest(
                categoryId, null, null,
                List.of(new TranslationRequest("ar", "ساعة كلاسيك ذهبية",
                        "وصف قصير للساعة", "وصف تفصيلي طويل للساعة الذهبية الكلاسيكية",
                        "ساعة كلاسيك ذهبية - فيلورا", "أفضل ساعة كلاسيك ذهبية في مصر")),
                false, false, null, null, ShippingSizeClass.MEDIUM));
        productId = created.id();

        // Simulate the admin form: load the product, change only the name, send the
        // WHOLE translations array back — exactly what get() now makes possible.
        TranslationResponse arBeforeEdit = productAdminService.get(productId).translations().get(0);
        TranslationRequest editedTranslation = new TranslationRequest(
                arBeforeEdit.locale(),
                "ساعة كلاسيك ذهبية - محدثة",
                arBeforeEdit.shortDescription(),
                arBeforeEdit.description(),
                arBeforeEdit.metaTitle(),
                arBeforeEdit.metaDescription());

        productAdminService.update(productId, new ProductUpdateRequest(
                categoryId, null, null, List.of(editedTranslation), false, false, null,
                null, null));

        TranslationResponse afterEdit = productAdminService.get(productId).translations().get(0);
        assertThat(afterEdit.name()).isEqualTo("ساعة كلاسيك ذهبية - محدثة");
        assertThat(afterEdit.shortDescription())
                .as("shortDescription must survive a name-only edit")
                .isEqualTo("وصف قصير للساعة");
        assertThat(afterEdit.description())
                .as("description must survive a name-only edit")
                .isEqualTo("وصف تفصيلي طويل للساعة الذهبية الكلاسيكية");
        assertThat(afterEdit.metaTitle())
                .as("metaTitle must survive a name-only edit")
                .isEqualTo("ساعة كلاسيك ذهبية - فيلورا");
        assertThat(afterEdit.metaDescription())
                .as("metaDescription must survive a name-only edit")
                .isEqualTo("أفضل ساعة كلاسيك ذهبية في مصر");
    }
}
