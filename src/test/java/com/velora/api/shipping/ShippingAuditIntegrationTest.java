package com.velora.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.domain.AuditLog;
import com.velora.api.audit.repository.AuditLogRepository;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.shipping.dto.MaxShippingCostRequest;
import com.velora.api.shipping.dto.ShippingRateRequest;
import com.velora.api.shipping.dto.ShippingZoneResponse;
import com.velora.api.shipping.dto.SizeRateResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.service.ShippingAdminService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Opening or closing a governorate, and changing a shipping price or cap, are commercial
 * decisions that change what the company can sell and what customers pay. "Who closed
 * Aswan?" and "who changed the Delta price?" must have an answer in the audit log.
 *
 * <p>Every test acts as one named staff member and then reads the log back by that actor,
 * so it only ever sees its own entries. Whatever it changes it puts back.
 */
@SpringBootTest
class ShippingAuditIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ShippingAdminService shippingAdminService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long staffId;
    private String staffName;

    @BeforeEach
    void createStaffMember() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        AppUser user = new AppUser();
        user.setEmail("shipping-audit-" + unique + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Audit");
        user.setLastName("Tester " + unique);
        staffId = userRepository.save(user).getId();
        staffName = "Audit Tester " + unique;
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", staffId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", staffId);
    }

    // -------------------------------------------------------------- governorates

    @Test
    @DisplayName("Opening and then closing a governorate is logged with who, from and to")
    void openAndCloseAreLogged() {
        Long aswan = idOf("ASW");
        Long upperEgypt = zoneIdOf("UPPER_EGYPT");

        try {
            shippingAdminService.assignGovernorate(aswan, upperEgypt, staffId);
            shippingAdminService.closeGovernorate(aswan, staffId);
        } finally {
            shippingAdminService.closeGovernorate(aswan, null);
        }

        List<AuditLog> entries = entriesBy(AuditAction.GOVERNORATE_SERVICE_CHANGED);
        assertThat(entries).hasSize(2);

        AuditLog closed = entries.get(0);   // newest first
        assertThat(closed.getEntityType()).isEqualTo("GOVERNORATE");
        assertThat(closed.getEntityId()).isEqualTo(String.valueOf(aswan));
        assertThat(closed.getEntityLabel()).contains("ASW");
        assertThat(closed.getOldValue()).isEqualTo("UPPER_EGYPT");
        assertThat(closed.getNewValue()).isEqualTo("CLOSED");
        assertThat(closed.getActorId()).isEqualTo(staffId);
        assertThat(closed.getActorName()).isEqualTo(staffName);

        AuditLog opened = entries.get(1);
        assertThat(opened.getOldValue()).isEqualTo("CLOSED");
        assertThat(opened.getNewValue()).isEqualTo("UPPER_EGYPT");
    }

    @Test
    @DisplayName("Moving a governorate between zones is logged with both zone codes")
    void moveIsLogged() {
        Long portSaid = idOf("PTS");
        try {
            shippingAdminService.assignGovernorate(portSaid, zoneIdOf("DELTA"), staffId);
        } finally {
            shippingAdminService.assignGovernorate(portSaid, zoneIdOf("CANAL"), staffId);
        }

        List<AuditLog> entries = entriesBy(AuditAction.GOVERNORATE_SERVICE_CHANGED);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getOldValue()).isEqualTo("CANAL");
        assertThat(entries.get(1).getNewValue()).isEqualTo("DELTA");
        assertThat(entries.get(0).getOldValue()).isEqualTo("DELTA");
        assertThat(entries.get(0).getNewValue()).isEqualTo("CANAL");
    }

    @Test
    @DisplayName("Calls that change nothing, or are refused, leave no entry")
    void noOpsAndRefusalsAreNotLogged() {
        Long aswan = idOf("ASW");                      // closed
        Long portSaid = idOf("PTS");                   // in CANAL
        Long emptyZone = jdbc.queryForObject("INSERT INTO shipping_zone "
                + "(code, name_ar, name_en, is_active) OUTPUT INSERTED.id "
                + "VALUES ('TEST_EMPTY_AUDIT', 'test', 'Test empty', 1)", Long.class);
        try {
            shippingAdminService.closeGovernorate(aswan, staffId);                     // already closed
            shippingAdminService.assignGovernorate(portSaid, zoneIdOf("CANAL"), staffId); // already there
            assertThatThrownBy(() -> shippingAdminService.assignGovernorate(
                    aswan, emptyZone, staffId)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> shippingAdminService.closeGovernorate(999_999L, staffId))
                    .isInstanceOf(BusinessException.class);
        } finally {
            jdbc.update("DELETE FROM shipping_zone WHERE id = ?", emptyZone);
        }

        assertThat(allEntriesBy()).isEmpty();
    }

    // -------------------------------------------------------------------- prices

    @Test
    @DisplayName("Changing one size's price is logged with the old and new amount")
    void priceChangeIsLogged() {
        Long delta = zoneIdOf("DELTA");
        BigDecimal original = unitCost("DELTA", ShippingSizeClass.MEDIUM);
        try {
            shippingAdminService.saveRate(rate(delta, ShippingSizeClass.MEDIUM, "210.00"), staffId);
            // Saving the same price again is not a change.
            shippingAdminService.saveRate(rate(delta, ShippingSizeClass.MEDIUM, "210.00"), staffId);
        } finally {
            shippingAdminService.saveRate(rate(delta, ShippingSizeClass.MEDIUM, original.toPlainString()), staffId);
        }

        List<AuditLog> entries = entriesBy(AuditAction.SHIPPING_RATE_CHANGED);
        assertThat(entries).as("change + restore, and the repeated save logged nothing").hasSize(2);

        AuditLog change = entries.get(1);
        assertThat(change.getEntityType()).isEqualTo("SHIPPING_RATE");
        assertThat(change.getEntityLabel()).isEqualTo("DELTA / MEDIUM");
        assertThat(change.getOldValue()).isEqualTo("200.0000");
        assertThat(change.getNewValue()).as("amounts are stored at one scale").isEqualTo("210.0000");
        assertThat(change.getActorId()).isEqualTo(staffId);
        assertThat(change.getActorName()).isEqualTo(staffName);
    }

    @Test
    @DisplayName("Changing the COD fee or delivery days is logged against the zone")
    void zoneTermsChangeIsLogged() {
        Long delta = zoneIdOf("DELTA");
        BigDecimal price = unitCost("DELTA", ShippingSizeClass.SMALL);
        try {
            shippingAdminService.saveRate(new ShippingRateRequest(delta, ShippingSizeClass.SMALL,
                    price, new BigDecimal("5.00"), 3, 6), staffId);
        } finally {
            shippingAdminService.saveRate(new ShippingRateRequest(delta, ShippingSizeClass.SMALL,
                    price, BigDecimal.ZERO, 2, 4), staffId);
        }

        List<AuditLog> entries = entriesBy(AuditAction.SHIPPING_RATE_CHANGED);
        assertThat(entries).as("the price itself did not move, only the zone terms").hasSize(2);

        AuditLog change = entries.get(1);
        assertThat(change.getEntityType()).isEqualTo("SHIPPING_ZONE");
        assertThat(change.getEntityLabel()).isEqualTo("DELTA terms");
        assertThat(change.getOldValue()).isEqualTo("codFee=0.0000, deliveryDays=2-4");
        assertThat(change.getNewValue()).isEqualTo("codFee=5.0000, deliveryDays=3-6");
    }

    @Test
    @DisplayName("Changing and removing the zone cap is logged; removal reads as 'none'")
    void capChangeIsLogged() {
        Long delta = zoneIdOf("DELTA");
        BigDecimal original = capOf("DELTA");
        try {
            shippingAdminService.saveMaxShippingCost(delta,
                    new MaxShippingCostRequest(new BigDecimal("300.00")), staffId);
            shippingAdminService.saveMaxShippingCost(delta,
                    new MaxShippingCostRequest(new BigDecimal("300.00")), staffId);   // no change
            shippingAdminService.saveMaxShippingCost(delta,
                    new MaxShippingCostRequest(null), staffId);
        } finally {
            shippingAdminService.saveMaxShippingCost(delta,
                    new MaxShippingCostRequest(original), staffId);
        }

        List<AuditLog> entries = entriesBy(AuditAction.SHIPPING_RATE_CHANGED);
        assertThat(entries).hasSize(3);   // set, remove, restore

        AuditLog set = entries.get(2);
        assertThat(set.getEntityType()).isEqualTo("SHIPPING_ZONE");
        assertThat(set.getEntityLabel()).isEqualTo("DELTA cap");
        assertThat(set.getOldValue()).isEqualTo("1500.0000");
        assertThat(set.getNewValue()).isEqualTo("300.0000");

        AuditLog removed = entries.get(1);
        assertThat(removed.getOldValue()).isEqualTo("300.0000");
        assertThat(removed.getNewValue()).isEqualTo("none");

        assertThat(entries.get(0).getOldValue()).isEqualTo("none");
        assertThat(entries.get(0).getNewValue()).isEqualTo("1500.0000");
    }

    // ----------------------------------------------------------------- HTTP layer

    @Test
    @DisplayName("Through the API the acting admin is the one recorded, and the log can be filtered by the new action")
    void apiRecordsTheCallerAndAuditCanFilterByTheAction() throws Exception {
        Long luxor = idOf("LUX");
        Long delta = zoneIdOf("DELTA");
        BigDecimal original = unitCost("DELTA", ShippingSizeClass.LARGE);

        try {
            mvc.perform(put("/api/v1/admin/shipping/governorates/" + luxor + "/zone")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zoneId\": " + zoneIdOf("UPPER_EGYPT") + "}")
                            .with(admin()))
                    .andExpect(status().isOk());
            mvc.perform(delete("/api/v1/admin/shipping/governorates/" + luxor + "/zone")
                            .with(admin()))
                    .andExpect(status().isOk());
            mvc.perform(put("/api/v1/admin/shipping/rates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zoneId\": " + delta + ", \"sizeClass\": \"LARGE\", "
                                    + "\"baseCost\": 410}")
                            .with(admin()))
                    .andExpect(status().isOk());
            mvc.perform(put("/api/v1/admin/shipping/zones/" + delta + "/max-shipping-cost")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxShippingCost\": 1400}")
                            .with(admin()))
                    .andExpect(status().isOk());
        } finally {
            shippingAdminService.saveRate(rate(delta, ShippingSizeClass.LARGE,
                    original.toPlainString()), staffId);
            shippingAdminService.saveMaxShippingCost(delta,
                    new MaxShippingCostRequest(new BigDecimal("1500.00")), staffId);
            shippingAdminService.closeGovernorate(luxor, null);
        }

        assertThat(entriesBy(AuditAction.GOVERNORATE_SERVICE_CHANGED)).hasSize(2)
                .allSatisfy(e -> assertThat(e.getActorId()).isEqualTo(staffId));
        assertThat(entriesBy(AuditAction.SHIPPING_RATE_CHANGED))
                .as("price + cap through the API, then both restored")
                .hasSize(4)
                .allSatisfy(e -> assertThat(e.getActorName()).isEqualTo(staffName));

        mvc.perform(get("/api/v1/admin/audit").param("action", "GOVERNORATE_SERVICE_CHANGED")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("GOVERNORATE_SERVICE_CHANGED"));
    }

    // ------------------------------------------------------------------ helpers

    private RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(staffId, "admin@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }

    /** This staff member's entries of one action, newest first. */
    private List<AuditLog> entriesBy(AuditAction action) {
        return allEntriesBy().stream().filter(e -> e.getAction() == action).toList();
    }

    private List<AuditLog> allEntriesBy() {
        return auditLogRepository
                .findByActorIdOrderByCreatedAtDesc(staffId, PageRequest.of(0, 100))
                .getContent();
    }

    private static ShippingRateRequest rate(Long zoneId, ShippingSizeClass size, String cost) {
        return new ShippingRateRequest(zoneId, size, new BigDecimal(cost), null, null, null);
    }

    private Long idOf(String governorateCode) {
        return governorateRepository.findByCode(governorateCode).orElseThrow().getId();
    }

    private ShippingZoneResponse zone(String code) {
        return shippingAdminService.listZones().stream()
                .filter(z -> z.code().equals(code)).findFirst().orElseThrow();
    }

    private Long zoneIdOf(String code) {
        return zone(code).zoneId();
    }

    private BigDecimal capOf(String code) {
        return zone(code).maxShippingCost();
    }

    private BigDecimal unitCost(String zoneCode, ShippingSizeClass size) {
        return zone(zoneCode).rates().stream()
                .filter(r -> r.sizeClass() == size)
                .map(SizeRateResponse::unitCost)
                .findFirst().orElseThrow();
    }
}
