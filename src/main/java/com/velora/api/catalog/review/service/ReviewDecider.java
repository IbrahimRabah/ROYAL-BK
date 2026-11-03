package com.velora.api.catalog.review.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.review.domain.ProductReview;
import com.velora.api.catalog.review.domain.ReviewStatus;
import com.velora.api.catalog.review.dto.AdminReviewResponse;
import com.velora.api.catalog.review.repository.ProductReviewRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One moderation decision in its own transaction. A separate bean so that
 * {@link ReviewModerationService#bulk} can call it once per review through the transaction proxy: a review that
 * cannot be decided costs only itself.
 */
@Service
public class ReviewDecider {

    private static final String ENTITY = "PRODUCT_REVIEW";

    private final ProductReviewRepository reviewRepository;
    private final AuditService auditService;

    public ReviewDecider(ProductReviewRepository reviewRepository, AuditService auditService) {
        this.reviewRepository = reviewRepository;
        this.auditService = auditService;
    }

    /**
     * @throws BusinessException REVIEW_NOT_FOUND, or REVIEW_ALREADY_DECIDED when it is already in that state
     */
    @Transactional
    public AdminReviewResponse decide(Long id, ReviewStatus to, String note, Long actorId) {
        ProductReview review = reviewRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        ReviewStatus from = review.getStatus();
        if (from == to) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }
        review.setStatus(to);
        review.setModeratedBy(actorId);
        review.setModeratedAt(OffsetDateTime.now(ZoneOffset.UTC));
        String cleaned = note == null || note.isBlank() ? null : note.strip();
        review.setModerationNote(cleaned);
        reviewRepository.save(review);

        auditService.record(AuditAction.REVIEW_MODERATED, ENTITY, review.getId(), review.getProduct().getSlug(),
                from.name(), to.name(), cleaned, actorId);
        return toAdmin(review, null);
    }

    static AdminReviewResponse toAdmin(ProductReview r, String customerName) {
        Product p = r.getProduct();
        return new AdminReviewResponse(r.getId(), p.getId(), p.getSlug(), p.nameFor("ar"), r.getCustomerId(),
                customerName, r.getReviewerName(), r.getOrderNumber(), r.getRating(), r.getComment(),
                r.getStatus().name(), r.getModeratedBy(), r.getModeratedAt(), r.getModerationNote(),
                r.getCreatedAt());
    }
}
