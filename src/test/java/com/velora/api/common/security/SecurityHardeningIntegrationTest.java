package com.velora.api.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.security.GuestTokenService;
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
import com.velora.api.common.storage.StorageService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Three access-control fixes, each with a caller that must be refused and a caller that must
 * still get through: the anonymous cart merge, whose cart a shipping quote may read, and who may
 * download an invoice PDF.
 */
@SpringBootTest
class SecurityHardeningIntegrationTest {

    private static final String PHONE_LOCAL = "01012345699";
    private static final String PHONE_E164 = "+201012345699";

    @Autowired private WebApplicationContext context;
    @Autowired private CartService cartService;
    @Autowired private GuestTokenService guestTokenService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private StorageService storageService;
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
    private Long staffId;
    private Long ownerId;
    private Long strangerId;
    private Long cairoId;
    private final List<Long> cartIds = new ArrayList<>();
    private int invoiceYear;
    private Integer invoiceSequenceBaseline;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String unique = UUID.randomUUID().toString().substring(0, 8);

        invoiceYear = LocalDate.now().getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                (rs, n) -> rs.getInt(1), invoiceYear);
        invoiceSequenceBaseline = existing.isEmpty() ? null : existing.get(0);

        cairoId = governorateRepository.findByCode("CAI").orElseThrow().getId();

        transactionTemplate.executeWithoutResult(s -> {
            Category category = new Category();
            category.setSlug("sec-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("sec-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("SEC-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(20);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            staffId = newUser("sec-staff-" + unique);
            ownerId = newUser("sec-owner-" + unique);
            strangerId = newUser("sec-stranger-" + unique);
        });
    }

    @AfterEach
    void removeTestData() {
        String orders = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        for (String key : jdbc.queryForList("SELECT pdf_key FROM invoice WHERE order_id IN ("
                + orders + ") AND pdf_key IS NOT NULL", String.class)) {
            storageService.delete(key);
        }
        jdbc.update("DELETE FROM invoice WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        for (Long cartId : cartIds) {
            jdbc.update("DELETE FROM cart_item WHERE cart_id = ?", cartId);
            jdbc.update("DELETE FROM cart WHERE id = ?", cartId);
        }
        jdbc.update("DELETE FROM cart WHERE user_id IN (?, ?)", ownerId, strangerId);
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", staffId, ownerId, strangerId);

        if (invoiceSequenceBaseline == null) {
            jdbc.update("DELETE FROM invoice_sequence WHERE fiscal_year = ?", invoiceYear);
        } else {
            jdbc.update("UPDATE invoice_sequence SET last_number = ? WHERE fiscal_year = ? "
                    + "AND last_number > ?", invoiceSequenceBaseline, invoiceYear, invoiceSequenceBaseline);
        }
    }

    // ===================================================================== cart merge

    @Test
    @DisplayName("POST /cart/merge: anonymous is 401 (not 500); a signed-in customer still merges")
    void mergeNeedsSignIn() throws Exception {
        String body = "{\"guestToken\":\"" + guestTokenService.generate() + "\"}";

        mvc.perform(post("/api/v1/cart/merge").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/v1/cart/merge").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(signedIn(ownerId, "CUSTOMER")))
                .andExpect(status().isOk());
        trackUserCart(ownerId);
    }

    @Test
    @DisplayName("Anonymous callers of other protected routes get 401 too")
    void otherAnonymousRoutes() throws Exception {
        mvc.perform(get("/api/v1/admin/invoices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/orders")).andExpect(status().isUnauthorized());
    }

    // ===================================================================== shipping quote

    @Test
    @DisplayName("POST /shipping/quote with a cartId: only the guest holding that cart, or its owner")
    void quoteOnlyOwnCart() throws Exception {
        String ownerToken = guestTokenService.generate();
        String otherToken = guestTokenService.generate();
        cartService.addItem(null, ownerToken, new AddToCartRequest(variantId, 2), "ar");
        Long guestCartId = cartService.getCart(null, ownerToken, "ar").cartId();
        cartIds.add(guestCartId);

        // The guest's own cart still quotes, with and without naming it.
        quote(guestCartId, ownerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderSubtotal").value(2000.0));
        quote(null, ownerToken, null).andExpect(status().isOk());

        // Someone else's guest session, no session, and a signed-in stranger are all refused.
        quote(guestCartId, otherToken, null).andExpect(status().isNotFound());
        quote(guestCartId, null, null).andExpect(status().isNotFound());
        quote(guestCartId, null, signedIn(strangerId, "CUSTOMER")).andExpect(status().isNotFound());

        // An account cart: its owner quotes, a different customer does not.
        cartService.addItem(ownerId, null, new AddToCartRequest(variantId, 1), "ar");
        Long userCartId = cartService.getCart(ownerId, null, "ar").cartId();
        quote(userCartId, null, signedIn(ownerId, "CUSTOMER")).andExpect(status().isOk());
        quote(userCartId, null, signedIn(strangerId, "CUSTOMER")).andExpect(status().isNotFound());
        quote(userCartId, ownerToken, null).andExpect(status().isNotFound());

        // A cart that does not exist looks exactly like one that is not yours.
        quote(999_999_999L, ownerToken, null).andExpect(status().isNotFound());
    }

    // ===================================================================== invoice PDFs

    @Test
    @DisplayName("Invoice PDFs: no static URL, admin and the owning customer only")
    void invoicePdfAccess() throws Exception {
        Long orderId = deliveredOrderOf(ownerId);
        String invoiceNumber = invoiceService.findInvoiceNumberForOrder(orderId);
        assertThat(invoiceNumber).isNotNull();
        Long invoiceId = jdbc.queryForObject(
                "SELECT id FROM invoice WHERE order_id = ?", Long.class, orderId);

        // The static route is closed to everyone, admin included.
        String staticPath = "/uploads/invoices/" + invoiceYear + "/"
                + String.format("%02d", LocalDate.now().getMonthValue()) + "/" + invoiceNumber + ".pdf";
        mvc.perform(get(staticPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(staticPath).with(signedIn(staffId, "ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get(staticPath).with(signedIn(ownerId, "CUSTOMER"))).andExpect(status().isForbidden());
        // Other uploads (product and portfolio images) stay public: the request passes security
        // and only misses because no such file exists (that miss currently surfaces as a 500,
        // a separate pre-existing wart; what matters here is that it is not a 401/403).
        for (String open : List.of("/uploads/products/2000/01/none.png",
                "/uploads/portfolio/2000/01/none.png", "/uploads/categories/2000/01/none.png")) {
            int code = mvc.perform(get(open)).andReturn().getResponse().getStatus();
            assertThat(code).as(open).isNotIn(401, 403);
        }

        // The admin response points at the authenticated endpoint, not at /uploads.
        mvc.perform(get("/api/v1/admin/invoices/" + invoiceId).with(signedIn(staffId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pdfUrl").value("/api/v1/admin/invoices/" + invoiceId + "/pdf"));

        // Admin downloads any invoice.
        mvc.perform(get("/api/v1/admin/invoices/" + invoiceId + "/pdf").with(signedIn(staffId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
        // Customers and anonymous callers cannot use the admin endpoint.
        mvc.perform(get("/api/v1/admin/invoices/" + invoiceId + "/pdf"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/invoices/" + invoiceId + "/pdf").with(signedIn(ownerId, "CUSTOMER")))
                .andExpect(status().isForbidden());

        // The customer downloads their own invoice and nobody else's.
        String mine = "/api/v1/me/invoices/" + invoiceNumber + "/pdf";
        mvc.perform(get(mine).with(signedIn(ownerId, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
        mvc.perform(get(mine).with(signedIn(strangerId, "CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(get(mine)).andExpect(status().isUnauthorized());
    }

    // ===================================================================== helpers

    private org.springframework.test.web.servlet.ResultActions quote(
            Long cartId, String guestToken, RequestPostProcessor auth) throws Exception {
        String json = cartId == null
                ? "{\"governorateId\":" + cairoId + "}"
                : "{\"governorateId\":" + cairoId + ",\"cartId\":" + cartId + "}";
        var request = post("/api/v1/shipping/quote").contentType(MediaType.APPLICATION_JSON).content(json);
        if (guestToken != null) {
            request.header("X-Guest-Token", guestToken);
        }
        if (auth != null) {
            request.with(auth);
        }
        return mvc.perform(request);
    }

    private Long deliveredOrderOf(Long userId) {
        cartService.addItem(userId, null, new AddToCartRequest(variantId, 1), "ar");
        CustomerOrder order = checkoutService.placeOrder(userId, null,
                new PlaceOrderRequest(null,
                        new PlaceOrderRequest.AddressInput("عميل الاختبار", PHONE_LOCAL, null, null,
                                cairoId, "منطقة", "شارع الاختبار", "1", null, null, null),
                        "COD", null, null, null), "ar");
        for (FulfillmentStatus to : List.of(FulfillmentStatus.CONFIRMED, FulfillmentStatus.PROCESSING,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.OUT_FOR_DELIVERY, FulfillmentStatus.DELIVERED)) {
            orderService.changeFulfillmentStatus(order.getId(), to.name(), null, staffId, "ar");
        }
        return order.getId();
    }

    private void trackUserCart(Long userId) {
        cartIds.addAll(jdbc.queryForList("SELECT id FROM cart WHERE user_id = ?", Long.class, userId));
    }

    private Long newUser(String emailPrefix) {
        AppUser user = new AppUser();
        user.setEmail(emailPrefix + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Security");
        user.setLastName("Test");
        return userRepository.save(user).getId();
    }

    private static RequestPostProcessor signedIn(Long userId, String role) {
        UserPrincipal principal = UserPrincipal.of(userId, "user@example.com", null, List.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
