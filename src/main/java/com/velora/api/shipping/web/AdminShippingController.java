package com.velora.api.shipping.web;

import com.velora.api.shipping.dto.MaxShippingCostRequest;
import com.velora.api.shipping.dto.ShippingRateRequest;
import com.velora.api.shipping.dto.ShippingZoneResponse;
import com.velora.api.shipping.service.ShippingAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Shipping", description = "Zones and rates. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/shipping")
public class AdminShippingController {

    private final ShippingAdminService shippingAdminService;

    public AdminShippingController(ShippingAdminService shippingAdminService) {
        this.shippingAdminService = shippingAdminService;
    }

    @Operation(summary = "List zones with their rates and covered governorates")
    @GetMapping("/zones")
    public List<ShippingZoneResponse> zones() {
        return shippingAdminService.listZones();
    }

    @Operation(summary = "Set the price of one size class in a zone",
            description = """
                    `baseCost` is the cost of ONE unit of `sizeClass`. Replaces that
                    (zone, size) price rather than adding a second one, so a governorate
                    can never match two competing prices.

                    `codFee` and the delivery days describe the whole zone and are
                    written to all of its size rows.
                    """)
    @PutMapping("/rates")
    public Map<String, Long> saveRate(@Valid @RequestBody ShippingRateRequest request) {
        return Map.of("id", shippingAdminService.saveRate(request));
    }

    @Operation(summary = "Set or clear a zone's shipping cap",
            description = "Shipping for one order never exceeds this. Null removes the cap.")
    @PutMapping("/zones/{zoneId}/max-shipping-cost")
    public void saveMaxShippingCost(@PathVariable Long zoneId,
                                    @Valid @RequestBody MaxShippingCostRequest request) {
        shippingAdminService.saveMaxShippingCost(zoneId, request);
    }
}
