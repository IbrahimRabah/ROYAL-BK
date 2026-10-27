package com.velora.api.customrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.customrequest.service.MyCustomRequestService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A customer's own custom requests: only theirs, by customer_id and nothing else; the quote; and
 * accepting or rejecting it. Acceptance by phone, recorded by staff, is exercised alongside to show
 * it is unchanged. Runs against the real database, like every custom-request test.
 */
class MyCustomRequestIntegrationTest extends CustomRequestTestBase {

    private static final String ME = "/api/v1/me/custom-requests";
    private static final String INTERNAL_NOTE = "haggles, do not go under 15k";

    @Autowired private MyCustomRequestService myService;

    private Long alice;
    private Long bob;

    @BeforeEach
    void setUp() {
        alice = newUser("alice-" + System.nanoTime(), "Alice", "Customer");
        bob = newUser("bob-" + System.nanoTime(), "Bob", "Customer");
    }

    // =================================================================== ownership

    @Test
    @DisplayName("Only requests whose customer_id is the caller appear - never by phone or email, never a guest's")
    void onlyMyOwnRequests() throws Exception {
        CustomRequestCreatedResponse mine = mine(alice);
        CustomRequestCreatedResponse bobs = mine(bob);
        // Same phone AND email as Alice's, but submitted as a guest.
        CustomRequestCreatedResponse guest = createAsGuest();

        assertThat(numbersOf(getAs(alice, ME))).containsExactly(mine.requestNumber());
        assertThat(numbersOf(getAs(bob, ME))).containsExactly(bobs.requestNumber());

        // Another customer's, a guest's and an unknown reference: one and the same answer.
        for (String reference : List.of(bobs.requestNumber(), guest.requestNumber(), "REQ-2099-999999")) {
            mvc.perform(get(ME + "/" + reference).with(signedIn(alice, "CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_NOT_FOUND"));
            acceptRaw(alice, reference, "100").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_NOT_FOUND"));
            mvc.perform(post(ME + "/" + reference + "/reject").with(signedIn(alice, "CUSTOMER")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("Signing in is required")
    void signInRequired() throws Exception {
        mvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mvc.perform(get(ME + "/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post(ME + "/REQ-2026-000001/reject")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The list is newest first and the client cannot re-sort it")
    void newestFirst() throws Exception {
        CustomRequestCreatedResponse first = mine(alice);
        CustomRequestCreatedResponse second = mine(alice);
        CustomRequestCreatedResponse third = mine(alice);

        assertThat(numbersOf(getAs(alice, ME + "?sort=createdAt,asc")))
                .containsExactly(third.requestNumber(), second.requestNumber(), first.requestNumber());
    }

    // ===================================================================== shape

    @Test
    @DisplayName("The customer sees reference, type, status, date, product, their own words and the quote - nothing internal")
    void detailShapeAndNothingInternal() throws Exception {
        Long productId = readyMadeProduct();
        CustomRequestCreatedResponse created = track(customRequestService.create(
                sizeVariant(productId), alice, newIp()));
        adminService.quote(created.id(), new BigDecimal("4500.00"), INTERNAL_NOTE, staffId);

        String body = getAs(alice, ME + "/" + created.requestNumber());

        Map<String, Object> json = JsonPath.read(body, "$");
        assertThat(json.keySet()).containsExactlyInAnyOrder("requestNumber", "type", "status", "submittedAt",
                "product", "description", "quantity", "widthCm", "heightCm", "quotedAmount", "quotedAt",
                "canAccept", "canReject");
        assertThat(JsonPath.<String>read(body, "$.description")).isEqualTo("أريده أعرض قليلاً");
        assertThat(JsonPath.<Integer>read(body, "$.quantity")).isEqualTo(2);
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.product").keySet())
                .containsExactlyInAnyOrder("slug", "name");

        // The staff note is a box that also holds the reason for a rejection: it never leaves.
        assertThat(body).doesNotContain(INTERNAL_NOTE).doesNotContain("adminNote").doesNotContain("01012345678")
                .doesNotContain("customerId").doesNotContain("governorate").doesNotContain("attachments")
                .doesNotContain(":null");

        // Same for the list rows.
        String list = getAs(alice, ME);
        assertThat(list).doesNotContain(INTERNAL_NOTE).doesNotContain("adminNote").doesNotContain("description");
    }

    @Test
    @DisplayName("Before a quote there is no amount, no date, and nothing to answer - the server says so")
    void nothingToAnswerBeforeAQuote() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);

        String body = getAs(alice, ME + "/" + created.requestNumber());

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("NEW");
        assertThat(JsonPath.<Boolean>read(body, "$.canAccept")).isFalse();
        assertThat(JsonPath.<Boolean>read(body, "$.canReject")).isFalse();
        assertThat(body).doesNotContain("quotedAmount").doesNotContain("quotedAt").doesNotContain("product");

        acceptRaw(alice, created.requestNumber(), "100").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_QUOTE_REQUIRED"));
        mvc.perform(post(ME + "/" + created.requestNumber() + "/reject").with(signedIn(alice, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_QUOTE_REQUIRED"));
    }

    // ===================================================================== the quote

    @Test
    @DisplayName("A quote shows its amount and date and makes accept and reject available; the badge counts it")
    void quoteArrives() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);

        String body = getAs(alice, ME + "/" + created.requestNumber());

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("QUOTED");
        assertThat(new BigDecimal(JsonPath.read(body, "$.quotedAmount").toString())).isEqualByComparingTo("4500");
        assertThat(JsonPath.<String>read(body, "$.quotedAt")).isNotBlank();
        assertThat(JsonPath.<Boolean>read(body, "$.canAccept")).isTrue();
        assertThat(JsonPath.<Boolean>read(body, "$.canReject")).isTrue();
        assertThat(JsonPath.<Integer>read(getAs(alice, ME + "/summary"), "$.awaitingResponse")).isEqualTo(1);
    }

    @Test
    @DisplayName("The date belongs to the price: a revised amount gets a new date, the same amount saved again does not")
    void quotedAtMovesWithThePrice() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);
        OffsetDateTime first = quotedAt(created.id());

        pause();
        adminService.quote(created.id(), new BigDecimal("4500"), "same price again", staffId);
        assertThat(quotedAt(created.id())).as("same amount: same age").isEqualTo(first);

        pause();
        adminService.quote(created.id(), new BigDecimal("6000"), null, staffId);
        assertThat(quotedAt(created.id())).as("revised amount: a new date").isAfter(first);
    }

    @Test
    @DisplayName("The badge counts only my quotes that are waiting: not new, answered, someone else's or a guest's")
    void badgeCountsOnlyMyWaitingQuotes() throws Exception {
        CustomRequestCreatedResponse waitingOne = mine(alice);
        CustomRequestCreatedResponse waitingTwo = mine(alice);
        CustomRequestCreatedResponse answered = mine(alice);
        mine(alice);                                   // still NEW
        CustomRequestCreatedResponse bobs = mine(bob);
        CustomRequestCreatedResponse guest = createAsGuest();
        for (CustomRequestCreatedResponse r : List.of(waitingOne, waitingTwo, answered, bobs, guest)) {
            adminService.quote(r.id(), new BigDecimal("1000"), null, staffId);
        }
        acceptRaw(alice, answered.requestNumber(), "1000").andExpect(status().isOk());

        assertThat(JsonPath.<Integer>read(getAs(alice, ME + "/summary"), "$.awaitingResponse")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(getAs(bob, ME + "/summary"), "$.awaitingResponse")).isEqualTo(1);
    }

    // ============================================================== accept and reject

    @Test
    @DisplayName("Accepting moves QUOTED to ACCEPTED, leaves the staff note alone and is audited as the CUSTOMER's act")
    void acceptIsAuditedAsTheCustomer() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), INTERNAL_NOTE, staffId);

        acceptRaw(alice, created.requestNumber(), "4500.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.canAccept").value(false))
                .andExpect(jsonPath("$.canReject").value(false));

        assertThat(statusInDb(created.id())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT admin_note FROM custom_order_request WHERE id = ?",
                String.class, created.id())).as("the customer never overwrites the staff note").isEqualTo(INTERNAL_NOTE);

