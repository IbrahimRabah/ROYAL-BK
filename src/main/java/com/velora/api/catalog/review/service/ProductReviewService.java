package com.velora.api.catalog.review.service;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.review.domain.ProductReview;
import com.velora.api.catalog.review.domain.ReviewStatus;
import com.velora.api.catalog.review.dto.CreateReviewRequest;
import com.velora.api.catalog.review.dto.EligibleReviewResponse;
import com.velora.api.catalog.review.dto.MyReviewResponse;
import com.velora.api.catalog.review.dto.ProductReviewsResponse;
import com.velora.api.catalog.review.dto.PublicReviewResponse;
import com.velora.api.catalog.review.dto.UpdateReviewRequest;
import com.velora.api.catalog.review.repository.ProductReviewRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reviews from the customer's side, and the public list.
 *
 * <p>A customer may review a product only after an order of theirs that carried it is DELIVERED, once per
 * product. A new review is PENDING and invisible to everyone but its author until staff approve it; editing
 * an approved or rejected review puts it back to PENDING, so an approved text can never be swapped for an
 * unapproved one. Every method that takes a review id also takes the caller's id, and a review that is not
 * the caller's looks exactly like one that does not exist.
 */
@Service
public class ProductReviewService {

    private static final Logger log = LoggerFactory.getLogger(ProductReviewService.class);

    private final ProductReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final AppUserRepository userRepository;
    private final StorageService storageService;

    public ProductReviewService(ProductReviewRepository reviewRepository,
                                ProductRepository productRepository,
                                AppUserRepository userRepository,
                                StorageService storageService) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    // ------------------------------------------------------------------ customer

    @Transactional(readOnly = true)
    public List<EligibleReviewResponse> eligible(Long customerId, String locale) {
        Map<Long, String> orderByProduct = new LinkedHashMap<>();
        for (Object[] row : reviewRepository.eligible(customerId)) {
            orderByProduct.put((Long) row[0], (String) row[1]);
        }
        List<EligibleReviewResponse> out = new ArrayList<>();
        for (Product p : productRepository.findAllById(orderByProduct.keySet())) {
            ProductImage main = p.mainImage();
            out.add(new EligibleReviewResponse(p.getId(), p.getSlug(), p.nameFor(locale),
                    main == null ? null : storageService.urlFor(main.getUrl()), orderByProduct.get(p.getId())));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    @Transactional
    public MyReviewResponse create(Long customerId, CreateReviewRequest request, String locale) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        AppUser user = userRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        List<Object[]> proof = reviewRepository.proof(customerId, product.getId());
        if (proof.isEmpty()) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_ELIGIBLE);
        }
        if (reviewRepository.existsByCustomerIdAndProductId(customerId, product.getId())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        ProductReview review = new ProductReview();
        review.setProduct(product);
        review.setCustomerId(customerId);
        review.setOrderItemId((Long) proof.get(0)[0]);
        review.setOrderNumber((String) proof.get(0)[1]);
        review.setReviewerName(displayName(user));
        review.setRating(request.rating().shortValue());
        review.setComment(clean(request.comment()));
        review.setStatus(ReviewStatus.PENDING);
        reviewRepository.save(review);

        log.info("Review {} written for product {} by customer {}", review.getId(), product.getId(), customerId);
        return toMine(review, locale);
    }

    @Transactional
    public MyReviewResponse update(Long customerId, Long reviewId, UpdateReviewRequest request, String locale) {
        ProductReview review = reviewRepository.findByIdAndCustomerId(reviewId, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        review.setRating(request.rating().shortValue());
        review.setComment(clean(request.comment()));
        // Whatever it was, the new text has not been looked at.
        review.setStatus(ReviewStatus.PENDING);
        review.setModeratedBy(null);
        review.setModeratedAt(null);
        review.setModerationNote(null);
        reviewRepository.save(review);
        return toMine(review, locale);
    }

    @Transactional(readOnly = true)
    public PageResponse<MyReviewResponse> mine(Long customerId, Pageable pageable, String locale) {
        return PageResponse.from(reviewRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable),
                r -> toMine(r, locale));
    }

    // -------------------------------------------------------------------- public

    @Transactional(readOnly = true)
    public ProductReviewsResponse forProduct(String slug, Pageable pageable) {
        Long productId = productRepository.findActiveIdBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        Map<Integer, Integer> breakdown = new LinkedHashMap<>();
        for (int stars = 5; stars >= 1; stars--) {
            breakdown.put(stars, 0);
        }
        int count = 0;
        long weighted = 0;
        for (Object[] row : reviewRepository.breakdown(productId)) {
            int stars = ((Number) row[0]).intValue();
            int n = ((Number) row[1]).intValue();
            breakdown.put(stars, n);
            count += n;
            weighted += (long) stars * n;
        }
        BigDecimal average = count == 0 ? null
                : BigDecimal.valueOf(weighted).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);

        PageResponse<PublicReviewResponse> page = PageResponse.from(
                reviewRepository.findByProductIdAndStatusOrderByCreatedAtDesc(productId, ReviewStatus.APPROVED, pageable),
                r -> new PublicReviewResponse(r.getId(), r.getReviewerName(), r.getRating(), r.getComment(),
                        r.getCreatedAt()));
        return new ProductReviewsResponse(average, count, breakdown, page);
    }

    // ------------------------------------------------------------------ internal

    private MyReviewResponse toMine(ProductReview r, String locale) {
        Product p = r.getProduct();
        return new MyReviewResponse(r.getId(), p.getId(), p.getSlug(), p.nameFor(locale), r.getOrderNumber(),
                r.getRating(), r.getComment(), r.getStatus().name(),
                r.getStatus() == ReviewStatus.REJECTED ? r.getModerationNote() : null,
                r.getCreatedAt(), r.getUpdatedAt());
    }

    /** «Ahmed M.»: the first name and the initial of the last. Nothing that identifies the account. */
    static String displayName(AppUser user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String name = first.isEmpty() ? "" : first;
        if (!last.isEmpty()) {
            name = (name.isEmpty() ? "" : name + " ") + last.substring(0, last.offsetByCodePoints(0, 1)) + ".";
        }
        return name.isEmpty() ? "Customer" : name;
    }

    private static String clean(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Used by tests and the admin side to read what a review's customer is called. */
    static Map<Long, String> fullNames(Iterable<AppUser> users) {
        return java.util.stream.StreamSupport.stream(users.spliterator(), false)
                .collect(Collectors.toMap(AppUser::getId, u -> ((u.getFirstName() == null ? "" : u.getFirstName())
                        + " " + (u.getLastName() == null ? "" : u.getLastName())).trim()));
    }
}
