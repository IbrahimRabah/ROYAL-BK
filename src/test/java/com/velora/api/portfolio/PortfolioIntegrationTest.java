package com.velora.api.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.domain.AuditLog;
import com.velora.api.audit.repository.AuditLogRepository;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.common.storage.StorageService;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.testsupport.TestImages;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The portfolio over HTTP on the real database: slugs, the publish and archive lifecycle
 * (including coming back from the archive), the public side seeing nothing it should not, and
 * images held to the same byte-level rules as product images.
 */
@SpringBootTest
class PortfolioIntegrationTest {

    private static final String ADMIN = "/api/v1/admin/portfolio";
    private static final String PUBLIC = "/api/v1/portfolio";

    @Autowired private WebApplicationContext context;
    @Autowired private StorageService storageService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String tag;
    private Long categoryId;
    private final List<Long> itemIds = new ArrayList<>();
    private final List<String> imageKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        Category category = new Category();
        category.setSlug("portfolio-cat-" + tag);
        category.setActive(false);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE entity_type = 'PORTFOLIO_ITEM' AND actor_id = 999001");
        jdbc.query("SELECT url FROM portfolio_image WHERE portfolio_item_id IN "
                + "(SELECT id FROM portfolio_item WHERE slug LIKE ?)",
                rs -> { imageKeys.add(rs.getString(1)); }, "%" + tag + "%");
        imageKeys.forEach(storageService::delete);
        jdbc.update("DELETE FROM portfolio_image WHERE portfolio_item_id IN "
                + "(SELECT id FROM portfolio_item WHERE slug LIKE ?)", "%" + tag + "%");
        jdbc.update("DELETE FROM portfolio_item WHERE slug LIKE ?", "%" + tag + "%");
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    // ================================================================== slug

    @Test
    @DisplayName("The slug is generated from the Arabic title; a repeated title gets -2, -3")
    void slugFromArabicTitle() throws Exception {
        long first = create(title(), null);
        long second = create(title(), null);
        long third = create(title(), null);

        String base = slugOf(first);
        assertThat(base).matches("[a-z0-9-]+").endsWith(tag);
        assertThat(slugOf(second)).isEqualTo(base + "-2");
        assertThat(slugOf(third)).isEqualTo(base + "-3");
    }

