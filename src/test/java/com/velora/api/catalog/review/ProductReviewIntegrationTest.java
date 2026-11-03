package com.velora.api.catalog.review;

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
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest.VariantItem;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.catalog.service.admin.VariantAdminService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import java.math.BigDecimal;
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
 * Reviews end to end against the test database: who may review, that nothing is public before approval, the
 * moderation decisions and their audit trail, the average, and that one customer can never touch another's
 * review.
 */
@SpringBootTest
class ProductReviewIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProductAdminService productService;
    @Autowired private VariantAdminService variantService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AppUserRepository userRepository;

    private MockMvc mvc;
    private String tag;
    private Long categoryId;
    private Long ahmed;
    private Long mona;
    private long staff = 999_041L;
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
        Category category = new Category();
        category.setSlug("rev-" + tag);
        category.setActive(true);
        categoryId = categoryRepository.save(category).getId();
        ahmed = user("Ahmed", "Mansour");
        mona = user("Mona", "Said");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log WHERE entity_type = 'PRODUCT_REVIEW' AND entity_label LIKE ?", "rev-" + tag + "%");
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_review WHERE product_id = ?", id);
        }
        for (Long id : orderIds) {
            jdbc.update("DELETE FROM order_item WHERE order_id = ?", id);
            jdbc.update("DELETE FROM customer_order WHERE id = ?", id);
        }
        for (Long id : productIds) {
            jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", id);
            jdbc.update("DELETE FROM product_variant WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", ahmed, mona);
    }

    // ----------------------------------------------------------------- fixtures

    private Long user(String first, String last) {
        AppUser u = new AppUser();
        u.setEmail(first.toLowerCase() + "-" + tag + "@example.com");
        u.setPasswordHash("not-a-real-hash");
        u.setFirstName(first);
        u.setLastName(last);
        return userRepository.save(u).getId();
    }

    private long product(String suffix, String nameAr) {
        long id = productService.create(new ProductCreateRequest(categoryId, null, "rev-" + tag + "-" + suffix,
                List.of(new TranslationRequest("ar", nameAr, null, null, null, null),
                        new TranslationRequest("en", "Product " + suffix, null, null, null, null)),
                false, false, null, FulfillmentType.MADE_TO_ORDER, null)).id();
        productIds.add(id);
        variantService.saveVariants(id, new VariantSaveRequest(List.of(new VariantItem(null, "RV" + tag + suffix, null,
                new BigDecimal("100.00"), null, null, null, null, List.of(), null, null))), null);
        productService.publish(id);
        return id;
    }

    /** An order of the customer's that carried the product, in the given fulfilment status. */
    private long order(Long customerId, long productId, String fulfilment) {
        String number = "RV-" + tag + "-" + orderIds.size();
        jdbc.update("INSERT INTO customer_order (order_number, customer_id, fulfillment_status, subtotal_gross, "
                + "grand_total, tax_total, net_total, contact_name, contact_phone, ship_governorate_name, "
                + "ship_city_name, ship_street_address) VALUES (?, ?, ?, 100, 100, 0, 100, N'عميل', '01000000000', "
                + "N'القاهرة', N'القاهرة', N'شارع')", number, customerId, fulfilment);
        long orderId = jdbc.queryForObject("SELECT id FROM customer_order WHERE order_number = ?", Long.class, number);
        orderIds.add(orderId);
        jdbc.update("INSERT INTO order_item (order_id, product_id, product_name_ar, product_name_en, sku, "
                + "unit_price_gross, quantity, tax_rate, line_total_gross, line_tax_amount) "
                + "VALUES (?, ?, N'منتج', 'Product', 'X', 100, 1, 0, 100, 0)", orderId, productId);
        return orderId;
    }

    private static RequestPostProcessor as(Long id, String role) {
        UserPrincipal p = UserPrincipal.of(id, "rev@example.com", null, List.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    private RequestPostProcessor customer(Long id) {
        return as(id, "CUSTOMER");
    }

    private RequestPostProcessor admin() {
        return as(staff, "ADMIN");
    }

    private JsonNode write(Long customerId, long productId, int rating, String comment) throws Exception {
        String body = mvc.perform(post("/api/v1/me/reviews").with(customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"rating\":" + rating + ",\"comment\":"
                                + (comment == null ? "null" : mapper.writeValueAsString(comment)) + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    private JsonNode publicList(String slug) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/products/" + slug + "/reviews"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String slug(long productId) {
        return jdbc.queryForObject("SELECT slug FROM product WHERE id = ?", String.class, productId);
    }

    // -------------------------------------------------------------------- tests

    @Test
    @DisplayName("Only a product that arrived can be reviewed: pending, cancelled or someone else's order is refused")
    void onlyAfterDelivery() throws Exception {
        long p = product("a", "كرسي");
        long q = product("b", "طاولة");

        // Ahmed has a SHIPPED order for p only, Mona a DELIVERED one for q.
        order(ahmed, p, "SHIPPED");
        order(mona, q, "DELIVERED");

        String body = "{\"productId\":" + p + ",\"rating\":5}";
        mvc.perform(post("/api/v1/me/reviews").with(customer(ahmed)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REVIEW_NOT_ELIGIBLE"));
        // Ahmed never ordered q at all.
        mvc.perform(post("/api/v1/me/reviews").with(customer(ahmed)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + q + ",\"rating\":5}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REVIEW_NOT_ELIGIBLE"));
        // Mona may review q.
        write(mona, q, 4, "جميلة");

        // Once the parcel arrives Ahmed may review p.
        jdbc.update("UPDATE customer_order SET fulfillment_status = 'DELIVERED' WHERE customer_id = ?", ahmed);
        write(ahmed, p, 5, null);
    }

    @Test
    @DisplayName("Eligible lists the delivered products without a review, and one review per product is enforced")
    void eligibleAndOnce() throws Exception {
        long p = product("a", "كرسي");
        long q = product("b", "طاولة");
        order(ahmed, p, "DELIVERED");
        order(ahmed, q, "DELIVERED");

        mvc.perform(get("/api/v1/me/reviews/eligible").with(customer(ahmed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        write(ahmed, p, 5, "ممتاز");
        mvc.perform(get("/api/v1/me/reviews/eligible").with(customer(ahmed)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].productId").value(q));
        mvc.perform(post("/api/v1/me/reviews").with(customer(ahmed)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + p + ",\"rating\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_ALREADY_EXISTS"));
        // The rating must be 1 to 5.
        mvc.perform(post("/api/v1/me/reviews").with(customer(ahmed)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + q + ",\"rating\":6}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A new review is invisible to the public until approved; the average counts approved reviews only")
    void pendingIsInvisibleAndAverageIsApprovedOnly() throws Exception {
        long p = product("a", "كرسي");
        order(ahmed, p, "DELIVERED");
        order(mona, p, "DELIVERED");
        JsonNode first = write(ahmed, p, 5, "ممتاز جدا");
        JsonNode second = write(mona, p, 2, "عادي");
        assertThat(first.get("status").asText()).isEqualTo("PENDING");

        JsonNode none = publicList(slug(p));
        assertThat(none.get("count").asInt()).isZero();
        assertThat(none.has("average")).isFalse();
        assertThat(none.at("/reviews/content").size()).isZero();

        mvc.perform(post("/api/v1/admin/reviews/" + first.get("id").asLong() + "/approve").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode one = publicList(slug(p));
        assertThat(one.get("count").asInt()).isEqualTo(1);
        assertThat(one.get("average").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(one.at("/breakdown/5").asInt()).isEqualTo(1);
        assertThat(one.at("/breakdown/2").asInt()).isZero();
        // The page shows «Ahmed M.», never the full name or the account.
        assertThat(one.at("/reviews/content/0/reviewerName").asText()).isEqualTo("Ahmed M.");
        assertThat(one.at("/reviews/content/0").has("customerId")).isFalse();

        mvc.perform(post("/api/v1/admin/reviews/" + second.get("id").asLong() + "/approve").with(admin()));
        JsonNode both = publicList(slug(p));
        assertThat(both.get("count").asInt()).isEqualTo(2);
        assertThat(both.get("average").decimalValue()).isEqualByComparingTo("3.50");

        // The product list and detail carry the same figures.
        mvc.perform(get("/api/v1/products/" + slug(p))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(2)).andExpect(jsonPath("$.ratingAvg").value(3.5));
    }

    @Test
    @DisplayName("Rejecting an approved review takes it down; editing an approved review sends it back to pending")
    void rejectAndEdit() throws Exception {
        long p = product("a", "كرسي");
        order(ahmed, p, "DELIVERED");
        long id = write(ahmed, p, 5, "ممتاز").get("id").asLong();
        mvc.perform(post("/api/v1/admin/reviews/" + id + "/approve").with(admin()));
        assertThat(publicList(slug(p)).get("count").asInt()).isEqualTo(1);

        mvc.perform(post("/api/v1/admin/reviews/" + id + "/reject").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"محتوى غير مناسب\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.moderatedBy").value(staff));
        assertThat(publicList(slug(p)).get("count").asInt()).isZero();
        // The author sees the note on their own review.
        mvc.perform(get("/api/v1/me/reviews").with(customer(ahmed)))
                .andExpect(jsonPath("$.content[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.content[0].moderationNote").value("محتوى غير مناسب"));
        // Deciding twice the same way is a conflict.
        mvc.perform(post("/api/v1/admin/reviews/" + id + "/reject").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_ALREADY_DECIDED"));

        mvc.perform(post("/api/v1/admin/reviews/" + id + "/approve").with(admin())).andExpect(status().isOk());
        // Ahmed edits the approved text: it is no longer public until staff look at it again.
        mvc.perform(put("/api/v1/me/reviews/" + id).with(customer(ahmed)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3,\"comment\":\"غيّرت رأيي\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rating").value(3));
        assertThat(publicList(slug(p)).get("count").asInt()).isZero();
    }

    @Test
    @DisplayName("Another customer's review looks exactly like one that does not exist")
    void notYoursIsNotFound() throws Exception {
        long p = product("a", "كرسي");
        order(ahmed, p, "DELIVERED");
        long id = write(ahmed, p, 5, "ممتاز").get("id").asLong();

        mvc.perform(put("/api/v1/me/reviews/" + id).with(customer(mona)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mvc.perform(put("/api/v1/me/reviews/999999999").with(customer(mona)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mvc.perform(get("/api/v1/me/reviews").with(customer(mona)))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("The admin list filters by status, stars and text, counts the tabs, and bulk decides many")
    void adminListCountsAndBulk() throws Exception {
        long p = product("a", "كرسي");
        order(ahmed, p, "DELIVERED");
        order(mona, p, "DELIVERED");
        long a = write(ahmed, p, 5, "تجربة رائعة").get("id").asLong();
        long m = write(mona, p, 1, "سيئ").get("id").asLong();

        mvc.perform(get("/api/v1/admin/reviews?status=PENDING&productId=" + p).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].customerName").exists());
        mvc.perform(get("/api/v1/admin/reviews?productId=" + p + "&rating=1").with(admin()))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(m));
        mvc.perform(get("/api/v1/admin/reviews?productId=" + p + "&q=رائعة").with(admin()))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(a));

        mvc.perform(post("/api/v1/admin/reviews/bulk").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"ids\":[" + a + "," + m + ",999999999," + a + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].reason").value("REVIEW_NOT_FOUND"));
        // Approving again: nothing to do, reported.
        mvc.perform(post("/api/v1/admin/reviews/bulk").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"ids\":[" + a + "]}"))
                .andExpect(jsonPath("$.succeeded.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_APPROVED"));

        JsonNode counts = mapper.readTree(mvc.perform(get("/api/v1/admin/reviews/counts").with(admin()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(counts.get("approved").asLong()).isGreaterThanOrEqualTo(2);

        // Every decision is in the audit log with who made it.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'REVIEW_MODERATED' "
                + "AND entity_id = ? AND actor_id IS NULL OR action = 'REVIEW_MODERATED' AND entity_id = ?",
                Integer.class, String.valueOf(a), String.valueOf(a))).isGreaterThanOrEqualTo(1);

        mvc.perform(delete("/api/v1/admin/reviews/" + m).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/reviews/" + m).with(admin())).andExpect(status().isNotFound());
        assertThat(publicList(slug(p)).get("count").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Only an admin moderates; guests cannot write; a pending product page still loads")
    void guards() throws Exception {
        long p = product("a", "كرسي");
        mvc.perform(get("/api/v1/admin/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/reviews").with(customer(ahmed))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reviews/1/approve").with(customer(ahmed))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + p + ",\"rating\":5}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/products/no-such-product-" + tag + "/reviews")).andExpect(status().isNotFound());
        assertThat(publicList(slug(p)).get("count").asInt()).isZero();
    }
}
