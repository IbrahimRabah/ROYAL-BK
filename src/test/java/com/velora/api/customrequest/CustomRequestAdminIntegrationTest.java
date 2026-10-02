package com.velora.api.customrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.domain.AuditLog;
import com.velora.api.audit.repository.AuditLogRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.domain.CustomRequestType;
import com.velora.api.customrequest.dto.CustomRequestCreateRequest;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.customrequest.dto.CustomRequestFilter;
import com.velora.api.customrequest.dto.CustomRequestResponse;
import com.velora.api.customrequest.dto.CustomRequestSummaryResponse;
import com.velora.api.shipping.service.ShippingAdminService;
import com.velora.api.testsupport.TestImages;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static com.velora.api.customrequest.domain.CustomRequestStatus.ACCEPTED;
import static com.velora.api.customrequest.domain.CustomRequestStatus.CONTACTED;
import static com.velora.api.customrequest.domain.CustomRequestStatus.CONVERTED;
import static com.velora.api.customrequest.domain.CustomRequestStatus.NEW;
import static com.velora.api.customrequest.domain.CustomRequestStatus.QUOTED;
import static com.velora.api.customrequest.domain.CustomRequestStatus.REJECTED;

/**
 * What staff can do with a request: read it, move it through the statuses, quote it —
 * and the audit trail that makes "who told the customer that price?" answerable.
 */
class CustomRequestAdminIntegrationTest extends CustomRequestTestBase {

    private static final String BASE = "/api/v1/admin/custom-requests";

    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ShippingAdminService shippingAdminService;

    // --------------------------------------------------------------- status moves

    @Test
    @DisplayName("NEW to CONTACTED works, keeps the note, and is audited with who and from/to")
    void contactedIsAuditedWithActorAndNote() {
        CustomRequestCreatedResponse created = createAsGuest();

        CustomRequestResponse updated = adminService.changeStatus(
                created.id(), CONTACTED, "اتصلنا بالعميل", staffId);

        assertThat(updated.status()).isEqualTo("CONTACTED");
        assertThat(updated.adminNote()).isEqualTo("اتصلنا بالعميل");

        AuditLog entry = onlyEntry(created, AuditAction.CUSTOM_REQUEST_STATUS_CHANGED);
        assertThat(entry.getEntityType()).isEqualTo("CUSTOM_REQUEST");
        assertThat(entry.getEntityLabel()).isEqualTo(created.requestNumber());
        assertThat(entry.getOldValue()).isEqualTo("NEW");
        assertThat(entry.getNewValue()).isEqualTo("CONTACTED");
        assertThat(entry.getReason()).isEqualTo("اتصلنا بالعميل");
        assertThat(entry.getActorId()).isEqualTo(staffId);
        assertThat(entry.getActorName()).isEqualTo(staffName);
    }

    @Test
    @DisplayName("Rejecting needs a note; with one it is recorded")
    void rejectingNeedsANote() {
        CustomRequestCreatedResponse created = createAsGuest();

        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> adminService.changeStatus(created.id(), REJECTED, blank, staffId))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(statusOf(created)).as("a refused change leaves the status alone").isEqualTo(NEW);
        assertThat(entries(created)).isEmpty();

