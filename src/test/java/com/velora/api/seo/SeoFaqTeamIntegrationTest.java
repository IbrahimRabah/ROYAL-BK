package com.velora.api.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import java.nio.charset.StandardCharsets;
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
 * FAQs, the SEO screens, the sitemap, and the SEO_EDITOR role with the Team screen, against the test database.
 */
@SpringBootTest
class SeoFaqTeamIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProductAdminService productService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AppUserRepository userRepository;

    private MockMvc mvc;
    private String tag;
    private Long categoryId;
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final long staff = 999_051L;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
        Category category = new Category();
        category.setSlug("seo-" + tag);
        category.setActive(true);
        putTranslation(category, "ar", "فئة " + tag);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM faq_item WHERE question_ar LIKE ?", "%" + tag + "%");
        jdbc.update("DELETE FROM audit_log WHERE entity_label LIKE ? OR entity_label LIKE ?", "seo-" + tag + "%", "%" + tag + "%");
        jdbc.update("UPDATE seo_page SET title_ar = NULL, description_ar = NULL, noindex = 0 WHERE page_key IN ('ABOUT', 'CONTACT')");
        for (Long id : productIds) {
            jdbc.update("DELETE FROM faq_item WHERE scope = 'PRODUCT' AND target_id = ?", id);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM faq_item WHERE scope = 'CATEGORY' AND target_id = ?", categoryId);
        jdbc.update("DELETE FROM category_translation WHERE category_id = ?", categoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        for (Long id : userIds) {
            jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role_id IN (SELECT id FROM role WHERE code <> 'CUSTOMER')", id);
            jdbc.update("DELETE FROM audit_log WHERE entity_type = 'APP_USER' AND entity_id = ?", String.valueOf(id));
            jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
    }

    // ----------------------------------------------------------------- fixtures

    private static void putTranslation(Category category, String locale, String name) {
        CategoryTranslation t = new CategoryTranslation();
        t.attachTo(category, locale);
        t.setName(name);
        category.getTranslations().put(locale, t);
    }

    private long product(String suffix, boolean publish) {
        long id = productService.create(new ProductCreateRequest(categoryId, null, "seo-" + tag + "-" + suffix,
                List.of(new TranslationRequest("ar", "منتج " + suffix + " " + tag, null, null, null, null),
                        new TranslationRequest("en", "Product " + suffix, null, null, null, null)),
                false, false, null, FulfillmentType.MADE_TO_ORDER, null)).id();
        productIds.add(id);
        if (publish) {
            productService.publish(id);
        }
        return id;
    }

    private Long user(String first) {
        AppUser u = new AppUser();
        u.setEmail(first.toLowerCase() + "-" + tag + "@example.com");
        u.setPasswordHash("not-a-real-hash");
        u.setFirstName(first);
        u.setLastName("Tester");
        Long id = userRepository.save(u).getId();
        userIds.add(id);
        return id;
    }

    private static RequestPostProcessor as(Long id, String role) {
        UserPrincipal p = UserPrincipal.of(id, "seo@example.com", null, List.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    private RequestPostProcessor admin() {
        return as(staff, "ADMIN");
    }

    private RequestPostProcessor seoEditor() {
        return as(staff, "SEO_EDITOR");
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    private String faqBody(String scope, Long target, String topic, String question, String status) {
        return "{\"scope\":\"" + scope + "\",\"targetId\":" + target + ",\"topic\":"
                + (topic == null ? "null" : "\"" + topic + "\"") + ",\"questionAr\":\"" + question
                + "\",\"answerAr\":\"إجابة\",\"questionEn\":\"English question\",\"answerEn\":\"English answer\","
                + "\"status\":\"" + status + "\"}";
    }

    private long createFaq(String body) throws Exception {
        String res = mvc.perform(post("/api/v1/admin/faqs").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return json(res).get("id").asLong();
    }

    private String slug(long productId) {
        return jdbc.queryForObject("SELECT slug FROM product WHERE id = ?", String.class, productId);
    }

    // ------------------------------------------------------------------- FAQs

    @Test
    @DisplayName("A FAQ is invisible until published, shows in the language asked for, and keeps its order")
    void faqPublicVisibility() throws Exception {
        long p = product("a", true);
        long draft = createFaq(faqBody("PRODUCT", p, null, "سؤال مسودة " + tag, "DRAFT"));

        mvc.perform(get("/api/v1/products/" + slug(p) + "/faqs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        long first = createFaq(faqBody("PRODUCT", p, null, "السؤال الأول " + tag, "PUBLISHED"));
        long second = createFaq(faqBody("PRODUCT", p, null, "السؤال الثاني " + tag, "PUBLISHED"));
        mvc.perform(get("/api/v1/products/" + slug(p) + "/faqs"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first)).andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[0].question").value("السؤال الأول " + tag));
        mvc.perform(get("/api/v1/products/" + slug(p) + "/faqs").header("Accept-Language", "en"))
                .andExpect(jsonPath("$[0].question").value("English question"));

        // Reorder: the second goes first.
        mvc.perform(put("/api/v1/admin/faqs/reorder").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + second + "," + first + "]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + slug(p) + "/faqs")).andExpect(jsonPath("$[0].id").value(second));

        // Publishing the draft, in bulk, puts it in the public list too.
        mvc.perform(post("/api/v1/admin/faqs/bulk-status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + draft + "],\"status\":\"PUBLISHED\"}"))
                .andExpect(jsonPath("$.changed").value(1));
        mvc.perform(get("/api/v1/products/" + slug(p) + "/faqs")).andExpect(jsonPath("$.length()").value(3));

        mvc.perform(delete("/api/v1/admin/faqs/" + draft).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/faqs/" + draft).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("General FAQs group by topic, category FAQs belong to their category, and bad targets are refused")
    void faqScopes() throws Exception {
        createFaq(faqBody("GENERAL", null, "SHIPPING", "كم يستغرق الشحن " + tag, "PUBLISHED"));
        createFaq(faqBody("GENERAL", null, "PAYMENT", "طرق الدفع " + tag, "PUBLISHED"));
        createFaq(faqBody("CATEGORY", categoryId, null, "سؤال الفئة " + tag, "PUBLISHED"));

        JsonNode shipping = json(mvc.perform(get("/api/v1/faqs?topic=SHIPPING")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(shipping.toString()).contains("كم يستغرق الشحن " + tag).doesNotContain("طرق الدفع " + tag);
        mvc.perform(get("/api/v1/categories/" + categoryId + "/faqs"))
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/v1/admin/faqs").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(faqBody("GENERAL", 5L, "SHIPPING", "x " + tag, "DRAFT")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/faqs").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(faqBody("PRODUCT", null, null, "x " + tag, "DRAFT")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/faqs").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(faqBody("PRODUCT", 999999999L, null, "x " + tag, "DRAFT")))
                .andExpect(status().isNotFound());
        // Plain text only: the English side needs both halves, or neither is stored.
        long half = createFaq("{\"scope\":\"GENERAL\",\"topic\":\"GENERAL\",\"questionAr\":\"نصف " + tag
                + "\",\"answerAr\":\"ج\",\"questionEn\":\"Only a question\",\"status\":\"PUBLISHED\"}");
        assertThat(jdbc.queryForObject("SELECT question_en FROM faq_item WHERE id = ?", String.class, half)).isNull();

        mvc.perform(get("/api/v1/admin/faqs?q=" + tag + "&status=PUBLISHED").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(4));
    }

    // -------------------------------------------------------------------- SEO

    @Test
    @DisplayName("The overview counts what is missing; editing a product's meta fixes it, is audited, and nothing else changes")
    void seoProductsAndOverview() throws Exception {
        long p = product("a", true);
        long q = product("b", true);

        JsonNode before = json(mvc.perform(get("/api/v1/admin/seo/overview").with(seoEditor())).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        long missing = before.at("/products/missingTitle").asLong();
        assertThat(missing).isGreaterThanOrEqualTo(2);

        String body = "{\"metaTitleAr\":\"عنوان قصير " + tag + "\",\"metaDescriptionAr\":\"وصف\","
                + "\"metaTitleEn\":\"English title\",\"metaDescriptionEn\":\"English description\"}";
        mvc.perform(put("/api/v1/admin/seo/products/" + p).with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metaTitleAr").value("عنوان قصير " + tag))
                .andExpect(jsonPath("$.metaTitleEn").value("English title"))
                .andExpect(jsonPath("$.duplicateTitle").value(false));
        // Nothing but the meta texts moved.
        assertThat(jdbc.queryForObject("SELECT name FROM product_translation WHERE product_id = ? AND locale = 'ar'",
                String.class, p)).isEqualTo("منتج a " + tag);
        assertThat(jdbc.queryForObject("SELECT slug FROM product WHERE id = ?", String.class, p)).isEqualTo("seo-" + tag + "-a");

        JsonNode after = json(mvc.perform(get("/api/v1/admin/seo/overview").with(seoEditor())).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(after.at("/products/missingTitle").asLong()).isEqualTo(missing - 1);

        // The same title on the other product is flagged on both.
        mvc.perform(put("/api/v1/admin/seo/products/" + q).with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("English title", "Other")))
                .andExpect(jsonPath("$.duplicateTitle").value(true));
        mvc.perform(get("/api/v1/admin/seo/products?filter=DUPLICATE&q=" + tag).with(seoEditor()))
                .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/v1/admin/seo/products?filter=NO_FAQ&q=" + tag).with(seoEditor()))
                .andExpect(jsonPath("$.content.length()").value(2));

        // The change is in the audit log with the person who made it.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SEO_CHANGED' "
                + "AND entity_type = 'PRODUCT' AND entity_id = ? AND actor_id IS NULL OR action = 'SEO_CHANGED' "
                + "AND entity_type = 'PRODUCT' AND entity_id = ?", Integer.class, String.valueOf(p), String.valueOf(p)))
                .isEqualTo(1);

        // Clearing a text leaves it empty again.
        mvc.perform(put("/api/v1/admin/seo/products/" + p).with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metaTitleAr\":\"\",\"metaDescriptionAr\":\"\"}"))
                .andExpect(jsonPath("$.metaTitleAr").doesNotExist());
        mvc.perform(put("/api/v1/admin/seo/products/999999999").with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Category meta can be edited, and a static page's text is public until it is hidden from search")
    void seoCategoriesAndPages() throws Exception {
        mvc.perform(put("/api/v1/admin/seo/categories/" + categoryId).with(seoEditor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metaTitleAr\":\"عنوان الفئة\",\"metaDescriptionAr\":\"وصف الفئة\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metaTitleAr").value("عنوان الفئة"));

        mvc.perform(put("/api/v1/admin/seo/pages/about").with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"من نحن | رويال\",\"descriptionAr\":\"قصة الحديد\",\"titleEn\":\"About\","
                                + "\"noindex\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageKey").value("ABOUT"));
        mvc.perform(get("/api/v1/seo/pages/ABOUT")).andExpect(jsonPath("$.title").value("من نحن | رويال"));
        mvc.perform(get("/api/v1/seo/pages/ABOUT").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.title").value("About"))
                // English description is empty, so the Arabic one is used.
                .andExpect(jsonPath("$.description").value("قصة الحديد"));
        // An unknown page has no custom text, which is not an error for the storefront.
        mvc.perform(get("/api/v1/seo/pages/NOPE")).andExpect(status().isOk()).andExpect(jsonPath("$.noindex").value(false));
        mvc.perform(put("/api/v1/admin/seo/pages/NOPE").with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noindex\":false}")).andExpect(status().isNotFound());

        // Hidden from search: out of the sitemap.
        String sitemapBefore = mvc.perform(get("/api/v1/seo/sitemap.xml")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sitemapBefore).contains("/about</loc>");
        mvc.perform(put("/api/v1/admin/seo/pages/ABOUT").with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleAr\":\"من نحن\",\"noindex\":true}")).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/v1/seo/sitemap.xml")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).doesNotContain("/about</loc>");
    }

    @Test
    @DisplayName("The sitemap lists live products and nothing else; robots.txt closes the private areas and names the sitemap")
    void sitemapAndRobots() throws Exception {
        long live = product("live", true);
        long draft = product("draft", false);

        String xml = mvc.perform(get("/api/v1/seo/sitemap.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml").contains("/products/" + slug(live) + "</loc>")
                .doesNotContain(slug(draft)).contains("<loc>http://localhost:4200/</loc>");

        String robots = mvc.perform(get("/api/v1/seo/robots.txt")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(robots).contains("Disallow: /admin").contains("Disallow: /checkout")
                .contains("Sitemap: http://localhost:4200/sitemap.xml");
    }

    // ---------------------------------------------------------------- access

    @Test
    @DisplayName("The SEO editor reaches the SEO and FAQ screens and nothing else of the admin")
    void seoEditorIsConfined() throws Exception {
        mvc.perform(get("/api/v1/admin/seo/overview").with(seoEditor())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/faqs").with(seoEditor())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/seo/overview").with(admin())).andExpect(status().isOk());

        for (String path : List.of("/api/v1/admin/orders", "/api/v1/admin/team", "/api/v1/admin/reviews",
                "/api/v1/admin/products", "/api/v1/admin/customers", "/api/v1/admin/invoices")) {
            mvc.perform(get(path).with(seoEditor())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/seo/overview").with(as(staff, "CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/seo/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/faqs")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------- team

    @Test
    @DisplayName("The owner grants roles to a person's own account; self-change and removing the last admin are refused")
    void team() throws Exception {
        Long ahmed = user("Ahmed");
        Long mona = user("Mona");

        mvc.perform(get("/api/v1/admin/team/lookup?email=AHMED-" + tag + "@example.com").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ahmed))
                .andExpect(jsonPath("$.roles.length()").value(0));
        mvc.perform(get("/api/v1/admin/team/lookup?email=nobody-" + tag + "@example.com").with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TEAM_USER_NOT_FOUND"));

        mvc.perform(put("/api/v1/admin/team/" + ahmed + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"seo_editor\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("SEO_EDITOR"));
        assertThat(jdbc.queryForObject("SELECT is_staff FROM app_user WHERE id = ?", Boolean.class, ahmed)).isTrue();
        mvc.perform(get("/api/v1/admin/team").with(admin())).andExpect(jsonPath("$[?(@.id == " + ahmed + ")]").exists());

        // Only the staff roles can be granted here, and never to oneself.
        mvc.perform(put("/api/v1/admin/team/" + ahmed + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"CUSTOMER\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/team/" + staff + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEAM_SELF_CHANGE"));

        // Mona becomes the one administrator; she cannot be demoted while she is the last.
        mvc.perform(put("/api/v1/admin/team/" + mona + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\"]}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/team/" + mona + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEAM_LAST_ADMIN"));
        // With a second administrator she can be.
        mvc.perform(put("/api/v1/admin/team/" + ahmed + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\"]}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/team/" + mona + "/roles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[]}")).andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(0));

        // Every change is in the audit log.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'PERMISSION_CHANGED' "
                + "AND entity_type = 'APP_USER' AND entity_id = ?", Integer.class, String.valueOf(mona))).isEqualTo(2);

        // Only an administrator reaches the team endpoints.
        mvc.perform(put("/api/v1/admin/team/" + mona + "/roles").with(seoEditor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\"]}")).andExpect(status().isForbidden());
    }
}
