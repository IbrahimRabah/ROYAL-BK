package com.velora.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.dto.MaxShippingCostRequest;
import com.velora.api.shipping.dto.ShippingBreakdownLine;
import com.velora.api.shipping.dto.ShippingQuoteResponse;
import com.velora.api.shipping.dto.ShippingRateRequest;
import com.velora.api.shipping.dto.ShippingZoneResponse;
import com.velora.api.shipping.dto.SizeRateResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.service.ShippingAdminService;
import com.velora.api.shipping.service.ShippingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shipping priced per (zone, size class), against the real database and the real
 * seeded rates from V12: the quote, the order snapshot and the admin edit all have to
 * agree, and the failure modes (no size, over the cap) have to behave.
 */
@SpringBootTest
class ShippingBySizeIntegrationTest {

    private static final String GUEST_TOKEN_PREFIX = "ship-size-test-";
    private static final String PHONE_LOCAL = "01012345679";
    private static final String PHONE_E164 = "+201012345679";

    @Autowired private ShippingService shippingService;
    @Autowired private ShippingAdminService shippingAdminService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private String guestToken;
    private Long auditBaseline;
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> variantIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
        guestToken = GUEST_TOKEN_PREFIX + UUID.randomUUID();
        Category category = new Category();
        category.setSlug("ship-size-cat-" + UUID.randomUUID().toString().substring(0, 8));
        category.setActive(false);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void removeTestData() {
        // The admin calls these tests make are audited; do not leave their entries behind.
        jdbc.update("DELETE FROM audit_log WHERE id > ? AND entity_type IN "
                + "('GOVERNORATE', 'SHIPPING_RATE', 'SHIPPING_ZONE')", auditBaseline);
        String orderFilter = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        for (Long variantId : variantIds) {
            jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        }
        jdbc.update("DELETE FROM cart WHERE guest_token LIKE ?", GUEST_TOKEN_PREFIX + "%");
        for (Long variantId : variantIds) {
            jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    // -------------------------------------------------------------------- quotes

    @Test
    @DisplayName("A cart with different size classes is priced per class and summed")
    void mixedSizeCart() {
        Long large = variantOf(ShippingSizeClass.LARGE);
        Long small = variantOf(ShippingSizeClass.SMALL);
        add(large, 1);
        add(small, 2);

        // Upper Egypt (Asyut): 1 x 550 + 2 x 150
        ShippingQuoteResponse quote = quote("AST");

        assertThat(quote.shippingCost()).isEqualByComparingTo("850.00");
        assertThat(quote.shippingCapApplied()).isFalse();
        assertThat(quote.uncappedCost()).isEqualByComparingTo("850.00");
        assertThat(quote.breakdown()).extracting(ShippingBreakdownLine::sizeClass)
                .containsExactly(ShippingSizeClass.LARGE, ShippingSizeClass.SMALL);
        assertThat(quote.breakdown().get(0).unitCost()).isEqualByComparingTo("550.00");
        assertThat(quote.breakdown().get(1).lineCost()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("A cart that passes the zone cap is charged the cap and says so")
    void cartOverTheCap() {
        add(variantOf(ShippingSizeClass.LARGE), 4);
        add(variantOf(ShippingSizeClass.SMALL), 5);

        // 4 x 550 + 5 x 150 = 2950, capped at 1500
        ShippingQuoteResponse quote = quote("AST");

        assertThat(quote.shippingCost()).isEqualByComparingTo("1500.00");
        assertThat(quote.shippingCapApplied()).isTrue();
        assertThat(quote.uncappedCost()).isEqualByComparingTo("2950.00");
        assertThat(quote.breakdown()).hasSize(2);
        assertThat(quote.estimatedTotal())
                .as("the cap, not the uncapped figure, goes into the total")
                .isEqualByComparingTo(quote.orderSubtotal().add(new BigDecimal("1500.00")));
    }

    @Test
    @DisplayName("Greater Cairo ships free whatever the cart holds")
    void cairoIsFree() {
        add(variantOf(ShippingSizeClass.LARGE), 3);
        add(variantOf(ShippingSizeClass.SMALL), 1);

        ShippingQuoteResponse quote = quote("CAI");

        assertThat(quote.shippingCost()).isEqualByComparingTo("0.00");
        assertThat(quote.freeShippingApplied()).isTrue();
        assertThat(quote.shippingCapApplied()).isFalse();
    }

    @Test
    @DisplayName("Alexandria, the Canal cities and Qalyubia are in the zones the price list names")
    void governoratesAreInTheRightZones() {
        add(variantOf(ShippingSizeClass.SMALL), 1);

        assertThat(quote("ALX").shippingCost()).isEqualByComparingTo("120.00");
        assertThat(quote("PTS").shippingCost()).isEqualByComparingTo("120.00");
        assertThat(quote("SUZ").shippingCost()).isEqualByComparingTo("120.00");
        assertThat(quote("DAK").shippingCost()).isEqualByComparingTo("100.00");
        assertThat(quote("QLY").shippingCost()).isEqualByComparingTo("0.00");
        assertThat(quote("MTR").shippingCost()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("A cart holding a product with no size class is refused, naming the SKU")
    void nullSizeQuoteIsRefused() {
        Long sized = variantOf(ShippingSizeClass.SMALL);
        Long unsized = variantOf(null);
        add(sized, 1);
        add(unsized, 1);

        assertThatThrownBy(() -> quote("AST"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIPPING_SIZE_MISSING);
                    assertThat(e.getMessage()).contains(skuOf(unsized));
                });
    }

    // ----------------------------------------------------------- order snapshot

    @Test
    @DisplayName("Placing an order stores the breakdown, not just the final number")
    void orderSnapshotCarriesTheBreakdown() {
        add(variantOf(ShippingSizeClass.LARGE), 4);
        add(variantOf(ShippingSizeClass.SMALL), 5);

        CustomerOrder placed = place("AST");

        CustomerOrder stored = orderRepository.findById(placed.getId()).orElseThrow();
        assertThat(stored.getShippingCost()).isEqualByComparingTo("1500.00");
        assertThat(stored.getShippingCapApplied()).isTrue();
        assertThat(stored.getShippingUncappedCost()).isEqualByComparingTo("2950.00");
        assertThat(stored.getShippingBreakdown()).contains("LARGE").contains("SMALL");

        OrderResponse response = orderService.getForAdmin(placed.getId(), "ar");
        assertThat(response.shippingCost()).isEqualByComparingTo("1500.00");
        assertThat(response.shippingCapApplied()).isTrue();
        assertThat(response.shippingUncappedCost()).isEqualByComparingTo("2950.00");
        assertThat(response.shippingBreakdown()).hasSize(2);
        assertThat(response.shippingBreakdown().get(0).sizeClass()).isEqualTo(ShippingSizeClass.LARGE);
        assertThat(response.shippingBreakdown().get(0).quantity()).isEqualTo(4);
        assertThat(response.shippingBreakdown().get(0).lineCost()).isEqualByComparingTo("2200.00");
        assertThat(response.grandTotal())
                .isEqualByComparingTo(response.subtotal().add(new BigDecimal("1500.00")));
    }

    @Test
    @DisplayName("The snapshot does not move when the rates change afterwards")
    void snapshotSurvivesARateChange() {
        add(variantOf(ShippingSizeClass.SMALL), 2);
        CustomerOrder placed = place("AST");

        BigDecimal original = rateOf("UPPER_EGYPT", ShippingSizeClass.SMALL);
        try {
            saveRate(new ShippingRateRequest(
                    zoneId("UPPER_EGYPT"), ShippingSizeClass.SMALL,
                    new BigDecimal("999.00"), null, null, null));

            OrderResponse response = orderService.getForAdmin(placed.getId(), "ar");
            assertThat(response.shippingCost()).isEqualByComparingTo("300.00");
            assertThat(response.shippingBreakdown().get(0).unitCost())
                    .isEqualByComparingTo("150.00");
        } finally {
            saveRate(new ShippingRateRequest(
                    zoneId("UPPER_EGYPT"), ShippingSizeClass.SMALL, original, null, null, null));
        }
    }

    @Test
    @DisplayName("An order placed before the breakdown existed still reads cleanly")
    void orderWithoutSnapshotStillReads() {
        add(variantOf(ShippingSizeClass.SMALL), 1);
        CustomerOrder placed = place("AST");

        // What a pre-V12 row looks like: only the final figure.
        jdbc.update("UPDATE customer_order SET shipping_breakdown = NULL, "
                + "shipping_cap_applied = NULL, shipping_uncapped_cost = NULL WHERE id = ?",
                placed.getId());

        OrderResponse response = orderService.getForAdmin(placed.getId(), "ar");

        assertThat(response.shippingCost()).isEqualByComparingTo("150.00");
        assertThat(response.shippingBreakdown())
                .as("null means 'not recorded', which is not the same as 'empty'")
                .isNull();
        assertThat(response.shippingUncappedCost()).isNull();
        assertThat(response.shippingCapApplied()).isFalse();
    }

    @Test
    @DisplayName("An order with an unsized product is refused and holds no stock")
    void orderWithUnsizedProductIsRefusedBeforeReserving() {
        Long unsized = variantOf(null);
        add(unsized, 2);

        assertThatThrownBy(() -> place("AST"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIPPING_SIZE_MISSING));

        Inventory inventory = inventoryRepository.findByVariantId(unsized).orElseThrow();
        assertThat(inventory.getQtyReserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order "
                + "WHERE contact_phone = ?", Integer.class, PHONE_E164)).isZero();
    }

    // -------------------------------------------------------------------- admin

    @Test
    @DisplayName("The admin can price one size class of a zone without touching the others")
    void adminSetsOneSizeRate() {
        BigDecimal original = rateOf("DELTA", ShippingSizeClass.MEDIUM);
        try {
            saveRate(new ShippingRateRequest(
                    zoneId("DELTA"), ShippingSizeClass.MEDIUM,
                    new BigDecimal("210.00"), null, null, null));

            ShippingZoneResponse delta = zone("DELTA");
            assertThat(unitCost(delta, ShippingSizeClass.MEDIUM)).isEqualByComparingTo("210.00");
            assertThat(unitCost(delta, ShippingSizeClass.SMALL)).isEqualByComparingTo("100.00");
            assertThat(unitCost(delta, ShippingSizeClass.LARGE)).isEqualByComparingTo("400.00");
            assertThat(delta.rates()).hasSize(3);
        } finally {
            saveRate(new ShippingRateRequest(
                    zoneId("DELTA"), ShippingSizeClass.MEDIUM, original, null, null, null));
        }
    }

    @Test
    @DisplayName("The zone cap can be changed and removed through the admin API")
    void adminChangesTheCap() {
        Long zoneId = zoneId("DELTA");
        BigDecimal original = zone("DELTA").maxShippingCost();
        try {
            saveMaxShippingCost(zoneId,
                    new MaxShippingCostRequest(new BigDecimal("300.00")));
            assertThat(zone("DELTA").maxShippingCost()).isEqualByComparingTo("300.00");

            add(variantOf(ShippingSizeClass.LARGE), 2);
            ShippingQuoteResponse capped = quote("DAK");
            assertThat(capped.shippingCost()).isEqualByComparingTo("300.00");
            assertThat(capped.uncappedCost()).isEqualByComparingTo("800.00");

            saveMaxShippingCost(zoneId, new MaxShippingCostRequest(null));
            assertThat(zone("DELTA").maxShippingCost()).isNull();
            assertThat(quote("DAK").shippingCost()).isEqualByComparingTo("800.00");
        } finally {
            saveMaxShippingCost(zoneId, new MaxShippingCostRequest(original));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ShippingQuoteResponse quote(String governorateCode) {
        Long governorateId = governorateRepository.findByCode(governorateCode).orElseThrow().getId();
        return shippingService.quote(governorateId, null, guestToken, null, true, "ar");
    }

    private CustomerOrder place(String governorateCode) {
        Long governorateId = governorateRepository.findByCode(governorateCode).orElseThrow().getId();
        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل الاختبار", PHONE_LOCAL, null, null, governorateId,
                        "منطقة الاختبار", "شارع الاختبار", "1", null, null, null),
                "COD",
                null);
        return checkoutService.placeOrder(null, guestToken, request, "ar");
    }

    private void add(Long variantId, int quantity) {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, quantity), "ar");
    }

    /** A sellable product + variant with plenty of stock; null size = a legacy product. */
    private Long variantOf(ShippingSizeClass size) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long[] variantId = new Long[1];

        transactionTemplate.executeWithoutResult(status -> {
            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("ship-size-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(size);
            Long productId = productRepository.save(product).getId();
            productIds.add(productId);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("SHIP-SIZE-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId[0] = variantRepository.save(variant).getId();
            variantIds.add(variantId[0]);

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId[0]).orElseThrow());
            inventory.setQtyOnHand(50);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);
        });
        return variantId[0];
    }

    private String skuOf(Long variantId) {
        return variantRepository.findById(variantId).orElseThrow().getSku();
    }

    private ShippingZoneResponse zone(String code) {
        return shippingAdminService.listZones().stream()
                .filter(z -> z.code().equals(code)).findFirst().orElseThrow();
    }

    private Long zoneId(String code) {
        return zone(code).zoneId();
    }

    private BigDecimal rateOf(String zoneCode, ShippingSizeClass size) {
        return unitCost(zone(zoneCode), size);
    }

    private static BigDecimal unitCost(ShippingZoneResponse zone, ShippingSizeClass size) {
        return zone.rates().stream()
                .filter(r -> r.sizeClass() == size)
                .map(SizeRateResponse::unitCost)
                .findFirst().orElseThrow();
    }

    // The admin service takes the acting staff member for the audit log; these tests are
    // about pricing, not attribution, so they act as "system". Attribution is covered in
    // ShippingAuditIntegrationTest.
    private void saveRate(ShippingRateRequest request) {
        shippingAdminService.saveRate(request, null);
    }

    private void saveMaxShippingCost(Long zoneId, MaxShippingCostRequest request) {
        shippingAdminService.saveMaxShippingCost(zoneId, request, null);
    }
}
