package com.velora.api.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.domain.ReservationStatus;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.inventory.repository.StockReservationRepository;
import com.velora.api.inventory.service.ReservationService;
import com.velora.api.invoice.domain.Invoice;
import com.velora.api.invoice.repository.InvoiceRepository;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.PaymentStatus;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.domain.Governorate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One customer, one product, from the cart to a delivered order with an invoice.
 *
 * <p>This test exists because of a specific afternoon. Three bugs shipped past 138
 * green unit tests, and all three needed the same thing to surface: a real order
 * moving through real transactions over simulated time.
 *
 * <ul>
 *   <li>A lazily-loaded shipping zone read after its read-only transaction closed.</li>
 *   <li>Cart entities detached by the reservation's {@code clearAutomatically}, so
 *       product translations could not be read while building the order.</li>
 *   <li>A reservation expiring twenty minutes after the order was placed, so shipping
 *       committed nothing and sold stock quietly returned to the shelf.</li>
 * </ul>
 *
 * <p>The third one is the reason this file matters most. It was completely silent:
 * the order shipped, the invoice issued, and the only symptom was numbers that never
 * moved — surfacing days later when someone bought an item already in a box.
 *
 * <p>Runs against the real database on purpose. Every one of those bugs lived in the
 * seam between transactions, and a mock has no seams.
 */
@SpringBootTest
class PurchaseJourneyIntegrationTest {

    private static final String GUEST_TOKEN_PREFIX = "journey-test-";
    private static final int OPENING_STOCK = 5;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("1000.0000");

    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private ReservationService reservationService;

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StockReservationRepository reservationRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceService invoiceService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private GovernorateRepository governorateRepository;

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long governorateId;
    private String guestToken;

    private int currentFiscalYear;
    private Integer invoiceSequenceBaseline;

    // ------------------------------------------------------------------- setup

    @BeforeEach
    void createSellableProduct() {
        guestToken = GUEST_TOKEN_PREFIX + UUID.randomUUID();
        String unique = UUID.randomUUID().toString().substring(0, 8);

        /*
         * Invoice numbers are gapless by design (InvoiceService — a real accounting
         * requirement), which means issuing one here for a test order permanently
         * burns a number: the invoice row gets deleted below, but nothing decrements
         * invoice_sequence.last_number to match. Every test run that reaches
         * DELIVERED pushes the sequence further ahead of the real invoice count,
         * forever. Captured here and restored in tearDown() so this test leaves the
         * sequence exactly as it found it.
         */
        currentFiscalYear = LocalDate.now().getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                (rs, rowNum) -> rs.getInt("last_number"), currentFiscalYear);
        invoiceSequenceBaseline = existing.isEmpty() ? null : existing.get(0);

