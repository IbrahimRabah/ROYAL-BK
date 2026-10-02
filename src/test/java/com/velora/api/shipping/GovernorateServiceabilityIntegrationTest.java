package com.velora.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.customer.dto.AddressRequest;
import com.velora.api.customer.dto.AddressResponse;
import com.velora.api.customer.service.AddressService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.shipping.dto.AdminGovernorateResponse;
import com.velora.api.shipping.dto.GovernorateResponse;
import com.velora.api.shipping.dto.ShippingQuoteResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.service.ShippingAdminService;
import com.velora.api.shipping.service.ShippingService;
import java.math.BigDecimal;
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * South Sinai, Aswan and Luxor are closed (V13). A closed governorate is one that is in
 * no shipping zone: it stays in the governorate list as {@code served: false}, and
 * quote, checkout and saved addresses all refuse it with the same code. The admin API
 * can reopen and close governorates.
 *
 * <p>Tests that open or close governorates restore the state V13 left behind.
 */
@SpringBootTest
class GovernorateServiceabilityIntegrationTest {

    private static final List<String> CLOSED = List.of("SSI", "ASW", "LUX");
    private static final String GUEST_TOKEN_PREFIX = "serviceability-test-";
    private static final String PHONE_LOCAL = "01012345680";
    private static final String PHONE_E164 = "+201012345680";

    @Autowired private WebApplicationContext context;
    @Autowired private ShippingService shippingService;
    @Autowired private ShippingAdminService shippingAdminService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private AddressService addressService;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long userId;
    private String guestToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        guestToken = GUEST_TOKEN_PREFIX + UUID.randomUUID();
        String unique = UUID.randomUUID().toString().substring(0, 8);

        transactionTemplate.executeWithoutResult(status -> {
            Category category = new Category();
            category.setSlug("serviceability-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("serviceability-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.SMALL);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("SERVICEABILITY-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(50);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            AppUser user = new AppUser();
            user.setEmail("serviceability-" + unique + "@example.com");
            user.setPasswordHash("not-a-real-hash");
            user.setFirstName("Serviceability");
            user.setLastName("Test " + unique);
            userId = userRepository.save(user).getId();
        });
    }

