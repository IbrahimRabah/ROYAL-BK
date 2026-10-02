package com.velora.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.dto.ShippingZoneResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.service.ShippingAdminService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Alexandria used to show up in two places on the admin shipping screen: as its own
 * empty "Alexandria" zone (pre-provisioned by V1 for a future price split — see
 * {@code ShippingZone}'s javadoc) and, separately, as a governorate nested under
 * "Delta" (where V2 actually put it). An admin had no way to tell which price
 * applied. {@code V10__deactivate_orphan_shipping_zones.sql} hid the empty ALEXANDRIA
 * and CANAL zones; {@code V12__shipping_rate_by_size_class.sql} then gave them their
 * own governorates (Alexandria; Port Said, Ismailia, Suez) and re-activated them, with
 * their own prices. These tests pin the invariant that survives both: every listed
 * zone covers something, and no governorate sits in two zones.
 */
@SpringBootTest
class ShippingAdminServiceIntegrationTest {

    @Autowired private ShippingAdminService shippingAdminService;
    @Autowired private GovernorateRepository governorateRepository;

    @Test
    @DisplayName("Every active zone covers at least one governorate — no empty zone is listed")
    void noActiveZoneIsEmpty() {
        List<ShippingZoneResponse> zones = shippingAdminService.listZones();

        assertThat(zones).allSatisfy(zone ->
                assertThat(zone.governorates())
                        .as("zone %s (%s) has no governorates but is still listed",
                                zone.code(), zone.nameEn())
                        .isNotEmpty());
    }

    @Test
    @DisplayName("Alexandria belongs to exactly one zone — the Alexandria zone, not Delta")
    void alexandriaBelongsToExactlyOneZone() {
        Governorate alexandria = governorateRepository.findByCode("ALX").orElseThrow();

        List<ShippingZoneResponse> zonesContainingAlexandria = shippingAdminService.listZones()
                .stream()
                .filter(zone -> zone.governorates().contains(alexandria.getNameAr()))
                .toList();

        assertThat(zonesContainingAlexandria).hasSize(1);
        assertThat(zonesContainingAlexandria.get(0).code()).isEqualTo("ALEXANDRIA");
    }

    @Test
    @DisplayName("No governorate is listed under more than one zone")
    void noGovernorateAppearsInTwoZones() {
        List<ShippingZoneResponse> zones = shippingAdminService.listZones();

        Map<String, Long> zoneCountByGovernorate = new HashMap<>();
        for (ShippingZoneResponse zone : zones) {
            for (String governorateName : zone.governorates()) {
                zoneCountByGovernorate.merge(governorateName, 1L, Long::sum);
            }
        }

        assertThat(zoneCountByGovernorate)
                .as("every value must be 1 — a governorate matching more than one zone "
                        + "means two different shipping prices could apply to it")
                .allSatisfy((governorateName, count) -> assertThat(count).isEqualTo(1L));
    }
}