    @Test
    @DisplayName("An explicit slug is normalised; one that is already taken is a 409, not silently renamed")
    void overrideSlug() throws Exception {
        long id = create(title(), "Iron Gate " + tag);
        assertThat(slugOf(id)).isEqualTo("iron-gate-" + tag);

        mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title(), "iron-gate-" + tag)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLUG_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("A title that gives no Latin slug and no override is a 400 that says what to do")
    void noSlugDerivable() throws Exception {
        mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("!!!", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Update can change the slug (taken -> 409) and keeps it when omitted")
    void updateSlug() throws Exception {
        long a = create(title(), null);
        long b = create(title(), null);
        String keep = slugOf(a);

        mvc.perform(put(ADMIN + "/{id}", a).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title(), null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(keep));

        mvc.perform(put(ADMIN + "/{id}", a).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title(), slugOf(b))))
                .andExpect(status().isConflict());

        mvc.perform(put(ADMIN + "/{id}", a).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title(), "renamed-" + tag)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("renamed-" + tag));
    }

    // ================================================================ lifecycle

    @Test
    @DisplayName("A new item is a draft: invisible to the public until published")
    void draftIsInvisible() throws Exception {
        long id = create(title(), null);
        String slug = slugOf(id);

        mvc.perform(get(PUBLIC + "/{slug}", slug)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_ITEM_NOT_FOUND"));
        assertThat(publicSlugs()).doesNotContain(slug);

        setPublished(id, true).andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));

        mvc.perform(get(PUBLIC + "/{slug}", slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug));
        assertThat(publicSlugs()).contains(slug);

        setPublished(id, false).andExpect(status().isOk());
        mvc.perform(get(PUBLIC + "/{slug}", slug)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Publishing twice is the same as once: explicit state, not a toggle")
    void publishIsNotAToggle() throws Exception {
        long id = create(title(), null);
        setPublished(id, true);
        setPublished(id, true).andExpect(jsonPath("$.published").value(true));
        mvc.perform(patch(ADMIN + "/{id}/publish", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Archive hides it, forces it unpublished, keeps the row, and is harmless to repeat")
    void archive() throws Exception {
        long id = create(title(), null);
        String slug = slugOf(id);
        setPublished(id, true);

        mvc.perform(delete(ADMIN + "/{id}", id).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        mvc.perform(delete(ADMIN + "/{id}", id).with(admin())).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_item WHERE id = ?",
                Integer.class, id)).as("never deleted").isEqualTo(1);
        mvc.perform(get(PUBLIC + "/{slug}", slug)).andExpect(status().isNotFound());
        assertThat(publicSlugs()).doesNotContain(slug);

        assertThat(adminIds(false)).doesNotContain(id);
        assertThat(adminIds(true)).contains(id);
        mvc.perform(get(ADMIN + "/{id}", id).with(admin())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An archived item cannot be published; restore brings it back as a draft, then publish works")
    void restore() throws Exception {
        long id = create(title(), null);
        String slug = slugOf(id);
        setPublished(id, true);
        mvc.perform(delete(ADMIN + "/{id}", id).with(admin()));

        setPublished(id, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_ITEM_ARCHIVED"));

        mvc.perform(patch(ADMIN + "/{id}/restore", id).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.published").value(false));
        mvc.perform(patch(ADMIN + "/{id}/restore", id).with(admin())).andExpect(status().isOk());
        mvc.perform(get(PUBLIC + "/{slug}", slug)).andExpect(status().isNotFound());

        setPublished(id, true).andExpect(status().isOk());
        mvc.perform(get(PUBLIC + "/{slug}", slug)).andExpect(status().isOk());
        assertThat(adminIds(false)).contains(id);
    }

    @Test
    @DisplayName("An archived item keeps its slug: a new item with the same title cannot take it")
    void archivedSlugStaysReserved() throws Exception {
        String title = title();
        long first = create(title, null);
        String slug = slugOf(first);
        mvc.perform(delete(ADMIN + "/{id}", first).with(admin()));

        long second = create(title, null);

        assertThat(slugOf(second)).isEqualTo(slug + "-2");
    }

    @Test
    @DisplayName("The database itself refuses an item that is both archived and published")
    void databaseRefusesLiveArchived() throws Exception {
        long id = create(title(), null);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE portfolio_item SET published = 1, archived_at = SYSDATETIMEOFFSET() WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ================================================================== audit

    @Test
    @DisplayName("Publishing, hiding, archiving and restoring are audited with who and DRAFT/LIVE/ARCHIVED; no-ops record nothing")
    void stateChangesAreAudited() throws Exception {
        long id = create(title(), null);
        assertThat(audit(id)).as("creating is not a state change").isEmpty();

        setPublished(id, true);
        setPublished(id, true);                                   // already live: nothing
        setPublished(id, false);
        setPublished(id, true);
        mvc.perform(delete(ADMIN + "/{id}", id).with(admin()));
        mvc.perform(delete(ADMIN + "/{id}", id).with(admin())); // already archived: nothing
        setPublished(id, true).andExpect(status().isConflict()); // refused: nothing
        mvc.perform(patch(ADMIN + "/{id}/restore", id).with(admin()));
        mvc.perform(patch(ADMIN + "/{id}/restore", id).with(admin())); // not archived: nothing

        List<AuditLog> entries = audit(id);
        assertThat(entries).extracting(e -> e.getOldValue() + ">" + e.getNewValue())
                .containsExactly("DRAFT>LIVE", "LIVE>DRAFT", "DRAFT>LIVE", "LIVE>ARCHIVED", "ARCHIVED>DRAFT");
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.PORTFOLIO_STATUS_CHANGED);
            assertThat(e.getEntityType()).isEqualTo("PORTFOLIO_ITEM");
            assertThat(e.getEntityLabel()).isEqualTo(slugOf(id));
            assertThat(e.getActorId()).isEqualTo(999_001L);
        });
    }

    // ================================================================== public

    @Test
    @DisplayName("The public list is a PageResponse in curator order; sort cannot be forced; category filter works")
    void publicListing() throws Exception {
        long a = create(title(), null, 20, null);
        long b = create(title(), null, 10, categoryId);
        long hidden = create(title(), null, 0, categoryId);
        setPublished(a, true);
        setPublished(b, true);

        MvcResult result = mvc.perform(get(PUBLIC).param("categoryId", categoryId.toString())
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        List<String> slugs = JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].slug");
        assertThat(slugs).containsExactly(slugOf(b));
        assertThat(publicSlugs()).doesNotContain(slugOf(hidden));

        List<String> all = publicSlugs();
        assertThat(all.indexOf(slugOf(b))).as("displayOrder 10 before 20").isLessThan(all.indexOf(slugOf(a)));
    }

    @Test
    @DisplayName("English titles are served on Accept-Language: en, falling back to Arabic when there is none")
    void localisedTitle() throws Exception {
        long withEn = create(title(), null, 0, null, "Iron Gate");
        long withoutEn = create(title(), null);
        setPublished(withEn, true);
        setPublished(withoutEn, true);

        mvc.perform(get(PUBLIC + "/{slug}", slugOf(withEn)).header("Accept-Language", "en"))
                .andExpect(jsonPath("$.title").value("Iron Gate"));
        mvc.perform(get(PUBLIC + "/{slug}", slugOf(withEn)))
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.containsString("بوابة")));
        mvc.perform(get(PUBLIC + "/{slug}", slugOf(withoutEn)).header("Accept-Language", "en"))
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.containsString("بوابة")));
    }

    @Test
    @DisplayName("Unknown category is a 404 CATEGORY_NOT_FOUND on create")
    void unknownCategory() throws Exception {
        mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"" + title() + "\",\"categoryId\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    // ================================================================= security

    @Test
    @DisplayName("Every admin route needs an admin: anonymous and customers are both refused with 403")
    void adminOnly() throws Exception {
        mvc.perform(get(ADMIN)).andExpect(status().isForbidden());
        mvc.perform(post(ADMIN).contentType(MediaType.APPLICATION_JSON).content(body(title(), null)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN).with(signedIn("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(delete(ADMIN + "/1").with(signedIn("CUSTOMER"))).andExpect(status().isForbidden());
    }

    // =================================================================== images

    @Test
    @DisplayName("Real image bytes are stored under the detected type's extension, whatever the filename says")
    void imageStoredByDetectedType() throws Exception {
        long id = create(title(), null);

        String key = upload(id, "page.html", "image/png", TestImages.png()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.main").value(true)).andReturn().getResponse().getContentAsString();
        String stored = JsonPath.read(key, "$.key");

        assertThat(stored).startsWith("portfolio/").endsWith(".png").doesNotContain("page");
        assertThat(storageService.exists(stored)).isTrue();
        assertThat((String) JsonPath.read(key, "$.url")).isNotBlank();
    }

    @Test
    @DisplayName("HTML or a script sent as an image is refused by its bytes, and nothing is stored")
    void disguisedFileRefused() throws Exception {
        long id = create(title(), null);

        upload(id, "evil.png", "image/png", TestImages.html()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        upload(id, "empty.png", "image/png", new byte[0]).andExpect(status().isBadRequest());
        upload(id, "big.png", "image/png", TestImages.padded(TestImages.png(), 5 * 1024 * 1024 + 1))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_image WHERE portfolio_item_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("All four formats are accepted")
    void allFormats() throws Exception {
        long id = create(title(), null);
        upload(id, "a.jpg", "image/jpeg", TestImages.jpeg()).andExpect(status().isCreated());
        upload(id, "b.png", "image/png", TestImages.png()).andExpect(status().isCreated());
        upload(id, "c.webp", "image/webp", TestImages.webp()).andExpect(status().isCreated());
        upload(id, "d.avif", "image/avif", TestImages.avif()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("First image is main; main can move; deleting the main promotes another and removes the file")
    void mainImageAndDelete() throws Exception {
        long id = create(title(), null);
        String k1 = keyOf(upload(id, "1.png", "image/png", TestImages.png()));
        String k2 = keyOf(upload(id, "2.jpg", "image/jpeg", TestImages.jpeg()));
        long img1 = imageId(id, k1);
        long img2 = imageId(id, k2);

        assertThat(mainImageId(id)).isEqualTo(img1);

        mvc.perform(patch(ADMIN + "/{id}/images/{img}", id, img2).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"main\":true,\"altTextAr\":\"بوابة\",\"displayOrder\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.main").value(true))
                .andExpect(jsonPath("$.altTextAr").value("بوابة"));
        assertThat(mainImageId(id)).isEqualTo(img2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_image WHERE portfolio_item_id = ? "
                + "AND is_main = 1", Integer.class, id)).isEqualTo(1);

        mvc.perform(delete(ADMIN + "/{id}/images/{img}", id, img2).with(admin()))
                .andExpect(status().isNoContent());
        assertThat(storageService.exists(k2)).as("the file goes with the image").isFalse();
        assertThat(mainImageId(id)).isEqualTo(img1);

        mvc.perform(delete(ADMIN + "/{id}/images/{img}", id, img2).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("An image of another item is not reachable through this item's URL")
    void imageBelongsToItsItem() throws Exception {
        long mine = create(title(), null);
        long other = create(title(), null);
        String key = keyOf(upload(other, "x.png", "image/png", TestImages.png()));
        long otherImage = imageId(other, key);

        mvc.perform(delete(ADMIN + "/{id}/images/{img}", mine, otherImage).with(admin()))
                .andExpect(status().isNotFound());
        assertThat(storageService.exists(key)).isTrue();
    }

    @Test
    @DisplayName("The public page shows the images in order with the main one flagged")
    void publicImages() throws Exception {
        long id = create(title(), null);
        upload(id, "1.png", "image/png", TestImages.png());
        upload(id, "2.jpg", "image/jpeg", TestImages.jpeg());
        setPublished(id, true);

        mvc.perform(get(PUBLIC + "/{slug}", slugOf(id)))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].main").value(true))
                .andExpect(jsonPath("$.images[0].url").isNotEmpty());
        mvc.perform(get(PUBLIC))
                .andExpect(jsonPath("$.content[?(@.slug=='" + slugOf(id) + "')].imageUrl").isNotEmpty());
    }

    @Test
    @DisplayName("Archiving keeps the image files; an archived item takes no image changes until restored")
    void archivedItemKeepsAndFreezesImages() throws Exception {
        long id = create(title(), null);
        String key = keyOf(upload(id, "1.png", "image/png", TestImages.png()));
        long image = imageId(id, key);

        mvc.perform(delete(ADMIN + "/{id}", id).with(admin()));

        assertThat(storageService.exists(key)).isTrue();
        upload(id, "2.png", "image/png", TestImages.png()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_ITEM_ARCHIVED"));
        mvc.perform(delete(ADMIN + "/{id}/images/{img}", id, image).with(admin()))
                .andExpect(status().isConflict());

        mvc.perform(patch(ADMIN + "/{id}/restore", id).with(admin()));
        upload(id, "2.png", "image/png", TestImages.png()).andExpect(status().isCreated());
        assertThat(storageService.exists(key)).isTrue();
    }

    // =================================================================== helpers

    private String title() {
        return "بوابة حديد مشغول " + tag;
    }

    private String body(String titleAr, String slug) {
        return "{\"titleAr\":\"" + titleAr + "\""
                + (slug == null ? "" : ",\"slug\":\"" + slug + "\"") + "}";
    }

    private long create(String titleAr, String slug) throws Exception {
        return create(titleAr, slug, 0, null);
    }

    private long create(String titleAr, String slug, int order, Long category) throws Exception {
        return create(titleAr, slug, order, category, null);
    }

    private long create(String titleAr, String slug, int order, Long category, String titleEn)
            throws Exception {
        String json = "{\"titleAr\":\"" + titleAr + "\",\"displayOrder\":" + order
                + (slug == null ? "" : ",\"slug\":\"" + slug + "\"")
                + (category == null ? "" : ",\"categoryId\":" + category)
                + (titleEn == null ? "" : ",\"titleEn\":\"" + titleEn + "\"") + "}";
        MvcResult result = mvc.perform(post(ADMIN).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false))
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        itemIds.add(id);
        return id;
    }

    /** Oldest first. */
    private List<AuditLog> audit(long id) {
        List<AuditLog> entries = new ArrayList<>(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("PORTFOLIO_ITEM", String.valueOf(id),
                        PageRequest.of(0, 50)).getContent());
        java.util.Collections.reverse(entries);
        return entries;
    }

    private String slugOf(long id) {
        return jdbc.queryForObject("SELECT slug FROM portfolio_item WHERE id = ?", String.class, id);
    }

    private ResultActions setPublished(long id, boolean published) throws Exception {
        return mvc.perform(patch(ADMIN + "/{id}/publish", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"published\":" + published + "}"));
    }

    private List<String> publicSlugs() throws Exception {
        String json = mvc.perform(get(PUBLIC).param("size", "50")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.content[*].slug");
    }

    private List<Long> adminIds(boolean includeArchived) throws Exception {
        String json = mvc.perform(get(ADMIN).with(admin()).param("size", "100")
                        .param("includeArchived", String.valueOf(includeArchived)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(json, "$.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private ResultActions upload(long id, String filename, String contentType, byte[] bytes)
            throws Exception {
        return mvc.perform(multipart(ADMIN + "/{id}/images", id)
                .file(new MockMultipartFile("file", filename, contentType, bytes)).with(admin()));
    }

    private String keyOf(ResultActions upload) throws Exception {
        String key = JsonPath.read(upload.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.key");
        imageKeys.add(key);
        return key;
    }

    private long imageId(long itemId, String key) {
        return jdbc.queryForObject("SELECT id FROM portfolio_image WHERE portfolio_item_id = ? AND url = ?",
                Long.class, itemId, key);
    }

    private long mainImageId(long itemId) {
        return jdbc.queryForObject("SELECT id FROM portfolio_image WHERE portfolio_item_id = ? AND is_main = 1",
                Long.class, itemId);
    }

    private RequestPostProcessor admin() {
        return signedIn("ADMIN");
    }

    private static RequestPostProcessor signedIn(String role) {
        UserPrincipal principal = UserPrincipal.of(999_001L, "portfolio@example.com", null, List.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