        adminService.changeStatus(created.id(), REJECTED, "خارج نطاق عملنا", staffId);
        assertThat(statusOf(created)).isEqualTo(REJECTED);
    }

    @Test
    @DisplayName("ACCEPTED cannot happen without a quote — from NEW or CONTACTED")
    void acceptedNeedsAQuote() {
        CustomRequestCreatedResponse fresh = createAsGuest();
        CustomRequestCreatedResponse contacted = createAsGuest();
        adminService.changeStatus(contacted.id(), CONTACTED, null, staffId);

        for (CustomRequestCreatedResponse request : List.of(fresh, contacted)) {
            assertThatThrownBy(() -> adminService.changeStatus(request.id(), ACCEPTED, null, staffId))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.CUSTOM_REQUEST_QUOTE_REQUIRED));
        }
        assertThat(statusOf(fresh)).isEqualTo(NEW);
        assertThat(statusOf(contacted)).isEqualTo(CONTACTED);
    }

    @Test
    @DisplayName("QUOTED is reached by quoting, never set by hand")
    void quotedCannotBeSetDirectly() {
        CustomRequestCreatedResponse created = createAsGuest();

        assertThatThrownBy(() -> adminService.changeStatus(created.id(), QUOTED, null, staffId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(statusOf(created)).isEqualTo(NEW);
    }

    @Test
    @DisplayName("CONVERTED is refused for now, even from ACCEPTED, and nothing is linked")
    void convertedIsRefused() {
        CustomRequestCreatedResponse created = createAsGuest();
        adminService.quote(created.id(), new BigDecimal("1000"), null, staffId);
        adminService.changeStatus(created.id(), ACCEPTED, null, staffId);

        assertThatThrownBy(() -> adminService.changeStatus(created.id(), CONVERTED, null, staffId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(statusOf(created)).isEqualTo(ACCEPTED);
        assertThat(adminService.get(created.id()).convertedOrderId()).isNull();
    }

    @Test
    @DisplayName("ACCEPTED, REJECTED and CONVERTED cannot be left; nor can a status be repeated")
    void terminalStatesAreFinal() {
        CustomRequestCreatedResponse rejected = createAsGuest();
        adminService.changeStatus(rejected.id(), REJECTED, "no", staffId);
        CustomRequestCreatedResponse accepted = createAsGuest();
        adminService.quote(accepted.id(), new BigDecimal("500"), null, staffId);
        adminService.changeStatus(accepted.id(), ACCEPTED, null, staffId);

        for (CustomRequestStatus target : List.of(NEW, CONTACTED, REJECTED, ACCEPTED)) {
            assertInvalid(rejected, target);
        }
        for (CustomRequestStatus target : List.of(NEW, CONTACTED, REJECTED, ACCEPTED)) {
            assertInvalid(accepted, target);
        }

        CustomRequestCreatedResponse contacted = createAsGuest();
        adminService.changeStatus(contacted.id(), CONTACTED, null, staffId);
        assertInvalid(contacted, CONTACTED);
        assertInvalid(contacted, NEW);
    }

    @Test
    @DisplayName("The whole path: NEW, CONTACTED, quote, QUOTED, ACCEPTED")
    void fullHappyPath() {
        CustomRequestCreatedResponse created = createAsGuest();

        adminService.changeStatus(created.id(), CONTACTED, "called", staffId);
        adminService.quote(created.id(), new BigDecimal("4500"), "price agreed by phone", staffId);
        adminService.changeStatus(created.id(), ACCEPTED, "customer said yes", staffId);

        assertThat(statusOf(created)).isEqualTo(ACCEPTED);
        assertThat(entries(created, AuditAction.CUSTOM_REQUEST_STATUS_CHANGED))
                .extracting(e -> e.getOldValue() + ">" + e.getNewValue())
                .containsExactly("QUOTED>ACCEPTED", "CONTACTED>QUOTED", "NEW>CONTACTED");
    }

    @Test
    @DisplayName("An unknown request is CUSTOM_REQUEST_NOT_FOUND")
    void unknownRequest() {
        assertThatThrownBy(() -> adminService.get(999_999_999L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
        assertThatThrownBy(() -> adminService.changeStatus(999_999_999L, CONTACTED, null, staffId))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminService.quote(999_999_999L, BigDecimal.TEN, null, staffId))
                .isInstanceOf(BusinessException.class);
    }

    // --------------------------------------------------------------------- quote

    @Test
    @DisplayName("Quoting a NEW request stores the amount, moves it to QUOTED, and audits both")
    void quoteFromNew() {
        CustomRequestCreatedResponse created = createAsGuest();

        CustomRequestResponse quoted = adminService.quote(
                created.id(), new BigDecimal("4500.5"), "شامل التركيب", staffId);

        assertThat(quoted.status()).isEqualTo("QUOTED");
        assertThat(quoted.quotedAmount()).isEqualByComparingTo("4500.50");
        assertThat(quoted.adminNote()).isEqualTo("شامل التركيب");

        AuditLog quote = onlyEntry(created, AuditAction.CUSTOM_REQUEST_QUOTED);
        assertThat(quote.getOldValue()).isNull();
        assertThat(quote.getNewValue()).as("amounts are stored at one scale").isEqualTo("4500.5000");
        assertThat(quote.getActorId()).isEqualTo(staffId);
        assertThat(quote.getReason()).isEqualTo("شامل التركيب");

        AuditLog move = onlyEntry(created, AuditAction.CUSTOM_REQUEST_STATUS_CHANGED);
        assertThat(move.getOldValue()).isEqualTo("NEW");
        assertThat(move.getNewValue()).isEqualTo("QUOTED");
    }

    @Test
    @DisplayName("Quoting a CONTACTED request moves it to QUOTED too")
    void quoteFromContacted() {
        CustomRequestCreatedResponse created = createAsGuest();
        adminService.changeStatus(created.id(), CONTACTED, null, staffId);

        assertThat(adminService.quote(created.id(), new BigDecimal("900"), null, staffId).status())
                .isEqualTo("QUOTED");
    }

    @Test
    @DisplayName("A revised quote is audited old to new, and does not log a second status move")
    void requoteKeepsTheHistory() {
        CustomRequestCreatedResponse created = createAsGuest();
        adminService.quote(created.id(), new BigDecimal("4000"), null, staffId);
        adminService.quote(created.id(), new BigDecimal("5500"), "زادت الخامة", staffId);

        List<AuditLog> quotes = entries(created, AuditAction.CUSTOM_REQUEST_QUOTED);
        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).getOldValue()).isEqualTo("4000.0000");   // newest first
        assertThat(quotes.get(0).getNewValue()).isEqualTo("5500.0000");
        assertThat(entries(created, AuditAction.CUSTOM_REQUEST_STATUS_CHANGED))
                .as("NEW to QUOTED happened once").hasSize(1);
        assertThat(adminService.get(created.id()).quotedAmount()).isEqualByComparingTo("5500");
    }

    @Test
    @DisplayName("Quoting the same amount again records nothing")
    void sameQuoteIsNotLogged() {
        CustomRequestCreatedResponse created = createAsGuest();
        adminService.quote(created.id(), new BigDecimal("4000"), null, staffId);
        adminService.quote(created.id(), new BigDecimal("4000.00"), null, staffId);

        assertThat(entries(created, AuditAction.CUSTOM_REQUEST_QUOTED)).hasSize(1);
    }

    @Test
    @DisplayName("No quote once the customer accepted, or after a rejection")
    void noQuoteAfterTheDealIsDecided() {
        CustomRequestCreatedResponse accepted = createAsGuest();
        adminService.quote(accepted.id(), new BigDecimal("700"), null, staffId);
        adminService.changeStatus(accepted.id(), ACCEPTED, null, staffId);
        CustomRequestCreatedResponse rejected = createAsGuest();
        adminService.changeStatus(rejected.id(), REJECTED, "no", staffId);

        for (CustomRequestCreatedResponse request : List.of(accepted, rejected)) {
            assertThatThrownBy(() -> adminService.quote(
                    request.id(), new BigDecimal("800"), null, staffId))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        }
        assertThat(adminService.get(accepted.id()).quotedAmount()).isEqualByComparingTo("700");
    }

    // -------------------------------------------------------------------- read

    @Test
    @DisplayName("The detail has everything staff need: product, governorate, images, local phone format")
    void detailHasEverything() {
        Long product = readyMadeProduct();
        CustomRequestCreatedResponse created = createAsGuest(sizeVariant(product));
        customRequestService.addAttachment(created.id(), created.attachmentToken(), null,
                new MockMultipartFile("file", "p.png", "image/png", TestImages.png()), newIp());

        CustomRequestResponse detail = adminService.get(created.id());

        assertThat(detail.requestNumber()).isEqualTo(created.requestNumber());
        assertThat(detail.type()).isEqualTo("SIZE_VARIANT");
        assertThat(detail.product().id()).isEqualTo(product);
        assertThat(detail.product().fulfillmentType()).isEqualTo("READY_MADE");
        assertThat(detail.phone()).isEqualTo("01012345678");
        assertThat(detail.governorateName()).isNotBlank();
        assertThat(detail.widthCm()).isEqualByComparingTo("120");
        assertThat(detail.depthCm()).isNull();
        assertThat(detail.quantity()).isEqualTo(2);
        assertThat(detail.attachments()).hasSize(1);
        assertThat(detail.attachments().get(0).url()).contains("custom-requests/");
        assertThat(detail.customerId()).isNull();
        assertThat(detail.convertedOrderId()).isNull();
        assertThat(detail.quotedAmount()).isNull();
    }

    @Test
    @DisplayName("The list filters by status, type, governorate and free text, and they compose")
    void listFilters() {
        String marker = "filter-" + UUID.randomUUID().toString().substring(0, 8);
        CustomRequestCreatedResponse cairoNew = createAsGuest(named(customWork(), marker + "-a"));
        CustomRequestCreatedResponse cairoContacted = createAsGuest(named(customWork(), marker + "-b"));
        adminService.changeStatus(cairoContacted.id(), CONTACTED, null, staffId);
        CustomRequestCreatedResponse alexSize = createAsGuest(
                inGovernorate(named(sizeVariant(readyMadeProduct()), marker + "-c"), governorateId("ALX")));

        assertThat(numbers(filter(null, null, null, marker)))
                .containsExactlyInAnyOrder(cairoNew.requestNumber(), cairoContacted.requestNumber(),
                        alexSize.requestNumber());
        assertThat(numbers(filter(CONTACTED, null, null, marker)))
                .containsExactly(cairoContacted.requestNumber());
        assertThat(numbers(filter(null, CustomRequestType.SIZE_VARIANT, null, marker)))
                .containsExactly(alexSize.requestNumber());
        assertThat(numbers(filter(null, null, governorateId("ALX"), marker)))
                .containsExactly(alexSize.requestNumber());
        assertThat(numbers(filter(NEW, CustomRequestType.CUSTOM_WORK, governorateId("CAI"), marker)))
                .containsExactly(cairoNew.requestNumber());
        assertThat(numbers(filter(REJECTED, null, null, marker))).isEmpty();
    }

    @Test
    @DisplayName("Free text finds a phone in any form, a request number, or part of a name")
    void listSearch() {
        String marker = "search-" + UUID.randomUUID().toString().substring(0, 8);
        CustomRequestCreatedResponse created = createAsGuest(named(
                withPhone(customWork(), "01155544433"), marker));

        assertThat(numbers(filter(null, null, null, "01155544433"))).contains(created.requestNumber());
        assertThat(numbers(filter(null, null, null, "+201155544433"))).contains(created.requestNumber());
        assertThat(numbers(filter(null, null, null, "201155544433"))).contains(created.requestNumber());
        assertThat(numbers(filter(null, null, null, created.requestNumber().toLowerCase())))
                .containsExactly(created.requestNumber());
        assertThat(numbers(filter(null, null, null, marker.substring(0, 12).toUpperCase())))
                .contains(created.requestNumber());
        assertThat(numbers(filter(null, null, null, "%"))).as("a wildcard is text, not a pattern").isEmpty();
    }

    @Test
    @DisplayName("Dates filter inclusively in Cairo time; the list is paged, newest first")
    void listDatesAndPaging() {
        String marker = "paging-" + UUID.randomUUID().toString().substring(0, 8);
        CustomRequestCreatedResponse first = createAsGuest(named(customWork(), marker));
        CustomRequestCreatedResponse second = createAsGuest(named(customWork(), marker));
        CustomRequestCreatedResponse third = createAsGuest(named(customWork(), marker));
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Cairo"));

        PageResponse<CustomRequestSummaryResponse> page = adminService.list(
                new CustomRequestFilter(null, null, null, marker, today, today),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).requestNumber()).isEqualTo(third.requestNumber());
        assertThat(page.content().get(1).requestNumber()).isEqualTo(second.requestNumber());
        assertThat(adminService.list(new CustomRequestFilter(null, null, null, marker, today, today),
                PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt")))
                .content().get(0).requestNumber()).isEqualTo(first.requestNumber());

        assertThat(adminService.list(new CustomRequestFilter(null, null, null, marker,
                today.minusDays(5), today.minusDays(1)), PageRequest.of(0, 10)).totalElements())
                .as("created today, so not in a window that ended yesterday").isZero();
        assertThat(adminService.list(new CustomRequestFilter(null, null, null, marker,
                today.plusDays(1), null), PageRequest.of(0, 10)).totalElements()).isZero();
    }

    @Test
    @DisplayName("A request from a closed governorate is accepted, and its detail says we cannot deliver there")
    void detailWarnsWhenTheGovernorateIsClosed() throws Exception {
        Long aswan = governorateId("ASW");            // closed since V13
        CustomRequestCreatedResponse fromAswan = createAsGuest(inGovernorate(customWork(), aswan));
        CustomRequestCreatedResponse fromCairo = createAsGuest(customWork());

        assertThat(adminService.get(fromAswan.id()).governorateServed()).isFalse();
        assertThat(adminService.get(fromCairo.id()).governorateServed()).isTrue();
        assertThat(adminService.quote(fromAswan.id(), new BigDecimal("900"), null, staffId)
                .governorateServed()).as("every response of a request carries it, not just GET").isFalse();

        mvc.perform(get(BASE + "/{id}", fromAswan.id()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.governorateServed").value(false))
                .andExpect(jsonPath("$.governorateName").isNotEmpty());
        mvc.perform(get(BASE + "/{id}", fromCairo.id()).with(admin()))
                .andExpect(jsonPath("$.governorateServed").value(true));
    }

    @Test
    @DisplayName("The flag is live, not a snapshot: reopening the governorate turns it true, closing turns it false")
    void servedFlagFollowsTheGovernorateNow() {
        Long aswan = governorateId("ASW");
        CustomRequestCreatedResponse request = createAsGuest(inGovernorate(customWork(), aswan));
        Long upperEgypt = shippingAdminService.listZones().stream()
                .filter(z -> z.code().equals("UPPER_EGYPT")).findFirst().orElseThrow().zoneId();

        assertThat(adminService.get(request.id()).governorateServed()).isFalse();
        try {
            shippingAdminService.assignGovernorate(aswan, upperEgypt, staffId);
            assertThat(adminService.get(request.id()).governorateServed()).isTrue();
        } finally {
            shippingAdminService.closeGovernorate(aswan, staffId);
        }
        assertThat(adminService.get(request.id()).governorateServed()).isFalse();
    }

    // ----------------------------------------------------------------- HTTP layer

    @Test
    @DisplayName("Over HTTP the admin lists, reads, moves and quotes — and the audit names the caller")
    void adminFlowOverHttp() throws Exception {
        String marker = "http-" + UUID.randomUUID().toString().substring(0, 8);
        CustomRequestCreatedResponse created = createAsGuest(named(customWork(), marker));

        mvc.perform(get(BASE).param("q", marker).param("size", "5").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].requestNumber").value(created.requestNumber()))
                .andExpect(jsonPath("$.content[0].phone").value("01012345678"));

        mvc.perform(get(BASE + "/{id}", created.id()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONTACTED\", \"note\": \"called\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTACTED"));

        mvc.perform(patch(BASE + "/{id}/quote", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 4500.50, \"note\": \"incl. fitting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.quotedAmount").value(4500.5));

        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACCEPTED\"}"))
                .andExpect(status().isOk());

        assertThat(entries(created)).hasSize(4)
                .allSatisfy(e -> assertThat(e.getActorName()).isEqualTo(staffName));
    }

    @Test
    @DisplayName("Over HTTP: the error codes a client branches on")
    void errorCodesOverHttp() throws Exception {
        CustomRequestCreatedResponse created = createAsGuest();

        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"ACCEPTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_QUOTE_REQUIRED"));
        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"CONVERTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"BANANA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(BASE + "/{id}", 999_999_999L).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOM_REQUEST_NOT_FOUND"));
        mvc.perform(get(BASE).param("status", "BANANA").with(admin()))
                .andExpect(status().isBadRequest());

        for (String amount : new String[] {"0", "-5", "0.001", "100000001", "null"}) {
            mvc.perform(patch(BASE + "/{id}/quote", created.id()).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": " + amount + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(statusOf(created)).isEqualTo(NEW);
        assertThat(entries(created)).isEmpty();
    }

    @Test
    @DisplayName("The admin endpoints are for admins: a customer gets 403 and changes nothing")
    void adminEndpointsAreAdminOnly() throws Exception {
        CustomRequestCreatedResponse created = createAsGuest();
        Long customer = newUser("custom-req-nonadmin-" + UUID.randomUUID().toString().substring(0, 8),
                "Non", "Admin");

        mvc.perform(get(BASE).with(signedIn(customer, "CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/{id}", created.id()).with(signedIn(customer, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(patch(BASE + "/{id}/status", created.id()).with(signedIn(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"CONTACTED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(BASE + "/{id}/quote", created.id()).with(signedIn(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1}"))
                .andExpect(status().isForbidden());

        assertThat(statusOf(created)).isEqualTo(NEW);
        assertThat(adminService.get(created.id()).quotedAmount()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private void assertInvalid(CustomRequestCreatedResponse request, CustomRequestStatus target) {
        assertThatThrownBy(() -> adminService.changeStatus(request.id(), target, "note", staffId))
                .as("-> %s", target)
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    private CustomRequestStatus statusOf(CustomRequestCreatedResponse request) {
        return CustomRequestStatus.valueOf(adminService.get(request.id()).status());
    }

    /** Every audit entry about one request, newest first. */
    private List<AuditLog> entries(CustomRequestCreatedResponse request) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                "CUSTOM_REQUEST", String.valueOf(request.id()), PageRequest.of(0, 50)).getContent();
    }

    private List<AuditLog> entries(CustomRequestCreatedResponse request, AuditAction action) {
        return entries(request).stream().filter(e -> e.getAction() == action).toList();
    }

    private AuditLog onlyEntry(CustomRequestCreatedResponse request, AuditAction action) {
        List<AuditLog> found = entries(request, action);
        assertThat(found).as("audit entries of %s", action).hasSize(1);
        return found.get(0);
    }

    private PageResponse<CustomRequestSummaryResponse> filter(CustomRequestStatus status,
                                                              CustomRequestType type,
                                                              Long governorateId, String q) {
        return adminService.list(new CustomRequestFilter(status, type, governorateId, q, null, null),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private static List<String> numbers(PageResponse<CustomRequestSummaryResponse> page) {
        return page.content().stream().map(CustomRequestSummaryResponse::requestNumber).toList();
    }

    private static CustomRequestCreateRequest named(CustomRequestCreateRequest r, String name) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), name, r.phone(), r.altPhone(),
                r.email(), r.governorateId(), r.area(), r.streetAddress(), r.widthCm(), r.heightCm(),
                r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest withPhone(CustomRequestCreateRequest r, String phone) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), phone,
                r.altPhone(), r.email(), r.governorateId(), r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }

    private static CustomRequestCreateRequest inGovernorate(CustomRequestCreateRequest r, Long id) {
        return new CustomRequestCreateRequest(r.type(), r.productId(), r.contactName(), r.phone(),
                r.altPhone(), r.email(), id, r.area(), r.streetAddress(),
                r.widthCm(), r.heightCm(), r.depthCm(), r.quantity(), r.notes());
    }
}
