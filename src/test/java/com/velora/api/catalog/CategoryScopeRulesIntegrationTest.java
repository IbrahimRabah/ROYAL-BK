package com.velora.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The scope rules: a child shares its parent's scope, a product sits in a PRODUCT category and a
 * portfolio item in a PORTFOLIO one (on create AND on update, in both directions), the scope of a
 * category in use cannot change, and an omitted scope on an update means "no change". The database
 * holds the parent/child rule too (composite foreign key), checked here with raw SQL.
 */
@SpringBootTest
class CategoryScopeRulesIntegrationTest {

    private static final long ACTOR = 999_003L;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String tag;
    private final List<Long> categoryIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<String> itemSlugs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", ACTOR);
        itemSlugs.forEach(slug -> jdbc.update("DELETE FROM portfolio_item WHERE slug = ?", slug));
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        // Children before parents.
        List<Long> ids = new ArrayList<>(categoryIds);
        java.util.Collections.reverse(ids);
        for (Long id : ids) {
            jdbc.update("DELETE FROM category_translation WHERE category_id = ?", id);
            jdbc.update("DELETE FROM category WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------- parent and child

    @Test
    @DisplayName("A child must share its parent's scope, both ways: CATEGORY_SCOPE_MISMATCH 400")
    void childSharesTheParentsScope() throws Exception {
        long product = category("p", "PRODUCT", null);
        long portfolio = category("q", "PORTFOLIO", null);

        saveCategory(post("/api/v1/admin/categories"), "bad1", "PORTFOLIO", product)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));
        saveCategory(post("/api/v1/admin/categories"), "bad2", "PRODUCT", portfolio)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));

        // Moving an existing category under a parent of the other scope is refused too.
        long loose = category("loose", "PORTFOLIO", null);
        saveCategory(put("/api/v1/admin/categories/{id}", loose), "loose-" + tag, null, product)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));
    }

    @Test
    @DisplayName("A child created without a scope takes its parent's scope")
    void childInheritsWhenScopeIsOmitted() throws Exception {
        long portfolio = category("q2", "PORTFOLIO", null);
        long child = category("q2c", null, portfolio);

        assertThat(scopeOf(child)).isEqualTo("PORTFOLIO");
    }

    @Test
    @DisplayName("The database refuses a child whose scope differs from its parent, whatever the service does")
    void databaseHoldsTheParentChildRule() {
        long parent = insertCategory("db-parent-" + tag, "PRODUCT", null);
        long child = insertCategory("db-child-" + tag, "PRODUCT", parent);

        assertThatThrownBy(() -> jdbc.update("UPDATE category SET scope = 'PORTFOLIO' WHERE id = ?", child))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE category SET scope = 'PORTFOLIO' WHERE id = ?", parent))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCategory("db-bad-" + tag, "PORTFOLIO", parent))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE category SET scope = 'SHOP' WHERE id = ?", parent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------- changing and omitting scope

    @Test
    @DisplayName("An omitted scope on an update means NO CHANGE: a portfolio category stays a portfolio category")
    void omittedScopeOnUpdateIsNoChange() throws Exception {
        long portfolio = category("keep", "PORTFOLIO", null);

        saveCategory(put("/api/v1/admin/categories/{id}", portfolio), "keep-" + tag, null, null)
                .andExpect(status().isOk());

        assertThat(scopeOf(portfolio)).isEqualTo("PORTFOLIO");
    }

    @Test
    @DisplayName("An unused category can change scope; one with a child, a product or a portfolio item cannot")
    void scopeChangeIsRefusedWhileInUse() throws Exception {
        long free = category("free", "PRODUCT", null);
        saveCategory(put("/api/v1/admin/categories/{id}", free), "free-" + tag, "PORTFOLIO", null)
                .andExpect(status().isOk());
        assertThat(scopeOf(free)).isEqualTo("PORTFOLIO");

        long withChild = category("wc", "PRODUCT", null);
        category("wcc", "PRODUCT", withChild);
        saveCategory(put("/api/v1/admin/categories/{id}", withChild), "wc-" + tag, "PORTFOLIO", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_IN_USE"));

        long withProduct = category("wp", "PRODUCT", null);
        createProduct(withProduct).andExpect(status().isCreated());
        saveCategory(put("/api/v1/admin/categories/{id}", withProduct), "wp-" + tag, "PORTFOLIO", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_IN_USE"));

        long withItem = category("wi", "PORTFOLIO", null);
        createItem("wi-item-" + tag, withItem).andExpect(status().isCreated());
        saveCategory(put("/api/v1/admin/categories/{id}", withItem), "wi-" + tag, "PRODUCT", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_IN_USE"));
    }

    // ------------------------------------------------- products and portfolio items, 4 entry points

    @Test
    @DisplayName("Product create and update refuse a PORTFOLIO category; portfolio create and update refuse a PRODUCT one")
    void productsAndItemsKeepToTheirOwnScope() throws Exception {
        long product = category("own-p", "PRODUCT", null);
        long portfolio = category("own-q", "PORTFOLIO", null);

        // Product create.
        createProduct(portfolio).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));
        // Product update: a good product moved into a portfolio category.
        String created = createProduct(product).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        long productId = ((Number) JsonPath.read(created, "$.id")).longValue();
        mvc.perform(put("/api/v1/admin/products/{id}", productId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + portfolio + ",\"featured\":false,\"newArrival\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));

        // Portfolio create.
        createItem("mis-" + tag, product).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));
        // Portfolio update: a good item moved into a product category.
        String item = createItem("ok-" + tag, portfolio).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        long itemId = ((Number) JsonPath.read(item, "$.id")).longValue();
        mvc.perform(put("/api/v1/admin/portfolio/{id}", itemId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"عمل\",\"categoryId\":" + product + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SCOPE_MISMATCH"));

        // An item with no category at all stays valid on update.
        mvc.perform(put("/api/v1/admin/portfolio/{id}", itemId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleAr\":\"عمل\"}"))
                .andExpect(status().isOk());
    }

    // --------------------------------------------------------------------------------- helpers

    private long category(String name, String scope, Long parentId) throws Exception {
        String slug = "scope-" + name + "-" + tag;
        String created = saveCategory(post("/api/v1/admin/categories"), slug, scope, parentId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        categoryIds.add(id);
        return id;
    }

    private ResultActions saveCategory(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder method,
                                       String slug, String scope, Long parentId) throws Exception {
        String body = "{\"slug\":\"" + slug + "\",\"translations\":[{\"locale\":\"ar\",\"name\":\"تصنيف "
                + slug + "\"}]" + (scope == null ? "" : ",\"scope\":\"" + scope + "\"")
                + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
        return mvc.perform(method.with(admin()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createProduct(long categoryId) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/admin/products").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":" + categoryId + ",\"translations\":[{\"locale\":\"ar\",\"name\":\"منتج "
                        + tag + "\"}],\"featured\":false,\"newArrival\":false,"
                        + "\"fulfillmentType\":\"READY_MADE\",\"shippingSizeClass\":\"MEDIUM\"}"));
        // Remember every product that did get created so it can be removed.
        jdbc.query("SELECT p.id FROM product p WHERE p.category_id = ?",
                rs -> { productIds.add(rs.getLong(1)); }, categoryId);
        return result;
    }

    private ResultActions createItem(String slug, long categoryId) throws Exception {
        itemSlugs.add(slug);
        return mvc.perform(post("/api/v1/admin/portfolio").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleAr\":\"عمل " + slug + "\",\"slug\":\"" + slug + "\",\"categoryId\":"
                        + categoryId + "}"));
    }

    private String scopeOf(long id) {
        return jdbc.queryForObject("SELECT scope FROM category WHERE id = ?", String.class, id);
    }

    private long insertCategory(String slug, String scope, Long parentId) {
        jdbc.update("INSERT INTO category (slug, scope, parent_id, display_order, is_active) VALUES (?, ?, ?, 0, 1)",
                slug, scope, parentId);
        long id = jdbc.queryForObject("SELECT id FROM category WHERE slug = ?", Long.class, slug);
        categoryIds.add(id);
        return id;
    }

    private static RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(ACTOR, "scope@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
