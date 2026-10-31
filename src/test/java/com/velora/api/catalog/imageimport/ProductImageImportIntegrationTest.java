package com.velora.api.catalog.imageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest.VariantItem;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.catalog.service.admin.VariantAdminService;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.storage.StorageTransactionSupport;
import com.velora.api.identity.security.UserPrincipal;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The image import against the real (test) database and storage: the match report, attaching by file name,
 * the three modes, the checks on the bytes, the 20-image limit under concurrency, and the cleanup of a file
 * whose transaction does not commit.
 */
@SpringBootTest
class ProductImageImportIntegrationTest {

    private static final String MATCH = "/api/v1/admin/product-images/match";
    private static final String BULK = "/api/v1/admin/product-images/bulk";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProductAdminService productService;
    @Autowired private VariantAdminService variantService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private StorageService storage;
    @Autowired private StorageTransactionSupport storageSupport;
    @Autowired private TransactionTemplate tx;

    private MockMvc mvc;
    private String tag;
    private Long categoryId;
    private final List<Long> productIds = new ArrayList<>();
    private int seed;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Category category = new Category();
        category.setSlug("img-imp-" + tag.toLowerCase());
        category.setActive(true);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : productIds) {
            // Files first: the rows say which ones they are.
            jdbc.queryForList("SELECT url FROM product_image WHERE product_id = ?", String.class, id)
                    .forEach(storage::delete);
            jdbc.update("DELETE FROM product_image WHERE product_id = ?", id);
            jdbc.update("DELETE FROM stock_movement WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", id);
            jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", id);
            jdbc.update("DELETE FROM product_variant WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    // ----------------------------------------------------------------- fixtures

    private String sku(String suffix) {
        return "IM" + tag + suffix;
    }

    private long product(String suffix, String nameAr, String nameEn) {
        List<TranslationRequest> translations = new ArrayList<>();
        translations.add(new TranslationRequest("ar", nameAr, null, null, null, null));
        if (nameEn != null) {
            translations.add(new TranslationRequest("en", nameEn, null, null, null, null));
        }
        long id = productService.create(new ProductCreateRequest(categoryId, null, null, translations, false, false,
                null, FulfillmentType.MADE_TO_ORDER, null)).id();
        productIds.add(id);
        variantService.saveVariants(id, new VariantSaveRequest(List.of(new VariantItem(null, sku(suffix), null,
                new BigDecimal("100.00"), null, null, null, null, List.of(), null, null))), null);
        return id;
    }

    /** A real PNG; every seed gives different bytes, so a different hash. */
    private byte[] png(int s) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                image.setRGB(x, y, new Color((s * 37) % 256, (s * 91 + x) % 256, (s * 13 + y) % 256).getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] newPng() throws Exception {
        return png(++seed);
    }

    private static MockMultipartFile file(String name, byte[] bytes, String type) {
        return new MockMultipartFile("files", name, type, bytes);
    }

    private MockMultipartFile pngFile(String name) throws Exception {
        return file(name, newPng(), "image/png");
    }

    private JsonNode bulk(String runId, String mode, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(BULK);
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        String body = mvc.perform(request.param("runId", runId).param("mode", mode).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    private JsonNode match(String mode, String... names) throws Exception {
        StringBuilder files = new StringBuilder();
        for (String n : names) {
            files.append(files.isEmpty() ? "" : ",").append("{\"name\":\"").append(n).append("\",\"sizeBytes\":1000}");
        }
        String body = mvc.perform(post(MATCH).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"" + mode + "\",\"files\":[" + files + "]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    private List<Map<String, Object>> images(long productId) {
        return jdbc.queryForList("SELECT id, url, is_main, display_order, alt_text_ar, alt_text_en, variant_id, "
                + "content_hash, import_run_id FROM product_image WHERE product_id = ? ORDER BY display_order",
                productId);
    }

    private static String run() {
        return UUID.randomUUID().toString();
    }

    // -------------------------------------------------------------------- match

    @Test
    @DisplayName("The match report: matched, extra images, near misses, unsupported formats, clashes and products with no image")
    void matchReport() throws Exception {
        long a = product("A", "كرسي أ", "Chair A");
        long b = product("B", "كرسي ب", "Chair B");
        product("C", "كرسي ج", "Chair C");

        JsonNode r = match("SKIP",
                sku("A") + ".jpg", sku("A").toLowerCase() + "_2.PNG",
                sku("B") + ".png", sku("B") + ".webp",
                sku("A") + " (1).jpg" + "",
                "NOPE999.jpg", "Thumbs.db", sku("C") + ".heic");

        assertThat(r.at("/summary/files").asInt()).isEqualTo(8);
        JsonNode products = r.get("products");
        JsonNode pa = products.get(0);
        assertThat(pa.get("sku").asText()).isEqualTo(sku("A"));
        assertThat(pa.get("productId").asLong()).isEqualTo(a);
        assertThat(pa.get("nameAr").asText()).isEqualTo("كرسي أ");
        assertThat(pa.get("action").asText()).isEqualTo("ATTACH");
        assertThat(pa.get("files").size()).isEqualTo(2);
        assertThat(pa.at("/files/0/position").asInt()).isEqualTo(1);
        assertThat(pa.at("/files/1/position").asInt()).isEqualTo(2);
        assertThat(pa.at("/files/1/upload").asBoolean()).isTrue();

        // Two files for position 1 of B: neither is used.
        JsonNode conflict = r.get("conflicts").get(0);
        assertThat(conflict.get("sku").asText()).isEqualTo(sku("B"));
        assertThat(conflict.get("reason").asText()).isEqualTo("DUPLICATE_POSITION");
        assertThat(conflict.get("files").size()).isEqualTo(2);
        JsonNode pb = products.get(1);
        assertThat(pb.at("/files/0/upload").asBoolean()).isFalse();
        assertThat(pb.at("/files/0/problem").asText()).isEqualTo("DUPLICATE_POSITION");

        Map<String, JsonNode> unmatched = new java.util.HashMap<>();
        r.get("unmatched").forEach(u -> unmatched.put(u.get("name").asText(), u));
        // A near miss is suggested, never attached.
        assertThat(unmatched.get(sku("A") + " (1).jpg").get("reason").asText()).isEqualTo("NO_PRODUCT");
        assertThat(unmatched.get(sku("A") + " (1).jpg").get("suggestionSku").asText()).isEqualTo(sku("A"));
        // Null fields are stripped from the response, so «no suggestion» is an absent key.
        assertThat(unmatched.get("NOPE999.jpg").has("suggestionSku")).isFalse();
        assertThat(unmatched.get("Thumbs.db").get("reason").asText()).isEqualTo("NOT_AN_IMAGE");
        // HEIC: clearly unsupported, and the SKU it belongs to is named.
        assertThat(unmatched.get(sku("C") + ".heic").get("reason").asText()).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(unmatched.get(sku("C") + ".heic").get("sku").asText()).isEqualTo(sku("C"));

        // Product C got nothing usable, so it is listed as still without an image; A and B are not
        // (A has uploadable files; B's are in conflict, so it stays without one).
        List<Long> without = new ArrayList<>();
        r.at("/productsWithoutImage/items").forEach(i -> without.add(i.get("productId").asLong()));
        assertThat(without).contains(b).doesNotContain(a);
    }

    @Test
    @DisplayName("Mode SKIP flags a product that already has images; ADD and REPLACE do not")
    void matchModes() throws Exception {
        long a = product("A", "كرسي أ", null);
        jdbc.update("INSERT INTO product_image (product_id, url, is_main, display_order) VALUES (?, 'products/x/old.png', 1, 0)", a);

        assertThat(match("SKIP", sku("A") + ".jpg").at("/products/0/action").asText()).isEqualTo("SKIP_HAS_IMAGES");
        assertThat(match("SKIP", sku("A") + ".jpg").at("/products/0/files/0/upload").asBoolean()).isFalse();
        assertThat(match("SKIP", sku("A") + ".jpg").at("/summary/productsSkipped").asInt()).isEqualTo(1);
        assertThat(match("ADD", sku("A") + ".jpg").at("/products/0/action").asText()).isEqualTo("ATTACH");
        assertThat(match("REPLACE", sku("A") + ".jpg").at("/products/0/existingImageCount").asInt()).isEqualTo(1);
    }

    // -------------------------------------------------------------------- attach

    @Test
    @DisplayName("SKU.ext is the main image even when SKU_2 arrives first, in another request; alt text, hash and run id are stored")
    void attachesInAnyOrder() throws Exception {
        long a = product("A", "كرسي بار", "Bar stool");
        String run = run();

        JsonNode first = bulk(run, "SKIP", pngFile(sku("A") + "_2.jpg"));
        assertThat(first.at("/counts/attached").asInt()).isEqualTo(1);
        // Same run, later request: the product already has an image, but it is THIS run's own.
        JsonNode second = bulk(run, "SKIP", pngFile(sku("A").toLowerCase() + ".JPG"));
        assertThat(second.at("/results/0/status").asText()).isEqualTo("ATTACHED");
        assertThat(second.at("/results/0/url").asText()).contains("/uploads/products/");

        List<Map<String, Object>> rows = images(a);
        assertThat(rows).hasSize(2);
        // The base image is first and the only main one.
        assertThat(rows.get(0).get("display_order")).isEqualTo((short) 0);
        assertThat((Boolean) rows.get(0).get("is_main")).isTrue();
        assertThat((Boolean) rows.get(1).get("is_main")).isFalse();
        assertThat(rows.get(0).get("alt_text_ar")).isEqualTo("كرسي بار");
        assertThat(rows.get(0).get("alt_text_en")).isEqualTo("Bar stool");
        assertThat(rows.get(1).get("alt_text_en")).isEqualTo("Bar stool - 2");
        assertThat(rows.get(0).get("import_run_id")).isEqualTo(run);
        assertThat((String) rows.get(0).get("content_hash")).hasSize(64);
        // The file really is on disk, under a generated name: the file name sent is never the stored name.
        assertThat(storage.exists((String) rows.get(0).get("url"))).isTrue();
        assertThat((String) rows.get(0).get("url")).doesNotContain(sku("A"));
        assertThat(rows.get(0).get("variant_id")).isNull();
    }

    @Test
    @DisplayName("SKIP leaves a product that had images before the run alone")
    void skipLeavesExistingAlone() throws Exception {
        long a = product("A", "كرسي أ", null);
        bulk(run(), "SKIP", pngFile(sku("A") + ".png"));
        assertThat(images(a)).hasSize(1);

        JsonNode again = bulk(run(), "SKIP", pngFile(sku("A") + ".png"));
        assertThat(again.at("/results/0/status").asText()).isEqualTo("SKIPPED");
        assertThat(again.at("/results/0/code").asText()).isEqualTo("HAS_IMAGES");
        assertThat(images(a)).hasSize(1);
    }

    @Test
    @DisplayName("ADD appends after the existing images and never attaches the same picture twice")
    void addAppendsAndDeduplicates() throws Exception {
        long a = product("A", "كرسي أ", null);
        byte[] picture = newPng();
        bulk(run(), "SKIP", file(sku("A") + ".png", picture, "image/png"));

        JsonNode added = bulk(run(), "ADD", pngFile(sku("A") + ".png"));
        assertThat(added.at("/results/0/status").asText()).isEqualTo("ATTACHED");
        List<Map<String, Object>> rows = images(a);
        assertThat(rows).hasSize(2);
        assertThat((Boolean) rows.get(0).get("is_main")).isTrue();
        assertThat(rows.get(1).get("display_order")).isEqualTo((short) 1);

        // The very same picture again, in a new run.
        JsonNode dup = bulk(run(), "ADD", file(sku("A") + ".png", picture, "image/png"));
        assertThat(dup.at("/results/0/status").asText()).isEqualTo("SKIPPED");
        assertThat(dup.at("/results/0/code").asText()).isEqualTo("DUPLICATE_IMAGE");
        assertThat(images(a)).hasSize(2);
    }

    @Test
    @DisplayName("REPLACE removes the images from before the run, rows and files, and keeps the run's own")
    void replaceRemovesOldOnes() throws Exception {
        long a = product("A", "كرسي أ", null);
        bulk(run(), "SKIP", pngFile(sku("A") + ".png"), pngFile(sku("A") + "_2.png"));
        List<Map<String, Object>> old = images(a);
        assertThat(old).hasSize(2);

        String run = run();
        bulk(run, "REPLACE", pngFile(sku("A") + ".png"));
        // A second request of the same run must not delete what the first one just added.
        bulk(run, "REPLACE", pngFile(sku("A") + "_2.png"));

        List<Map<String, Object>> now = images(a);
        assertThat(now).hasSize(2);
        assertThat(now).allSatisfy(r -> assertThat(r.get("import_run_id")).isEqualTo(run));
        for (Map<String, Object> o : old) {
            assertThat(storage.exists((String) o.get("url"))).as("old file removed").isFalse();
        }
        for (Map<String, Object> n : now) {
            assertThat(storage.exists((String) n.get("url"))).isTrue();
        }
        assertThat((Boolean) now.get(0).get("is_main")).isTrue();
    }

    @Test
    @DisplayName("A file that is not a picture, HEIC, an unknown SKU or an archived product is rejected; the others still go in")
    void badFilesDoNotStopTheRest() throws Exception {
        long a = product("A", "كرسي أ", null);
        long archived = product("Z", "مؤرشف", null);
        productService.archive(archived);

        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1'};
        JsonNode r = bulk(run(), "SKIP",
                file(sku("A") + "_2.jpg", html, "image/jpeg"),       // HTML under a picture's name
                file(sku("A") + "_3.jpg", heic, "image/jpeg"),       // a phone photo
                pngFile("NOSUCH" + tag + ".png"),
                pngFile(sku("Z") + ".png"),
                file("Thumbs.db", new byte[] {1, 2, 3}, "application/octet-stream"),
                pngFile(sku("A") + ".png"));

        JsonNode results = r.get("results");
        assertThat(results.get(0).get("code").asText()).isEqualTo("NOT_AN_IMAGE");
        assertThat(results.get(1).get("code").asText()).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(results.get(2).get("code").asText()).isEqualTo("NO_MATCH");
        assertThat(results.get(3).get("code").asText()).isEqualTo("PRODUCT_ARCHIVED");
        assertThat(results.get(4).get("code").asText()).isEqualTo("NOT_AN_IMAGE");
        assertThat(results.get(5).get("status").asText()).isEqualTo("ATTACHED");
        assertThat(r.at("/counts/attached").asInt()).isEqualTo(1);
        assertThat(r.at("/counts/rejected").asInt()).isEqualTo(5);
        assertThat(images(a)).hasSize(1);
        assertThat(images(archived)).isEmpty();
    }

    @Test
    @DisplayName("Two files for the same place in one run: the second is refused, not silently stacked")
    void samePositionTwiceInOneRun() throws Exception {
        long a = product("A", "كرسي أ", null);
        String run = run();
        bulk(run, "SKIP", pngFile(sku("A") + ".png"));
        JsonNode r = bulk(run, "SKIP", pngFile(sku("A") + ".jpg"));
        assertThat(r.at("/results/0/code").asText()).isEqualTo("DUPLICATE_POSITION");
        assertThat(images(a)).hasSize(1);
    }

    @Test
    @DisplayName("At most 20 images per product")
    void twentyImageLimit() throws Exception {
        long a = product("A", "كرسي أ", null);
        for (int i = 0; i < 20; i++) {
            jdbc.update("INSERT INTO product_image (product_id, url, is_main, display_order) VALUES (?, ?, ?, ?)",
                    a, "products/x/old" + i + ".png", i == 0, i);
        }
        JsonNode r = bulk(run(), "ADD", pngFile(sku("A") + ".png"));
        assertThat(r.at("/results/0/code").asText()).isEqualTo("LIMIT_REACHED");
        assertThat(images(a)).hasSize(20);
    }

    @Test
    @DisplayName("Two requests racing for the last free place: exactly one gets it")
    void limitHoldsUnderConcurrency() throws Exception {
        long a = product("A", "كرسي أ", null);
        for (int i = 0; i < 19; i++) {
            jdbc.update("INSERT INTO product_image (product_id, url, is_main, display_order) VALUES (?, ?, ?, ?)",
                    a, "products/x/old" + i + ".png", i == 0, i);
        }
        MockMultipartFile one = pngFile(sku("A") + ".png");
        MockMultipartFile two = pngFile(sku("A") + ".png");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JsonNode> c1 = () -> bulk(run(), "ADD", one);
            Callable<JsonNode> c2 = () -> bulk(run(), "ADD", two);
            Future<JsonNode> f1 = pool.submit(c1);
            Future<JsonNode> f2 = pool.submit(c2);
            List<String> codes = List.of(
                    f1.get().at("/results/0/status").asText() + f1.get().at("/results/0/code").asText(),
                    f2.get().at("/results/0/status").asText() + f2.get().at("/results/0/code").asText());
            assertThat(codes).containsExactlyInAnyOrder("ATTACHED", "REJECTEDLIMIT_REACHED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(images(a)).hasSize(20);
        assertThat(images(a).stream().filter(r -> (Boolean) r.get("is_main")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("A product with several variants gets the image on the variant its file name carried")
    void variantImage() throws Exception {
        long a = product("A", "كرسي أ", null);
        variantService.saveVariants(a, new VariantSaveRequest(List.of(
                new VariantItem(null, sku("A2"), null, new BigDecimal("100.00"), null, null, null, null, List.of(), null, null)
        )), null);
        bulk(run(), "SKIP", pngFile(sku("A2") + ".png"));
        Long variantId = jdbc.queryForObject("SELECT id FROM product_variant WHERE sku = ?", Long.class, sku("A2"));
        assertThat(images(a).get(0).get("variant_id")).isEqualTo(variantId);
    }

    // ------------------------------------------------------- transactions & files

    @Test
    @DisplayName("A file written in a transaction that rolls back is removed; one that commits stays")
    void fileFollowsItsTransaction() {
        String rolledBack = storeOne();
        try {
            tx.executeWithoutResult(s -> {
                storageSupport.deleteUnlessCommitted(rolledBack);
                s.setRollbackOnly();
            });
        } finally {
            assertThat(storage.exists(rolledBack)).isFalse();
        }

        String committed = storeOne();
        tx.executeWithoutResult(s -> storageSupport.deleteUnlessCommitted(committed));
        assertThat(storage.exists(committed)).isTrue();
        storage.delete(committed);
    }

    @Test
    @DisplayName("A replaced file is deleted only after the commit; a rollback keeps it")
    void replacedFileGoesAfterCommit() {
        String key = storeOne();
        tx.executeWithoutResult(s -> {
            storageSupport.deleteAfterCommit(key);
            s.setRollbackOnly();
        });
        assertThat(storage.exists(key)).isTrue();

        tx.executeWithoutResult(s -> storageSupport.deleteAfterCommit(key));
        assertThat(storage.exists(key)).isFalse();
    }

    private String storeOne() {
        try {
            return storage.store(new MockMultipartFile("file", "x.png", "image/png", newPng()), "products").key();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ----------------------------------------------------------- guards & warnings

    @Test
    @DisplayName("Only an admin; a bad run id, no files, too many files or no names are refused")
    void guards() throws Exception {
        mvc.perform(post(MATCH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SKIP\",\"files\":[{\"name\":\"a.jpg\",\"sizeBytes\":1}]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(BULK).file(pngFile("a.png")).param("runId", run()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(MATCH).with(customer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SKIP\",\"files\":[{\"name\":\"a.jpg\",\"sizeBytes\":1}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(BULK).file(pngFile("a.png")).param("runId", run()).with(customer()))
                .andExpect(status().isForbidden());

        mvc.perform(post(MATCH).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SKIP\",\"files\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(BULK).file(pngFile("a.png")).param("runId", "not-a-uuid").with(admin()))
                .andExpect(status().isBadRequest());

        MockMultipartHttpServletRequestBuilder tooMany = multipart(BULK);
        for (int i = 0; i < 13; i++) {
            tooMany.file(pngFile("f" + i + ".png"));
        }
        mvc.perform(tooMany.param("runId", run()).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_IMPORT_TOO_MANY_FILES"));
    }

    @Test
    @DisplayName("Publishing a product with no image works and WARNS; one with an image does not warn")
    void publishWarnsAboutMissingImage() throws Exception {
        long bare = product("A", "بلا صورة", null);
        long photographed = product("B", "بصورة", null);
        bulk(run(), "SKIP", pngFile(sku("B") + ".png"));

        mvc.perform(post("/api/v1/admin/products/bulk-status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\",\"ids\":[" + bare + "," + photographed + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].id").value(bare))
                .andExpect(jsonPath("$.warnings[0].code").value("NO_IMAGE"));
        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, bare)).isEqualTo("ACTIVE");
    }

    private static RequestPostProcessor admin() {
        return user(List.of("ADMIN"));
    }

    private static RequestPostProcessor customer() {
        return user(List.of("CUSTOMER"));
    }

    private static RequestPostProcessor user(List<String> roles) {
        UserPrincipal principal = UserPrincipal.of(999_021L, "img@example.com", null, roles);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }
}
