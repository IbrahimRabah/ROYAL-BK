package com.velora.api.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.identity.security.UserPrincipal;
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
 * Pins what a category used by the portfolio does to the shop today. The portfolio shares the
 * catalogue's category table, so a category made for a portfolio item is, as far as the shop is
 * concerned, just another active category — these tests record that as it actually behaves, so
 * changing it later is a decision with a failing test attached, not a surprise.
 */
@SpringBootTest
class PortfolioCategoryVisibilityIntegrationTest {

    private static final long ACTOR = 999_002L;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String tag;
    private String categorySlug;
    private long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        categorySlug = "gallery-doors-" + tag;

        String created = mvc.perform(post("/api/v1/admin/categories").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + categorySlug + "\",\"translations\":"
                                + "[{\"locale\":\"ar\",\"name\":\"أبواب " + tag + "\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        categoryId = ((Number) JsonPath.read(created, "$.id")).longValue();

        String item = mvc.perform(post("/api/v1/admin/portfolio").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"باب حديد " + tag + "\",\"slug\":\"gallery-door-" + tag
                                + "\",\"categoryId\":" + categoryId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long itemId = ((Number) JsonPath.read(item, "$.id")).longValue();

        mvc.perform(patch("/api/v1/admin/portfolio/{id}/publish", itemId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"published\":true}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", ACTOR);
        jdbc.update("DELETE FROM portfolio_item WHERE slug = ?", "gallery-door-" + tag);
        jdbc.update("DELETE FROM category_translation WHERE category_id = ?", categoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("A category made for a published portfolio item appears in the public shop menu with no products")
    void portfolioCategoryAppearsInTheShopTree() throws Exception {
        String tree = mvc.perform(get("/api/v1/categories/tree")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> counts = JsonPath.read(tree, "$..[?(@.slug=='" + categorySlug + "')].productCount");
        assertThat(counts).as("the category is in the menu").hasSize(1);
        assertThat(counts.get(0)).as("and a customer who opens it finds nothing").isZero();
    }

    @Test
    @DisplayName("Its product list is an empty page, not an error, and its filter facets are empty")
    void portfolioCategoryHasNoProducts() throws Exception {
        mvc.perform(get("/api/v1/products").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/v1/categories/filters").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes").isEmpty());

        mvc.perform(get("/api/v1/categories/{slug}", categorySlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(0));
    }

    @Test
    @DisplayName("The portfolio finds the item by that category")
    void portfolioListsTheItemByCategory() throws Exception {
        mvc.perform(get("/api/v1/portfolio").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("gallery-door-" + tag));
    }

    private static RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(ACTOR, "gallery@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
