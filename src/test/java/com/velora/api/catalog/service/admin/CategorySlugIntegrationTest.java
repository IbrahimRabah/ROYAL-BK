package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.dto.admin.CategorySaveRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Editing a category must not move its URL; a real clash with another category still suffixes. */
@SpringBootTest
class CategorySlugIntegrationTest {

    @Autowired private TaxonomyAdminService taxonomy;
    @Autowired private CategoryRepository categories;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM category_translation WHERE category_id = ?", id);
            jdbc.update("DELETE FROM category WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("Re-saving a category with its own slug keeps the slug, however many times")
    void ownSlugIsNotAClash() {
        String slug = "slug-keep-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = create(slug);

        for (int i = 0; i < 3; i++) {
            taxonomy.updateCategory(id, request(slug));
            assertThat(slugOf(id)).as("after save #" + (i + 1)).isEqualTo(slug);
        }
    }

    @Test
    @DisplayName("A slug already used by a DIFFERENT category is still suffixed, as before")
    void realClashStillSuffixed() {
        String slug = "slug-clash-" + UUID.randomUUID().toString().substring(0, 8);
        create(slug);
        Long other = create("slug-other-" + UUID.randomUUID().toString().substring(0, 8));

        taxonomy.updateCategory(other, request(slug));

        assertThat(slugOf(other)).isEqualTo(slug + "-2");
    }

    @Test
    @DisplayName("Creating a category whose slug is taken is suffixed too")
    void createClashSuffixed() {
        String slug = "slug-new-" + UUID.randomUUID().toString().substring(0, 8);
        create(slug);

        Long second = create(slug);

        assertThat(slugOf(second)).isEqualTo(slug + "-2");
    }

    private Long create(String slug) {
        Long id = taxonomy.createCategory(request(slug));
        created.add(id);
        return id;
    }

    private CategorySaveRequest request(String slug) {
        return new CategorySaveRequest(null, slug,
                List.of(new TranslationRequest("ar", "تصنيف اختبار", null, null, null, null)),
                null, null);
    }

    private String slugOf(Long id) {
        return categories.findById(id).orElseThrow().getSlug();
    }
}
