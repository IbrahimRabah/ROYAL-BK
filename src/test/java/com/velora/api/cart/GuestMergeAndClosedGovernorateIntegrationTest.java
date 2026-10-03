package com.velora.api.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.dto.CartResponse;
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
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.shipping.repository.GovernorateRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Two behaviours the frontend notes rely on, run rather than read from the code: a saved address
 * in a closed governorate is refused, and merging a guest cart skips what can no longer be bought.
 */
@SpringBootTest
class GuestMergeAndClosedGovernorateIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private CartService cartService;
    @Autowired private GuestTokenService guestTokenService;
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
    private Long userId;
    private Long readyProductId;
    private Long readyVariantId;
    private Long flippedProductId;
    private Long flippedVariantId;
    private String guestToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        guestToken = guestTokenService.generate();

        transactionTemplate.executeWithoutResult(s -> {
            Category category = new Category();
            category.setSlug("merge-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            long[] ready = newSellable("merge-ready-" + unique);
            readyProductId = ready[0];
            readyVariantId = ready[1];
            long[] flipped = newSellable("merge-flip-" + unique);
            flippedProductId = flipped[0];
            flippedVariantId = flipped[1];

            AppUser user = new AppUser();
            user.setEmail("merge-" + unique + "@example.com");
            user.setPasswordHash("not-a-real-hash");
            user.setFirstName("Merge");
            user.setLastName("Test");
            userId = userRepository.save(user).getId();
        });
    }

    private long[] newSellable(String slug) {
        Product product = new Product();
        product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        product.setSlug(slug);
        product.setStatus(ProductStatus.ACTIVE);
        product.setShippingSizeClass(ShippingSizeClass.SMALL);
        Long productId = productRepository.save(product).getId();

        ProductVariant variant = new ProductVariant();
        variant.setProduct(productRepository.findById(productId).orElseThrow());
        variant.setSku(slug.toUpperCase());
        variant.setPrice(new BigDecimal("500.0000"));
        variant.setTaxRate(new BigDecimal("0.1400"));
        variant.setWeightGrams(100);
        variant.setStatus(VariantStatus.ACTIVE);
        Long variantId = variantRepository.save(variant).getId();

        Inventory inventory = new Inventory();
        inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
        inventory.setQtyOnHand(10);
        inventory.setQtyReserved(0);
        inventory.setMinStockLevel(1);
        inventoryRepository.save(inventory);
        return new long[] {productId, variantId};
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM customer_address WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id IN (?, ?)", readyVariantId, flippedVariantId);
        jdbc.update("DELETE FROM cart WHERE user_id = ? OR guest_token = ?", userId, guestToken);
        jdbc.update("DELETE FROM inventory WHERE variant_id IN (?, ?)", readyVariantId, flippedVariantId);
        jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?)", readyVariantId, flippedVariantId);
        jdbc.update("DELETE FROM product WHERE id IN (?, ?)", readyProductId, flippedProductId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    // ============================================================ addresses

    @Test
    @DisplayName("Creating an address in a closed governorate is 409 GOVERNORATE_NOT_SERVED; an open one is accepted")
    void createInClosedGovernorate() throws Exception {
        for (String closed : List.of("SSI", "ASW", "LUX")) {
            saveAddress(post("/api/v1/me/addresses"), closed)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("GOVERNORATE_NOT_SERVED"));
        }
        assertThat(addressCount()).isZero();

        saveAddress(post("/api/v1/me/addresses"), "CAI").andExpect(status().isCreated());
        assertThat(addressCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Moving an existing address into a closed governorate is refused and leaves it where it was")
    void updateToClosedGovernorate() throws Exception {
        String created = saveAddress(post("/api/v1/me/addresses"), "CAI").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");

        saveAddress(put("/api/v1/me/addresses/{id}", id), "ASW")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOVERNORATE_NOT_SERVED"));

        assertThat(jdbc.queryForObject("SELECT governorate_id FROM customer_address WHERE id = ?",
                Long.class, id.longValue())).isEqualTo(governorateId("CAI"));

        saveAddress(put("/api/v1/me/addresses/{id}", id), "ALX").andExpect(status().isOk());
    }

    // ================================================================= merge

    @Test
    @DisplayName("Once a product stops being READY_MADE, its guest cart line is flagged unavailable and blocks checkout")
    void guestCartLineBecomesUnavailable() {
        cartService.addItem(null, guestToken, new AddToCartRequest(flippedVariantId, 1), "ar");
        CartResponse before = cartService.getCart(null, guestToken, "ar");
        assertThat(before.checkoutReady()).isTrue();

        makeToOrder(flippedProductId);

        CartResponse after = cartService.getCart(null, guestToken, "ar");
        assertThat(after.warnings()).extracting(w -> w.code()).contains("PRODUCT_UNAVAILABLE");
        assertThat(after.checkoutReady()).isFalse();
    }

    @Test
    @DisplayName("Merging a guest cart skips the MADE_TO_ORDER line silently and brings the rest")
    void mergeSkipsMadeToOrder() throws Exception {
        cartService.addItem(null, guestToken, new AddToCartRequest(readyVariantId, 2), "ar");
        cartService.addItem(null, guestToken, new AddToCartRequest(flippedVariantId, 1), "ar");
        makeToOrder(flippedProductId);

        mvc.perform(post("/api/v1/cart/merge").with(signedIn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestToken\":\"" + guestToken + "\"}"))
                .andExpect(status().isOk())                                   // no error, no warning
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(readyVariantId))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.checkoutReady").value(true));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_item ci JOIN cart c ON c.id = ci.cart_id "
                + "WHERE c.user_id = ? AND ci.variant_id = ?", Integer.class, userId, flippedVariantId))
                .as("the account cart never received it").isZero();
    }

    // =============================================================== helpers

    private void makeToOrder(Long productId) {
        jdbc.update("UPDATE product SET fulfillment_type = 'MADE_TO_ORDER' WHERE id = ?", productId);
    }

    private ResultActions saveAddress(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                      String governorateCode) throws Exception {
        return mvc.perform(request.with(signedIn()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientName\":\"عميل الاختبار\",\"phone\":\"01012345685\","
                        + "\"governorateId\":" + governorateId(governorateCode)
                        + ",\"streetAddress\":\"شارع الاختبار\"}"));
    }

    private Long governorateId(String code) {
        return governorateRepository.findByCode(code).orElseThrow().getId();
    }

    private int addressCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customer_address WHERE user_id = ?",
                Integer.class, userId);
    }

    private RequestPostProcessor signedIn() {
        UserPrincipal principal = UserPrincipal.of(userId, "merge@example.com", null, List.of("CUSTOMER"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
