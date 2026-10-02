package com.velora.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.domain.AuditLog;
import com.velora.api.audit.repository.AuditLogRepository;
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
import com.velora.api.dashboard.dto.DashboardResponse;
import com.velora.api.dashboard.repository.DashboardQueries;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.DeliverySlot;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static com.velora.api.order.domain.FulfillmentStatus.AWAITING_SCHEDULE;
import static com.velora.api.order.domain.FulfillmentStatus.CANCELLED;
import static com.velora.api.order.domain.FulfillmentStatus.CONFIRMED;
import static com.velora.api.order.domain.FulfillmentStatus.DELIVERED;
import static com.velora.api.order.domain.FulfillmentStatus.DELIVERY_FAILED;
import static com.velora.api.order.domain.FulfillmentStatus.OUT_FOR_DELIVERY;
import static com.velora.api.order.domain.FulfillmentStatus.PENDING;
import static com.velora.api.order.domain.FulfillmentStatus.PROCESSING;
import static com.velora.api.order.domain.FulfillmentStatus.SHIPPED;

/**
 * Delivery scheduling and the AWAITING_SCHEDULE status, end to end on the real database.
 *
 * <p>What has to hold: the new step is optional and changes nothing else — SHIPPED still
 * takes the stock for good and DELIVERED still issues the invoice — a missing date is reported
 * as what it is, an appointment can be set until delivery (rescheduling after a failed attempt
 * above all), the dashboard does not go blind to the new status or cry wolf about it, and the
 * database accepts every status.
 */
@SpringBootTest
class OrderDeliveryScheduleIntegrationTest {

    private static final String GUEST_PREFIX = "schedule-test-";
    private static final String PHONE_LOCAL = "01012345681";
    private static final String PHONE_E164 = "+201012345681";
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final int OPENING_STOCK = 20;

    @Autowired private WebApplicationContext context;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private DashboardQueries dashboardQueries;
    @Autowired private AuditLogRepository auditLogRepository;
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
    private Long customerId;
    private String guestToken;
    private Long auditBaseline;
    private int invoiceYear;
    private Integer invoiceSequenceBaseline;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        guestToken = GUEST_PREFIX + UUID.randomUUID();
        String unique = UUID.randomUUID().toString().substring(0, 8);

