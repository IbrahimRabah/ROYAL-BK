package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryImageType;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.dto.admin.CategoryAdminResponse;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.common.storage.StorageService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * CARD and BANNER are two independent single-image slots on a category, not a
 * gallery: a second upload of the same type must replace the file on disk, not
 * accumulate one, and a delete must both null the column and remove the file so the
 * front end can tell "no image" from "image failed to load".
 *
 * <p>Runs against the real database and the real {@code StorageService}, like
 * {@code TaxonomyAdminServiceIntegrationTest} — the file-replacement behaviour only
 * exists once actual bytes are written and deleted on disk, which a mocked
 * {@code StorageService} would not catch.
 */
@SpringBootTest
class CategoryImageIntegrationTest {

    @Autowired private TaxonomyAdminService taxonomyService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private StorageService storageService;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);

        Category category = new Category();
        category.setSlug("test-image-cat-" + unique);
        category.setActive(true);
        category.setDisplayOrder((short) 1);
        CategoryTranslation translation = new CategoryTranslation();
        translation.attachTo(category, "ar");
        translation.setName("قسم تجريبي للصور " + unique);
        category.getTranslations().put("ar", translation);

        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void tearDown() {
        Category category = categoryRepository.findById(categoryId).orElseThrow();
        storageService.delete(category.getImageUrl());
        storageService.delete(category.getBannerUrl());
        jdbc.update("DELETE FROM category_translation WHERE category_id = ?", categoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("Upload stores a key on the category and returns a resolvable full URL")
    void upload_setsKeyAndReturnsFullUrl() {
        CategoryAdminResponse response = taxonomyService.uploadCategoryImage(
                categoryId, CategoryImageType.CARD, jpeg("card.jpg"));

        assertThat(response.imageUrl()).isNotNull().startsWith("http");

        Category reloaded = categoryRepository.findById(categoryId).orElseThrow();
        // The database stores the KEY, not the URL returned to the client.
        assertThat(reloaded.getImageUrl()).isNotBlank();
        assertThat(reloaded.getImageUrl()).isNotEqualTo(response.imageUrl());
        assertThat(storageService.exists(reloaded.getImageUrl())).isTrue();
    }

    @Test
    @DisplayName("Uploading the same type again replaces the file instead of keeping both")
    void upload_replacesPreviousFileOfSameType() {
        taxonomyService.uploadCategoryImage(categoryId, CategoryImageType.CARD, jpeg("first.jpg"));
        String firstKey = categoryRepository.findById(categoryId).orElseThrow().getImageUrl();

        taxonomyService.uploadCategoryImage(categoryId, CategoryImageType.CARD, jpeg("second.jpg"));
        String secondKey = categoryRepository.findById(categoryId).orElseThrow().getImageUrl();

        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(storageService.exists(firstKey)).isFalse();
        assertThat(storageService.exists(secondKey)).isTrue();
    }

    @Test
    @DisplayName("CARD and BANNER are independent slots on the same category")
    void cardAndBannerAreIndependentSlots() {
        taxonomyService.uploadCategoryImage(categoryId, CategoryImageType.CARD, jpeg("card.jpg"));
        taxonomyService.uploadCategoryImage(categoryId, CategoryImageType.BANNER, jpeg("banner.jpg"));

        Category reloaded = categoryRepository.findById(categoryId).orElseThrow();
        assertThat(reloaded.getImageUrl()).isNotBlank();
        assertThat(reloaded.getBannerUrl()).isNotBlank();
        assertThat(reloaded.getImageUrl()).isNotEqualTo(reloaded.getBannerUrl());
    }

    @Test
    @DisplayName("Delete nulls the column and removes the stored file")
    void delete_nullsColumnAndRemovesFile() {
        taxonomyService.uploadCategoryImage(categoryId, CategoryImageType.BANNER, jpeg("banner.jpg"));
        String key = categoryRepository.findById(categoryId).orElseThrow().getBannerUrl();

        taxonomyService.deleteCategoryImage(categoryId, CategoryImageType.BANNER);

        Category reloaded = categoryRepository.findById(categoryId).orElseThrow();
        assertThat(reloaded.getBannerUrl()).isNull();
        assertThat(storageService.exists(key)).isFalse();
    }

    private MockMultipartFile jpeg(String filename) {
        return new MockMultipartFile("file", filename, "image/jpeg", "fake-image-bytes".getBytes());
    }
}
