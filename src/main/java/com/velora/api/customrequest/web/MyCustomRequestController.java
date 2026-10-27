package com.velora.api.customrequest.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.customrequest.dto.AcceptQuoteRequest;
import com.velora.api.customrequest.dto.MyCustomRequestCountResponse;
import com.velora.api.customrequest.dto.MyCustomRequestResponse;
import com.velora.api.customrequest.dto.MyCustomRequestSummaryResponse;
import com.velora.api.customrequest.service.MyCustomRequestService;
import com.velora.api.identity.security.UserPrincipal;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "My custom requests", description = "The signed-in customer's own custom requests")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/custom-requests")
public class MyCustomRequestController {

    private final MyCustomRequestService service;

    public MyCustomRequestController(MyCustomRequestService service) {
        this.service = service;
    }

    @Operation(summary = "My custom requests, newest first",
            description = """
                    Only requests submitted while signed in to THIS account. A request is never
                    matched by phone number or email, and a guest's request never appears here.
                    Staff notes are never returned.
                    """)
    @GetMapping
    public PageResponse<MyCustomRequestSummaryResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @PageableDefault(size = 10) Pageable pageable,
            HttpServletRequest request) {
        return service.list(principal.id(), pageable, LocaleResolver.resolve(request));
    }

    @Operation(summary = "How many of my quotes are waiting for my answer",
            description = "One number for a badge. It stays until the quote is answered; it is not "
                    + "\"new since your last visit\".")
    @GetMapping("/summary")
    public MyCustomRequestCountResponse summary(@AuthenticationPrincipal UserPrincipal principal) {
        return service.awaitingResponse(principal.id());
    }

    @Operation(summary = "One of my requests, by its reference",
            description = "404 CUSTOM_REQUEST_NOT_FOUND for a reference that does not exist AND for "
                    + "one that is not mine - the same answer, so references cannot be probed.")
    @GetMapping("/{requestNumber}")
    public MyCustomRequestResponse get(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable String requestNumber,
                                       HttpServletRequest request) {
        return service.get(principal.id(), requestNumber, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Accept the quote",
            description = """
                    Send the amount the customer saw. If staff revised the price since, this is
                    `409 QUOTE_CHANGED` and nothing is accepted. Asking again for what already
                    happened (the request is already accepted at that price) is a `200`, not a
                    conflict. `409 QUOTE_ALREADY_ANSWERED` when it was REJECTED instead.
                    Acceptance by phone, recorded by staff, is unchanged.
                    """)
    @PostMapping("/{requestNumber}/accept")
    public MyCustomRequestResponse accept(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable String requestNumber,
                                          @Valid @RequestBody AcceptQuoteRequest body,
                                          HttpServletRequest request) {
        return service.accept(principal.id(), requestNumber, body.quotedAmount(),
                LocaleResolver.resolve(request));
    }

    @Operation(summary = "Reject the quote",
            description = """
                    No body, and no reason: there is no customer-visible note field to put one
                    in. Rejecting an already rejected request is a `200`. `409
                    QUOTE_ALREADY_ANSWERED` when it was ACCEPTED instead.
                    """)
    @PostMapping("/{requestNumber}/reject")
    public MyCustomRequestResponse reject(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable String requestNumber,
                                          HttpServletRequest request) {
        return service.reject(principal.id(), requestNumber, LocaleResolver.resolve(request));
    }
}