        // Delivering an order issues an invoice, which permanently uses a number. Put the
        // counter back afterwards, as PurchaseJourneyIntegrationTest does.
        invoiceYear = LocalDate.now().getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                (rs, n) -> rs.getInt(1), invoiceYear);
        invoiceSequenceBaseline = existing.isEmpty() ? null : existing.get(0);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);

        transactionTemplate.executeWithoutResult(s -> {
            Category category = new Category();
            category.setSlug("schedule-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("schedule-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("SCHEDULE-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
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

            staffId = newUser("schedule-staff-" + unique, "Schedule", "Staff " + unique);
            customerId = newUser("schedule-customer-" + unique, "Schedule", "Customer");
        });
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE id > ? AND entity_type = 'ORDER'", auditBaseline);
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", staffId);

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

    // =============================================================== the two paths

    @Test
    @DisplayName("With the new step: CONFIRMED, AWAITING_SCHEDULE, date set, PROCESSING, SHIPPED takes the stock, DELIVERED issues the invoice")
    void pathThroughAwaitingSchedule() {
        Long orderId = placeOrder(2);

        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);
        assertThat(stock().getQtyOnHand()).as("nothing has left the warehouse").isEqualTo(OPENING_STOCK);
        assertThat(stock().getQtyReserved()).as("and the order still holds its units").isEqualTo(2);

        // No date yet: refused, with the specific code, and the order has not moved.
        assertThatThrownBy(() -> advance(orderId, PROCESSING))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DELIVERY_NOT_SCHEDULED);
                    assertThat(e.getMessage()).containsIgnoringCase("delivery date");
                });
        assertThat(statusOf(orderId)).isEqualTo(AWAITING_SCHEDULE);

        orderService.scheduleDelivery(orderId, inDays(10, 10), "agreed by phone", staffId, "ar");
        assertThat(statusOf(orderId)).as("setting the date does not move the status").isEqualTo(AWAITING_SCHEDULE);

        advance(orderId, PROCESSING);
        assertThat(stock().getQtyReserved()).as("still held while it is being packed").isEqualTo(2);

        advance(orderId, SHIPPED);
        assertThat(stock().getQtyOnHand()).as("SHIPPED takes the stock for good").isEqualTo(OPENING_STOCK - 2);
        assertThat(stock().getQtyReserved()).isZero();

        assertThat(invoiceNumberOf(orderId)).as("no invoice before delivery").isNull();
        advance(orderId, OUT_FOR_DELIVERY);
        assertThat(invoiceNumberOf(orderId)).isNull();
        advance(orderId, DELIVERED);
        assertThat(invoiceNumberOf(orderId)).as("DELIVERED issues the invoice").isNotNull();

        List<String> timeline = orderService.getForAdmin(orderId, "ar").timeline().stream()
                .map(t -> t.to()).toList();
        assertThat(timeline).containsSubsequence("CONFIRMED", "AWAITING_SCHEDULE", "PROCESSING",
                "SHIPPED", "OUT_FOR_DELIVERY", "DELIVERED");
    }

    @Test
    @DisplayName("Without the new step: CONFIRMED goes straight to PROCESSING with no date, as before")
    void pathWithoutAwaitingSchedule() {
        Long orderId = placeOrder(1);

        advance(orderId, CONFIRMED);
        advance(orderId, PROCESSING);                 // no date needed: scheduling is optional
        advance(orderId, SHIPPED);
        assertThat(stock().getQtyOnHand()).isEqualTo(OPENING_STOCK - 1);
        assertThat(stock().getQtyReserved()).isZero();
        advance(orderId, OUT_FOR_DELIVERY);
        advance(orderId, DELIVERED);

        OrderResponse order = orderService.getForAdmin(orderId, "ar");
        assertThat(order.fulfillmentStatus()).isEqualTo("DELIVERED");
        assertThat(order.scheduledDeliveryAt()).isNull();
        assertThat(order.invoiceNumber()).as("the invoice is still issued on delivery").isNotNull();
    }

    @Test
    @DisplayName("The retry loop after a failed delivery still works")
    void failedDeliveryRetryStillWorks() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        advance(orderId, PROCESSING);
        advance(orderId, SHIPPED);
        advance(orderId, OUT_FOR_DELIVERY);

        orderService.changeFulfillmentStatus(orderId, "DELIVERY_FAILED", "nobody home", staffId, "ar");
        advance(orderId, OUT_FOR_DELIVERY);
        advance(orderId, DELIVERED);

        assertThat(statusOf(orderId)).isEqualTo(DELIVERED);
        assertThat(invoiceNumberOf(orderId)).isNotNull();
    }

    @Test
    @DisplayName("Cancelling from AWAITING_SCHEDULE gives the reserved stock back")
    void cancelFromAwaitingScheduleReleasesTheStock() {
        Long orderId = placeOrder(3);
        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);
        assertThat(stock().getQtyReserved()).isEqualTo(3);

        orderService.cancelByStaff(orderId, "customer changed their mind", staffId, "ar");

        assertThat(statusOf(orderId)).isEqualTo(CANCELLED);
        assertThat(stock().getQtyReserved()).as("the hold is released").isZero();
        assertThat(stock().getQtyOnHand()).as("and nothing was ever taken").isEqualTo(OPENING_STOCK);
        assertThat(orderService.getForAdmin(orderId, "ar").cancellable()).isFalse();
    }

    @Test
    @DisplayName("A customer can cancel an order that is awaiting its schedule, until it ships")
    void customerCanCancelWhileAwaitingSchedule() {
        Long orderId = placeOrderAs(customerId, 1);
        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);
        String number = orderService.getForAdmin(orderId, "ar").orderNumber();

        assertThat(orderService.getForAdmin(orderId, "ar").cancellable()).isTrue();
        assertThat(orderService.cancelByCustomer(customerId, number, "changed my mind", "ar")
                .fulfillmentStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("The new status admits no shortcuts: not to SHIPPED, not back to CONFIRMED, not from PENDING")
    void illegalMovesAroundTheNewStatus() {
        Long orderId = placeOrder(1);
        assertInvalid(orderId, AWAITING_SCHEDULE);              // PENDING -> AWAITING_SCHEDULE

        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);
        for (FulfillmentStatus target : List.of(SHIPPED, OUT_FOR_DELIVERY, DELIVERED, CONFIRMED, PENDING,
                AWAITING_SCHEDULE)) {
            assertInvalid(orderId, target);
        }

        orderService.scheduleDelivery(orderId, inDays(5, 9), null, staffId, "ar");
        advance(orderId, PROCESSING);
        assertInvalid(orderId, AWAITING_SCHEDULE);              // no going back
    }

    // ======================================================== the missing-date error

    @Test
    @DisplayName("Over HTTP, a missing date is 409 DELIVERY_NOT_SCHEDULED telling staff what to do — not a generic transition error")
    void missingDateOverHttp() throws Exception {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);

        mvc.perform(patch("/api/v1/admin/orders/{id}/fulfillment-status", orderId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PROCESSING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_SCHEDULED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("delivery date")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("/schedule")));

        // A genuinely illegal move is still the generic code.
        mvc.perform(patch("/api/v1/admin/orders/{id}/fulfillment-status", orderId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SHIPPED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    // ============================================================ the schedule endpoint

    @Test
    @DisplayName("An appointment can be set from CONFIRMED to DELIVERY_FAILED, and not before confirmation or after the end")
    void whichStatusesAllowScheduling() {
        Long pending = placeOrder(1);
        assertThatThrownBy(() -> orderService.scheduleDelivery(pending, inDays(3, 10), null, staffId, "ar"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_SCHEDULABLE);
                    assertThat(e.getMessage()).containsIgnoringCase("confirm the order");
                });

        Long order = placeOrder(1);
        advance(order, CONFIRMED);
        assertSchedulable(order, 3);                                   // CONFIRMED
        advance(order, AWAITING_SCHEDULE);
        assertSchedulable(order, 4);                                   // AWAITING_SCHEDULE (status stays)
        assertThat(statusOf(order)).isEqualTo(AWAITING_SCHEDULE);
        advance(order, PROCESSING);
        assertSchedulable(order, 5);                                   // PROCESSING
        advance(order, SHIPPED);
        assertSchedulable(order, 6);                                   // SHIPPED
        advance(order, OUT_FOR_DELIVERY);
        assertSchedulable(order, 7);                                   // OUT_FOR_DELIVERY
        advance(order, DELIVERED);
        assertThatThrownBy(() -> orderService.scheduleDelivery(order, inDays(8, 10), null, staffId, "ar"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_SCHEDULABLE));

        Long cancelled = placeOrder(1);
        advance(cancelled, CONFIRMED);
        orderService.cancelByStaff(cancelled, "no longer wanted", staffId, "ar");
        assertThatThrownBy(() -> orderService.scheduleDelivery(cancelled, inDays(3, 10), null, staffId, "ar"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_SCHEDULABLE));
    }

    @Test
    @DisplayName("After a failed delivery attempt the appointment can be moved, then the order goes out again")
    void rescheduleAfterAFailedAttempt() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        advance(orderId, PROCESSING);
        orderService.scheduleDelivery(orderId, inDays(3, 10), null, staffId, "ar");
        advance(orderId, SHIPPED);
        advance(orderId, OUT_FOR_DELIVERY);
        orderService.changeFulfillmentStatus(orderId, "DELIVERY_FAILED", "customer not at home", staffId, "ar");
        assertThat(statusOf(orderId)).isEqualTo(DELIVERY_FAILED);

        OffsetDateTime second = inDays(6, 15);
        OrderResponse moved = orderService.scheduleDelivery(
                orderId, second, "customer asked for Thursday afternoon", staffId, "ar");

        assertThat(moved.scheduledDeliveryAt().toInstant()).isEqualTo(second.toInstant());
        advance(orderId, OUT_FOR_DELIVERY);
        advance(orderId, DELIVERED);
        assertThat(statusOf(orderId)).isEqualTo(DELIVERED);
    }

    @Test
    @DisplayName("The appointment must be in the future")
    void appointmentMustBeInTheFuture() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);

        for (OffsetDateTime when : List.of(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().minusSeconds(5))) {
            assertThatThrownBy(() -> orderService.scheduleDelivery(orderId, when, null, staffId, "ar"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(orderService.getForAdmin(orderId, "ar").scheduledDeliveryAt()).isNull();
    }

    // ======================================================================= audit

    @Test
    @DisplayName("Setting and moving the appointment are audited: who, from what, to what, and why")
    void schedulingIsAudited() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        OffsetDateTime first = inDays(7, 10);
        OffsetDateTime second = inDays(9, 16);

        orderService.scheduleDelivery(orderId, first, "agreed by phone", staffId, "ar");
        orderService.scheduleDelivery(orderId, second, "customer asked to move it", staffId, "ar");
        orderService.scheduleDelivery(orderId, second, "pressed save twice", staffId, "ar");   // no change

        List<AuditLog> entries = auditEntries(orderId);
        assertThat(entries).as("two real changes; the repeat recorded nothing").hasSize(2);

        AuditLog moved = entries.get(0);   // newest first
        assertThat(moved.getEntityType()).isEqualTo("ORDER");
        assertThat(moved.getEntityLabel()).isEqualTo(orderNumberOf(orderId));
        assertThat(moved.getReason()).isEqualTo("customer asked to move it");
        assertThat(moved.getActorId()).isEqualTo(staffId);
        assertThat(instantOf(moved.getOldValue())).isEqualTo(first.toInstant());
        assertThat(instantOf(moved.getNewValue())).isEqualTo(second.toInstant());
        assertThat(OffsetDateTime.parse(moved.getNewValue()).getOffset())
                .as("written in Cairo time, whatever offset it was sent with")
                .isEqualTo(second.atZoneSameInstant(CAIRO).getOffset());

        AuditLog set = entries.get(1);
        assertThat(set.getOldValue()).as("the first appointment has no 'before'").isNull();
        assertThat(instantOf(set.getNewValue())).isEqualTo(first.toInstant());
        assertThat(set.getReason()).isEqualTo("agreed by phone");
    }

    @Test
    @DisplayName("A refused schedule leaves no audit entry")
    void refusedScheduleIsNotAudited() {
        Long orderId = placeOrder(1);       // PENDING: cannot be scheduled yet

        assertThatThrownBy(() -> orderService.scheduleDelivery(orderId, inDays(3, 10), "x", staffId, "ar"))
                .isInstanceOf(BusinessException.class);

        assertThat(auditEntries(orderId)).isEmpty();
    }

    // =============================================================== HTTP layer

    @Test
    @DisplayName("PATCH /admin/orders/{id}/schedule: 200 for an admin with the date in the response, 403 for a customer")
    void scheduleEndpointOverHttp() throws Exception {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        String when = inDays(8, 11).toString();

        mvc.perform(patch("/api/v1/admin/orders/{id}/schedule", orderId)
                        .with(signedIn(customerId, "CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledDeliveryAt\": \"" + when + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(orderService.getForAdmin(orderId, "ar").scheduledDeliveryAt()).isNull();

        mvc.perform(patch("/api/v1/admin/orders/{id}/schedule", orderId)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledDeliveryAt\": \"" + when + "\", \"note\": \"confirmed with the customer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledDeliveryAt").isNotEmpty())
                .andExpect(jsonPath("$.fulfillmentStatus").value("CONFIRMED"));

        assertThat(auditEntries(orderId)).hasSize(1);
        assertThat(auditEntries(orderId).get(0).getActorId()).isEqualTo(staffId);
    }

    @Test
    @DisplayName("Over HTTP the schedule endpoint's errors are the codes a client branches on")
    void scheduleErrorsOverHttp() throws Exception {
        Long pending = placeOrder(1);
        String future = inDays(8, 11).toString();

        mvc.perform(patch("/api/v1/admin/orders/{id}/schedule", pending).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledDeliveryAt\": \"" + future + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_SCHEDULABLE"));
        mvc.perform(patch("/api/v1/admin/orders/{id}/schedule", pending).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        Long confirmed = placeOrder(1);
        advance(confirmed, CONFIRMED);
        // The status is checked first, so a past date is only reported for an order that could be scheduled.
        mvc.perform(patch("/api/v1/admin/orders/{id}/schedule", confirmed).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledDeliveryAt\": \"2020-01-01T10:00:00+02:00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/orders/{id}/schedule", 999_999_999L).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledDeliveryAt\": \"" + future + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    // ========================================================= what the customer asks for

    @Test
    @DisplayName("The preferred date and slot are accepted at checkout and shown in the order")
    void preferredDeliveryIsStoredAndShown() {
        LocalDate wanted = today().plusDays(14);

        Long orderId = placeOrderWith(null, 1, wanted, DeliverySlot.EVENING);

        OrderResponse order = orderService.getForAdmin(orderId, "ar");
        assertThat(order.preferredDeliveryDate()).isEqualTo(wanted);
        assertThat(order.preferredDeliverySlot()).isEqualTo(DeliverySlot.EVENING);
        assertThat(order.scheduledDeliveryAt()).as("the preference is a request, not a booking").isNull();
    }

    @Test
    @DisplayName("Over HTTP: POST /orders takes preferredDeliveryDate and preferredDeliverySlot, and returns all three fields")
    void preferredDeliveryOverHttp() throws Exception {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
        String wanted = today().plusDays(10).toString();

        mvc.perform(post("/api/v1/orders").header("X-Guest-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": {"recipientName": "عميل", "phone": "%s",
                                  "governorateId": %d, "streetAddress": "شارع الاختبار"},
                                 "paymentMethod": "COD",
                                 "preferredDeliveryDate": "%s",
                                 "preferredDeliverySlot": "MORNING"}
                                """.formatted(PHONE_LOCAL, governorateId("CAI"), wanted)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preferredDeliveryDate").value(wanted))
                .andExpect(jsonPath("$.preferredDeliverySlot").value("MORNING"))
                .andExpect(jsonPath("$.scheduledDeliveryAt").doesNotExist());
    }

    @Test
    @DisplayName("An order with no preference is unchanged: the three fields are simply absent")
    void noPreferenceIsStillFine() throws Exception {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");

        mvc.perform(post("/api/v1/orders").header("X-Guest-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": {"recipientName": "عميل", "phone": "%s",
                                  "governorateId": %d, "streetAddress": "شارع الاختبار"},
                                 "paymentMethod": "COD"}
                                """.formatted(PHONE_LOCAL, governorateId("CAI"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preferredDeliveryDate").doesNotExist())
                .andExpect(jsonPath("$.preferredDeliverySlot").doesNotExist())
                .andExpect(jsonPath("$.scheduledDeliveryAt").doesNotExist());
    }

    @Test
    @DisplayName("The preferred date: today and 90 days ahead are fine; yesterday and 91 days ahead are not; a slot needs a date")
    void preferredDateValidation() {
        LocalDate today = today();

        for (LocalDate fine : List.of(today, today.plusDays(1), today.plusDays(90))) {
            Long orderId = placeOrderWith(null, 1, fine, null);
            assertThat(orderService.getForAdmin(orderId, "ar").preferredDeliveryDate()).isEqualTo(fine);
        }

        for (LocalDate bad : List.of(today.minusDays(1), today.minusDays(30), today.plusDays(91))) {
            cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 1), "ar");
            assertThatThrownBy(() -> checkoutService.placeOrder(null, guestToken,
                    request(null, bad, null), "ar"))
                    .as("date %s", bad)
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        assertThatThrownBy(() -> checkoutService.placeOrder(null, guestToken,
                request(null, null, DeliverySlot.MORNING), "ar"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).containsIgnoringCase("date");
                });
    }

    @Test
    @DisplayName("A refused preference reserves no stock and creates no order")
    void refusedPreferenceHoldsNothing() {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");

        assertThatThrownBy(() -> checkoutService.placeOrder(null, guestToken,
                request(null, today().minusDays(2), DeliverySlot.AFTERNOON), "ar"))
                .isInstanceOf(BusinessException.class);

        assertThat(stock().getQtyReserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE contact_phone = ?",
                Integer.class, PHONE_E164)).isZero();
    }

    // ===================================================================== database

    @Test
    @DisplayName("The database accepts every fulfilment status, the new one included")
    void everyStatusFitsTheCheckConstraint() {
        Long orderId = placeOrder(1);

        for (FulfillmentStatus status : FulfillmentStatus.values()) {
            jdbc.update("UPDATE customer_order SET fulfillment_status = ? WHERE id = ?", status.name(), orderId);
            assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM customer_order WHERE id = ?",
                    String.class, orderId)).isEqualTo(status.name());
        }
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE customer_order SET fulfillment_status = 'NOT_A_STATUS' WHERE id = ?", orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("The database refuses a delivery slot that is not one of the three, and a slot with no date")
    void slotConstraints() {
        Long orderId = placeOrder(1);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE customer_order SET preferred_delivery_date = '2030-01-01', "
                        + "preferred_delivery_slot = 'NOON' WHERE id = ?", orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE customer_order SET preferred_delivery_date = NULL, "
                        + "preferred_delivery_slot = 'MORNING' WHERE id = ?", orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE customer_order SET preferred_delivery_date = '2030-01-01', "
                + "preferred_delivery_slot = 'EVENING' WHERE id = ?", orderId)).isEqualTo(1);
    }

    // ==================================================================== dashboard

    @Test
    @DisplayName("The dashboard counts AWAITING_SCHEDULE orders in its queues, with an Arabic label")
    void dashboardQueuesIncludeTheNewStatus() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);

        DashboardResponse.StatusCount queue = dashboardQueues().stream()
                .filter(q -> q.status().equals("AWAITING_SCHEDULE")).findFirst().orElseThrow();

        assertThat(queue.count()).isGreaterThanOrEqualTo(1);
        assertThat(queue.label()).isEqualTo("بانتظار تحديد الموعد");
    }

    @Test
    @DisplayName("Stale: an order awaiting a date nobody set is stale; once a date is set it is not — it is waiting for that date")
    void staleAlertDistinguishesWhoIsWaiting() {
        Long noDate = placeOrder(1);
        advance(noDate, CONFIRMED);
        advance(noDate, AWAITING_SCHEDULE);

        Long hasDate = placeOrder(1);
        advance(hasDate, CONFIRMED);
        advance(hasDate, AWAITING_SCHEDULE);
        orderService.scheduleDelivery(hasDate, inDays(12, 10), null, staffId, "ar");

        Long pendingOld = placeOrder(1);                       // the rule that existed before

        for (Long id : List.of(noDate, hasDate, pendingOld)) {
            jdbc.update("UPDATE customer_order SET updated_at = DATEADD(day, -3, SYSDATETIMEOFFSET()) WHERE id = ?", id);
        }

        List<Long> stale = dashboardQueries.staleOrders(24).stream()
                .map(DashboardResponse.StaleOrder::orderId).toList();

        assertThat(stale).contains(noDate, pendingOld);
        assertThat(stale).as("a scheduled order is waiting for its date, not stuck")
                .doesNotContain(hasDate);
    }

    @Test
    @DisplayName("An order with a delivery date still ahead is not stale in any status before shipping")
    void futureDateIsNeverStaleBeforeShipping() {
        Long confirmed = placeOrder(1);
        advance(confirmed, CONFIRMED);
        orderService.scheduleDelivery(confirmed, inDays(10, 10), null, staffId, "ar");

        Long awaiting = placeOrder(1);
        advance(awaiting, CONFIRMED);
        advance(awaiting, AWAITING_SCHEDULE);
        orderService.scheduleDelivery(awaiting, inDays(10, 10), null, staffId, "ar");

        Long processing = placeOrder(1);
        advance(processing, CONFIRMED);
        advance(processing, PROCESSING);
        orderService.scheduleDelivery(processing, inDays(10, 10), null, staffId, "ar");

        for (Long id : List.of(confirmed, awaiting, processing)) {
            ageByDays(id, 3);
        }

        assertThat(staleIds()).as("each of them is waiting for its date, not stuck")
                .doesNotContain(confirmed, awaiting, processing);
    }

    @Test
    @DisplayName("The same orders count as stale again once the date has passed without them moving")
    void staleAgainWhenTheDatePasses() {
        Long confirmed = placeOrder(1);
        advance(confirmed, CONFIRMED);
        orderService.scheduleDelivery(confirmed, inDays(10, 10), null, staffId, "ar");

        Long awaiting = placeOrder(1);
        advance(awaiting, CONFIRMED);
        advance(awaiting, AWAITING_SCHEDULE);
        orderService.scheduleDelivery(awaiting, inDays(10, 10), null, staffId, "ar");

        Long processing = placeOrder(1);
        advance(processing, CONFIRMED);
        advance(processing, PROCESSING);
        orderService.scheduleDelivery(processing, inDays(10, 10), null, staffId, "ar");

        for (Long id : List.of(confirmed, awaiting, processing)) {
            ageByDays(id, 3);
        }
        assertThat(staleIds()).doesNotContain(confirmed, awaiting, processing);

        // Time passes: the appointment is now behind us and nothing shipped.
        for (Long id : List.of(confirmed, awaiting, processing)) {
            jdbc.update("UPDATE customer_order SET scheduled_delivery_at = DATEADD(hour, -2, SYSDATETIMEOFFSET()) "
                    + "WHERE id = ?", id);
        }

        assertThat(staleIds()).as("late is exactly what the list is for")
                .contains(confirmed, awaiting, processing);
    }

    @Test
    @DisplayName("An order with no date follows the plain rule: stuck in PROCESSING or CONFIRMED for a day is stale")
    void noDateStillFollowsThePlainRule() {
        Long confirmed = placeOrder(1);
        advance(confirmed, CONFIRMED);
        Long processing = placeOrder(1);
        advance(processing, CONFIRMED);
        advance(processing, PROCESSING);
        Long freshProcessing = placeOrder(1);
        advance(freshProcessing, CONFIRMED);
        advance(freshProcessing, PROCESSING);

        ageByDays(confirmed, 3);
        ageByDays(processing, 3);

        assertThat(staleIds()).contains(confirmed, processing).doesNotContain(freshProcessing);
    }

    @Test
    @DisplayName("Rescheduling into the future takes a late order off the list again")
    void reschedulingClearsTheAlert() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        advance(orderId, PROCESSING);
        orderService.scheduleDelivery(orderId, inDays(5, 10), null, staffId, "ar");
        ageByDays(orderId, 3);
        jdbc.update("UPDATE customer_order SET scheduled_delivery_at = DATEADD(hour, -2, SYSDATETIMEOFFSET()) "
                + "WHERE id = ?", orderId);
        assertThat(staleIds()).as("the appointment came and went").contains(orderId);

        orderService.scheduleDelivery(orderId, inDays(6, 10), "customer asked for another day", staffId, "ar");
        ageByDays(orderId, 3);   // even long untouched, it now has a date ahead

        assertThat(staleIds()).doesNotContain(orderId);
    }

    @Test
    @DisplayName("A fresh AWAITING_SCHEDULE order is not stale yet")
    void freshOrderIsNotStale() {
        Long orderId = placeOrder(1);
        advance(orderId, CONFIRMED);
        advance(orderId, AWAITING_SCHEDULE);

        assertThat(dashboardQueries.staleOrders(24).stream().map(DashboardResponse.StaleOrder::orderId))
                .doesNotContain(orderId);
    }

    // =================================================================== helpers

    private Long placeOrder(int quantity) {
        return placeOrderWith(null, quantity, null, null);
    }

    private Long placeOrderAs(Long userId, int quantity) {
        return placeOrderWith(userId, quantity, null, null);
    }

    private Long placeOrderWith(Long userId, int quantity, LocalDate date, DeliverySlot slot) {
        cartService.addItem(userId, userId == null ? guestToken : null,
                new AddToCartRequest(variantId, quantity), "ar");
        CustomerOrder order = checkoutService.placeOrder(userId, userId == null ? guestToken : null,
                request(userId, date, slot), "ar");
        return order.getId();
    }

    private PlaceOrderRequest request(Long userId, LocalDate date, DeliverySlot slot) {
        return new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل الاختبار", PHONE_LOCAL, null, null, governorateId("CAI"),
                        "منطقة الاختبار", "شارع الاختبار", "1", null, null, null),
                "COD", null, date, slot);
    }

    private void advance(Long orderId, FulfillmentStatus to) {
        orderService.changeFulfillmentStatus(orderId, to.name(), null, staffId, "ar");
    }

    private void assertInvalid(Long orderId, FulfillmentStatus to) {
        assertThatThrownBy(() -> advance(orderId, to))
                .as("-> %s", to)
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    private void assertSchedulable(Long orderId, int daysAhead) {
        OffsetDateTime when = inDays(daysAhead, 10);
        OrderResponse response = orderService.scheduleDelivery(orderId, when, null, staffId, "ar");
        assertThat(response.scheduledDeliveryAt().toInstant()).isEqualTo(when.toInstant());
    }

    private FulfillmentStatus statusOf(Long orderId) {
        return FulfillmentStatus.valueOf(orderService.getForAdmin(orderId, "ar").fulfillmentStatus());
    }

    private String orderNumberOf(Long orderId) {
        return orderService.getForAdmin(orderId, "ar").orderNumber();
    }

    private String invoiceNumberOf(Long orderId) {
        return invoiceService.findInvoiceNumberForOrder(orderId);
    }

    private List<Long> staleIds() {
        return dashboardQueries.staleOrders(24).stream()
                .map(DashboardResponse.StaleOrder::orderId).toList();
    }

    private void ageByDays(Long orderId, int days) {
        jdbc.update("UPDATE customer_order SET updated_at = DATEADD(day, ?, SYSDATETIMEOFFSET()) WHERE id = ?",
                -days, orderId);
    }

    private Inventory stock() {
        return inventoryRepository.findByVariantId(variantId).orElseThrow();
    }

    private List<AuditLog> auditEntries(Long orderId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                "ORDER", String.valueOf(orderId), PageRequest.of(0, 20)).getContent().stream()
                .filter(e -> e.getAction() == AuditAction.ORDER_DELIVERY_SCHEDULED).toList();
    }

    private List<DashboardResponse.StatusCount> dashboardQueues() {
        return dashboardQueries.actionQueues();
    }

    private static java.time.Instant instantOf(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    private static LocalDate today() {
        return LocalDate.now(CAIRO);
    }

    /** A time {@code days} ahead at the given hour, Cairo time: always in the future. */
    private static OffsetDateTime inDays(int days, int hour) {
        return LocalDate.now(CAIRO).plusDays(days).atTime(hour, 0).atZone(CAIRO).toOffsetDateTime();
    }

    private Long governorateId(String code) {
        return governorateRepository.findByCode(code).orElseThrow().getId();
    }

    private Long newUser(String emailPrefix, String first, String last) {
        AppUser user = new AppUser();
        user.setEmail(emailPrefix + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName(first);
        user.setLastName(last);
        return userRepository.save(user).getId();
    }

    private RequestPostProcessor admin() {
        return signedIn(staffId, "ADMIN");
    }

    private static RequestPostProcessor signedIn(Long userId, String role) {
        UserPrincipal principal = UserPrincipal.of(userId, "user@example.com", null, List.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
