package com.velora.api.customer.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.velora.api.common.dto.PageResponse;
import com.velora.api.customer.dto.CustomerDetailResponse;
import com.velora.api.customer.dto.CustomerSummaryResponse;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /admin/customers} and {@code GET /admin/customers/{id}} 500'd for every
 * customer with at least one order. {@code CustomerQueries.toOffset()} read a
 * DATETIMEOFFSET column back as the driver-specific {@code microsoft.sql.DateTimeOffset}
 * type, fell through to a {@code toString().replace(" ", "T")} fallback, and that
 * fallback broke on the driver's own format — it has a space before the date/time
 * AND another before the offset, so the blind replace mangled both into an
 * unparseable string. A customer with zero orders never exercised that fallback
 * (every timestamp column comes back null), which is why the bug went unnoticed —
 * these tests specifically place a real order so `lastOrderAt` is non-null.
 */
@SpringBootTest
class CustomerAdminServiceIntegrationTest {

    private static final int OPENING_STOCK = 5;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");

    @Autowired private CustomerAdminService customerAdminService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private AppUserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long governorateId;
    private Long userId;
    private Long orderId;

    @BeforeEach
    void seedCustomerWithOneOrder() {
        String unique = UUID.randomUUID().toString().substring(0, 8);

        transactionTemplate.executeWithoutResult(status -> {
            Category category = new Category();
            category.setSlug("cust-admin-test-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("cust-admin-test-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("CUST-ADMIN-TEST-" + unique.toUpperCase());
            variant.setPrice(UNIT_PRICE);
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(100);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(OPENING_STOCK);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            governorateId = governorateRepository.findByCode("CAI")
                    .map(Governorate::getId)
                    .orElseGet(() -> governorateRepository
                            .findByActiveTrueOrderByDisplayOrderAsc().get(0).getId());

            AppUser user = new AppUser();
            user.setEmail("cust-admin-" + unique + "@example.com");
            user.setPasswordHash("not-a-real-hash");
            user.setFirstName("Customer");
            user.setLastName("Admin Test " + unique);
            userId = userRepository.save(user).getId();
        });

        String memberToken = "cust-admin-test-" + UUID.randomUUID();
        cartService.addItem(userId, memberToken, new AddToCartRequest(variantId, 1), "ar");

        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل اختبار لوحة الإدارة", "01099999994", null, null,
                        governorateId, "منطقة", "شارع اختبار", "1", null, null, null),
                "COD", null);

        orderId = checkoutService.placeOrder(userId, memberToken, request, "ar").getId();
    }

    @AfterEach
    void tearDown() {
        if (orderId != null) {
            jdbc.update("DELETE FROM order_status_history WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_item WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM stock_reservation WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM customer_order WHERE id = ?", orderId);
        }
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        if (userId != null) {
            jdbc.update("DELETE FROM cart WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("Listing customers does not throw when a customer has a placed order, "
            + "and reports its lastOrderAt")
    void listDoesNotThrowAndReportsLastOrderAt() {
        PageResponse<CustomerSummaryResponse> page = customerAdminService.list(
                null, "recent_order", PageRequest.of(0, 25));

        CustomerSummaryResponse customer = page.content().stream()
                .filter(c -> c.id().equals(userId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seeded customer not found in the list"));

        assertThat(customer.lastOrderAt()).isNotNull();
        assertThat(customer.orderCount()).isEqualTo(1);
        // grand_total includes shipping on top of UNIT_PRICE — just prove it's not
        // the zero/null a failed aggregate would produce.
        assertThat(customer.totalSpent()).isGreaterThanOrEqualTo(UNIT_PRICE);
    }

    @Test
    @DisplayName("Every documented sort value returns without throwing")
    void everyDocumentedSortValueWorks() {
        for (String sort : new String[] {"spent_desc", "orders_desc", "recent_order", "name", null}) {
            assertThat(customerAdminService.list(null, sort, PageRequest.of(0, 25)).content())
                    .as("sort=%s", sort)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("Customer detail does not throw when the customer has a placed order, "
            + "and reports purchase timestamps")
    void getDoesNotThrowAndReportsPurchaseTimestamps() {
        CustomerDetailResponse detail = customerAdminService.get(userId);

        assertThat(detail.purchases().totalOrders()).isEqualTo(1);
        assertThat(detail.purchases().firstOrderAt()).isNotNull();
        assertThat(detail.purchases().lastOrderAt()).isNotNull();
        assertThat(detail.recentOrders()).hasSize(1);
        assertThat(detail.recentOrders().get(0).placedAt()).isNotNull();
    }
}
