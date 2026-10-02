package com.velora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.velora.api.customer.dto.AddressRequest;
import com.velora.api.customer.service.AddressService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.repository.GovernorateRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code CheckoutService.placeOrder()} used to leave a {@code TODO} where
 * {@code OrderPlacedEvent} should have been published — no confirmation ever went
 * out, and a guest in particular saw their order number once and then lost it.
 *
 * <p>{@code JavaMailSender} is replaced with a mock: no real SMTP connection is
 * attempted, but {@code createMimeMessage()} returns a genuine {@code MimeMessage} so
 * {@code MailService}'s {@code MimeMessageHelper} has something real to write headers
 * and content onto.
 *
 * <p>Runs against the real database — the point being verified is that
 * {@code @TransactionalEventListener(AFTER_COMMIT)} actually fires once
 * {@code placeOrder()}'s transaction commits, which a mocked repository cannot show.
 */
// Replacing JavaMailSender with a mock removes the concrete JavaMailSenderImpl bean
// the mail health indicator scans for at startup, which otherwise fails context
// creation outright ("'beans' must not be empty") — irrelevant to what this test
// verifies, so the indicator is disabled here.
@SpringBootTest(properties = "management.health.mail.enabled=false")
class OrderConfirmationEmailIntegrationTest {

    private static final int OPENING_STOCK = 5;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");

    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private AddressService addressService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private JavaMailSender mailSender;

    private String guestToken;
    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long governorateId;
    private Long userId;
    private Long orderId;

    @BeforeEach
    void seedSellableProductAndGovernorate() {
        guestToken = "mail-test-" + UUID.randomUUID();
        String unique = UUID.randomUUID().toString().substring(0, 8);

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        transactionTemplate.executeWithoutResult(status -> {
            Category category = new Category();
            category.setSlug("mail-test-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("mail-test-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("MAIL-TEST-" + unique.toUpperCase());
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
        });
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
        jdbc.update("DELETE FROM cart WHERE guest_token = ?", guestToken);
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        if (userId != null) {
            jdbc.update("DELETE FROM cart WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM customer_address WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("A guest order with an email placed sends the confirmation")
    void guestOrderWithEmail_sendsConfirmation() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");

        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل اختبار الإيميل", "01099999991", null,
                        "mail-test-" + UUID.randomUUID() + "@example.com",
                        governorateId, "منطقة", "شارع اختبار الإيميل",
                        "1", null, null, null),
                "COD", null);

        CustomerOrder order = checkoutService.placeOrder(null, guestToken, request, "ar");
        orderId = order.getId();

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("A guest order with no email does not attempt to send anything")
    void guestOrderWithoutEmail_sendsNothing() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");

        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل بلا إيميل", "01099999992", null, null,
                        governorateId, "منطقة", "شارع اختبار الإيميل",
                        "1", null, null, null),
                "COD", null);

        CustomerOrder order = checkoutService.placeOrder(null, guestToken, request, "ar");
        orderId = order.getId();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("A signed-in customer using a saved address (no email column) "
            + "falls back to the account email")
    void savedAddressOrder_fallsBackToAccountEmail() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String accountEmail = "account-" + unique + "@example.com";

        AppUser user = new AppUser();
        user.setEmail(accountEmail);
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Test");
        userId = userRepository.save(user).getId();

        var addressResponse = addressService.create(userId, new AddressRequest(
                "HOME", "عميل مسجل", "01099999993", null, governorateId,
                "منطقة", "شارع اختبار الإيميل", "1", null, null, null, true), "ar");

        String memberToken = "mail-test-member-" + UUID.randomUUID();
        cartService.addItem(userId, memberToken, new AddToCartRequest(variantId, 1), "ar");

        PlaceOrderRequest request = new PlaceOrderRequest(
                addressResponse.id(), null, "COD", null);

        CustomerOrder order = checkoutService.placeOrder(userId, memberToken, request, "ar");
        orderId = order.getId();

        assertThat(order.getContactEmail())
                .as("no email column exists on CustomerAddress — this must come from the account")
                .isEqualTo(accountEmail);
        verify(mailSender).send(any(MimeMessage.class));
    }
}
