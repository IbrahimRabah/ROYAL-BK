package com.velora.api.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.velora.api.common.storage.StorageService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.invoice.domain.InvoiceStatus;
import com.velora.api.invoice.dto.InvoiceResponse;
import com.velora.api.invoice.repository.InvoiceRepository;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.PaymentStatus;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.remittance.domain.RemittanceStatus;
import com.velora.api.remittance.dto.CreateRemittanceRequest;
import com.velora.api.remittance.dto.RemittanceResponse;
import com.velora.api.remittance.service.RemittanceService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * A cancelled invoice or settlement must not strand its order. Each test goes the whole way
 * round (issue/settle, cancel, do it again) and checks the numbering: nothing reused, nothing
 * skipped, and the cancelled document keeps what it had.
 *
 * <p>Every invoice here is issued through the normal application path (JPA), so a database that
 * rejected the application's own writes to the filtered indexes would fail these tests.
 */
@SpringBootTest
class ReplaceableDocumentsIntegrationTest {

    private static final String PHONE_LOCAL = "01012345677";
    private static final String PHONE_E164 = "+201012345677";

    @Autowired private WebApplicationContext context;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private RemittanceService remittanceService;
    @Autowired private StorageService storageService;
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
    private final List<Long> remittanceIds = new ArrayList<>();
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
            category.setSlug("repl-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("repl-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.SMALL);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("REPL-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("500.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(100);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(50);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            staffId = newUser("repl-staff-" + unique);
            customerId = newUser("repl-customer-" + unique);
        });
    }

    @AfterEach
    void removeTestData() {
        String orders = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        for (String key : jdbc.queryForList("SELECT pdf_key FROM invoice WHERE order_id IN ("
                + orders + ") AND pdf_key IS NOT NULL", String.class)) {
            storageService.delete(key);
        }
        jdbc.update("DELETE FROM audit_log WHERE entity_type = 'INVOICE' AND entity_id IN "
                + "(SELECT CAST(id AS VARCHAR(40)) FROM invoice WHERE order_id IN (" + orders + "))");
        for (Long id : remittanceIds) {
            jdbc.update("DELETE FROM audit_log WHERE entity_type = 'COD_REMITTANCE' AND entity_id = ?",
                    String.valueOf(id));
            jdbc.update("DELETE FROM cod_remittance_item WHERE remittance_id = ?", id);
            jdbc.update("DELETE FROM cod_remittance WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM invoice WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart WHERE user_id = ?", customerId);
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
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

    // =================================================================== invoices

    @Test
    @DisplayName("Issue, cancel, issue again, twice: numbers run k, k+1, k+2 with no reuse and no gap")
    void invoiceRoundTrip() {
        Long orderId = deliveredOrder();
        Long first = invoiceId(orderId);
        int k = sequenceOf(first);

        // --- cancel #1: the order now has no live invoice, and says so
        invoiceService.cancel(first, "wrong buyer name", staffId);
        assertThat(invoiceService.findInvoiceNumberForOrder(orderId)).isNull();
        assertThat(orderService.getForAdmin(orderId, "ar").invoiceNumber()).isNull();
        assertThat(invoiceService.findUninvoicedDeliveredOrders()).contains(orderId);

        // --- issue again: a NEW invoice, next number, pointing back at the cancelled one
        Long second = invoiceService.issueForOrder(orderId, staffId).getId();
        assertThat(second).isNotEqualTo(first);
        assertThat(sequenceOf(second)).isEqualTo(k + 1);
        InvoiceResponse secondView = invoiceService.get(second);
        assertThat(secondView.status()).isEqualTo("ISSUED");
        assertThat(secondView.replacesInvoiceNumber()).isEqualTo(numberOf(first));
        assertThat(invoiceService.findInvoiceNumberForOrder(orderId)).isEqualTo(numberOf(second));
        assertThat(invoiceService.findUninvoicedDeliveredOrders()).doesNotContain(orderId);

        // --- issuing again while one is live is idempotent: same invoice, no number consumed
        assertThat(invoiceService.issueForOrder(orderId, staffId).getId()).isEqualTo(second);
        assertThat(lastNumber()).isEqualTo(k + 1);

        // --- cancel #2 and issue a third
        invoiceService.cancel(second, "reissued with new address", staffId);
        Long third = invoiceService.issueForOrder(orderId, staffId).getId();
        assertThat(sequenceOf(third)).isEqualTo(k + 2);
        assertThat(invoiceService.get(third).replacesInvoiceNumber()).isEqualTo(numberOf(second));

        // --- the whole record: three invoices, consecutive, distinct, one live
        List<Integer> sequences = jdbc.queryForList(
                "SELECT sequence_number FROM invoice WHERE order_id = ? ORDER BY sequence_number",
                Integer.class, orderId);
        assertThat(sequences).containsExactly(k, k + 1, k + 2);
        assertThat(jdbc.queryForList("SELECT DISTINCT invoice_number FROM invoice WHERE order_id = ?",
                String.class, orderId)).hasSize(3);
        assertThat(statusOf(first)).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(statusOf(second)).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(statusOf(third)).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(lastNumber()).isEqualTo(k + 2);

        // the cancelled invoices keep their own numbers
        assertThat(numberOf(first)).endsWith(String.format("%06d", k));
        assertThat(numberOf(second)).endsWith(String.format("%06d", k + 1));
        assertThat(numberOf(third)).endsWith(String.format("%06d", k + 2));

        // --- the accounting trail
        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'INVOICE' AND entity_id IN (?, ?, ?) "
                        + "ORDER BY id", String.class,
                String.valueOf(first), String.valueOf(second), String.valueOf(third));
        assertThat(actions).containsExactly("INVOICE_CANCELLED", "INVOICE_REISSUED",
                "INVOICE_CANCELLED", "INVOICE_REISSUED");
    }

    @Test
    @DisplayName("The database itself refuses a second live invoice for an order")
    void databaseRefusesTwoLiveInvoices() {
        Long orderId = deliveredOrder();
        Long first = invoiceId(orderId);

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'invoice' AND COLUMN_NAME <> 'id' ORDER BY ORDINAL_POSITION
                """, String.class);
        String insertList = String.join(", ", columns);
        String selectList = columns.stream()
                .map(c -> switch (c) {
                    case "invoice_number" -> "'RYL-INV-TEST-DUPLICATE'";
                    case "sequence_number" -> "2000000000";
                    default -> c;
                })
                .reduce((a, b) -> a + ", " + b).orElseThrow();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO invoice (" + insertList + ") SELECT "
                + selectList + " FROM invoice WHERE id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Admin can see cancelled invoices: ?orderId= on the list, and a hint on the order")
    void adminSeesCancelledInvoices() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        Long orderId = deliveredOrder();
        Long first = invoiceId(orderId);

        // nothing cancelled yet: the flag is absent, not false
        mvc.perform(get("/api/v1/admin/orders/{id}", orderId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCancelledInvoices").doesNotExist());

        invoiceService.cancel(first, "typo", staffId);
        Long second = invoiceService.issueForOrder(orderId, staffId).getId();

        // admin order: live invoice number plus the hint; customer order: no hint
        mvc.perform(get("/api/v1/admin/orders/{id}", orderId).with(admin()))
                .andExpect(jsonPath("$.invoiceNumber").value(numberOf(second)))
                .andExpect(jsonPath("$.hasCancelledInvoices").value(true));
        assertThat(orderService.getForCustomer(customerId,
                orderService.getForAdmin(orderId, "ar").orderNumber(), "ar").hasCancelledInvoices()).isNull();

        // with orderId: both invoices, newest first, cancelled one shows its status
        mvc.perform(get("/api/v1/admin/invoices").param("orderId", String.valueOf(orderId)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].invoiceNumber").value(numberOf(second)))
                .andExpect(jsonPath("$.content[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.content[0].replacesInvoiceNumber").value(numberOf(first)))
                .andExpect(jsonPath("$.content[1].invoiceNumber").value(numberOf(first)))
                .andExpect(jsonPath("$.content[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.content[1].replacesInvoiceNumber").doesNotExist());

        // paging works alongside it
        mvc.perform(get("/api/v1/admin/invoices").param("orderId", String.valueOf(orderId))
                        .param("size", "1").param("page", "1").with(admin()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalPages").value(2));

        // without it the list is the unfiltered one, as before
        assertThat(invoiceService.list(null, org.springframework.data.domain.PageRequest.of(0, 1))
                .totalElements()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/admin/invoices").with(admin())).andExpect(status().isOk());
        // an order id with no invoices is an empty page, not an error
        mvc.perform(get("/api/v1/admin/invoices").param("orderId", "999999999").with(admin()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // =================================================================== settlements

    @Test
    @DisplayName("Settle, cancel, settle the same orders again: references sequential, nothing stranded")
    void settlementRoundTrip() {
        Long a = deliveredOrder();
        Long b = deliveredOrder();
        BigDecimal total = orderService.getForAdmin(a, "ar").grandTotal()
                .add(orderService.getForAdmin(b, "ar").grandTotal());

        RemittanceResponse first = settle(List.of(a, b), total);
        assertThat(first.status()).isEqualTo("SETTLED");
        assertThat(paymentOf(a)).isEqualTo(PaymentStatus.PAID);

        remittanceService.cancel(first.id(), "recorded against the wrong courier", staffId);
        assertThat(paymentOf(a)).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentOf(b)).isEqualTo(PaymentStatus.PENDING);
        // the cancelled settlement keeps its items, now inactive
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cod_remittance_item "
                + "WHERE remittance_id = ? AND is_active = 0", Integer.class, first.id())).isEqualTo(2);
        assertThat(remittanceService.unsettled()).extracting(u -> u.orderId()).contains(a, b);

        // the same orders enter a new settlement
        RemittanceResponse second = settle(List.of(a, b), total);
        assertThat(second.status()).isEqualTo("SETTLED");
        assertThat(paymentOf(a)).isEqualTo(PaymentStatus.PAID);
        assertThat(sequenceTail(second.reference())).isEqualTo(sequenceTail(first.reference()) + 1);
        assertThat(remittanceService.get(first.id()).status()).isEqualTo("CANCELLED");
        assertThat(remittanceService.get(first.id()).orders()).hasSize(2);

        // an order is in at most one ACTIVE settlement, enforced by the database
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cod_remittance_item "
                + "(remittance_id, order_id, order_number, amount, is_active) "
                + "SELECT remittance_id, order_id, order_number, amount, 1 FROM cod_remittance_item "
                + "WHERE remittance_id = ? AND order_id = ?", second.id(), a))
                .isInstanceOf(DataIntegrityViolationException.class);

        // and a cancelled settlement stays cancelled: it cannot be cancelled twice, let alone reopened
        assertThatThrownBy(() -> remittanceService.cancel(first.id(), "again", staffId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    @Test
    @DisplayName("Cancelled is the end of a settlement's life")
    void cancelledIsTerminal() {
        assertThat(RemittanceStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RemittanceStatus.CANCELLED.canBeCancelled()).isFalse();
        assertThat(RemittanceStatus.SETTLED.canBeCancelled()).isTrue();
        assertThat(RemittanceStatus.SHORT.canBeCancelled()).isTrue();
    }

    // =================================================================== helpers

    private RemittanceResponse settle(List<Long> orderIds, BigDecimal received) {
        RemittanceResponse response = remittanceService.record(new CreateRemittanceRequest(
                "بوسطة", null, LocalDate.now(), orderIds, received, null), staffId);
        remittanceIds.add(response.id());
        return response;
    }

    private RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(staffId, "staff@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }

    private Long deliveredOrder() {
        cartService.addItem(customerId, null, new AddToCartRequest(variantId, 1), "ar");
        CustomerOrder order = checkoutService.placeOrder(customerId, null,
                new PlaceOrderRequest(null,
                        new PlaceOrderRequest.AddressInput("عميل", PHONE_LOCAL, null, null,
                                governorateRepository.findByCode("CAI").orElseThrow().getId(),
                                "منطقة", "شارع", "1", null, null, null),
                        "COD", null, null, null), "ar");
        for (FulfillmentStatus to : List.of(FulfillmentStatus.CONFIRMED, FulfillmentStatus.PROCESSING,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.OUT_FOR_DELIVERY, FulfillmentStatus.DELIVERED)) {
            orderService.changeFulfillmentStatus(order.getId(), to.name(), null, staffId, "ar");
        }
        return order.getId();
    }

    private Long invoiceId(Long orderId) {
        return invoiceRepository.findActiveByOrderId(orderId).orElseThrow().getId();
    }

    private int sequenceOf(Long invoiceId) {
        return jdbc.queryForObject("SELECT sequence_number FROM invoice WHERE id = ?", Integer.class, invoiceId);
    }

    private String numberOf(Long invoiceId) {
        return jdbc.queryForObject("SELECT invoice_number FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    private InvoiceStatus statusOf(Long invoiceId) {
        return InvoiceStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM invoice WHERE id = ?", String.class, invoiceId));
    }

    private int lastNumber() {
        return jdbc.queryForObject("SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                Integer.class, invoiceYear);
    }

    private PaymentStatus paymentOf(Long orderId) {
        return PaymentStatus.valueOf(jdbc.queryForObject(
                "SELECT payment_status FROM customer_order WHERE id = ?", String.class, orderId));
    }

    private static int sequenceTail(String reference) {
        return Integer.parseInt(reference.substring(reference.length() - 4));
    }

    private Long newUser(String emailPrefix) {
        AppUser user = new AppUser();
        user.setEmail(emailPrefix + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Replace");
        user.setLastName("Test");
        return userRepository.save(user).getId();
    }
}
