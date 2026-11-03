package com.velora.api.catalog.review.web;

import com.velora.api.catalog.review.domain.ReviewStatus;
import com.velora.api.catalog.review.dto.AdminReviewResponse;
import com.velora.api.catalog.review.dto.ReviewBulkRequest;
import com.velora.api.catalog.review.dto.ReviewBulkResponse;
import com.velora.api.catalog.review.dto.ReviewCountsResponse;
import com.velora.api.catalog.review.dto.ReviewModerationRequest;
import com.velora.api.catalog.review.service.ReviewModerationService;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Reviews", description = "Decide which customer reviews the public sees. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/reviews")
public class AdminReviewController {

    private final ReviewModerationService moderationService;

    public AdminReviewController(ReviewModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @Operation(summary = "Reviews, newest first",
            description = "Filter by status (PENDING / APPROVED / REJECTED), product, stars, or text "
                    + "(comment, reviewer name, order number). Omit the status to see all.")
    @GetMapping
    public PageResponse<AdminReviewResponse> list(
            @Parameter(description = "PENDING, APPROVED or REJECTED") @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) Long productId,
            @Parameter(description = "1 to 5") @RequestParam(required = false) Integer rating,
            @RequestParam(required = false, name = "q") String query,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return moderationService.list(status, productId, rating, query, pageable);
    }

    @Operation(summary = "How many reviews are waiting, approved and rejected",
            description = "For the tabs, the sidebar badge and the dashboard.")
    @GetMapping("/counts")
    public ReviewCountsResponse counts() {
        return moderationService.counts();
    }

    @Operation(summary = "Approve: the review appears on the product page",
            description = "409 REVIEW_ALREADY_DECIDED if it is already approved.")
    @PostMapping("/{id}/approve")
    public AdminReviewResponse approve(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) ReviewModerationRequest body,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return moderationService.approve(id, body == null ? null : body.note(), principal.id());
    }

    @Operation(summary = "Reject (or hide an approved review): it disappears from the product page",
            description = "The note is shown to the customer on their own review. 409 if already rejected.")
    @PostMapping("/{id}/reject")
    public AdminReviewResponse reject(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) ReviewModerationRequest body,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return moderationService.reject(id, body == null ? null : body.note(), principal.id());
    }

    @Operation(summary = "Approve or reject many at once",
            description = "At most 500 ids. Each review is its own transaction; the ones that could not change "
                    + "are listed in `skipped` with the reason.")
    @PostMapping("/bulk")
    public ReviewBulkResponse bulk(@Valid @RequestBody ReviewBulkRequest request,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return moderationService.bulk(request, principal.id());
    }

    @Operation(summary = "Delete a review (spam)", description = "Permanent. Prefer reject to keep a record.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        moderationService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }
}