        String audit = mvc.perform(get("/api/v1/admin/audit/CUSTOM_REQUEST/" + created.id()).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(audit,
                "$.content[?(@.action=='CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER')].actorKind"))
                .as("a customer decision is labelled as one").containsExactly("CUSTOMER");
        assertThat(JsonPath.<List<Integer>>read(audit,
                "$.content[?(@.action=='CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER')].actorId")
                .get(0).longValue()).isEqualTo(alice);
        assertThat(JsonPath.<List<String>>read(audit,
                "$.content[?(@.action=='CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER')].actorName"))
                .containsExactly("Alice Customer");
        assertThat(JsonPath.<List<String>>read(audit,
                "$.content[?(@.action=='CUSTOM_REQUEST_QUOTED')].actorKind"))
                .as("the staff quote stays a staff action").containsExactly("STAFF");
    }

    @Test
    @DisplayName("Rejecting moves QUOTED to REJECTED, leaves the staff note alone and is audited as the CUSTOMER's act")
    void rejectIsAuditedAsTheCustomer() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), INTERNAL_NOTE, staffId);

        rejectRaw(alice, created.requestNumber()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.canAccept").value(false));

        assertThat(statusInDb(created.id())).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT admin_note FROM custom_order_request WHERE id = ?",
                String.class, created.id())).isEqualTo(INTERNAL_NOTE);
        assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_REJECTED_BY_CUSTOMER")).isEqualTo(1);
    }

    @Test
    @DisplayName("Accepting a price the customer never saw is refused: QUOTE_CHANGED, and nothing is accepted")
    void revisedPriceIsNotSilentlyAccepted() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);
        // The customer is looking at 4500. Staff revise it. The status stays QUOTED, so no lock could notice.
        adminService.quote(created.id(), new BigDecimal("6000"), null, staffId);

        acceptRaw(alice, created.requestNumber(), "4500").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));
        assertThat(statusInDb(created.id())).isEqualTo("QUOTED");
        assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).isZero();

        // After reloading, the new price is accepted.
        acceptRaw(alice, created.requestNumber(), "6000").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("An unscaled amount (4500) is the same price as the stored 4500.0000 - BigDecimal.equals() would reject every accept")
    void unscaledAmountIsTheSamePrice() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);
        // The column keeps four decimals, so what is stored really is 4500.0000.
        assertThat(jdbc.queryForObject("SELECT CAST(quoted_amount AS VARCHAR(30)) FROM custom_order_request WHERE id = ?",
                String.class, created.id())).isEqualTo("4500.0000");

        // Three spellings of the same number, none of them 4500.0000.
        for (String spelling : List.of("4500", "4500.0", "4.5E3")) {
            CustomRequestCreatedResponse each = mine(alice);
            adminService.quote(each.id(), new BigDecimal("4500"), null, staffId);
            acceptRaw(alice, each.requestNumber(), spelling).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }
        acceptRaw(alice, created.requestNumber(), "4500").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(statusInDb(created.id())).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("A different amount is QUOTE_CHANGED, down to one piastre either way")
    void aPiastreOffIsAChange() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);

        for (String other : List.of("4500.01", "4499.99", "4500.0001", "450")) {
            acceptRaw(alice, created.requestNumber(), other).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));
        }
        assertThat(statusInDb(created.id())).as("nothing was accepted").isEqualTo("QUOTED");
        assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).isZero();
    }

    @Test
    @DisplayName("A repeat of what already happened is a 200 and writes no second audit entry; only opposites conflict")
    void idempotentRepeatsAndOppositeConflicts() throws Exception {
        CustomRequestCreatedResponse accepted = mine(alice);
        adminService.quote(accepted.id(), new BigDecimal("1000"), null, staffId);
        acceptRaw(alice, accepted.requestNumber(), "1000").andExpect(status().isOk());
        acceptRaw(alice, accepted.requestNumber(), "1000").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(customerAudit(accepted.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).isEqualTo(1);
        mvc.perform(post(ME + "/" + accepted.requestNumber() + "/reject").with(signedIn(alice, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_ANSWERED"));

        CustomRequestCreatedResponse rejected = mine(alice);
        adminService.quote(rejected.id(), new BigDecimal("1000"), null, staffId);
        rejectRaw(alice, rejected.requestNumber()).andExpect(status().isOk());
        rejectRaw(alice, rejected.requestNumber()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(customerAudit(rejected.id(), "CUSTOM_REQUEST_REJECTED_BY_CUSTOMER")).isEqualTo(1);
        acceptRaw(alice, rejected.requestNumber(), "1000").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("Acceptance by phone, recorded by staff, works exactly as before - and the customer sees it")
    void phoneAcceptanceIsUnchanged() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);

        adminService.changeStatus(created.id(), CustomRequestStatus.ACCEPTED, "agreed by phone", staffId);

        String body = getAs(alice, ME + "/" + created.requestNumber());
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACCEPTED");
        assertThat(JsonPath.<Boolean>read(body, "$.canAccept")).isFalse();
        assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).as("staff did this, not the customer").isZero();

        // The customer clicking accept afterwards for the same price: same outcome, success.
        acceptRaw(alice, created.requestNumber(), "4500").andExpect(status().isOk());
        assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).isZero();
        // ...but not for a price that is not the agreed one, and not to reject what staff accepted.
        acceptRaw(alice, created.requestNumber(), "4000").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));
        mvc.perform(post(ME + "/" + created.requestNumber() + "/reject").with(signedIn(alice, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("Staff rejecting by phone first: the customer's accept is a conflict, not a success")
    void staffRejectedFirst() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);
        adminService.changeStatus(created.id(), CustomRequestStatus.REJECTED, "customer said no", staffId);

        acceptRaw(alice, created.requestNumber(), "4500").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("The amount is required and positive")
    void amountIsRequired() throws Exception {
        CustomRequestCreatedResponse created = mine(alice);
        adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);

        mvc.perform(post(ME + "/" + created.requestNumber() + "/accept").with(signedIn(alice, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        acceptRaw(alice, created.requestNumber(), "0").andExpect(status().isBadRequest());
        assertThat(statusInDb(created.id())).isEqualTo("QUOTED");
    }

    // ==================================================================== the race

    @Test
    @DisplayName("The customer accepting while staff reject by phone: exactly one wins, the loser gets a clear conflict")
    void firstWriterWinsAgainstStaff() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 12; round++) {
                CustomRequestCreatedResponse created = mine(alice);
                adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);
                CountDownLatch go = new CountDownLatch(1);

                Future<String> customer = pool.submit(() -> attempt(go,
                        () -> myService.accept(alice, created.requestNumber(), new BigDecimal("4500"), "ar")));
                Future<String> staff = pool.submit(() -> attempt(go,
                        () -> adminService.changeStatus(created.id(), CustomRequestStatus.REJECTED, "phone", staffId)));
                go.countDown();
                String customerResult = customer.get();
                String staffResult = staff.get();

                String finalStatus = statusInDb(created.id());
                if (finalStatus.equals("ACCEPTED")) {
                    assertThat(customerResult).isEqualTo("OK");
                    assertThat(staffResult).as("staff lost").isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).isEqualTo(1);
                } else {
                    assertThat(finalStatus).isEqualTo("REJECTED");
                    assertThat(staffResult).isEqualTo("OK");
                    assertThat(customerResult).as("the customer lost: a clear code, not a 500")
                            .isEqualTo("QUOTE_ALREADY_ANSWERED");
                    assertThat(customerAudit(created.id(), "CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER")).isZero();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("The customer accepting and rejecting at once: exactly one wins")
    void firstWriterWinsAgainstTheSameCustomer() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                CustomRequestCreatedResponse created = mine(alice);
                adminService.quote(created.id(), new BigDecimal("4500"), null, staffId);
                CountDownLatch go = new CountDownLatch(1);

                Future<String> accept = pool.submit(() -> attempt(go,
                        () -> myService.accept(alice, created.requestNumber(), new BigDecimal("4500"), "ar")));
                Future<String> reject = pool.submit(() -> attempt(go,
                        () -> myService.reject(alice, created.requestNumber(), "ar")));
                go.countDown();

                String acceptResult = accept.get();
                String rejectResult = reject.get();
                assertThat(List.of(acceptResult, rejectResult)).containsExactlyInAnyOrder("OK", "QUOTE_ALREADY_ANSWERED");
                assertThat(statusInDb(created.id())).isEqualTo(acceptResult.equals("OK") ? "ACCEPTED" : "REJECTED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================== the migration

    @Test
    @DisplayName("V22's backfill dates an existing quote from its audit entry, else from the row, and touches nothing else")
    void backfillDatesExistingQuotes() throws Exception {
        CustomRequestCreatedResponse withAudit = mine(alice);
        adminService.quote(withAudit.id(), new BigDecimal("1000"), null, staffId);
        CustomRequestCreatedResponse withoutAudit = mine(alice);
        adminService.quote(withoutAudit.id(), new BigDecimal("2000"), null, staffId);
        CustomRequestCreatedResponse neverQuoted = mine(alice);

        jdbc.update("UPDATE custom_order_request SET quoted_at = NULL WHERE id IN (?, ?)",
                withAudit.id(), withoutAudit.id());
        jdbc.update("DELETE FROM audit_log WHERE action = 'CUSTOM_REQUEST_QUOTED' AND entity_id = ?",
                String.valueOf(withoutAudit.id()));

        runBackfill();

        OffsetDateTime auditDate = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM audit_log WHERE action = 'CUSTOM_REQUEST_QUOTED' AND entity_id = ?",
                OffsetDateTime.class, String.valueOf(withAudit.id()));
        // quoted_at keeps milliseconds (DATETIMEOFFSET(3)); the audit log keeps more, so allow a rounding.
        assertThat(quotedAt(withAudit.id()).toInstant())
                .isCloseTo(auditDate.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        OffsetDateTime updated = jdbc.queryForObject("SELECT updated_at FROM custom_order_request WHERE id = ?",
                OffsetDateTime.class, withoutAudit.id());
        assertThat(quotedAt(withoutAudit.id()).toInstant()).as("no audit entry: the row's own date")
                .isCloseTo(updated.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(quotedAt(neverQuoted.id())).isNull();
    }

    // ====================================================================== helpers

    private CustomRequestCreatedResponse mine(Long customerId) {
        return track(customRequestService.create(customWork(), customerId, newIp()));
    }

    private String getAs(Long userId, String url) throws Exception {
        return mvc.perform(get(url).with(signedIn(userId, "CUSTOMER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions acceptRaw(Long userId, String reference, String amount) throws Exception {
        return mvc.perform(post(ME + "/" + reference + "/accept").with(signedIn(userId, "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quotedAmount\":" + amount + "}"));
    }

    private ResultActions rejectRaw(Long userId, String reference) throws Exception {
        return mvc.perform(post(ME + "/" + reference + "/reject").with(signedIn(userId, "CUSTOMER")));
    }

    private static List<String> numbersOf(String pageJson) {
        return JsonPath.read(pageJson, "$.content[*].requestNumber");
    }

    private String statusInDb(Long id) {
        return jdbc.queryForObject("SELECT status FROM custom_order_request WHERE id = ?", String.class, id);
    }

    private OffsetDateTime quotedAt(Long id) {
        return jdbc.queryForObject("SELECT quoted_at FROM custom_order_request WHERE id = ?", OffsetDateTime.class, id);
    }

    private int customerAudit(Long id, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                Integer.class, action, String.valueOf(id));
    }

    /** Waits for the starting gun, runs, and reports "OK" or the error code - never throws. */
    private static String attempt(CountDownLatch go, Callable<Object> call) {
        try {
            go.await();
            call.call();
            return "OK";
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (Exception e) {
            return "UNEXPECTED " + e;
        }
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(25);
    }

    /** The backfill statement, exactly as written in V22 (the test login cannot run the DDL around it). */
    private void runBackfill() throws Exception {
        String script = new String(new org.springframework.core.io.ClassPathResource(
                "db/V22__custom_request_quoted_at.sql").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("EXEC\\('(UPDATE r.*?)'\\);", java.util.regex.Pattern.DOTALL).matcher(script);
        assertThat(m.find()).as("V22 has its backfill").isTrue();
        jdbc.update(m.group(1).replace("''", "'"));
    }

    @SuppressWarnings("unused")
    private static ErrorCode unused() {
        return ErrorCode.QUOTE_CHANGED;
    }
}
