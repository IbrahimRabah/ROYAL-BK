package com.velora.api.catalog.review.web;

import com.velora.api.catalog.review.dto.CreateReviewRequest;
import com.velora.api.catalog.review.dto.EligibleReviewResponse;
import com.velora.api.catalog.review.dto.MyReviewResponse;
import com.velora.api.catalog.review.dto.UpdateReviewRequest;
import com.velora.api.catalog.review.service.ProductReviewService;
import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "My reviews", description = "The signed-in customer's own reviews")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/reviews")
public class MyReviewController {

    private final ProductReviewService reviewService;

    public MyReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "My reviews, newest first",
            description = "Every state is visible to the author: PENDING, APPROVED or REJECTED (with the note).")
    @GetMapping
    public PageResponse<MyReviewResponse> mine(@AuthenticationPrincipal UserPrincipal principal,
                                               @ParameterObject @PageableDefault(size = 10) Pageable pageable,
                                               HttpServletRequest request) {
        return reviewService.mine(principal.id(), pageable, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Products I received and have not reviewed yet",
            description = "Products on a DELIVERED order of mine with no review of mine.")
    @GetMapping("/eligible")
    public List<EligibleReviewResponse> eligible(@AuthenticationPrincipal UserPrincipal principal,
                                                 HttpServletRequest request) {
        return reviewService.eligible(principal.id(), LocaleResolver.resolve(request));
    }

    @Operation(summary = "Review a product I received",
            description = """
                    Allowed once per product, after a DELIVERED order of mine carried it
                    (403 REVIEW_NOT_ELIGIBLE otherwise, 409 REVIEW_ALREADY_EXISTS for a second).
                    The review is PENDING: it appears on the product page only after staff approve it.
                    """)
    @PostMapping
    public ResponseEntity<MyReviewResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody CreateReviewRequest body,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.create(principal.id(), body, LocaleResolver.resolve(request)));
    }

    @Operation(summary = "Edit my review",
            description = "The review goes back to PENDING and is approved again. Someone else's review is 404.")
    @PutMapping("/{id}")
    public MyReviewResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable Long id,
                                   @Valid @RequestBody UpdateReviewRequest body,
                                   HttpServletRequest request) {
        return reviewService.update(principal.id(), id, body, LocaleResolver.resolve(request));
    }
}
