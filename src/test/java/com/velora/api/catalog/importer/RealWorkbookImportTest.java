package com.velora.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The furniture workbook the shop actually works in ({@code docs/01  Bar Stools.xlsx}: 154 products, the
 * 28 MB English-mirror sheet and all) goes through the import unchanged. The shop's two categories,
 * «كراسي البار» and «كراسي», are created for the run, because the test database has none.
 */
@SpringBootTest
class RealWorkbookImportTest {

    private static final Path WORKBOOK = Path.of("docs", "01  Bar Stools.xlsx");
    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String URL = "/api/v1/admin/product-imports";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AppUserRepository userRepository;

    private MockMvc mvc;
    private Long actor;
    private final List<Long> categoryIds = new ArrayList<>();
    private final List<Long> batchIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String tag = UUID.randomUUID().toString().substring(0, 6);

        AppUser user = new AppUser();
        user.setEmail("real-import-" + tag + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Import");
        actor = userRepository.save(user).getId();

        categoryIds.add(category("كراسي البار", "real-bar-" + tag));
        categoryIds.add(category("كراسي", "real-chairs-" + tag));
    }

    @AfterEach
    void removeTestData() {
        List<Long> productIds = jdbc.queryForList(
                "SELECT product_id FROM product_variant WHERE sku LIKE 'CH%'", Long.class);
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", actor);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id IN (SELECT id FROM product_variant WHERE sku LIKE 'CH%')");
        jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE sku LIKE 'CH%')");
        jdbc.update("DELETE FROM product_variant WHERE sku LIKE 'CH%'");
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_attribute_value WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        batchIds.forEach(id -> jdbc.update("DELETE FROM import_batch WHERE id = ?", id));
        categoryIds.forEach(id -> {
            jdbc.update("DELETE FROM category_translation WHERE category_id = ?", id);
            jdbc.update("DELETE FROM category WHERE id = ?", id);
        });
        jdbc.update("DELETE FROM app_user WHERE id = ?", actor);
    }

    @Test
    @DisplayName("The furniture workbook is accepted as it is: 154 rows, every one valid, junk cells only warnings")
    void theWorkbookImports() throws Exception {
        byte[] bytes = Files.readAllBytes(WORKBOOK);

        String upload = mvc.perform(multipart(URL)
                        .file(new MockMultipartFile("file", "01  Bar Stools.xlsx", XLSX_TYPE, bytes))
                        .with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(154))
                .andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(upload, "$.id")).longValue();
        batchIds.add(batchId);

        // What blocks, if anything: print it, then assert there is nothing.
        String errors = mvc.perform(get(URL + "/" + batchId + "/rows?status=ERROR&size=200").with(admin()))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> blocking = JsonPath.read(errors, "$.content[*].messages[?(@.severity == 'ERROR')]");
        assertThat(blocking).as("blocking findings: " + blocking.stream().limit(10).toList()).isEmpty();

        assertThat((Integer) JsonPath.read(upload, "$.errorRows")).isZero();
        assertThat((Boolean) JsonPath.read(upload, "$.canSubmit")).isTrue();

        // The first row, as the reviewer sees it.
        String first = mvc.perform(get(URL + "/" + batchId + "/rows?size=3").with(admin()))
                .andExpect(jsonPath("$.content[0].sku").value("CH135"))
                .andExpect(jsonPath("$.content[0].data.price").value(2650))
                .andExpect(jsonPath("$.content[0].data.slug").value("bar-stool-with-velvet-seat-white-ch135"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(first, "$.content[0].data.nameAr")).contains("CH135");
        assertThat((Map<String, String>) JsonPath.read(first, "$.content[0].data.specs"))
                .containsEntry("WIDTH_CM", "50").containsEntry("MATERIAL", "حديد");

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(154));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_variant WHERE sku LIKE 'CH%'",
                Integer.class)).isEqualTo(154);
        // Bar stools went to «كراسي البار», the dining chairs to «كراسي».
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN product_variant v ON v.product_id = p.id "
                + "WHERE v.sku LIKE 'CH%' AND p.category_id = ?", Integer.class, categoryIds.get(0))).isEqualTo(120);
    }

    private Long category(String arName, String slug) {
        Category category = new Category();
        category.setSlug(slug);
        category.setActive(true);
        CategoryTranslation ar = new CategoryTranslation();
        ar.attachTo(category, "ar");
        ar.setName(arName);
        category.getTranslations().put("ar", ar);
        return categoryRepository.save(category).getId();
    }

    private RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(actor, "import@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }

    static {
        if (!Files.exists(WORKBOOK)) {
            throw new IllegalStateException("The furniture workbook is missing: " + WORKBOOK.toAbsolutePath()
                    + " (run the tests from the project root)");
        }
    }

}
