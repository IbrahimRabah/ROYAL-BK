package com.velora.api.customrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.customrequest.domain.CustomRequestType;
import com.velora.api.customrequest.dto.CustomRequestCreateRequest;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.inventory.domain.Inventory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/**
 * Creating a custom request: who can, what is checked, what it must NOT do (reserve
 * stock), how the number behaves, and the per-IP limit on this public endpoint.
 */
class CustomRequestCreateIntegrationTest extends CustomRequestTestBase {

    private static final String PATH = "/api/v1/custom-requests";

    @Autowired private ObjectMapper objectMapper;

    // ------------------------------------------------------------------ guest

    @Test
    @DisplayName("A guest can create a request: no account, E.164 phone, status NEW, a number and a token")
    void guestCreatesARequest() {
        CustomRequestCreatedResponse created = createAsGuest(customWork());

        assertThat(created.requestNumber()).matches("REQ-" + year + "-\\d{6}");
        assertThat(created.status()).isEqualTo("NEW");
        assertThat(created.attachmentToken()).isNotBlank();
        assertThat(created.attachmentTokenExpiresAt()).isAfter(created.createdAt());

        var row = jdbc.queryForMap("SELECT customer_id, phone, status, quantity, email, type "
                + "FROM custom_order_request WHERE id = ?", created.id());
        assertThat(row.get("customer_id")).as("a guest has no account").isNull();
        assertThat(row.get("phone")).isEqualTo("+201012345678");
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("quantity")).as("defaults to 1").isEqualTo(1);
        assertThat(row.get("email")).isEqualTo("customer@example.com");
    }

    @Test
    @DisplayName("Over HTTP with no token at all: 201 and the request number")
    void guestCreatesOverHttp() throws Exception {
        String name = "http-guest-" + UUID.randomUUID();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json(withName(customWork(), name)))
                        .with(from(newIp())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestNumber").value(org.hamcrest.Matchers.startsWith("REQ-")))
                .andExpect(jsonPath("$.attachmentToken").isNotEmpty())
                .andExpect(jsonPath("$.status").value("NEW"));
        trackByName(name);
    }

    @Test
    @DisplayName("A signed-in customer's request is linked to their account")
    void signedInCustomerIsLinked() throws Exception {
        Long customerId = newUser("custom-req-customer-" + UUID.randomUUID().toString().substring(0, 8),
                "Cust", "Omer");
        String name = "signed-in-" + UUID.randomUUID();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json(withName(customWork(), name)))
                        .with(signedIn(customerId, "CUSTOMER")).with(from(newIp())))
                .andExpect(status().isCreated());
        trackByName(name);

        assertThat(jdbc.queryForObject("SELECT customer_id FROM custom_order_request "
                + "WHERE contact_name = ?", Long.class, name)).isEqualTo(customerId);
    }

    // ---------------------------------------------------------------- no stock

    @Test
    @DisplayName("A size request on a ready-made product reserves nothing — it is not a sale")
    void sizeRequestDoesNotReserveStock() {
        Long[] ids = productWithStock();
        Long variantId = ids[1];

        createAsGuest(sizeVariant(ids[0]));
        createAsGuest(sizeVariant(ids[0]));

        Inventory stock = inventoryRepository.findByVariantId(variantId).orElseThrow();
        assertThat(stock.getQtyOnHand()).isEqualTo(10);
        assertThat(stock.getQtyReserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservation WHERE variant_id = ?",
                Integer.class, variantId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE variant_id = ?",
                Integer.class, variantId)).isZero();
    }

    // -------------------------------------------------------------- type rules

    @Test
    @DisplayName("SIZE_VARIANT needs a product, at least one dimension, and a ready-made product")
    void sizeVariantRules() {
        assertRefused(sizeVariant(null), ErrorCode.VALIDATION_FAILED);

        CustomRequestCreateRequest noDimensions = new CustomRequestCreateRequest(
                CustomRequestType.SIZE_VARIANT, readyMadeProduct(), "اسم", "01012345678", null,
                null, governorateId("CAI"), null, null, null, null, null, 1, null);
        assertRefused(noDimensions, ErrorCode.VALIDATION_FAILED);

        Long madeToOrder = product(FulfillmentType.MADE_TO_ORDER, ProductStatus.ACTIVE);
        assertRefused(sizeVariant(madeToOrder), ErrorCode.VALIDATION_FAILED);

        assertThat(createAsGuest(sizeVariant(readyMadeProduct())).status()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("A product that is a draft, archived or unknown is 'not found' — a public caller cannot probe for drafts")
    void unavailableProductsLookNonexistent() {
        Long draft = product(FulfillmentType.READY_MADE, ProductStatus.DRAFT);
        Long archived = product(FulfillmentType.READY_MADE, ProductStatus.ARCHIVED);

        assertRefused(sizeVariant(draft), ErrorCode.PRODUCT_NOT_FOUND);
        assertRefused(sizeVariant(archived), ErrorCode.PRODUCT_NOT_FOUND);
        assertRefused(sizeVariant(999_999_999L), ErrorCode.PRODUCT_NOT_FOUND);
        assertRefused(withProduct(customWork(), draft), ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("MADE_TO_ORDER and CUSTOM_WORK work with or without a product")
    void otherTypesTakeAnOptionalProduct() {
        Long product = product(FulfillmentType.MADE_TO_ORDER, ProductStatus.ACTIVE);

        assertThat(createAsGuest(customWork()).id()).isNotNull();
        assertThat(createAsGuest(withProduct(customWork(), product)).id()).isNotNull();
        assertThat(createAsGuest(madeToOrder(null)).id()).isNotNull();
        assertThat(createAsGuest(madeToOrder(product)).id()).isNotNull();
    }

    // -------------------------------------------------------------- validation

    @Test
    @DisplayName("Bad input is refused: phone, governorate, and the fields that are required")
    void invalidInputIsRefused() throws Exception {
        assertRefused(withPhone(customWork(), "12345"), ErrorCode.INVALID_PHONE_FORMAT);
        assertRefused(withAltPhone(customWork(), "nope"), ErrorCode.INVALID_PHONE_FORMAT);
        assertRefused(withGovernorate(customWork(), 999_999L), ErrorCode.RESOURCE_NOT_FOUND);

        // Bean validation, through the HTTP layer where it runs.
        post400(withName(customWork(), ""));
        post400(withName(customWork(), null));
        post400(withPhone(customWork(), ""));
        post400(withGovernorate(customWork(), null));
        post400(withNotes(customWork(), "x".repeat(1001)));
        post400(withEmail(customWork(), "not-an-email"));
        post400(withQuantity(customWork(), 0));
        post400(new CustomRequestCreateRequest(CustomRequestType.CUSTOM_WORK, null, "اسم",
                "01012345678", null, null, governorateId("CAI"), null, null,
                new BigDecimal("-5"), null, null, 1, null));
        post400(new CustomRequestCreateRequest(null, null, "اسم", "01012345678", null, null,
                governorateId("CAI"), null, null, null, null, null, 1, null));
    }

    @Test
    @DisplayName("Notes of exactly 1000 characters are accepted; the quantity can be set")
    void boundaryValuesAreAccepted() {
        CustomRequestCreatedResponse created = createAsGuest(
                withQuantity(withNotes(customWork(), "ن".repeat(1000)), 7));

        assertThat(jdbc.queryForObject("SELECT LEN(notes) FROM custom_order_request WHERE id = ?",
                Integer.class, created.id())).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT quantity FROM custom_order_request WHERE id = ?",
                Integer.class, created.id())).isEqualTo(7);
    }

    // ----------------------------------------------------------------- counter

    @Test
    @DisplayName("Numbers are sequential with no gaps, within the year")
    void numbersAreConsecutive() {
        List<Integer> sequence = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sequence.add(sequenceOf(createAsGuest()));
        }

        for (int i = 1; i < sequence.size(); i++) {
            assertThat(sequence.get(i) - sequence.get(i - 1))
                    .as("a gap between request %d and %d", i - 1, i).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("A rolled-back request returns its number instead of burning it")
    void rollbackReturnsTheNumber() {
        int first = sequenceOf(createAsGuest());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            customRequestService.create(customWork(), null, newIp());
            throw new IllegalStateException("something after the number was allocated failed");
        })).isInstanceOf(IllegalStateException.class);

        int next = sequenceOf(createAsGuest());
        assertThat(next).as("the number the failed request held is reused, not skipped")
                .isEqualTo(first + 1);
    }

    @Test
    @DisplayName("A refused request does not use a number either")
    void refusedRequestUsesNoNumber() {
        int first = sequenceOf(createAsGuest());

        assertRefused(withPhone(customWork(), "12345"), ErrorCode.INVALID_PHONE_FORMAT);
        assertRefused(sizeVariant(null), ErrorCode.VALIDATION_FAILED);

        assertThat(sequenceOf(createAsGuest())).isEqualTo(first + 1);
    }

    @Test
    @DisplayName("Concurrent requests get distinct, consecutive numbers — also the first of a new year")
    void concurrentRequestsAreGaplessEvenOnAFreshYear() throws Exception {
        // No counter row at all: the state of the first request of a year.
        jdbc.update("DELETE FROM custom_order_request_sequence WHERE fiscal_year = ?", year);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CustomRequestCreatedResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String ip = newIp();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return customRequestService.create(customWork(), null, ip);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            List<Integer> numbers = Collections.synchronizedList(new ArrayList<>());
            for (Future<CustomRequestCreatedResponse> f : futures) {
                CustomRequestCreatedResponse created = f.get(60, TimeUnit.SECONDS);
                track(created);
                numbers.add(sequenceOf(created));
            }

            List<Integer> sorted = numbers.stream().sorted().toList();
            assertThat(sorted).doesNotHaveDuplicates();
            assertThat(sorted.get(sorted.size() - 1) - sorted.get(0))
                    .as("consecutive: %s", sorted).isEqualTo(threads - 1);
            assertThat(sorted.get(0)).as("a fresh year starts at 1").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("The database itself refuses a second request in the same slot of the sequence")
    void uniqueIndexBacksTheGuarantee() {
        CustomRequestCreatedResponse created = createAsGuest();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO custom_order_request (request_number, fiscal_year, sequence_number, "
                        + "type, status, contact_name, phone, governorate_id, quantity) "
                        + "SELECT 'REQ-DUPLICATE', fiscal_year, sequence_number, type, status, "
                        + "contact_name, phone, governorate_id, quantity "
                        + "FROM custom_order_request WHERE id = ?", created.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------- rate limit

    @Test
    @DisplayName("Ten requests an hour per IP; the eleventh is refused, another IP is not affected")
    void eleventhRequestFromOneIpIsRefused() {
        String ip = newIp();
        for (int i = 0; i < 10; i++) {
            track(customRequestService.create(customWork(), null, ip));
        }

        assertThatThrownBy(() -> customRequestService.create(customWork(), null, ip))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));

        track(customRequestService.create(customWork(), null, newIp()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custom_order_request "
                + "WHERE contact_name = ?", Integer.class, customWork().contactName()))
                .as("the refused request wrote nothing").isGreaterThanOrEqualTo(11);
    }

    @Test
    @DisplayName("Over HTTP the eleventh POST is a 429 RATE_LIMITED")
    void eleventhPostIsTooManyRequests() throws Exception {
        String ip = newIp();
        String name = "limit-http-" + UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(json(withName(customWork(), name))).with(from(ip)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json(withName(customWork(), name))).with(from(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        trackByName(name);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custom_order_request "
                + "WHERE contact_name = ?", Integer.class, name)).isEqualTo(10);
    }

    @Test
    @DisplayName("Behind a local proxy (ngrok) each visitor has their own limit, not the whole site")
    void limitIsPerVisitorBehindAProxy() throws Exception {
        String visitorA = newIp();
        String visitorB = newIp();
        String name = "limit-proxy-" + UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            postFromProxy(name, visitorA).andExpect(status().isCreated());
        }
        postFromProxy(name, visitorA).andExpect(status().isTooManyRequests());

        postFromProxy(name, visitorB).andExpect(status().isCreated());
        trackByName(name);
    }

    @Test
    @DisplayName("A caller on a public address cannot dodge the limit by sending X-Forwarded-For")
    void forwardedHeaderCannotBeUsedToDodgeTheLimit() throws Exception {
        String peer = newIp();
        String name = "limit-spoof-" + UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(json(withName(customWork(), name)))
                            .header("X-Forwarded-For", "203.0.113." + (i + 1))
                            .with(from(peer)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json(withName(customWork(), name)))
                        .header("X-Forwarded-For", "203.0.113.200")
                        .with(from(peer)))
                .andExpect(status().isTooManyRequests());
        trackByName(name);
    }

    // ----------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions postFromProxy(String name, String client)
            throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(json(withName(customWork(), name)))
                .header("X-Forwarded-For", client)
                .with(from("127.0.0.1")));
    }

    private void post400(CustomRequestCreateRequest body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(from(newIp())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void assertRefused(CustomRequestCreateRequest body, ErrorCode expected) {
        assertThatThrownBy(() -> customRequestService.create(body, null, newIp()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private int sequenceOf(CustomRequestCreatedResponse created) {
        return jdbc.queryForObject("SELECT sequence_number FROM custom_order_request WHERE id = ?",
                Integer.class, created.id());
    }

    private void trackByName(String contactName) {
        jdbc.queryForList("SELECT id FROM custom_order_request WHERE contact_name = ?",
                Long.class, contactName).forEach(this::trackRequest);
    }

    private String json(CustomRequestCreateRequest body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private CustomRequestCreateRequest madeToOrder(Long productId) {
        CustomRequestCreateRequest r = withProduct(customWork(), productId);
        return new CustomRequestCreateRequest(CustomRequestType.MADE_TO_ORDER, r.productId(),
                r.contactName(), r.phone(), r.altPhone(), r.email(), r.governorateId(), r.area(),
                r.streetAddress(), r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private CustomRequestCreateRequest withProduct(CustomRequestCreateRequest r, Long productId) {
        return new CustomRequestCreateRequest(r.type(), productId, r.contactName(), r.phone(),
                r.altPhone(), r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withName(CustomRequestCreateRequest r, String name) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), name, r.phone(),
                r.altPhone(), r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withPhone(CustomRequestCreateRequest r, String phone) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), phone,
                r.altPhone(), r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withAltPhone(CustomRequestCreateRequest r, String alt) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), r.phone(),
                alt, r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withGovernorate(CustomRequestCreateRequest r, Long id) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), r.phone(),
                r.altPhone(), r.email(), id, r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withNotes(CustomRequestCreateRequest r, String notes) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), r.phone(),
                r.altPhone(), r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), notes);
    }

    private static CustomRequestCreateRequest withEmail(CustomRequestCreateRequest r, String email) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), r.phone(),
                r.altPhone(), email, r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withQuantity(CustomRequestCreateRequest r, Integer q) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), r.phone(),
                r.altPhone(), r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), q, r.notes());
    }
}
