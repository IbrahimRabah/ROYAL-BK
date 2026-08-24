package com.velora.api.order.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.util.EnumParam;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.order.domain.PaymentStatus;
import com.velora.api.order.dto.CancelOrderRequest;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.OrderStatusUpdateRequest;
import com.velora.api.order.dto.OrderSummaryResponse;
import com.velora.api.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Orders", description = "Order operations. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "List orders",
            description = "Filter by status, or search by the customer's phone number.")
    @GetMapping
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String phone,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {

        return orderService.listForAdmin(status, phone, pageable,
                LocaleResolver.resolve(request));
    }

    @Operation(summary = "One order in full, with its timeline")
    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable Long orderId, HttpServletRequest request) {
        return orderService.getForAdmin(orderId, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Confirm — usually after the COD phone call",
            description = "Records who confirmed and when.")
    @PostMapping("/{orderId}/confirm")
    public OrderResponse confirm(@PathVariable Long orderId,
                                 @RequestParam(required = false) String note,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 HttpServletRequest request) {

        return orderService.confirm(orderId, note, principal.id(),
                LocaleResolver.resolve(request));
    }

    @Operation(summary = "Move the order to a new fulfilment status",
            description = """
                    Only legal transitions are accepted — the rules live in the service,
                    not in the UI dropdown.

                    `SHIPPED` commits the stock reservation: on-hand drops and the units
                    leave for good.

                    A note is REQUIRED for `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY`,
                    `RETURNED_TO_SELLER` and `CANCELLED`. Someone will read these months
                    later trying to understand what happened.
                    """)
    @PatchMapping("/{orderId}/fulfillment-status")
    public OrderResponse changeStatus(@PathVariable Long orderId,
                                      @Valid @RequestBody OrderStatusUpdateRequest body,
                                      @AuthenticationPrincipal UserPrincipal principal,
                                      HttpServletRequest request) {

        return orderService.changeFulfillmentStatus(orderId, body.status(), body.note(),
                principal.id(), LocaleResolver.resolve(request));
    }

    @Operation(summary = "Mark the payment as received",
            description = """
                    For COD this is normally driven by the courier remittance rather than
                    set by hand. It stays available for the cases the courier settles
                    outside the usual batch.
                    """)
    @PatchMapping("/{orderId}/payment-status")
    public OrderResponse markPaid(@PathVariable Long orderId,
                                  @RequestParam String status,
                                  @RequestParam(required = false) String note,
                                  @AuthenticationPrincipal UserPrincipal principal,
                                  HttpServletRequest request) {

        return orderService.changePaymentStatus(orderId,
                EnumParam.parse(PaymentStatus.class, status, "status"), note, principal.id(),
                LocaleResolver.resolve(request));
    }

    @Operation(summary = "Cancel an order", description = "A reason is required.")
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable Long orderId,
                                @Valid @RequestBody CancelOrderRequest body,
                                @AuthenticationPrincipal UserPrincipal principal,
                                HttpServletRequest request) {

        return orderService.cancelByStaff(orderId, body.reason(), principal.id(),
                LocaleResolver.resolve(request));
    }
}
