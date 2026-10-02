package com.velora.api.shipping.web;

import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.shipping.dto.AdminGovernorateResponse;
import com.velora.api.shipping.dto.AssignZoneRequest;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public Map<String, Long> saveRate(@Valid @RequestBody ShippingRateRequest request,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        return Map.of("id", shippingAdminService.saveRate(request, principal.id()));
    }

    @Operation(summary = "List every governorate, served or closed",
            description = "Unlike the zones list, this includes governorates that are in no "
                    + "zone — the ones we do not deliver to — so they can be reopened.")
    @GetMapping("/governorates")
    public List<AdminGovernorateResponse> governorates() {
        return shippingAdminService.listGovernorates();
    }

    @Operation(summary = "Open a governorate for delivery, or move it to another zone",
            description = """
                    Puts the governorate in the given zone. A governorate belongs to exactly
                    one zone, so this either opens a closed governorate or moves an open one.
                    Idempotent.

                    The zone must have a rate for every size class, otherwise the governorate
                    would look open here and still fail at checkout (409
                    SHIPPING_RATE_NOT_CONFIGURED).
                    """)
    @PutMapping("/governorates/{governorateId}/zone")
    public void assignGovernorate(@PathVariable Long governorateId,
                                  @Valid @RequestBody AssignZoneRequest request,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        shippingAdminService.assignGovernorate(governorateId, request.zoneId(), principal.id());
    }

    @Operation(summary = "Close a governorate for delivery",
            description = """
                    Removes the governorate from its zone. It stays in the governorate list
                    with `served: false`; quote, checkout and saved addresses refuse it with
                    GOVERNORATE_NOT_SERVED. Idempotent. Reopen it with the PUT above.
                    """)
    @DeleteMapping("/governorates/{governorateId}/zone")
    public void closeGovernorate(@PathVariable Long governorateId,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        shippingAdminService.closeGovernorate(governorateId, principal.id());
    }

    @Operation(summary = "Set or clear a zone's shipping cap",
            description = "Shipping for one order never exceeds this. Null removes the cap.")
    @PutMapping("/zones/{zoneId}/max-shipping-cost")
    public void saveMaxShippingCost(@PathVariable Long zoneId,
                                    @Valid @RequestBody MaxShippingCostRequest request,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        shippingAdminService.saveMaxShippingCost(zoneId, request, principal.id());
    }
}
