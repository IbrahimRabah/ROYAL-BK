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
 * Where a category made for the portfolio shows up, and where it must not. The portfolio shares
 * the category table with the shop, so the scope column is what keeps the two apart: a PORTFOLIO
 * category is for the portfolio only, never in the shop navigation or a product filter, and a PRODUCT
 * category never in the portfolio's list. Empty PRODUCT categories stay visible - that is deliberate
 * (the client wants to see the whole structure before filling it); only the scope decides.
 */
@SpringBootTest
class PortfolioCategoryVisibilityIntegrationTest {

    private static final long ACTOR = 999_002L;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String tag;
    private String portfolioSlug;
    private String productSlug;
    private long portfolioCategoryId;
    private long productCategoryId;
    private final List<String> itemSlugs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        portfolioSlug = "gallery-doors-" + tag;
        productSlug = "shop-empty-" + tag;

        portfolioCategoryId = createCategory(portfolioSlug, "PORTFOLIO");
        productCategoryId = createCategory(productSlug, null);   // scope omitted: PRODUCT

        long item = createItem("gallery-door-" + tag, portfolioCategoryId);
        publish(item);
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", ACTOR);
        itemSlugs.forEach(slug -> jdbc.update("DELETE FROM portfolio_item WHERE slug = ?", slug));
        for (long id : new long[] {portfolioCategoryId, productCategoryId}) {
            jdbc.update("DELETE FROM category_translation WHERE category_id = ?", id);
            jdbc.update("DELETE FROM category WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("A PORTFOLIO category is absent from the shop tree; an EMPTY product category is still there")
    void shopTreeShowsOnlyProductCategories() throws Exception {
        String tree = mvc.perform(get("/api/v1/categories/tree")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> portfolio = JsonPath.read(tree, "$..[?(@.slug=='" + portfolioSlug + "')].productCount");
        List<Integer> empty = JsonPath.read(tree, "$..[?(@.slug=='" + productSlug + "')].productCount");

        assertThat(portfolio).as("a portfolio category never reaches the shop navigation").isEmpty();
        assertThat(empty).as("an empty product category is deliberately still listed").hasSize(1);
        assertThat(empty.get(0)).isZero();
    }

    @Test
    @DisplayName("A PORTFOLIO slug is a 404 on the category page; a product category's page opens")
    void categoryPageIsProductOnly() throws Exception {
        mvc.perform(get("/api/v1/categories/{slug}", portfolioSlug))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/categories/{slug}", productSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(0));
    }

    @Test
    @DisplayName("Product listing and filters on a PORTFOLIO category find nothing")
    void productQueriesFindNothingInAPortfolioCategory() throws Exception {
        mvc.perform(get("/api/v1/products").param("categoryId", String.valueOf(portfolioCategoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/categories/filters").param("categoryId", String.valueOf(portfolioCategoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes").isEmpty());
    }

    @Test
    @DisplayName("The portfolio's category list holds the PORTFOLIO category and not the product one")
    void portfolioCategoryListIsPortfolioOnly() throws Exception {
        String json = mvc.perform(get("/api/v1/portfolio/categories")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> slugs = JsonPath.read(json, "$[*].slug");

        assertThat(slugs).contains(portfolioSlug).doesNotContain(productSlug);
    }

    @Test
    @DisplayName("The portfolio finds the item by its category")
    void portfolioListsTheItemByCategory() throws Exception {
        mvc.perform(get("/api/v1/portfolio").param("categoryId", String.valueOf(portfolioCategoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("gallery-door-" + tag));
    }

    @Test
    @DisplayName("The admin list filters by scope, and every node says which scope it has")
    void adminListFiltersByScope() throws Exception {
        assertThat(adminSlugs("PORTFOLIO")).contains(portfolioSlug).doesNotContain(productSlug);
        assertThat(adminSlugs("PRODUCT")).contains(productSlug).doesNotContain(portfolioSlug);
        assertThat(adminSlugs(null)).contains(portfolioSlug, productSlug);

        String all = mvc.perform(get("/api/v1/admin/categories").with(admin())).andReturn()
                .getResponse().getContentAsString();
        List<String> scopes = JsonPath.read(all, "$..[?(@.slug=='" + portfolioSlug + "')].scope");
        assertThat(scopes).containsExactly("PORTFOLIO");

        mvc.perform(get("/api/v1/admin/categories").param("scope", "SHOP").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ----------------------------------------- an item with no category must not disappear

    @Test
    @DisplayName("An uncategorised item is listed and opens, in the public portfolio and in the admin")
    void uncategorisedItemStillListsAndRenders() throws Exception {
        String slug = "gallery-plain-" + tag;
        long id = createItem(slug, null);
        publish(id);

        assertThat(publicSlugs(null)).as("public list, no filter").contains(slug);
        assertThat(adminItemIds()).as("admin list, no filter").contains(id);
        mvc.perform(get("/api/v1/portfolio/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.categorySlug").doesNotExist());
        mvc.perform(get("/api/v1/admin/portfolio/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").doesNotExist());
    }

    @Test
    @DisplayName("Filtering by a category leaves an uncategorised item out of that category, not out of the portfolio")
    void categoryFilterTreatsNoCategoryAsNoMatch() throws Exception {
        String slug = "gallery-plain-" + tag;
        publish(createItem(slug, null));

        assertThat(publicSlugs(portfolioCategoryId)).contains("gallery-door-" + tag).doesNotContain(slug);
        assertThat(publicSlugs(null)).contains("gallery-door-" + tag, slug);
    }

    // ------------------------------------------------------------------------- helpers

    private long createCategory(String slug, String scope) throws Exception {
        String body = "{\"slug\":\"" + slug + "\",\"translations\":[{\"locale\":\"ar\",\"name\":\"تصنيف "
                + slug + "\"}]" + (scope == null ? "" : ",\"scope\":\"" + scope + "\"") + "}";
        String created = mvc.perform(post("/api/v1/admin/categories").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }

    private long createItem(String slug, Long categoryId) throws Exception {
        String body = "{\"titleAr\":\"عمل " + slug + "\",\"slug\":\"" + slug + "\""
                + (categoryId == null ? "" : ",\"categoryId\":" + categoryId) + "}";
        String item = mvc.perform(post("/api/v1/admin/portfolio").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        itemSlugs.add(slug);
        return ((Number) JsonPath.read(item, "$.id")).longValue();
    }

    private void publish(long id) throws Exception {
        mvc.perform(patch("/api/v1/admin/portfolio/{id}/publish", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"published\":true}"))
                .andExpect(status().isOk());
    }

    private List<String> adminSlugs(String scope) throws Exception {
        var request = get("/api/v1/admin/categories").with(admin());
        if (scope != null) {
            request = request.param("scope", scope);
        }
        String json = mvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return JsonPath.read(json, "$..slug");
    }

    private List<String> publicSlugs(Long categoryId) throws Exception {
        var request = get("/api/v1/portfolio").param("size", "50");
        if (categoryId != null) {
            request = request.param("categoryId", String.valueOf(categoryId));
        }
        String json = mvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return JsonPath.read(json, "$.content[*].slug");
    }

    private List<Long> adminItemIds() throws Exception {
        String json = mvc.perform(get("/api/v1/admin/portfolio").with(admin()).param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(json, "$.content[*].id").stream().map(Number::longValue).toList();
    }

    private static RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(ACTOR, "gallery@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
