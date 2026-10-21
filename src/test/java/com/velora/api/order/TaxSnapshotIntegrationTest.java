package com.velora.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.admin.VariantAdminResponse;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.service.admin.VariantAdminService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tax belongs to the variant and is frozen on the order line. The application config carries no
 * tax setting, and changing a variant's rate later never touches an order or an issued invoice.
 */
@SpringBootTest
class TaxSnapshotIntegrationTest {

    private static final String PHONE_LOCAL = "01012345688";
    private static final String PHONE_E164 = "+201012345688";

    @Autowired private Environment environment;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private VariantAdminService variantAdminService;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long staffId;
    private Long customerId;
    private int invoiceYear;
    private Integer invoiceSequenceBaseline;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        invoiceYear = LocalDate.now().getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                (rs, n) -> rs.getInt(1), invoiceYear);
        invoiceSequenceBaseline = existing.isEmpty() ? null : existing.get(0);

        transactionTemplate.executeWithoutResult(s -> {
            Category category = new Category();
            category.setSlug("tax-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("tax-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("TAX-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1140.0000"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(20);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            staffId = newUser("tax-staff-" + unique);
            customerId = newUser("tax-customer-" + unique);
        });
    }

    @AfterEach
    void removeTestData() {
        String orders = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        jdbc.update("DELETE FROM invoice WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM cart WHERE user_id = ?", customerId);
        jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM product_variant WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", staffId, customerId);
        if (invoiceSequenceBaseline == null) {
            jdbc.update("DELETE FROM invoice_sequence WHERE fiscal_year = ?", invoiceYear);
        } else {
            jdbc.update("UPDATE invoice_sequence SET last_number = ? WHERE fiscal_year = ? "
                    + "AND last_number > ?", invoiceSequenceBaseline, invoiceYear, invoiceSequenceBaseline);
        }
    }

    @Test
    @DisplayName("No tax setting exists in the configuration")
    void noTaxSettingInConfig() {
        assertThat(environment.containsProperty("velora.business.default-tax-rate")).isFalse();
        assertThat(environment.containsProperty("velora.business.prices-include-tax")).isFalse();
    }

    @Test
    @DisplayName("A variant created without a rate gets the 14% default; an explicit rate wins")
    void defaultRate() {
        List<VariantAdminResponse> saved = variantAdminService.saveVariants(productId,
                new VariantSaveRequest(List.of(
                        item("TAX-DEF-" + productId, null),
                        item("TAX-EXP-" + productId, new BigDecimal("0.0500")))), staffId);

        assertThat(saved.get(0).taxRate()).isEqualByComparingTo(ProductVariant.DEFAULT_TAX_RATE);
        assertThat(saved.get(1).taxRate()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("Changing a variant's rate afterwards leaves the order and its invoice untouched")
    void rateChangeDoesNotTouchHistory() {
        cartService.addItem(customerId, null, new AddToCartRequest(variantId, 1), "ar");
        CustomerOrder placed = checkoutService.placeOrder(customerId, null,
                new PlaceOrderRequest(null,
                        new PlaceOrderRequest.AddressInput("عميل", PHONE_LOCAL, null, null,
                                governorateRepository.findByCode("CAI").orElseThrow().getId(),
                                "منطقة", "شارع", "1", null, null, null),
                        "COD", null, null, null), "ar");
        Long orderId = placed.getId();
        OrderResponse before = orderService.getForAdmin(orderId, "ar");
        // 1140 gross at 14%: net 1000, tax 140.
        assertThat(before.items().get(0).taxAmount()).isEqualByComparingTo("140");

        jdbc.update("UPDATE product_variant SET tax_rate = 0.0500 WHERE id = ?", variantId);

        for (FulfillmentStatus to : List.of(FulfillmentStatus.CONFIRMED, FulfillmentStatus.PROCESSING,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.OUT_FOR_DELIVERY, FulfillmentStatus.DELIVERED)) {
            orderService.changeFulfillmentStatus(orderId, to.name(), null, staffId, "ar");
        }

        OrderResponse after = orderService.getForAdmin(orderId, "ar");
        assertThat(after.taxTotal()).isEqualByComparingTo(before.taxTotal());
        assertThat(after.netTotal()).isEqualByComparingTo(before.netTotal());
        assertThat(after.items().get(0).taxAmount()).isEqualByComparingTo("140");
        assertThat(jdbc.queryForObject("SELECT tax_rate FROM order_item WHERE order_id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo("0.14");

        // The invoice, issued after the rate change, carries the order's frozen tax.
        BigDecimal invoiceTax = jdbc.queryForObject(
                "SELECT tax_total FROM invoice WHERE order_id = ?", BigDecimal.class, orderId);
        assertThat(invoiceTax).isEqualByComparingTo(before.taxTotal());
    }

    private VariantSaveRequest.VariantItem item(String sku, BigDecimal taxRate) {
        return new VariantSaveRequest.VariantItem(null, sku, null, new BigDecimal("100.00"),
                null, null, taxRate, 100, null, null, null);
    }

    private Long newUser(String emailPrefix) {
        AppUser user = new AppUser();
        user.setEmail(emailPrefix + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Tax");
        user.setLastName("Test");
        return userRepository.save(user).getId();
    }
}
