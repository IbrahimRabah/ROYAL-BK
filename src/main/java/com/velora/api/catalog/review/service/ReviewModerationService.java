package com.velora.api.catalog.review.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.review.domain.ProductReview;
import com.velora.api.catalog.review.domain.ReviewStatus;
import com.velora.api.catalog.review.dto.AdminReviewResponse;
import com.velora.api.catalog.review.dto.ReviewBulkRequest;
import com.velora.api.catalog.review.dto.ReviewBulkResponse;
import com.velora.api.catalog.review.dto.ReviewBulkResponse.Skipped;
import com.velora.api.catalog.review.dto.ReviewCountsResponse;
import com.velora.api.catalog.review.repository.ProductReviewRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.identity.repository.AppUserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where staff decide which reviews the public sees. Every decision is written to the audit log with who made
 * it, and nothing is hard-deleted except by an explicit delete (spam).
 *
 * <p>The bulk call is deliberately NOT one transaction: each review is decided in its own, so one that was
 * deleted meanwhile does not hold back the rest, same as the bulk publish of products.
 */
@Service
public class ReviewModerationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewModerationService.class);
    private static final String ENTITY = "PRODUCT_REVIEW";

    private final ProductReviewRepository reviewRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final ReviewDecider decider;

    public ReviewModerationService(ProductReviewRepository reviewRepository,
                                   AppUserRepository userRepository,
                                   AuditService auditService,
                                   ReviewDecider decider) {
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.decider = decider;
    }

    // --------------------------------------------------------------------- query

    @Transactional(readOnly = true)
    public PageResponse<AdminReviewResponse> list(ReviewStatus status, Long productId, Integer rating, String query,
                                                  Pageable pageable) {
        Specification<ProductReview> spec = (root, cq, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (productId != null) {
                all.add(cb.equal(root.get("product").get("id"), productId));
            }
            if (rating != null) {
                all.add(cb.equal(root.get("rating"), rating.shortValue()));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                all.add(cb.or(
                        cb.like(cb.lower(root.<String>get("comment")), like),
                        cb.like(cb.lower(root.<String>get("reviewerName")), like),
                        cb.like(cb.lower(root.<String>get("orderNumber")), like)));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
        Page<ProductReview> page = reviewRepository.findAll(spec, pageable);

        Map<Long, String> names = ProductReviewService.fullNames(userRepository.findAllById(
                page.getContent().stream().map(ProductReview::getCustomerId).distinct().toList()));
        return PageResponse.from(page, r -> ReviewDecider.toAdmin(r, names.get(r.getCustomerId())));
    }

    @Transactional(readOnly = true)
    public ReviewCountsResponse counts() {
        long pending = 0;
        long approved = 0;
        long rejected = 0;
        for (Object[] row : reviewRepository.countByStatus()) {
            long n = ((Number) row[1]).longValue();
            switch ((ReviewStatus) row[0]) {
                case PENDING -> pending = n;
                case APPROVED -> approved = n;
                case REJECTED -> rejected = n;
            }
        }
        return new ReviewCountsResponse(pending, approved, rejected, pending + approved + rejected);
    }

    // ------------------------------------------------------------------ decisions

    public AdminReviewResponse approve(Long id, String note, Long actorId) {
        return decide(id, ReviewStatus.APPROVED, note, actorId);
    }

    public AdminReviewResponse reject(Long id, String note, Long actorId) {
        return decide(id, ReviewStatus.REJECTED, note, actorId);
    }

    private AdminReviewResponse decide(Long id, ReviewStatus to, String note, Long actorId) {
        AdminReviewResponse done = decider.decide(id, to, note, actorId);
        log.info("Review {} {} by {}", id, to, actorId);
        return done;
    }

    public ReviewBulkResponse bulk(ReviewBulkRequest request, Long actorId) {
        ReviewStatus to = request.action() == ReviewBulkRequest.Action.APPROVE
                ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(request.ids()));
        List<Long> succeeded = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Long id : ids) {
            try {
                decide(id, to, request.note(), actorId);
                succeeded.add(id);
            } catch (BusinessException e) {
                skipped.add(new Skipped(id, e.getErrorCode() == ErrorCode.REVIEW_NOT_FOUND
                        ? "REVIEW_NOT_FOUND"
                        : (to == ReviewStatus.APPROVED ? "ALREADY_APPROVED" : "ALREADY_REJECTED")));
            }
        }
        return new ReviewBulkResponse(request.action().name(), ids.size(), succeeded, skipped);
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        ProductReview review = reviewRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        String label = review.getProduct().getSlug();
        String was = review.getStatus().name();
        reviewRepository.delete(review);
        auditService.record(AuditAction.REVIEW_MODERATED, ENTITY, id, label, was, "DELETED", null, actorId);
    }
}
