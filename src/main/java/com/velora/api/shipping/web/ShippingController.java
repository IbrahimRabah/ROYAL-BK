package com.velora.api.shipping.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.shipping.dto.ShippingQuoteRequest;
import com.velora.api.shipping.dto.ShippingQuoteResponse;
import com.velora.api.shipping.service.ShippingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.velora.api.catalog.domain.ShippingSizeClass;
import java.util.List;
import com.velora.api.shipping.dto.DeliveryOptionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shipping", description = "Delivery cost and estimate")
@RestController
@RequestMapping("/api/v1/shipping")
public class ShippingController {

    private static final String GUEST_HEADER = "X-Guest-Token";

    private final ShippingService shippingService;

    public ShippingController(ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    @Operation(summary = "Delivery cost and estimate per governorate (no cart needed)",
            description = """
                    For the product page. Every SERVED governorate with the zone's per-unit fee for
                    `sizeClass` (the product's `shippingSizeClass`), capped at the zone maximum, plus
                    `free` (true exactly when that fee is 0) and the delivery-days range. Closed
                    governorates are omitted. The fee excludes quantity, COD fee and assembly: the
                    cart total comes from `POST /shipping/quote`.
                    """,
            security = {})
    @GetMapping("/governorates")
    public List<DeliveryOptionResponse> governorates(
            @RequestParam(required = false) ShippingSizeClass sizeClass,
            HttpServletRequest request) {
        return shippingService.listDeliveryOptions(sizeClass, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Quote shipping for the current cart",
            description = """
                    Used by the cart-page estimator and again at checkout. Cheap enough to
                    call every time the customer changes governorate.

                    Cash on delivery is assumed, since it is the only payment method in V1.
                    """,
            security = {})
    @ApiResponse(responseCode = "409", description = "We do not deliver to that governorate")
    @PostMapping("/quote")
    public ShippingQuoteResponse quote(@AuthenticationPrincipal UserPrincipal principal,
                                       @RequestHeader(value = GUEST_HEADER, required = false)
                                       String guestToken,
                                       @Valid @RequestBody ShippingQuoteRequest body,
                                       HttpServletRequest request) {

        return shippingService.quote(
                body.governorateId(),
                principal == null ? null : principal.id(),
                guestToken,
                body.cartId(),
                true,
                LocaleResolver.resolve(request));
    }
}
