package com.velora.api.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.dto.CartResponse;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Discounts and announcements against the test database: the price a customer is quoted is the same in the product
 * list, its filters, the product page, the cart and the order; the safeguards hold; banners are checked by their
 * bytes; and the announcement bar shows what it should, when it should.
 */
@SpringBootTest
class PromotionIntegrationTest {

    private static final String GUEST_PREFIX = "promo-test-";
    private static final String PHONE_LOCAL = "01012345691";
    private static final String PHONE_E164 = "+201012345691";
    private static final BigDecimal VAT = new BigDecimal("0.1400");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private StorageService storage;
    @Autowired private TransactionTemplate tx;

    private MockMvc mvc;
    private String tag;
    private String guestToken;
    private Long categoryId;
    private Long childCategoryId;
    private Long productId;
    private Long variantId;
    private String slug;
    private final List<Long> promotionIds = new ArrayList<>();
    private final List<Long> announcementIds = new ArrayList<>();
    private final long staff = 999_061L;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
        guestToken = GUEST_PREFIX + UUID.randomUUID();
        slug = "promo-prod-" + tag;

        tx.executeWithoutResult(s -> {
            Category parent = new Category();
            parent.setSlug("promo-cat-" + tag);
            parent.setActive(true);
            translate(parent, "فئة خصم " + tag);
            categoryId = categoryRepository.save(parent).getId();

            Category child = new Category();
            child.setSlug("promo-child-" + tag);
            child.setActive(true);
            child.setParent(categoryRepository.findById(categoryId).orElseThrow());
            translate(child, "فئة فرعية " + tag);
            childCategoryId = categoryRepository.save(child).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(childCategoryId).orElseThrow());
            product.setSlug(slug);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            ProductTranslation ar = new ProductTranslation();
            ar.attachTo(product, "ar");
            ar.setName("منتج الخصم " + tag);
            product.getTranslations().put("ar", ar);
            ProductTranslation en = new ProductTranslation();
            en.attachTo(product, "en");
            en.setName("Promo product " + tag);
            product.getTranslations().put("en", en);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("PROMO-" + tag.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setCostPrice(new BigDecimal("400.0000"));
            variant.setTaxRate(VAT);
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(50);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);
        });
    }

    private static void translate(Category category, String name) {
        CategoryTranslation t = new CategoryTranslation();
        t.attachTo(category, "ar");
        t.setName(name);
        category.getTranslations().put("ar", t);
    }

    @AfterEach
    void cleanUp() {
        String orders = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        jdbc.update("DELETE FROM invoice WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart WHERE guest_token LIKE ?", GUEST_PREFIX + "%");
        for (Long id : announcementIds) {
            jdbc.update("DELETE FROM announcement WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM announcement WHERE text_ar LIKE ?", "%" + tag + "%");
        for (Long id : promotionIds) {
            jdbc.queryForList("SELECT banner_key FROM promotion WHERE id = ? AND banner_key IS NOT NULL", String.class, id)
                    .forEach(storage::delete);
            jdbc.queryForList("SELECT banner_mobile_key FROM promotion WHERE id = ? AND banner_mobile_key IS NOT NULL", String.class, id)
                    .forEach(storage::delete);
            jdbc.update("DELETE FROM announcement WHERE promotion_id = ?", id);
            jdbc.update("DELETE FROM promotion WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM audit_log WHERE entity_type IN ('PROMOTION', 'ANNOUNCEMENT') AND entity_label LIKE ?", "%" + tag + "%");
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category_translation WHERE category_id IN (?, ?)", categoryId, childCategoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", childCategoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    // ----------------------------------------------------------------- helpers

    private RequestPostProcessor admin() {
        UserPrincipal p = UserPrincipal.of(staff, "promo@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    private RequestPostProcessor customer() {
        UserPrincipal p = UserPrincipal.of(staff, "promo@example.com", null, List.of("CUSTOMER"));
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    private String body(String scope, Long target, String percent, OffsetDateTime from, OffsetDateTime to, boolean confirm) throws Exception {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("nameAr", "خصم " + scope + " " + tag);
        m.put("nameEn", "Sale " + scope + " " + tag);
        m.put("scope", scope);
        m.put("targetId", target);
        m.put("percent", new BigDecimal(percent));
        m.put("startsAt", from.toString());
        m.put("endsAt", to.toString());
        m.put("headlineAr", "عرض خاص");
        m.put("confirmHighDiscount", confirm);
        return mapper.writeValueAsString(m);
    }

    private long create(String scope, Long target, String percent, OffsetDateTime from, OffsetDateTime to) throws Exception {
        String res = mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(scope, target, percent, from, to, false)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = mapper.readTree(res).get("id").asLong();
        promotionIds.add(id);
        return id;
    }

    private long createRunning(String scope, Long target, String percent) throws Exception {
        long id = create(scope, target, percent, now().minusHours(1), now().plusDays(1));
        mvc.perform(post("/api/v1/admin/promotions/" + id + "/activate").with(admin())).andExpect(status().isOk());
        return id;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private JsonNode json(String url, RequestPostProcessor... auth) throws Exception {
        var request = get(url);
        for (RequestPostProcessor a : auth) {
            request.with(a);
        }
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode product() throws Exception {
        return json("/api/v1/products/" + slug);
    }

    private String listUrl(String extra) {
        return "/api/v1/products?categoryId=" + childCategoryId + extra;
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Long placeOrder(int quantity) {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, quantity), "ar");
        return checkoutService.placeOrder(null, guestToken, new PlaceOrderRequest(null,
                new PlaceOrderRequest.AddressInput("عميل الاختبار", PHONE_LOCAL, null, null,
                        governorateRepository.findByCode("CAI").orElseThrow().getId(),
                        "منطقة الاختبار", "شارع الاختبار", "1", null, null, null),
                "COD", null, null, null), "ar").getId();
    }

    // ============================================================ one price everywhere

    @Test
    @DisplayName("A running product discount gives one price on the product page, the list, its filters, the cart and the order")
    void onePriceEverywhere() throws Exception {
        long promo = createRunning("PRODUCT", productId, "20");

        // Product page
        JsonNode p = product();
        assertThat(p.at("/variants/0/price").decimalValue()).isEqualByComparingTo("800");
        assertThat(p.at("/variants/0/compareAtPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(p.at("/variants/0/discountPercent").asInt()).isEqualTo(20);
        assertThat(p.at("/priceRange/min").decimalValue()).isEqualByComparingTo("800");
        assertThat(p.at("/sale/percent").decimalValue()).isEqualByComparingTo("20");
        assertThat(p.at("/sale/promotionId").asLong()).isEqualTo(promo);
        assertThat(p.at("/sale/endsAt").asText()).isNotBlank();

        // List card
        JsonNode card = json(listUrl("")).at("/content/0");
        assertThat(card.get("minPrice").decimalValue()).isEqualByComparingTo("800");
        assertThat(card.get("compareAtPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(card.get("discountPercent").asInt()).isEqualTo(20);
        assertThat(card.has("saleEndsAt")).isTrue();

        // The SQL used by filters and sorting sees the same 800.00, to the piastre.
        assertThat(json(listUrl("&maxPrice=800")).at("/content").size()).isEqualTo(1);
        assertThat(json(listUrl("&maxPrice=799.99")).at("/content").size()).isZero();
        assertThat(json(listUrl("&minPrice=800")).at("/content").size()).isEqualTo(1);
        assertThat(json(listUrl("&minPrice=800.01")).at("/content").size()).isZero();
        assertThat(json(listUrl("&onSale=true")).at("/content").size()).isEqualTo(1);
        assertThat(json(listUrl("&sort=price_asc")).at("/content").size()).isEqualTo(1);

        // Cart
        CartResponse cart = cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");
        assertThat(cart.items().get(0).unitPrice()).isEqualByComparingTo("800");
        assertThat(cart.items().get(0).listPrice()).isEqualByComparingTo("1000");
        assertThat(cart.items().get(0).salePercent()).isEqualTo(20);
        assertThat(cart.items().get(0).priceAtAdd()).isEqualByComparingTo("800");
        assertThat(cart.items().get(0).priceChanged()).isFalse();
        assertThat(cart.subtotal()).isEqualByComparingTo("1600");
        assertThat(cart.taxIncluded()).isEqualByComparingTo(MoneyUtils.taxFromGross(new BigDecimal("1600"), VAT));

        // Order: the discounted price is what is frozen, with the list price and the promotion beside it.
        Long orderId = checkoutService.placeOrder(null, guestToken, new PlaceOrderRequest(null,
                new PlaceOrderRequest.AddressInput("عميل الاختبار", PHONE_LOCAL, null, null,
                        governorateRepository.findByCode("CAI").orElseThrow().getId(),
                        "منطقة الاختبار", "شارع الاختبار", "1", null, null, null),
                "COD", null, null, null), "ar").getId();
        CustomerOrder order = tx.execute(s -> {
            CustomerOrder o = orderRepository.findWithItemsById(orderId).orElseThrow();
            o.getItems().size();
            return o;
        });
        var item = order.getItems().get(0);
        assertThat(item.getUnitPriceGross()).isEqualByComparingTo("800");
        assertThat(item.getListPriceGross()).isEqualByComparingTo("1000");
        assertThat(item.getPromotionId()).isEqualTo(promo);
        assertThat(item.getPromotionName()).contains(tag);
        assertThat(item.getLineTotalGross()).isEqualByComparingTo("1600");
        assertThat(item.getLineTaxAmount()).isEqualByComparingTo(MoneyUtils.taxFromGross(new BigDecimal("1600"), VAT));

        // Pausing puts every price back, and the order already placed keeps what was paid.
        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/pause").with(admin())).andExpect(status().isOk());
        JsonNode back = product();
        assertThat(back.at("/variants/0/price").decimalValue()).isEqualByComparingTo("1000");
        assertThat(back.has("sale")).isFalse();
        assertThat(json(listUrl("&onSale=true")).at("/content").size()).isZero();
        assertThat(json(listUrl("&maxPrice=800")).at("/content").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT unit_price_gross FROM order_item WHERE order_id = ?", BigDecimal.class, orderId))
                .isEqualByComparingTo("800");
        // A promotion an order was placed under is ended, never deleted.
        mvc.perform(delete("/api/v1/admin/promotions/" + promo).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMOTION_IN_USE"));
    }

    @Test
    @DisplayName("Awkward numbers round the same in Java and in the SQL behind filters: 999.99 at 33.33% is 666.69")
    void javaAndSqlRoundAlike() throws Exception {
        jdbc.update("UPDATE product_variant SET price = 999.99, cost_price = 100 WHERE id = ?", variantId);
        createRunning("PRODUCT", productId, "33.33");
        // 999.99 x 33.33 / 100 = 333.296667 -> 333.30 ; price = 666.69
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("666.69");
        assertThat(json(listUrl("&maxPrice=666.69")).at("/content").size()).isEqualTo(1);
        assertThat(json(listUrl("&maxPrice=666.68")).at("/content").size()).isZero();
        assertThat(json(listUrl("&minPrice=666.69")).at("/content").size()).isEqualTo(1);
        assertThat(json(listUrl("&minPrice=666.70")).at("/content").size()).isZero();
    }

    @Test
    @DisplayName("Without a promotion an order has no list price or promotion on its lines")
    void noPromotionNoSnapshot() {
        Long orderId = placeOrder(1);
        assertThat(jdbc.queryForObject("SELECT list_price_gross FROM order_item WHERE order_id = ?", BigDecimal.class, orderId)).isNull();
        assertThat(jdbc.queryForObject("SELECT promotion_id FROM order_item WHERE order_id = ?", Long.class, orderId)).isNull();
    }

    @Test
    @DisplayName("A cart line added before a discount starts is flagged as cheaper, not blocked")
    void cartNoticesTheNewPrice() throws Exception {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
        createRunning("PRODUCT", productId, "20");
        CartResponse cart = cartService.getCart(null, guestToken, "ar");
        assertThat(cart.items().get(0).unitPrice()).isEqualByComparingTo("800");
        assertThat(cart.items().get(0).priceChanged()).isTrue();
        assertThat(cart.warnings()).anyMatch(w -> "PRICE_CHANGED".equals(w.code()));
        assertThat(cart.checkoutReady()).isTrue();
    }

    // ================================================================ which discount wins

    @Test
    @DisplayName("The biggest discount wins and they never stack; a category discount reaches its subcategories")
    void biggestWinsAndCategoryReachesChildren() throws Exception {
        createRunning("ALL", null, "10");
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("900");

        createRunning("CATEGORY", categoryId, "30");   // the product sits in the CHILD of this category
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("700");

        createRunning("PRODUCT", productId, "25");
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("700");
        assertThat(json(listUrl("&maxPrice=700")).at("/content").size()).isEqualTo(1);

        createRunning("PRODUCT", productId, "40");
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("600");
        assertThat(json(listUrl("&maxPrice=600")).at("/content").size()).isEqualTo(1);
        assertThat(json(listUrl("&maxPrice=599.99")).at("/content").size()).isZero();
    }

    @Test
    @DisplayName("A scheduled discount changes nothing until its start; an ended one cannot be activated")
    void schedule() throws Exception {
        long later = create("PRODUCT", productId, "20", now().plusHours(2), now().plusDays(1));
        mvc.perform(post("/api/v1/admin/promotions/" + later + "/activate").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SCHEDULED"));
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("1000");

        long past = create("PRODUCT", productId, "20", now().minusDays(3), now().minusDays(2));
        mvc.perform(post("/api/v1/admin/promotions/" + past + "/activate").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMOTION_ALREADY_ENDED"));

        // Starting and ending are the clock's job, not a job's: move the window in the table.
        jdbc.update("UPDATE promotion SET starts_at = ?, ends_at = ? WHERE id = ?",
                now().minusHours(1), now().plusHours(1), later);
        mvc.perform(get("/api/v1/admin/promotions/" + later).with(admin()))
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    // ================================================================== the safeguards

    @Test
    @DisplayName("A discount that would sell below cost is refused on activation and on edit; the impact preview says why")
    void priceFloor() throws Exception {
        jdbc.update("UPDATE product_variant SET cost_price = 900 WHERE id = ?", variantId);
        long promo = create("PRODUCT", productId, "20", now().minusHours(1), now().plusDays(1));

        JsonNode impact = json("/api/v1/admin/promotions/" + promo + "/impact", admin());
        assertThat(impact.get("affectedProducts").asInt()).isEqualTo(1);
        assertThat(impact.get("affectedVariants").asInt()).isEqualTo(1);
        assertThat(impact.get("belowCost").asInt()).isEqualTo(1);
        assertThat(impact.at("/examples/0/salePrice").decimalValue()).isEqualByComparingTo("800");
        assertThat(impact.at("/belowCostExamples/0/costPrice").decimalValue()).isEqualByComparingTo("900");

        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/activate").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMOTION_BELOW_COST"));
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("1000");

        // 5% leaves 950, above the 900 cost: allowed. Then an edit back to 20% is refused too.
        mvc.perform(put("/api/v1/admin/promotions/" + promo).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("PRODUCT", productId, "5", now().minusHours(1), now().plusDays(1), false))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/activate").with(admin())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/promotions/" + promo).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("PRODUCT", productId, "20", now().minusHours(1), now().plusDays(1), false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMOTION_BELOW_COST"));
        assertThat(product().at("/variants/0/price").decimalValue()).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("Above 70 percent must be confirmed; the form is validated; overlaps are reported")
    void validationAndOverlap() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ALL", null, "80", now(), now().plusDays(1), false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROMOTION_HIGH_DISCOUNT_UNCONFIRMED"));
        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ALL", null, "80", now(), now().plusDays(1), true))).andExpect(status().isCreated());
        // (that one is a draft: it is cleaned up through its id below)
        promotionIds.addAll(jdbc.queryForList("SELECT id FROM promotion WHERE name_ar LIKE ?", Long.class, "%" + tag + "%"));

        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ALL", null, "95.5", now(), now().plusDays(1), true))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ALL", null, "10", now().plusDays(1), now(), false)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ALL", 5L, "10", now(), now().plusDays(1), false))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("PRODUCT", null, "10", now(), now().plusDays(1), false))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("PRODUCT", 999999999L, "10", now(), now().plusDays(1), false))).andExpect(status().isNotFound());

        long first = createRunning("CATEGORY", categoryId, "10");
        long second = create("PRODUCT", productId, "20", now(), now().plusDays(2));
        JsonNode impact = json("/api/v1/admin/promotions/" + second + "/impact", admin());
        assertThat(impact.get("overlaps").toString()).contains("\"id\":" + first);
    }

    // ===================================================================== the banner

    @Test
    @DisplayName("A banner is checked by its bytes, replaces the old file, shows on the public list, and is removed with its file")
    void banner() throws Exception {
        long promo = createRunning("CATEGORY", categoryId, "15");

        // Not a picture, whatever its name says.
        mvc.perform(multipart("/api/v1/admin/promotions/" + promo + "/banner").file(
                        new MockMultipartFile("file", "banner.png", "image/png", "<html>x</html>".getBytes(StandardCharsets.UTF_8)))
                        .with(admin()))
                .andExpect(status().isBadRequest());

        JsonNode first = mapper.readTree(mvc.perform(multipart("/api/v1/admin/promotions/" + promo + "/banner")
                        .file(new MockMultipartFile("file", "banner.png", "image/png", png())).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String firstKey = jdbc.queryForObject("SELECT banner_key FROM promotion WHERE id = ?", String.class, promo);
        assertThat(first.get("bannerUrl").asText()).contains("/uploads/promotions/");
        assertThat(storage.exists(firstKey)).isTrue();

        mvc.perform(multipart("/api/v1/admin/promotions/" + promo + "/banner").param("kind", "MOBILE")
                .file(new MockMultipartFile("file", "m.png", "image/png", png())).with(admin())).andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/admin/promotions/" + promo + "/banner")
                .file(new MockMultipartFile("file", "banner2.png", "image/png", png())).with(admin())).andExpect(status().isOk());
        String secondKey = jdbc.queryForObject("SELECT banner_key FROM promotion WHERE id = ?", String.class, promo);
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(storage.exists(firstKey)).as("the replaced file is gone").isFalse();
        assertThat(storage.exists(secondKey)).isTrue();

        JsonNode running = json("/api/v1/promotions/active");
        JsonNode mine = null;
        for (JsonNode n : running) {
            if (n.get("id").asLong() == promo) {
                mine = n;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("bannerUrl").asText()).contains(secondKey);
        assertThat(mine.get("bannerMobileUrl").asText()).contains("/uploads/promotions/");
        assertThat(mine.get("landingPath").asText()).isEqualTo("/products?categoryId=" + categoryId);
        assertThat(mine.get("headline").asText()).isEqualTo("عرض خاص");

        mvc.perform(delete("/api/v1/admin/promotions/" + promo + "/banner").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.bannerUrl").doesNotExist());
        assertThat(storage.exists(secondKey)).isFalse();

        // A paused discount is not shown to visitors at all.
        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/pause").with(admin()));
        for (JsonNode n : json("/api/v1/promotions/active")) {
            assertThat(n.get("id").asLong()).isNotEqualTo(promo);
        }
    }

    // ============================================================= the announcement bar

    private String announcement(String textAr, Long promotionId, String link, String animation, boolean countdown) throws Exception {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("textAr", textAr);
        m.put("textEn", textAr == null ? null : "Hurry up " + tag);
        m.put("linkUrl", link);
        m.put("animation", animation);
        m.put("theme", "GOLD");
        m.put("showCountdown", countdown);
        m.put("promotionId", promotionId);
        return mapper.writeValueAsString(m);
    }

    private long createAnnouncement(String body) throws Exception {
        String res = mvc.perform(post("/api/v1/admin/announcements").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = mapper.readTree(res).get("id").asLong();
        announcementIds.add(id);
        return id;
    }

    private JsonNode mine(JsonNode list, long id) {
        for (JsonNode n : list) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("A message shows in the language asked for, with its animation, link and theme; off and out-of-window hide it")
    void announcementBasics() throws Exception {
        long id = createAnnouncement(announcement("اغتنم الخصم " + tag, null, "/products?onSale=true", "MARQUEE", false));

        JsonNode ar = mine(json("/api/v1/announcements/active"), id);
        assertThat(ar.get("text").asText()).isEqualTo("اغتنم الخصم " + tag);
        assertThat(ar.get("animation").asText()).isEqualTo("MARQUEE");
        assertThat(ar.get("theme").asText()).isEqualTo("GOLD");
        assertThat(ar.get("linkUrl").asText()).isEqualTo("/products?onSale=true");
        assertThat(ar.get("dismissible").asBoolean()).isTrue();
        assertThat(ar.has("countdownEndsAt")).isFalse();
        long version = ar.get("version").asLong();

        JsonNode en = mapper.readTree(mvc.perform(get("/api/v1/announcements/active").header("Accept-Language", "en"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(mine(en, id).get("text").asText()).isEqualTo("Hurry up " + tag);

        // Editing changes the version, so a visitor who dismissed the old text sees the new one.
        mvc.perform(put("/api/v1/admin/announcements/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(announcement("نص جديد " + tag, null, null, "PULSE", false))).andExpect(status().isOk());
        JsonNode edited = mine(json("/api/v1/announcements/active"), id);
        assertThat(edited.get("version").asLong()).isGreaterThan(version);
        assertThat(edited.get("animation").asText()).isEqualTo("PULSE");

        mvc.perform(post("/api/v1/admin/announcements/" + id + "/off").with(admin())).andExpect(status().isOk());
        assertThat(mine(json("/api/v1/announcements/active"), id)).isNull();
        mvc.perform(post("/api/v1/admin/announcements/" + id + "/on").with(admin())).andExpect(status().isOk());
        assertThat(mine(json("/api/v1/announcements/active"), id)).isNotNull();

        jdbc.update("UPDATE announcement SET starts_at = ?, ends_at = ? WHERE id = ?", now().plusHours(1), now().plusHours(2), id);
        assertThat(mine(json("/api/v1/announcements/active"), id)).isNull();
        jdbc.update("UPDATE announcement SET starts_at = ?, ends_at = ? WHERE id = ?", now().minusHours(2), now().minusHours(1), id);
        assertThat(mine(json("/api/v1/announcements/active"), id)).isNull();
    }

    @Test
    @DisplayName("A message linked to a discount shows only while it runs, writes its own text, and counts down to its end")
    void announcementLinkedToDiscount() throws Exception {
        long promo = create("ALL", null, "20", now().minusHours(1), now().plusDays(1));
        long id = createAnnouncement(announcement(null, promo, null, "FADE", true));

        // Draft: nothing to announce yet.
        assertThat(mine(json("/api/v1/announcements/active"), id)).isNull();

        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/activate").with(admin())).andExpect(status().isOk());
        JsonNode ar = mine(json("/api/v1/announcements/active"), id);
        assertThat(ar.get("text").asText()).isEqualTo("سارع! خصم 20% - خصم ALL " + tag);
        assertThat(ar.get("linkUrl").asText()).isEqualTo("/products?onSale=true");
        assertThat(ar.get("promotionPercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(ar.get("countdownEndsAt").asText()).isNotBlank();

        JsonNode en = mapper.readTree(mvc.perform(get("/api/v1/announcements/active").header("Accept-Language", "en"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(mine(en, id).get("text").asText()).isEqualTo("Hurry up! 20% off - Sale ALL " + tag);

        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/pause").with(admin()));
        assertThat(mine(json("/api/v1/announcements/active"), id)).isNull();

        // An announcement that uses it stops it being deleted.
        mvc.perform(delete("/api/v1/admin/promotions/" + promo).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROMOTION_IN_USE"));
    }

    @Test
    @DisplayName("Unsafe links, a missing text and bad windows are refused; messages reorder and delete")
    void announcementValidationAndOrder() throws Exception {
        for (String bad : List.of("javascript:alert(1)", "//evil.example", "http://plain.example", "data:text/html,x")) {
            mvc.perform(post("/api/v1/admin/announcements").with(admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(announcement("نص " + tag, null, bad, "STATIC", false))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/admin/announcements").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(announcement(null, null, null, "STATIC", false))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/announcements").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(announcement("نص " + tag, 999999999L, null, "STATIC", false))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/announcements").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(announcement("نص " + tag, null, "https://example.com/x", "STATIC", false))).andExpect(status().isCreated());
        announcementIds.addAll(jdbc.queryForList("SELECT id FROM announcement WHERE text_ar LIKE ?", Long.class, "%" + tag + "%"));

        long a = createAnnouncement(announcement("أول " + tag, null, null, "STATIC", false));
        long b = createAnnouncement(announcement("ثاني " + tag, null, null, "STATIC", false));
        mvc.perform(put("/api/v1/admin/announcements/reorder").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + b + "," + a + "]}")).andExpect(status().isOk());
        List<Long> order = new ArrayList<>();
        for (JsonNode n : json("/api/v1/announcements/active")) {
            if (n.get("id").asLong() == a || n.get("id").asLong() == b) {
                order.add(n.get("id").asLong());
            }
        }
        assertThat(order).containsExactly(b, a);

        mvc.perform(delete("/api/v1/admin/announcements/" + a).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/announcements/" + a).with(admin())).andExpect(status().isNotFound());
    }

    // ================================================================ access and audit

    @Test
    @DisplayName("Only an administrator manages discounts and announcements; visitors read the public lists; changes are audited")
    void accessAndAudit() throws Exception {
        for (String path : List.of("/api/v1/admin/promotions", "/api/v1/admin/announcements")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(customer())).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/admin/promotions").with(customer()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ALL", null, "10", now(), now().plusDays(1), false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/announcements/active")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/promotions/active")).andExpect(status().isOk());

        long promo = createRunning("PRODUCT", productId, "20");
        mvc.perform(post("/api/v1/admin/promotions/" + promo + "/end").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ENDED"));
        assertThat(product().has("sale")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'PROMOTION_CHANGED' AND entity_id = ?",
                Integer.class, String.valueOf(promo))).isGreaterThanOrEqualTo(3);

        mvc.perform(delete("/api/v1/admin/promotions/" + promo).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/promotions/" + promo).with(admin())).andExpect(status().isNotFound());
    }
}