    @AfterEach
    void removeTestData() {
        String orderFilter = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart WHERE guest_token LIKE ?", GUEST_TOKEN_PREFIX + "%");
        jdbc.update("DELETE FROM cart WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM customer_address WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    // ------------------------------------------------------- the closed three

    @Test
    @DisplayName("POST /shipping/quote refuses South Sinai, Aswan and Luxor with GOVERNORATE_NOT_SERVED")
    void quoteRefusesClosedGovernorates() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");

        for (String code : CLOSED) {
            assertThatThrownBy(() -> shippingService.quote(
                    idOf(code), null, guestToken, null, true, "ar"))
                    .as("quote to %s", code)
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GOVERNORATE_NOT_SERVED));
        }
    }

    @Test
    @DisplayName("POST /orders refuses them with the same code and holds no stock")
    void checkoutRefusesClosedGovernorates() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");

        for (String code : CLOSED) {
            assertThatThrownBy(() -> checkoutService.placeOrder(
                    null, guestToken, inlineOrder(idOf(code)), "ar"))
                    .as("order to %s", code)
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GOVERNORATE_NOT_SERVED));
        }

        assertThat(inventoryRepository.findByVariantId(variantId).orElseThrow().getQtyReserved())
                .as("a refused order must hold nothing").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order "
                + "WHERE contact_phone = ?", Integer.class, PHONE_E164)).isZero();
    }

    @Test
    @DisplayName("GET /geo/governorates still lists them, as served: false — it does not hide them")
    void geoListStillContainsClosedGovernorates() {
        List<GovernorateResponse> all = shippingService.listGovernorates("ar");

        assertThat(all).as("all 27 governorates are still returned").hasSize(27);
        for (String code : CLOSED) {
            GovernorateResponse closed = all.stream()
                    .filter(g -> g.code().equals(code)).findFirst().orElseThrow();
            assertThat(closed.served()).as(code).isFalse();
            assertThat(closed.shippingRates()).isEmpty();
            assertThat(closed.zoneName()).isNull();
        }
        assertThat(all.stream().filter(g -> !CLOSED.contains(g.code())))
                .as("every other governorate is still served")
                .allSatisfy(g -> assertThat(g.served()).isTrue());
    }

    @Test
    @DisplayName("POST /me/addresses refuses an address in a closed governorate")
    void addressInClosedGovernorateIsRefused() {
        for (String code : CLOSED) {
            assertThatThrownBy(() -> addressService.create(
                    userId, addressIn(idOf(code)), "ar"))
                    .as("address in %s", code)
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GOVERNORATE_NOT_SERVED));
        }
    }

    @Test
    @DisplayName("An address saved while the governorate was open is refused at checkout once it closes")
    void savedAddressInAGovernorateThatLaterClosed() {
        Long daqahliya = idOf("DAK");
        Long originalZone = adminView("DAK").zoneId();
        AddressResponse saved = addressService.create(userId, addressIn(daqahliya), "ar");
        cartService.addItem(userId, null, new AddToCartRequest(variantId, 1), "ar");

        try {
            shippingAdminService.closeGovernorate(daqahliya);

            assertThatThrownBy(() -> checkoutService.placeOrder(userId, null,
                    new PlaceOrderRequest(saved.id(), null, "COD", null), "ar"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GOVERNORATE_NOT_SERVED));
            assertThat(inventoryRepository.findByVariantId(variantId).orElseThrow()
                    .getQtyReserved()).isZero();
        } finally {
            shippingAdminService.assignGovernorate(daqahliya, originalZone);
        }
    }

    @Test
    @DisplayName("A zone with rates for only some sizes does not make checkout say 'not served'")
    void missingSizeIsStillTheRateError() {
        // The rule: no zone at all -> GOVERNORATE_NOT_SERVED; a zone that cannot price
        // one SIZE -> SHIPPING_RATE_NOT_CONFIGURED. Covered end to end by temporarily
        // removing one size row from a zone.
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
        Long deltaZone = adminView("DAK").zoneId();
        Long smallRateId = jdbc.queryForObject("SELECT id FROM shipping_rate "
                + "WHERE zone_id = ? AND size_class = 'SMALL'", Long.class, deltaZone);
        Object[] row = jdbc.queryForObject("SELECT base_cost, cod_fee, delivery_days_min, "
                        + "delivery_days_max, cost_per_extra_kg FROM shipping_rate WHERE id = ?",
                (rs, n) -> new Object[] {rs.getBigDecimal(1), rs.getBigDecimal(2),
                        rs.getShort(3), rs.getShort(4), rs.getBigDecimal(5)}, smallRateId);

        jdbc.update("DELETE FROM shipping_rate WHERE id = ?", smallRateId);
        try {
            assertThatThrownBy(() -> checkoutService.placeOrder(
                    null, guestToken, inlineOrder(idOf("DAK")), "ar"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.SHIPPING_RATE_NOT_CONFIGURED));
        } finally {
            jdbc.update("INSERT INTO shipping_rate (zone_id, base_cost, cod_fee, "
                            + "delivery_days_min, delivery_days_max, cost_per_extra_kg, is_active, "
                            + "created_at, size_class) VALUES (?, ?, ?, ?, ?, ?, 1, "
                            + "SYSDATETIMEOFFSET(), 'SMALL')",
                    deltaZone, row[0], row[1], row[2], row[3], row[4]);
        }
    }

    // ------------------------------------------------------------- reopening

    @Test
    @DisplayName("A reopened governorate can be quoted again, at its zone's prices")
    void reopenedGovernorateIsServed() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");
        Long aswan = idOf("ASW");
        Long upperEgypt = zoneIdOf("UPPER_EGYPT");

        try {
            shippingAdminService.assignGovernorate(aswan, upperEgypt);

            ShippingQuoteResponse quote = shippingService.quote(
                    aswan, null, guestToken, null, true, "ar");
            assertThat(quote.shippingCost()).isEqualByComparingTo("300.00"); // 2 x 150, SMALL
            assertThat(adminView("ASW").served()).isTrue();
            assertThat(adminView("ASW").zoneCode()).isEqualTo("UPPER_EGYPT");
            assertThat(shippingService.listGovernorates("ar").stream()
                    .filter(g -> g.code().equals("ASW")).findFirst().orElseThrow().served())
                    .isTrue();

            // And it can take an order again.
            assertThat(checkoutService.placeOrder(null, guestToken, inlineOrder(aswan), "ar")
                    .getShippingCost()).isEqualByComparingTo("300.00");
        } finally {
            shippingAdminService.closeGovernorate(aswan);
        }
        assertThat(adminView("ASW").served()).as("restored to closed").isFalse();
    }

    @Test
    @DisplayName("Closing is idempotent, and moving a governorate between zones changes its price")
    void closeTwiceAndMove() {
        Long aswan = idOf("ASW");
        shippingAdminService.closeGovernorate(aswan);
        shippingAdminService.closeGovernorate(aswan);
        assertThat(adminView("ASW").zoneId()).isNull();

        Long delta = idOfZone("DELTA");
        Long canal = idOfZone("CANAL");
        Long portSaid = idOf("PTS");
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
        try {
            assertThat(quoteCost(portSaid)).isEqualByComparingTo("120.00");     // CANAL, SMALL
            shippingAdminService.assignGovernorate(portSaid, delta);
            assertThat(quoteCost(portSaid)).isEqualByComparingTo("100.00");     // DELTA, SMALL
            shippingAdminService.assignGovernorate(portSaid, delta);            // idempotent
            assertThat(quoteCost(portSaid)).isEqualByComparingTo("100.00");
        } finally {
            shippingAdminService.assignGovernorate(portSaid, canal);
        }
        assertThat(quoteCost(portSaid)).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("A zone that cannot price every size class cannot take a governorate")
    void zoneWithoutRatesIsRefused() {
        Long emptyZone = jdbc.queryForObject("INSERT INTO shipping_zone "
                + "(code, name_ar, name_en, is_active) OUTPUT INSERTED.id "
                + "VALUES ('TEST_EMPTY', 'test', 'Test empty', 1)", Long.class);
        try {
            assertThatThrownBy(() -> shippingAdminService.assignGovernorate(idOf("ASW"), emptyZone))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.SHIPPING_RATE_NOT_CONFIGURED));
            assertThat(adminView("ASW").zoneId())
                    .as("the refused call must leave the governorate closed").isNull();
        } finally {
            jdbc.update("DELETE FROM shipping_zone WHERE id = ?", emptyZone);
        }
    }

    @Test
    @DisplayName("An unknown governorate or zone is a 404")
    void unknownIdsAreNotFound() {
        assertThatThrownBy(() -> shippingAdminService.closeGovernorate(999_999L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> shippingAdminService.assignGovernorate(999_999L, zoneIdOf("DELTA")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> shippingAdminService.assignGovernorate(idOf("ASW"), 999_999L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("The admin list shows closed governorates too, with no zone")
    void adminListIncludesClosedGovernorates() {
        List<AdminGovernorateResponse> all = shippingAdminService.listGovernorates();

        assertThat(all).hasSize(27);
        for (String code : CLOSED) {
            AdminGovernorateResponse closed = all.stream()
                    .filter(g -> g.code().equals(code)).findFirst().orElseThrow();
            assertThat(closed.served()).isFalse();
            assertThat(closed.zoneId()).isNull();
            assertThat(closed.zoneCode()).isNull();
        }
    }

    // ------------------------------------------------------------- HTTP layer

    @Test
    @DisplayName("PUT and DELETE /admin/shipping/governorates/{id}/zone work for an admin and 403 for a customer")
    void adminEndpointsAreAdminOnly() throws Exception {
        Long luxor = idOf("LUX");
        String path = "/api/v1/admin/shipping/governorates/" + luxor + "/zone";
        String body = "{\"zoneId\": " + zoneIdOf("UPPER_EGYPT") + "}";

        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        assertThat(adminView("LUX").served()).as("a forbidden call changes nothing").isFalse();

        try {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk());
            assertThat(adminView("LUX").served()).isTrue();

            mvc.perform(delete(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk());
            assertThat(adminView("LUX").served()).isFalse();
        } finally {
            shippingAdminService.closeGovernorate(luxor);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Long idOf(String governorateCode) {
        return governorateRepository.findByCode(governorateCode).orElseThrow().getId();
    }

    private Long zoneIdOf(String zoneCode) {
        return shippingAdminService.listZones().stream()
                .filter(z -> z.code().equals(zoneCode)).findFirst().orElseThrow().zoneId();
    }

    private Long idOfZone(String zoneCode) {
        return zoneIdOf(zoneCode);
    }

    private AdminGovernorateResponse adminView(String governorateCode) {
        return shippingAdminService.listGovernorates().stream()
                .filter(g -> g.code().equals(governorateCode)).findFirst().orElseThrow();
    }

    private BigDecimal quoteCost(Long governorateId) {
        return shippingService.quote(governorateId, null, guestToken, null, true, "ar")
                .shippingCost();
    }

    private PlaceOrderRequest inlineOrder(Long governorateId) {
        return new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل الاختبار", PHONE_LOCAL, null, null, governorateId,
                        "منطقة الاختبار", "شارع الاختبار", "1", null, null, null),
                "COD",
                null);
    }

    private AddressRequest addressIn(Long governorateId) {
        return new AddressRequest("HOME", "عميل الاختبار", PHONE_LOCAL, null, governorateId,
                "منطقة الاختبار", "شارع الاختبار", "1", null, null, null, null);
    }
}
