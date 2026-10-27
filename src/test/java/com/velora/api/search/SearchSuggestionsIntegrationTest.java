package com.velora.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.identity.security.UserPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Search suggestions: what appears, what must not, and the Arabic spellings that must find each other.
 * Every name carries a per-run tag and every query includes it, so the assertions are about THIS data
 * and not whatever else royal_test holds. Anonymous throughout - the endpoint is public.
 */
@SpringBootTest
class SearchSuggestionsIntegrationTest {

    private static final long ACTOR = 999_006L;
    private static final String URL = "/api/v1/search/suggestions";

    @Autowired private WebApplicationContext context;
    @Autowired private ProductAdminService productAdminService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String tag;
    private long shopCategoryId;
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        // The category the test products live in. Its name has none of the words queried below.
        shopCategoryId = category("حاوية " + tag, "PRODUCT", true);
    }

    @AfterEach
    void removeTestData() {
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_image WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_variant WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM audit_log WHERE entity_type = 'PRODUCT' AND entity_id = ?", String.valueOf(id));
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        for (Long id : categoryIds) {
            jdbc.update("DELETE FROM category_translation WHERE category_id = ?", id);
            jdbc.update("DELETE FROM category WHERE id = ?", id);
        }
    }

    // ============================================================== products

    @Test
    @DisplayName("A product match carries the name, slug, image and price - and nothing else")
    void productMatchShape() throws Exception {
        long id = product("أثاث " + tag, true);
        variant(id, "1250.00");
        jdbc.update("INSERT INTO product_image (product_id, url, is_main, display_order) VALUES (?, ?, 1, 0)",
                id, "products/2026/10/sug-" + tag + ".png");

        String body = suggest("أثاث " + tag);

        assertThat(JsonPath.<List<String>>read(body, "$.products[*].name")).containsExactly("أثاث " + tag);
        Map<String, Object> row = JsonPath.read(body, "$.products[0]");
        assertThat(row.keySet()).containsExactlyInAnyOrder("name", "slug", "imageUrl", "price");
        assertThat((String) row.get("imageUrl")).endsWith("sug-" + tag + ".png");
        assertThat(new BigDecimal(row.get("price").toString())).isEqualByComparingTo("1250.00");
        assertThat(JsonPath.<List<?>>read(body, "$.categories")).isEmpty();
    }

    @Test
    @DisplayName("A product with no image and no variant on sale has neither field, rather than null")
    void emptyFieldsAreLeftOut() throws Exception {
        product("بلا صورة " + tag, true);

        String body = suggest("بلا صورة " + tag);

        Map<String, Object> row = JsonPath.read(body, "$.products[0]");
        assertThat(row.keySet()).containsExactlyInAnyOrder("name", "slug");
        assertThat(body).doesNotContain(":null");
    }

    @Test
    @DisplayName("A draft product and an archived one are never suggested")
    void draftAndArchivedAreHidden() throws Exception {
        product("مسودة " + tag, false);
        long archived = product("مؤرشف " + tag, true);
        jdbc.update("UPDATE product SET archived_at = SYSDATETIMEOFFSET() WHERE id = ?", archived);
        product("ظاهر " + tag, true);

        assertThat(productNames(suggest("مسودة " + tag))).isEmpty();
        assertThat(productNames(suggest("مؤرشف " + tag))).isEmpty();
        assertThat(productNames(suggest("ظاهر " + tag))).containsExactly("ظاهر " + tag);
    }

    @Test
    @DisplayName("At most 6 products come back, however many match")
    void productsAreCapped() throws Exception {
        for (int i = 1; i <= 8; i++) {
            product("سقف " + tag + " " + i, true);
        }

        assertThat(productNames(suggest("سقف " + tag))).hasSize(6);
    }

    @Test
    @DisplayName("Matching covers the name in either language, whichever language the request asks for")
    void matchesEitherLanguage() throws Exception {
        Long id = productAdminService.create(new ProductCreateRequest(shopCategoryId, null,
                "sug-" + UUID.randomUUID().toString().substring(0, 12),
                List.of(new TranslationRequest("ar", "كنبة " + tag, null, null, null, null),
                        new TranslationRequest("en", "Sofa " + tag, null, null, null, null)),
                false, false, null, null, ShippingSizeClass.SMALL)).id();
        productIds.add(id);
        jdbc.update("UPDATE product SET status = 'ACTIVE' WHERE id = ?", id);

        // English typed, Arabic UI (the default) - and the other way round.
        assertThat(productNames(suggest("sofa " + tag))).containsExactly("كنبة " + tag);
        String english = mvc.perform(get(URL).param("q", "كنبه " + tag).header("Accept-Language", "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(productNames(english)).as("name comes back in the requested language").containsExactly("Sofa " + tag);
    }

    // ============================================================ Arabic spellings

    @Test
    @DisplayName("alef: اثاث finds أثاث")
    void alefForms() throws Exception {
        product("أثاث " + tag, true);
        assertThat(productNames(suggest("اثاث " + tag))).containsExactly("أثاث " + tag);
    }

    @Test
    @DisplayName("yaa: كرسى finds كرسي")
    void yaaForms() throws Exception {
        product("كرسي " + tag, true);
        assertThat(productNames(suggest("كرسى " + tag))).containsExactly("كرسي " + tag);
    }

    @Test
    @DisplayName("taa marbuta: طاوله finds طاولة")
    void taaMarbutaForms() throws Exception {
        product("طاولة " + tag, true);
        assertThat(productNames(suggest("طاوله " + tag))).containsExactly("طاولة " + tag);
    }

    @Test
    @DisplayName("tatweel: سرير finds سريــر")
    void tatweelIsIgnored() throws Exception {
        product("سريــر " + tag, true);
        assertThat(productNames(suggest("سرير " + tag))).containsExactly("سريــر " + tag);
    }

    @Test
    @DisplayName("The other direction works too: the fancy spelling typed finds the plain one")
    void fancyTypedFindsPlain() throws Exception {
        product("اثاث " + tag, true);
        product("طاوله " + tag, true);
        product("كرسى " + tag, true);

        assertThat(productNames(suggest("أثاث " + tag))).containsExactly("اثاث " + tag);
        assertThat(productNames(suggest("طاولة " + tag))).containsExactly("طاوله " + tag);
        assertThat(productNames(suggest("كرسي " + tag))).containsExactly("كرسى " + tag);
        assertThat(productNames(suggest("كُرْسِي " + tag))).as("diacritics typed").containsExactly("كرسى " + tag);
    }

    @Test
    @DisplayName("Categories fold Arabic spellings the same way")
    void categoryArabicForms() throws Exception {
        category("كرسي مكتب " + tag, "PRODUCT", true);
        category("طاولة طعام " + tag, "PRODUCT", true);

        assertThat(categoryNames(suggest("كرسى مكتب " + tag))).containsExactly("كرسي مكتب " + tag);
        assertThat(categoryNames(suggest("طاوله طعام " + tag))).containsExactly("طاولة طعام " + tag);
    }

    // ============================================================== categories

    @Test
    @DisplayName("A category match carries the id, name and slug - the id being the one the category tree uses")
    void categoryMatchShape() throws Exception {
        long id = category("غرفة معيشة " + tag, "PRODUCT", true);

        String body = suggest("معيشه " + tag);

        Map<String, Object> row = JsonPath.read(body, "$.categories[0]");
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "name", "slug");
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(id);
        assertThat(row.get("name")).isEqualTo("غرفة معيشة " + tag);

        // The same id, with the same slug, is what the shop's category tree returns for it.
        String tree = mvc.perform(get("/api/v1/categories/tree")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> treeIds = JsonPath.read(tree, "$..[?(@.slug=='" + row.get("slug") + "')].id");
        assertThat(treeIds).hasSize(1);
        assertThat(treeIds.get(0).longValue()).isEqualTo(id);
    }

    @Test
    @DisplayName("A PORTFOLIO category never appears in shop suggestions")
    void portfolioCategoryIsNeverSuggested() throws Exception {
        category("أبواب معرض " + tag, "PORTFOLIO", true);
        category("أبواب متجر " + tag, "PRODUCT", true);

        assertThat(categoryNames(suggest("ابواب " + tag))).containsExactly("أبواب متجر " + tag);
    }

    @Test
    @DisplayName("An inactive category is not suggested; an EMPTY active product category is, deliberately")
    void inactiveHiddenEmptyShown() throws Exception {
        category("مخفي " + tag, "PRODUCT", false);
        category("فاضي " + tag, "PRODUCT", true);   // no products in it

        assertThat(categoryNames(suggest("مخفي " + tag))).isEmpty();
        assertThat(categoryNames(suggest("فاضي " + tag))).containsExactly("فاضي " + tag);
    }

    @Test
    @DisplayName("A category under an inactive parent is not suggested - the shop menu cannot reach it either")
    void categoryUnderInactiveParentIsHidden() throws Exception {
        long parent = category("أب معطل " + tag, "PRODUCT", false);
        categoryUnder("ابن ظاهر " + tag, parent);

        assertThat(categoryNames(suggest("ابن ظاهر " + tag))).isEmpty();
    }

    @Test
    @DisplayName("At most 4 categories come back")
    void categoriesAreCapped() throws Exception {
        for (int i = 1; i <= 6; i++) {
            category("فئة " + tag + " " + i, "PRODUCT", true);
        }

        assertThat(categoryNames(suggest("فئة " + tag))).hasSize(4);
    }

    // =========================================================== short and empty

    @Test
    @DisplayName("A one-character query is 200 with two empty lists, not an error")
    void oneCharacter() throws Exception {
        product("ا" + tag, true);

        assertThat(suggestRaw("ا")).isEqualTo("{\"products\":[],\"categories\":[]}");
        assertThat(suggestRaw("a")).isEqualTo("{\"products\":[],\"categories\":[]}");
        assertThat(suggestRaw("  ب  ")).isEqualTo("{\"products\":[],\"categories\":[]}");
    }

    @Test
    @DisplayName("A missing, blank or punctuation-only q is the same empty answer")
    void missingBlankAndPunctuation() throws Exception {
        String empty = "{\"products\":[],\"categories\":[]}";
        assertThat(mvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .isEqualTo(empty);
        assertThat(suggestRaw("   ")).isEqualTo(empty);
        assertThat(suggestRaw("!!")).isEqualTo(empty);
        assertThat(suggestRaw("%_")).as("SQL wildcards are not a query").isEqualTo(empty);
    }

    @Test
    @DisplayName("No match is 200 with two empty lists, never 404")
    void noMatch() throws Exception {
        assertThat(suggestRaw("لاشيءيطابق " + tag)).isEqualTo("{\"products\":[],\"categories\":[]}");
    }

    // ================================================================= helpers

    private String suggest(String q) throws Exception {
        return suggestRaw(q);
    }

    private String suggestRaw(String q) throws Exception {
        return mvc.perform(get(URL).param("q", q)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> productNames(String body) {
        return JsonPath.read(body, "$.products[*].name");
    }

    private static List<String> categoryNames(String body) {
        return JsonPath.read(body, "$.categories[*].name");
    }

    /** Created through the service so search_text is written the way production writes it. */
    private long product(String nameAr, boolean active) {
        Long id = productAdminService.create(new ProductCreateRequest(shopCategoryId, null,
                "sug-" + UUID.randomUUID().toString().substring(0, 12),
                List.of(new TranslationRequest("ar", nameAr, null, null, null, null)),
                false, false, null, null, ShippingSizeClass.SMALL)).id();
        productIds.add(id);
        if (active) {
            jdbc.update("UPDATE product SET status = 'ACTIVE' WHERE id = ?", id);
        }
        return id;
    }

    private void variant(long productId, String price) {
        transactionTemplate.executeWithoutResult(s -> {
            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("SUG-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
            variant.setPrice(new BigDecimal(price));
            variant.setWeightGrams(100);
            variant.setStatus(VariantStatus.ACTIVE);
            variantRepository.save(variant);
        });
    }

    private long category(String nameAr, String scope, boolean active) throws Exception {
        return createCategory(nameAr, scope, active, null);
    }

    private long categoryUnder(String nameAr, long parentId) throws Exception {
        return createCategory(nameAr, "PRODUCT", true, parentId);
    }

    private long createCategory(String nameAr, String scope, boolean active, Long parentId) throws Exception {
        UserPrincipal admin = UserPrincipal.of(ACTOR, "sug@example.com", null, List.of("ADMIN"));
        String body = "{\"slug\":\"sug-" + UUID.randomUUID().toString().substring(0, 10) + "\",\"scope\":\"" + scope
                + "\",\"active\":" + active + (parentId == null ? "" : ",\"parentId\":" + parentId)
                + ",\"translations\":[{\"locale\":\"ar\",\"name\":\"" + nameAr + "\"}]}";
        String created = mvc.perform(post("/api/v1/admin/categories")
                        .with(authentication(new UsernamePasswordAuthenticationToken(admin, null, admin.authorities())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        categoryIds.add(0, id);   // newest first: children are removed before their parents
        return id;
    }
}