        transactionTemplate.executeWithoutResult(status -> {
            Category category = new Category();
            category.setSlug("journey-cat-" + unique);
            category.setActive(false);            // invisible to the storefront
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("journey-product-" + unique);
            // Must be ACTIVE: the cart refuses anything that is not for sale.
            product.setStatus(ProductStatus.ACTIVE);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("JOURNEY-" + unique.toUpperCase());
            variant.setPrice(UNIT_PRICE);
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(OPENING_STOCK);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            // Any governorate that actually has a shipping rate — without one the
            // checkout refuses before it reaches anything worth testing.
            governorateId = governorateRepository.findByCode("CAI")
                    .map(Governorate::getId)
                    .orElseGet(() -> governorateRepository
                            .findByActiveTrueOrderByDisplayOrderAsc().get(0).getId());
        });
    }

    @AfterEach
    void removeTestData() {
        /*
         * Raw SQL rather than repositories: teardown has to run in foreign-key order
         * across seven tables, and expressing that through JPA cascades would mean
         * shaping production mappings around a test.
         */
        String orderFilter = "SELECT id FROM customer_order WHERE contact_phone = '+201012345678'";

        jdbc.update("DELETE FROM invoice WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = '+201012345678'");
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart WHERE guest_token LIKE ?", GUEST_TOKEN_PREFIX + "%");
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM variant_attribute_value WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category_translation WHERE category_id = ?", categoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);

        // Restore the sequence to what this test found it at — see the comment in
        // createSellableProduct(). Guarded to only ever move it down, never up, in
        // case something else advanced it concurrently.
        if (invoiceSequenceBaseline != null) {
            jdbc.update("UPDATE invoice_sequence SET last_number = ? "
                            + "WHERE fiscal_year = ? AND last_number > ?",
                    invoiceSequenceBaseline, currentFiscalYear, invoiceSequenceBaseline);
        }
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("Cart to delivered: stock moves correctly at every step and an invoice is issued")
    void fullPurchaseJourney() {
        // ---- 1. Add to cart. Nothing is held yet: a cart is a proposal. ----
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");

        Inventory afterCart = stock();
        assertThat(afterCart.getQtyOnHand()).isEqualTo(OPENING_STOCK);
        assertThat(afterCart.getQtyReserved())
                .as("a cart must not hold stock")
                .isZero();

        // ---- 2. Place the order. NOW stock is held. ----
        Long orderId = placeOrder();

        Inventory afterOrder = stock();
        assertThat(afterOrder.getQtyOnHand())
                .as("the goods are still physically here until they ship")
                .isEqualTo(OPENING_STOCK);
        assertThat(afterOrder.getQtyReserved()).isEqualTo(2);
        assertThat(afterOrder.getAvailable()).isEqualTo(OPENING_STOCK - 2);

        // ---- 3. Confirm and pick. Stock does not move. ----
        advance(orderId, FulfillmentStatus.CONFIRMED);
        advance(orderId, FulfillmentStatus.PROCESSING);

        assertThat(stock().getQtyOnHand()).isEqualTo(OPENING_STOCK);
        assertThat(stock().getQtyReserved()).isEqualTo(2);

        // ---- 4. Ship. The hold becomes a real reduction. ----
        advance(orderId, FulfillmentStatus.SHIPPED);

        Inventory afterShip = stock();
        assertThat(afterShip.getQtyOnHand())
                .as("shipping is what actually removes goods")
                .isEqualTo(OPENING_STOCK - 2);
        assertThat(afterShip.getQtyReserved()).isZero();
        assertThat(afterShip.getAvailable())
                .as("available must not change on shipping — those units were never "
                        + "available to anyone else while held")
                .isEqualTo(OPENING_STOCK - 2);

        // Not delivered yet, so there is no invoice — the response must say so
        // plainly rather than the field silently being missing from the DTO.
        assertThat(orderService.getForAdmin(orderId, "ar").invoiceNumber())
                .as("no invoice before delivery")
                .isNull();

        // ---- 5. Deliver. The invoice issues itself. ----
        advance(orderId, FulfillmentStatus.OUT_FOR_DELIVERY);
        advance(orderId, FulfillmentStatus.DELIVERED);

        Invoice invoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        assertThat(invoice)
                .as("delivery must produce an invoice with no further action")
                .isNotNull();
        assertThat(invoice.getInvoiceNumber()).startsWith("VLR-INV-");
        assertThat(invoice.getSequenceNumber()).isPositive();

        // The customer needs this number to build GET /me/invoices/{invoiceNumber}/pdf.
        OrderResponse afterDelivery = orderService.getForAdmin(orderId, "ar");
        assertThat(afterDelivery.invoiceNumber()).isEqualTo(invoice.getInvoiceNumber());

        // Payment stays PENDING on a delivered COD order until the courier remits.
        CustomerOrder delivered = order(orderId);
        assertThat(delivered.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(delivered.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        // The invoice snapshotted the order, not the catalog.
        assertThat(invoice.getGrandTotal()).isEqualByComparingTo(delivered.getGrandTotal());
        assertThat(invoice.getBuyerName()).isEqualTo(delivered.getContactName());
    }

    @Test
    @DisplayName("REGRESSION: an order's hold survives the TTL — it is not an abandoned cart")
    void orderHoldSurvivesExpiry() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");
        Long orderId = placeOrder();

        assertThat(stock().getQtyReserved()).isEqualTo(2);

        // Twenty-one minutes pass before anyone gets round to shipping. Real orders
        // sit for days.
        expireAllHoldsFor(variantId);
        int released = reservationService.releaseExpired(100);

        assertThat(released)
                .as("a hold attached to an order must never be auto-released — the sale "
                        + "already happened")
                .isZero();
        assertThat(stock().getQtyReserved())
                .as("stock is still committed to this order")
                .isEqualTo(2);

        // And shipping still has something to commit.
        advance(orderId, FulfillmentStatus.CONFIRMED);
        advance(orderId, FulfillmentStatus.PROCESSING);
        advance(orderId, FulfillmentStatus.SHIPPED);

        assertThat(stock().getQtyOnHand())
                .as("this is the silent failure: shipping with no hold left leaves "
                        + "on-hand untouched, and sold goods stay countable")
                .isEqualTo(OPENING_STOCK - 2);
    }

    @Test
    @DisplayName("An abandoned cart's hold IS released — that is what the TTL is for")
    void abandonedCheckoutHoldIsReleased() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");

        // A hold taken during checkout, before any order exists.
        ProductVariant variant = transactionTemplate.execute(s ->
                variantRepository.findById(variantId).orElseThrow());
        Long cartId = jdbc.queryForObject(
                "SELECT id FROM cart WHERE guest_token = ?", Long.class, guestToken);

        boolean reserved = reservationService.tryReserveOne(variant, 1, cartId);
        assertThat(reserved).isTrue();
        assertThat(stock().getQtyReserved()).isEqualTo(1);

        expireAllHoldsFor(variantId);
        int released = reservationService.releaseExpired(100);

        assertThat(released)
                .as("a cart-only hold has no sale behind it and must be freed")
                .isEqualTo(1);
        assertThat(stock().getQtyReserved()).isZero();
        assertThat(stock().getAvailable()).isEqualTo(OPENING_STOCK);
    }

    @Test
    @DisplayName("Cancelling before shipment returns the stock")
    void cancellationReturnsStock() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 3), "ar");
        Long orderId = placeOrder();

        assertThat(stock().getAvailable()).isEqualTo(OPENING_STOCK - 3);

        orderService.cancelByStaff(orderId, "اختبار الإلغاء", null, "ar");

        Inventory afterCancel = stock();
        assertThat(afterCancel.getQtyReserved()).isZero();
        assertThat(afterCancel.getQtyOnHand()).isEqualTo(OPENING_STOCK);
        assertThat(afterCancel.getAvailable()).isEqualTo(OPENING_STOCK);

        assertThat(reservationRepository.findByOrderIdAndStatus(
                orderId, ReservationStatus.HELD)).isEmpty();
    }

    @Test
    @DisplayName("Cancelling after shipment is refused — the parcel is gone")
    void cannotCancelAfterShipping() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
        Long orderId = placeOrder();

        advance(orderId, FulfillmentStatus.CONFIRMED);
        advance(orderId, FulfillmentStatus.PROCESSING);
        advance(orderId, FulfillmentStatus.SHIPPED);

        assertThatThrownBy(() ->
                orderService.cancelByStaff(orderId, "too late", null, "ar"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SHIPPED");
    }

    @Test
    @DisplayName("Delivering twice does not issue a second invoice or burn a number")
    void invoiceIssuanceIsIdempotent() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
        Long orderId = placeOrder();

        advance(orderId, FulfillmentStatus.CONFIRMED);
        advance(orderId, FulfillmentStatus.PROCESSING);
        advance(orderId, FulfillmentStatus.SHIPPED);
        advance(orderId, FulfillmentStatus.OUT_FOR_DELIVERY);
        advance(orderId, FulfillmentStatus.DELIVERED);

        Invoice first = invoiceRepository.findByOrderId(orderId).orElseThrow();

        Integer beforeCounter = jdbc.queryForObject(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                Integer.class, first.getFiscalYear());

        // The manual re-issue an operator reaches for when unsure whether it worked.
        Invoice second = invoiceService.issueForOrder(orderId);

        Integer afterCounter = jdbc.queryForObject(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                Integer.class, first.getFiscalYear());

        assertThat(second.getInvoiceNumber()).isEqualTo(first.getInvoiceNumber());
        assertThat(afterCounter)
                .as("an invoice number must never be consumed without an invoice")
                .isEqualTo(beforeCounter);
    }

    @Test
    @DisplayName("Ordering more than exists is refused before anything is written")
    void cannotOverOrder() {
        assertThatThrownBy(() -> cartService.addItem(
                null, guestToken, new AddToCartRequest(variantId, OPENING_STOCK + 1), "ar"))
                .isInstanceOf(BusinessException.class);

        assertThat(stock().getQtyReserved())
                .as("a refused add must leave no trace")
                .isZero();
    }

    // ----------------------------------------------------------------- helpers

    private Long placeOrder() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل الاختبار",
                        "01012345678",
                        null,
                        null,
                        governorateId,
                        "منطقة الاختبار",
                        "شارع الاختبار",
                        "1", null, null, null),
                "COD",
                null);

        CustomerOrder order = checkoutService.placeOrder(null, guestToken, request, "ar");
        return order.getId();
    }

    private void advance(Long orderId, FulfillmentStatus to) {
        // A note is required for the outcomes someone will need explained later.
        orderService.changeFulfillmentStatus(orderId, to.name(), "اختبار آلي", null, "ar");
    }

    /**
     * Time travel, without sleeping.
     *
     * <p>Pushing {@code expires_at} into the past is exactly what twenty-one minutes
     * of real time would produce, and it keeps the test instant.
     */
    private void expireAllHoldsFor(Long variantId) {
        jdbc.update("UPDATE stock_reservation SET expires_at = DATEADD(minute, -1, "
                + "SYSDATETIMEOFFSET()) WHERE variant_id = ? AND status = 'HELD'", variantId);
    }

    private Inventory stock() {
        return transactionTemplate.execute(s ->
                inventoryRepository.findByVariantId(variantId).orElseThrow());
    }

    private CustomerOrder order(Long orderId) {
        return transactionTemplate.execute(s ->
                orderRepository.findWithItemsById(orderId).orElseThrow());
    }
}
