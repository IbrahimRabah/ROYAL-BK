package com.velora.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.shipping.dto.DeliveryOptionResponse;
import com.velora.api.shipping.service.ShippingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * GET /shipping/governorates: per-governorate fee and delivery days with no cart, against the
 * rates V12 seeds. Read-only.
 */
@SpringBootTest
class DeliveryOptionsIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ShippingService shippingService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    @DisplayName("Public: no token needed, with or without sizeClass; an unknown size is a 400")
    void isPublic() throws Exception {
        mvc.perform(get("/api/v1/shipping/governorates")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/shipping/governorates").param("sizeClass", "LARGE"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/shipping/governorates").param("sizeClass", "HUGE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Cost is the zone's per-unit fee for the size; free is exactly cost == 0")
    void costAndFreeFlag() {
        List<DeliveryOptionResponse> large = shippingService.listDeliveryOptions(ShippingSizeClass.LARGE, "en");

        DeliveryOptionResponse cairo = byCode(large, "CAI");
        assertThat(cairo.shippingCost()).isEqualByComparingTo("0.00");
        assertThat(cairo.free()).isTrue();

        DeliveryOptionResponse asyut = byCode(large, "AST");
        assertThat(asyut.shippingCost()).isEqualByComparingTo("550.00");
        assertThat(asyut.free()).isFalse();
        assertThat(asyut.sizeClass()).isEqualTo(ShippingSizeClass.LARGE);
        assertThat(asyut.deliveryDaysMin()).isLessThanOrEqualTo(asyut.deliveryDaysMax());

        for (DeliveryOptionResponse option : large) {
            assertThat(option.free()).as(option.code()).isEqualTo(option.shippingCost().signum() == 0);
        }
    }

    @Test
    @DisplayName("Without sizeClass the cost is the cheapest size; names follow the locale")
    void defaultsToCheapestSizeAndLocalises() {
        DeliveryOptionResponse delta = byCode(shippingService.listDeliveryOptions(null, "en"), "DAK");
        assertThat(delta.sizeClass()).isNull();
        assertThat(delta.shippingCost()).isEqualByComparingTo("100.00");

        assertThat(byCode(shippingService.listDeliveryOptions(null, "en"), "CAI").name()).isEqualTo("Cairo");
        assertThat(byCode(shippingService.listDeliveryOptions(null, "ar"), "CAI").name())
                .isNotEqualTo("Cairo");
    }

    @Test
    @DisplayName("Governorates in no zone are omitted, as GOVERNORATE_NOT_SERVED treats them")
    void closedGovernoratesAreOmitted() {
        List<DeliveryOptionResponse> all = shippingService.listDeliveryOptions(null, "en");
        assertThat(all).extracting(DeliveryOptionResponse::code)
                .doesNotContain("SSI", "ASW", "LUX").contains("CAI", "ALX");
    }

    private static DeliveryOptionResponse byCode(List<DeliveryOptionResponse> list, String code) {
        return list.stream().filter(o -> o.code().equals(code)).findFirst().orElseThrow();
    }
}
