package com.velora.api.customrequest.web;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.customrequest.dto.CustomRequestFilter;
import com.velora.api.customrequest.dto.CustomRequestQuoteRequest;
import com.velora.api.customrequest.dto.CustomRequestResponse;
import com.velora.api.customrequest.dto.CustomRequestStatusRequest;
import com.velora.api.customrequest.dto.CustomRequestSummaryResponse;
import com.velora.api.customrequest.service.CustomRequestAdminService;
import com.velora.api.identity.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Custom requests", description = "Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/custom-requests")
public class AdminCustomRequestController {

    private final CustomRequestAdminService adminService;

    public AdminCustomRequestController(CustomRequestAdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "List custom requests",
            description = "Newest first. Every filter is optional and they compose.")
    @GetMapping
    public PageResponse<CustomRequestSummaryResponse> list(
            @ParameterObject CustomRequestFilter filter,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.list(filter, pageable);
    }

    @Operation(summary = "One custom request, with its images")
    @GetMapping("/{id}")
    public CustomRequestResponse get(@PathVariable Long id) {
        return adminService.get(id);
    }

    @Operation(summary = "Move a request to another status",
            description = """
                    Allowed here: CONTACTED, ACCEPTED, REJECTED. QUOTED comes from giving a
                    quote; CONVERTED is not available yet. ACCEPTED needs a quote, REJECTED
                    needs a note. Recorded in the audit log.
                    """)
    @PatchMapping("/{id}/status")
    public CustomRequestResponse changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody CustomRequestStatusRequest body,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return adminService.changeStatus(id, body.status(), body.note(), principal.id());
    }

    @Operation(summary = "Give or change the quote",
            description = """
                    The tax-inclusive total for the whole request. From NEW or CONTACTED the
                    request becomes QUOTED. May be revised while QUOTED; not after the customer
                    accepted or the request closed. Recorded in the audit log, old and new
                    amount.
                    """)
    @PatchMapping("/{id}/quote")
    public CustomRequestResponse quote(@PathVariable Long id,
                                       @Valid @RequestBody CustomRequestQuoteRequest body,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return adminService.quote(id, body.amount(), body.note(), principal.id());
    }
}
