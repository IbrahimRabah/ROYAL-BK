package com.velora.api.catalog.importer;

import static com.velora.api.catalog.importer.ImportWorkbooks.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.importer.service.ImportTemplate;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The bulk import end to end, through the real endpoints and the real database: template download,
 * upload and review (which must write nothing), and the all-or-nothing submit.
 *
 * <p>Test products are written the way the furniture workbook writes them: the product in the main
 * sheet, its category and slug in the SEO sheet. Their SKUs all start with {@code IMP<tag>}.
 */
@SpringBootTest
class ProductImportIntegrationTest {

    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String URL = "/api/v1/admin/product-imports";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AppUserRepository userRepository;

    private MockMvc mvc;
    private String tag;
    private String categoryName;
    private Long categoryId;
    private Long actor;
    private final List<Long> batchIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        categoryName = "كراسي اختبار " + tag;

        AppUser user = new AppUser();
        user.setEmail("import-test-" + tag.toLowerCase() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Import");
        actor = userRepository.save(user).getId();

        Category category = new Category();
        category.setSlug("import-test-" + tag.toLowerCase());
        category.setActive(true);
        CategoryTranslation ar = new CategoryTranslation();
        ar.attachTo(category, "ar");
        ar.setName(categoryName);
        category.getTranslations().put("ar", ar);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void removeTestData() {
        List<Long> productIds = jdbc.queryForList(
                "SELECT product_id FROM product_variant WHERE sku LIKE ?", Long.class, "IMP" + tag + "%");
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", actor);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id IN "
                + "(SELECT id FROM product_variant WHERE sku LIKE ?)", "IMP" + tag + "%");
        jdbc.update("DELETE FROM inventory WHERE variant_id IN "
                + "(SELECT id FROM product_variant WHERE sku LIKE ?)", "IMP" + tag + "%");
        jdbc.update("DELETE FROM product_variant WHERE sku LIKE ?", "IMP" + tag + "%");
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_attribute_value WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        batchIds.forEach(id -> jdbc.update("DELETE FROM import_batch WHERE id = ?", id));
        jdbc.update("DELETE FROM category_translation WHERE category_id = ?", categoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", actor);
    }

    // ----------------------------------------------------------------------- template

    @Test
    @DisplayName("The template downloads as an xlsx with headers only; uploaded as it is, it is refused as empty")
    void templateDownload() throws Exception {
        byte[] bytes = mvc.perform(get(URL + "/template").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_TYPE))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(bytes).isEqualTo(ImportWorkbooks.template());

        mvc.perform(multipart(URL).file(file(bytes)).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_EMPTY"));
    }

    @Test
    @DisplayName("The template and the upload need an admin")
    void adminOnly() throws Exception {
        mvc.perform(get(URL + "/template")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(URL).file(file(ImportWorkbooks.template()))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------ upload

    @Test
    @DisplayName("A file that is not the template is rejected with IMPORT_TEMPLATE_MISMATCH and nothing is stored")
    void wrongTemplateIsRejected() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM import_batch", Long.class);

        byte[] altered = ImportWorkbooks.empty(wb -> {
            wb.getSheet(ImportTemplate.SHEET_MAIN).getRow(0).getCell(4).setCellValue("الخامة");
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                    "name_ar", "كرسي", "price", "100"));
        });

        mvc.perform(multipart(URL).file(file(altered)).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_TEMPLATE_MISMATCH"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("column E")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_batch", Long.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("Anything but .xlsx is rejected")
    void wrongFileType() throws Exception {
        mvc.perform(multipart(URL)
                        .file(new MockMultipartFile("file", "products.csv", "text/csv", "a,b".getBytes()))
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        mvc.perform(multipart(URL)
                        .file(new MockMultipartFile("file", "products.xlsx", XLSX_TYPE, "not a zip".getBytes()))
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    // --------------------------------------------------- preview writes nothing, submit does

    @Test
    @DisplayName("Preview stores nothing in the catalogue; the submit creates every product, variant, stock and spec")
    void previewThenCommit() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "imp" + tag + "A", "category", categoryName,
                    "name_ar", "كرسي بار حديد أبيض", "name_en", "Iron Bar Stool White",
                    "short_ar", "كرسي بار", "desc_ar", "وصف كامل", "desc_en", "Full description",
                    "meta_title_ar", "عنوان", "meta_desc_ar", "وصف السيو",
                    "meta_title_en", "Title", "meta_desc_en", "Meta desc",
                    "slug", "iron-bar-stool-" + tag.toLowerCase(),
                    "price", "2650", "compare_at", "3000.5", "stock", "7",
                    "spec_width_cm", "50", "spec_body_color", "أبيض", "spec_swivel", "لا",
                    "spec_material", "حديد", "spec_comfort_level", "8"));
            ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "B", "category", categoryName,
                    "name_ar", "كرسي بار حديد أسود", "price", "٣٬٧٠٠"));
        });

        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREVIEW"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andExpect(jsonPath("$.canSubmit").value(true))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        // Review: every row, with its parsed values.
        mvc.perform(get(URL + "/" + batchId + "/rows").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].sku").value("IMP" + tag + "A"))
                .andExpect(jsonPath("$.content[0].action").value("CREATE"))
                .andExpect(jsonPath("$.content[0].data.nameAr").value("كرسي بار حديد أبيض"))
                .andExpect(jsonPath("$.content[0].data.price").value(2650))
                .andExpect(jsonPath("$.content[0].data.fulfillmentType").value("MADE_TO_ORDER"))
                .andExpect(jsonPath("$.content[0].data.specs.length()").value(5))
                .andExpect(jsonPath("$.content[0].specifications.length()").value(29))
                .andExpect(jsonPath("$.content[1].data.price").value(3700));

        // Nothing is in the catalogue yet.
        assertThat(skuCount()).isZero();

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        Map<String, Object> a = jdbc.queryForMap(
                "SELECT p.id, p.status, p.fulfillment_type, p.slug, p.category_id, "
                        + "v.price, v.compare_at_price, v.sku "
                        + "FROM product_variant v JOIN product p ON p.id = v.product_id WHERE v.sku = ?",
                "IMP" + tag + "A");
        assertThat(a.get("status")).isEqualTo("DRAFT");
        assertThat(a.get("fulfillment_type")).isEqualTo("MADE_TO_ORDER");
        assertThat(a.get("slug")).isEqualTo("iron-bar-stool-" + tag.toLowerCase());
        assertThat(((Number) a.get("category_id")).longValue()).isEqualTo(categoryId);
        assertThat((BigDecimal) a.get("price")).isEqualByComparingTo("2650");
        assertThat((BigDecimal) a.get("compare_at_price")).isEqualByComparingTo("3000.5");
        assertThat(a.get("sku")).isEqualTo("IMP" + tag + "A");   // stored upper-case

        long productA = ((Number) a.get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT name FROM product_translation WHERE product_id = ? AND locale = 'ar'",
                String.class, productA)).isEqualTo("كرسي بار حديد أبيض");
        assertThat(jdbc.queryForObject("SELECT name FROM product_translation WHERE product_id = ? AND locale = 'en'",
                String.class, productA)).isEqualTo("Iron Bar Stool White");
        assertThat(jdbc.queryForObject(
                "SELECT meta_title FROM product_translation WHERE product_id = ? AND locale = 'ar'",
                String.class, productA)).isEqualTo("عنوان");
        assertThat(jdbc.queryForObject("SELECT search_text FROM product_translation "
                + "WHERE product_id = ? AND locale = 'ar'", String.class, productA)).contains("كرسي");

        assertThat(specs(productA)).containsEntry("WIDTH_CM", "50").containsEntry("BODY_COLOR", "أبيض")
                .containsEntry("SWIVEL", "لا").containsEntry("MATERIAL", "حديد")
                .containsEntry("COMFORT_LEVEL", "8");

        assertThat(jdbc.queryForObject("SELECT i.qty_on_hand FROM inventory i JOIN product_variant v "
                + "ON v.id = i.variant_id WHERE v.sku = ?", Integer.class, "IMP" + tag + "A")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement m JOIN product_variant v "
                + "ON v.id = m.variant_id WHERE v.sku = ? AND m.quantity_delta = 7", Integer.class,
                "IMP" + tag + "A")).isEqualTo(1);
        // No opening stock still gets an inventory row.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory i JOIN product_variant v "
                + "ON v.id = i.variant_id WHERE v.sku = ?", Integer.class, "IMP" + tag + "B")).isEqualTo(1);

        mvc.perform(get(URL + "/" + batchId).with(admin()))
                .andExpect(jsonPath("$.status").value("COMMITTED"));
        mvc.perform(get(URL + "/" + batchId + "/rows").with(admin()))
                .andExpect(jsonPath("$.content[0].productId").value(productA));

        // A batch is submitted once.
        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_BATCH_NOT_PENDING"));
    }

    @Test
    @DisplayName("One bad row blocks the submit of the whole sheet, and the good rows are not saved either")
    void oneErrorBlocksEverything() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                    "name_ar", "كرسي سليم", "price", "100"));
            ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "B", "category", "فئة غير موجودة",
                    "name_ar", "كرسي بفئة خاطئة", "price", "100"));
            ImportWorkbooks.put(wb, 3, row("sku", "IMP" + tag + "C", "category", categoryName,
                    "name_ar", "كرسي بلا سعر"));
            ImportWorkbooks.put(wb, 4, row("sku", "IMP" + tag + "D", "category", categoryName,
                    "name_ar", "كرسي بسعر خاطئ", "price", "100", "compare_at", "50"));
            // A SKU with no SEO row has no category.
            wb.getSheet(ImportTemplate.SHEET_MAIN).createRow(5).createCell(0).setCellValue("IMP" + tag + "E");
        });

        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.errorRows").value(4))
                .andExpect(jsonPath("$.validRows").value(1))
                .andExpect(jsonPath("$.canSubmit").value(false))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        String errors = mvc.perform(get(URL + "/" + batchId + "/rows?status=ERROR").with(admin()))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(errors, "$.content[*].messages[*].code");
        assertThat(codes).contains("UNKNOWN_CATEGORY", "REQUIRED", "COMPARE_AT_BELOW_PRICE", "SEO_ROW_MISSING");

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(skuCount()).isZero();
    }

    @Test
    @DisplayName("Junk in a specification cell is a warning and the cell is skipped; it never blocks the product")
    void specificationJunkIsOnlyAWarning() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> ImportWorkbooks.put(wb, 1, row(
                "sku", "IMP" + tag + "A", "category", categoryName, "name_ar", "كرسي", "price", "100",
                "spec_comfort_level", "Number", "spec_extendable", "10", "spec_seat_height_cm", "لا",
                "spec_width_cm", "45")));

        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andExpect(jsonPath("$.warningRows").value(1))
                .andExpect(jsonPath("$.canSubmit").value(true))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        String rows = mvc.perform(get(URL + "/" + batchId + "/rows").with(admin()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.severity == 'WARNING')].code"))
                .contains("INVALID_NUMBER", "INVALID_VALUE");
        // Only the clean cell is kept.
        assertThat((Map<String, String>) JsonPath.read(rows, "$.content[0].data.specs"))
                .containsOnlyKeys("WIDTH_CM");

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin())).andExpect(status().isOk());
        assertThat(skuCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("The sheet's «كراسي بار» finds the shop's «كراسي البار»")
    void categoryNameIgnoresTheArticle() throws Exception {
        jdbc.update("UPDATE category_translation SET name = ? WHERE category_id = ? AND locale = 'ar'",
                "كراسي البار " + tag, categoryId);

        byte[] sheet = ImportWorkbooks.empty(wb -> ImportWorkbooks.put(wb, 1, row(
                "sku", "IMP" + tag + "A", "category", "كراسي بار " + tag, "name_ar", "كرسي", "price", "100")));

        mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andDo(result -> batchIds.add(((Number) JsonPath.read(
                        result.getResponse().getContentAsString(), "$.id")).longValue()));
    }

    @Test
    @DisplayName("The same SKU twice in one file marks both rows")
    void duplicateSkuInFile() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                    "name_ar", "أ", "price", "10"));
            ImportWorkbooks.put(wb, 2, row("sku", "imp" + tag + "a", "category", categoryName,
                    "name_ar", "ب", "price", "10"));
        });

        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(jsonPath("$.errorRows").value(2))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        String rows = mvc.perform(get(URL + "/" + batchId + "/rows").with(admin()))
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(rows, "$.content[*].messages[*].code");
        assertThat(codes).filteredOn(c -> c.startsWith("DUPLICATE")).containsExactly("DUPLICATE_SKU", "DUPLICATE_SKU");
    }

    // --------------------------------------------------------- an existing SKU is updated

    @Test
    @DisplayName("A SKU the shop already sells is an error that names its product; a new SKU in the same row fixes it")
    void existingSkuIsAnError() throws Exception {
        commit(ImportWorkbooks.empty(wb -> ImportWorkbooks.put(wb, 1, row(
                "sku", "IMP" + tag + "A", "category", categoryName,
                "name_ar", "كرسي أصلي", "price", "1000"))));

        // The same data again, as a re-upload of an already imported sheet would be.
        String upload = mvc.perform(multipart(URL).file(file(ImportWorkbooks.empty(wb ->
                        ImportWorkbooks.put(wb, 1, row(
                                "sku", "imp" + tag + "a", "category", categoryName,
                                "name_ar", "كرسي أصلي", "price", "1000")))))
                        .with(admin()))
                .andExpect(jsonPath("$.errorRows").value(1))
                .andExpect(jsonPath("$.canSubmit").value(false))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        String rows = mvc.perform(get(URL + "/" + batchId + "/rows").with(admin()))
                .andExpect(jsonPath("$.content[0].status").value("ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'SKU_EXISTS')].field"))
                .containsExactly("sku");
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'SKU_EXISTS')].cell"))
                .containsExactly("A2");
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'SKU_EXISTS')].message"))
                .allMatch(m -> m.contains("كرسي أصلي"));

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(skuCount()).isEqualTo(1);

        // A new SKU in the editor makes the row valid, and the submit then creates the second product.
        mvc.perform(put(URL + "/" + batchId + "/rows/2").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"sku\": \"IMP" + tag + "B\"}}"))
                .andExpect(jsonPath("$.row.status").value("VALID"))
                .andExpect(jsonPath("$.batch.canSubmit").value(true));
        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        assertThat(skuCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A discarded preview cannot be submitted")
    void discard() throws Exception {
        String upload = mvc.perform(multipart(URL).file(file(ImportWorkbooks.empty(wb ->
                        ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                                "name_ar", "ك", "price", "10")))))
                        .with(admin()))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        mvc.perform(delete(URL + "/" + batchId).with(admin())).andExpect(status().isNoContent());
        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_BATCH_NOT_PENDING"));
        mvc.perform(get(URL + "/999999999").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_BATCH_NOT_FOUND"));
    }

    // ------------------------------------------------------- fixing a row in the preview

    @Test
    @DisplayName("Findings say which column and cell, and fixing the row in the preview clears the batch for submit")
    void editARowToFixIt() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                    "name_ar", "كرسي سليم", "price", "100"));
            ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "B", "category", "فئة غير موجودة",
                    "name_ar", "كرسي ينقصه سعر", "slug", "Bad Slug", "spec_width_cm", "abc"));
        });
        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(jsonPath("$.errorRows").value(1))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        // Where each problem is: column, field key and cell.
        String rows = mvc.perform(get(URL + "/" + batchId + "/rows?status=ERROR").with(admin()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'REQUIRED')].field"))
                .containsExactly("price");
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'REQUIRED')].cell"))
                .containsExactly("AN3");
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'INVALID_SLUG')].cell"))
                .containsExactly("SEO!T3");
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'UNKNOWN_CATEGORY')].column"))
                .containsExactly("القسم");
        assertThat((List<String>) JsonPath.read(rows, "$.content[0].messages[?(@.code == 'INVALID_NUMBER')].field"))
                .containsExactly("spec_width_cm");
        // The editor gets every value and every specification field.
        assertThat((Map<String, String>) JsonPath.read(rows, "$.content[0].values"))
                .containsEntry("slug", "Bad Slug").containsEntry("category", "فئة غير موجودة");

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));

        // Fix everything in one edit.
        mvc.perform(put(URL + "/" + batchId + "/rows/3").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"category\": \"" + categoryName + "\", \"price\": \"2500\", "
                                + "\"slug\": \"fixed-slug-" + tag.toLowerCase() + "\", \"spec_width_cm\": \"55\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("VALID"))
                .andExpect(jsonPath("$.row.data.price").value(2500))
                .andExpect(jsonPath("$.row.data.specs.WIDTH_CM").value("55"))
                .andExpect(jsonPath("$.batch.errorRows").value(0))
                .andExpect(jsonPath("$.batch.canSubmit").value(true));

        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));
        // What was committed is the edited row, not the uploaded cells.
        assertThat(jdbc.queryForObject("SELECT v.price FROM product_variant v WHERE v.sku = ?",
                BigDecimal.class, "IMP" + tag + "B")).isEqualByComparingTo("2500");
        assertThat(jdbc.queryForObject("SELECT p.slug FROM product p JOIN product_variant v ON v.product_id = p.id "
                + "WHERE v.sku = ?", String.class, "IMP" + tag + "B")).isEqualTo("fixed-slug-" + tag.toLowerCase());
    }

    @Test
    @DisplayName("Editing a SKU re-checks the duplicates of the whole batch: both rows clear, or both are flagged again")
    void editingADuplicateSku() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                    "name_ar", "أ", "price", "10"));
            ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "A", "category", categoryName,
                    "name_ar", "ب", "price", "10"));
        });
        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(jsonPath("$.errorRows").value(2))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        mvc.perform(put(URL + "/" + batchId + "/rows/3").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"sku\": \"IMP" + tag + "B\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.errorRows").value(0))
                .andExpect(jsonPath("$.batch.canSubmit").value(true));
        // The OTHER row was cleared too, not only the one edited.
        mvc.perform(get(URL + "/" + batchId + "/rows?status=ERROR").with(admin()))
                .andExpect(jsonPath("$.totalElements").value(0));

        // And putting the same SKU back flags both again.
        mvc.perform(put(URL + "/" + batchId + "/rows/3").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"sku\": \"imp" + tag + "a\"}}"))
                .andExpect(jsonPath("$.batch.errorRows").value(2));
    }

    @Test
    @DisplayName("A bad edit comes back as findings, not as a rejected request, and the batch stays blocked")
    void aBadEditIsReported() throws Exception {
        String upload = mvc.perform(multipart(URL).file(file(ImportWorkbooks.empty(wb ->
                        ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                                "name_ar", "كرسي", "price", "100")))))
                        .with(admin()))
                .andExpect(jsonPath("$.canSubmit").value(true))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        mvc.perform(put(URL + "/" + batchId + "/rows/2").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"price\": \"abc\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("ERROR"))
                .andExpect(jsonPath("$.row.messages[?(@.code == 'INVALID_NUMBER')].field").value("price"))
                .andExpect(jsonPath("$.batch.canSubmit").value(false));
    }

    @Test
    @DisplayName("Editing refuses an unknown field, an unknown row, and a batch that is no longer a preview")
    void editGuards() throws Exception {
        String upload = mvc.perform(multipart(URL).file(file(ImportWorkbooks.empty(wb ->
                        ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                                "name_ar", "كرسي", "price", "100")))))
                        .with(admin()))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        mvc.perform(put(URL + "/" + batchId + "/rows/2").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"price_ag\": \"5\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put(URL + "/" + batchId + "/rows/99").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_ROW_NOT_FOUND"));

        mvc.perform(delete(URL + "/" + batchId).with(admin())).andExpect(status().isNoContent());
        mvc.perform(put(URL + "/" + batchId + "/rows/2").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"price\": \"5\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_BATCH_NOT_PENDING"));
    }

    // ------------------------------------------- order, counts, SKU check

    @Test
    @DisplayName("Rows with errors come first, then the valid ones, each group in sheet order; the counts add up")
    void invalidRowsComeFirst() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName, "name_ar", "أ", "price", "10"));
            ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "B", "category", categoryName, "name_ar", "ب"));
            ImportWorkbooks.put(wb, 3, row("sku", "IMP" + tag + "C", "category", categoryName, "name_ar", "ج", "price", "10"));
            ImportWorkbooks.put(wb, 4, row("sku", "IMP" + tag + "D", "category", "لا توجد", "name_ar", "د", "price", "10"));
        });
        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.errorRows").value(2))
                .andExpect(jsonPath("$.validRows").value(2))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        String rows = mvc.perform(get(URL + "/" + batchId + "/rows").with(admin()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<Integer>) JsonPath.read(rows, "$.content[*].rowNo")).containsExactly(3, 5, 2, 4);
        assertThat((List<String>) JsonPath.read(rows, "$.content[*].status"))
                .containsExactly("ERROR", "ERROR", "VALID", "VALID");

        // Fix one of them: valid + invalid is still the total, and it is the one count the screen shows.
        mvc.perform(put(URL + "/" + batchId + "/rows/3").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"price\": \"20\"}}"))
                .andExpect(jsonPath("$.batch.errorRows").value(1))
                .andExpect(jsonPath("$.batch.validRows").value(3))
                .andExpect(jsonPath("$.batch.totalRows").value(4));
    }

    @Test
    @DisplayName("The SKU check says unique, taken by another row, or already sold in the catalogue")
    void skuCheck() throws Exception {
        commit(ImportWorkbooks.empty(wb -> ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "OLD",
                "category", categoryName, "name_ar", "قديم", "price", "10"))));

        String upload = mvc.perform(multipart(URL).file(file(ImportWorkbooks.empty(wb -> {
                        ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName,
                                "name_ar", "أ", "price", "10"));
                        ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "B", "category", categoryName,
                                "name_ar", "ب", "price", "10"));
                    })))
                        .with(admin()))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);
        String url = URL + "/" + batchId + "/sku-check";

        // Free, and a row never clashes with itself.
        mvc.perform(get(url).param("sku", "IMP" + tag + "NEW").param("rowNo", "2").with(admin()))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(get(url).param("sku", "imp" + tag + "a").param("rowNo", "2").with(admin()))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        // Another row has it.
        mvc.perform(get(url).param("sku", "IMP" + tag + "B").param("rowNo", "2").with(admin()))
                .andExpect(jsonPath("$.status").value("DUPLICATE_IN_FILE"))
                .andExpect(jsonPath("$.rowNos[0]").value(3));
        // The shop already sells it: the row would update that product.
        mvc.perform(get(url).param("sku", "IMP" + tag + "OLD").param("rowNo", "2").with(admin()))
                .andExpect(jsonPath("$.status").value("IN_CATALOG"))
                .andExpect(jsonPath("$.productName").value("قديم"));
        mvc.perform(get(url).param("sku", "  ").with(admin()))
                .andExpect(jsonPath("$.status").value("EMPTY"));
    }

    @Test
    @DisplayName("An edit that clears a duplicate returns the other row it cleared, so the screen can update it in place")
    void editReturnsAffectedRows() throws Exception {
        byte[] sheet = ImportWorkbooks.empty(wb -> {
            ImportWorkbooks.put(wb, 1, row("sku", "IMP" + tag + "A", "category", categoryName, "name_ar", "أ", "price", "10"));
            ImportWorkbooks.put(wb, 2, row("sku", "IMP" + tag + "A", "category", categoryName, "name_ar", "ب", "price", "10"));
        });
        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);

        mvc.perform(put(URL + "/" + batchId + "/rows/3").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\": {\"sku\": \"IMP" + tag + "B\"}}"))
                .andExpect(jsonPath("$.row.status").value("VALID"))
                .andExpect(jsonPath("$.affected.length()").value(1))
                .andExpect(jsonPath("$.affected[0].rowNo").value(2))
                .andExpect(jsonPath("$.affected[0].status").value("VALID"));
    }

    // ---------------------------------------------------------------------- helpers

    private void commit(byte[] sheet) throws Exception {
        String upload = mvc.perform(multipart(URL).file(file(sheet)).with(admin()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long batchId = trackBatch(upload);
        mvc.perform(post(URL + "/" + batchId + "/commit").with(admin())).andExpect(status().isOk());
    }

    private Map<String, String> specs(long productId) {
        Map<String, String> specs = new LinkedHashMap<>();
        jdbc.query("SELECT a.code, pav.value_text FROM product_attribute_value pav "
                        + "JOIN attribute a ON a.id = pav.attribute_id WHERE pav.product_id = ?",
                rs -> { specs.put(rs.getString(1), rs.getString(2)); }, productId);
        return specs;
    }

    private long trackBatch(String json) {
        long id = ((Number) JsonPath.read(json, "$.id")).longValue();
        batchIds.add(id);
        return id;
    }

    private int skuCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_variant WHERE sku LIKE ?", Integer.class,
                "IMP" + tag + "%");
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "products.xlsx", XLSX_TYPE, bytes);
    }

    private RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(actor, "import@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
