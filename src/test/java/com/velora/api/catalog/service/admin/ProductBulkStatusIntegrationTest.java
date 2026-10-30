package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.identity.security.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Publish and unpublish from the product list, many at a time. One product that cannot go live must be
 * reported and skipped without holding back the others.
 */
@SpringBootTest
class ProductBulkStatusIntegrationTest {

    private static final String URL = "/api/v1/admin/products/bulk-status";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductAdminService productService;
    @Autowired private CategoryRepository categoryRepository;

    private MockMvc mvc;
    private Long categoryId;
    private final List<Long> productIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        Category category = new Category();
        category.setSlug("bulk-status-" + UUID.randomUUID().toString().substring(0, 8));
        category.setActive(true);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void removeTestData() {
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    private long draft(String name, FulfillmentType type, ShippingSizeClass size) {
        long id = productService.create(new ProductCreateRequest(categoryId, null, null,
                List.of(new TranslationRequest("ar", name, null, null, null, null)),
                false, false, null, type, size)).id();
        productIds.add(id);
        return id;
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("Publish goes live for the drafts that can, and reports the one that cannot without stopping the rest")
    void publishManyWithOneThatCannot() throws Exception {
        long a = draft("منتج أ", FulfillmentType.MADE_TO_ORDER, null);
        long b = draft("منتج ب", FulfillmentType.MADE_TO_ORDER, null);
        // Ready-made with no variant cannot be published.
        long noVariant = draft("منتج بلا خيارات", FulfillmentType.READY_MADE, ShippingSizeClass.SMALL);

        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\",\"ids\":[" + a + "," + noVariant + "," + b + "," + a + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value(noVariant))
                .andExpect(jsonPath("$.skipped[0].name").value("منتج بلا خيارات"))
                .andExpect(jsonPath("$.skipped[0].reason").value("PRODUCT_HAS_NO_VARIANTS"));

        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        assertThat(statusOf(b)).isEqualTo("ACTIVE");
        assertThat(statusOf(noVariant)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT published_at FROM product WHERE id = ?", Object.class, a)).isNotNull();
    }

    @Test
    @DisplayName("Only a draft is published and only a live product is unpublished; an archived one is never touched")
    void onlyTheRightStatesChange() throws Exception {
        long live = draft("حي", FulfillmentType.MADE_TO_ORDER, null);
        long archived = draft("مؤرشف", FulfillmentType.MADE_TO_ORDER, null);
        productService.publish(live);
        productService.archive(archived);

        // Publishing a live and an archived product changes neither.
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\",\"ids\":[" + live + "," + archived + "]}"))
                .andExpect(jsonPath("$.succeeded.length()").value(0))
                .andExpect(jsonPath("$.skipped[?(@.id == " + live + ")].reason").value("NOT_DRAFT"))
                .andExpect(jsonPath("$.skipped[?(@.id == " + archived + ")].reason").value("NOT_DRAFT"));
        assertThat(statusOf(archived)).isEqualTo("ARCHIVED");

        // Unpublishing: the live one goes back to draft, the archived one is left alone.
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"UNPUBLISH\",\"ids\":[" + live + "," + archived + "]}"))
                .andExpect(jsonPath("$.succeeded.length()").value(1))
                .andExpect(jsonPath("$.succeeded[0]").value(live))
                .andExpect(jsonPath("$.skipped[0].reason").value("NOT_ACTIVE"));
        assertThat(statusOf(live)).isEqualTo("DRAFT");
        assertThat(statusOf(archived)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("An unknown product is reported, an empty or oversized list and a missing action are refused")
    void guards() throws Exception {
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\",\"ids\":[999999999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("PRODUCT_NOT_FOUND"));
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\",\"ids\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\",\"ids\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(999_011L, "bulk@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
