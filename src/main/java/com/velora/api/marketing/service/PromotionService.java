package com.velora.api.marketing.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.storage.StorageTransactionSupport;
import com.velora.api.common.storage.StoredFile;
import com.velora.api.marketing.domain.Promotion;
import com.velora.api.marketing.domain.PromotionScope;
import com.velora.api.marketing.domain.PromotionState;
import com.velora.api.marketing.domain.PromotionStatus;
import com.velora.api.marketing.dto.PromotionImpactResponse;
import com.velora.api.marketing.dto.PromotionImpactResponse.Example;
import com.velora.api.marketing.dto.PromotionImpactResponse.Overlap;
import com.velora.api.marketing.dto.PromotionResponse;
import com.velora.api.marketing.dto.PromotionSaveRequest;
import com.velora.api.marketing.dto.PublicPromotionResponse;
import com.velora.api.marketing.repository.AnnouncementRepository;
import com.velora.api.marketing.repository.PromotionRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Discounts: what staff create, check and switch on, and the running ones the storefront shows as banners.
 *
 * <p>Three safeguards stand between a mistake and a price on the website:
 * <ul>
 *   <li>an activation (or an edit of an active discount) is REFUSED while any variant would sell below its cost
 *       price;</li>
 *   <li>a discount above 70 percent must be confirmed;</li>
 *   <li>{@link #impact} shows the affected products, example prices and overlaps before anything is switched on.</li>
 * </ul>
 * A discount used by an order is ended, never deleted: its id sits on that order's lines.
 */
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);
    private static final String ENTITY = "PROMOTION";
    private static final BigDecimal HIGH_DISCOUNT = new BigDecimal("70");
    private static final int EXAMPLES = 5;
    private static final String VARIANT_SELECT = """
            SELECT v.sku, v.price, v.cost_price, p.id AS pid, t.name AS name
            FROM product_variant v
            JOIN product p ON p.id = v.product_id
            LEFT JOIN product_translation t ON t.product_id = p.id AND t.locale = 'ar'
            WHERE v.status = 'ACTIVE' AND v.archived_at IS NULL AND p.archived_at IS NULL AND p.status = 'ACTIVE'
            """;

    public enum BannerKind { DESKTOP, MOBILE }

    private final PromotionRepository repository;
    private final AnnouncementRepository announcementRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final PromotionPricingService pricing;
    private final StorageService storageService;
    private final StorageTransactionSupport storageSupport;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public PromotionService(PromotionRepository repository, AnnouncementRepository announcementRepository,
                            CategoryRepository categoryRepository, ProductRepository productRepository,
                            PromotionPricingService pricing, StorageService storageService,
                            StorageTransactionSupport storageSupport, AuditService auditService, JdbcTemplate jdbc) {
        this.repository = repository;
        this.announcementRepository = announcementRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.pricing = pricing;
        this.storageService = storageService;
        this.storageSupport = storageSupport;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------- query

    @Transactional(readOnly = true)
    public PageResponse<PromotionResponse> list(PromotionState state, Pageable pageable) {
        OffsetDateTime now = now();
        Specification<Promotion> spec = (root, cq, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (state != null) {
                switch (state) {
                    case DRAFT -> all.add(cb.equal(root.get("status"), PromotionStatus.DRAFT));
                    case PAUSED -> all.add(cb.equal(root.get("status"), PromotionStatus.PAUSED));
                    case SCHEDULED -> {
                        all.add(cb.equal(root.get("status"), PromotionStatus.ACTIVE));
                        all.add(cb.greaterThan(root.<OffsetDateTime>get("startsAt"), now));
                    }
                    case RUNNING -> {
                        all.add(cb.equal(root.get("status"), PromotionStatus.ACTIVE));
                        all.add(cb.lessThanOrEqualTo(root.<OffsetDateTime>get("startsAt"), now));
                        all.add(cb.greaterThan(root.<OffsetDateTime>get("endsAt"), now));
                    }
                    case ENDED -> {
                        all.add(cb.equal(root.get("status"), PromotionStatus.ACTIVE));
                        all.add(cb.lessThanOrEqualTo(root.<OffsetDateTime>get("endsAt"), now));
                    }
                }
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
        Page<Promotion> page = repository.findAll(spec, pageable);
        return PageResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public PromotionResponse get(Long id) {
        return toResponse(load(id));
    }

    /** The running discounts, for the storefront banners. */
    @Transactional(readOnly = true)
    public List<PublicPromotionResponse> running(String locale) {
        OffsetDateTime now = now();
        boolean en = "en".equalsIgnoreCase(locale);
        List<PublicPromotionResponse> out = new ArrayList<>();
        for (Promotion p : repository.findByStatus(PromotionStatus.ACTIVE)) {
            if (!p.isRunningAt(now)) {
                continue;
            }
            String slug = p.getScope() == PromotionScope.PRODUCT ? productSlug(p.getTargetId()) : null;
            String name = en && notBlank(p.getNameEn()) ? p.getNameEn() : p.getNameAr();
            String headline = en && notBlank(p.getHeadlineEn()) ? p.getHeadlineEn() : p.getHeadlineAr();
            out.add(new PublicPromotionResponse(p.getId(), name, headline, p.getPercent(), p.getScope().name(),
                    p.getTargetId(), slug, urlOf(p.getBannerKey()), urlOf(p.getBannerMobileKey()), p.getEndsAt(),
                    landingPath(p, slug)));
        }
        out.sort((a, b) -> b.percent().compareTo(a.percent()));
        return out;
    }

    // --------------------------------------------------------------------- change

    @Transactional
    public PromotionResponse create(PromotionSaveRequest request, Long actorId) {
        Promotion p = new Promotion();
        apply(p, request);
        p.setStatus(PromotionStatus.DRAFT);
        p.setCreatedBy(actorId);
        repository.save(p);
        audit(p, "CREATED", null, p.getPercent() + "%", actorId);
        return toResponse(p);
    }

    @Transactional
    public PromotionResponse update(Long id, PromotionSaveRequest request, Long actorId) {
        Promotion p = load(id);
        String before = describe(p);
        apply(p, request);
        if (p.getStatus() == PromotionStatus.ACTIVE) {
            // An edit must not smuggle in what activation would have refused.
            refuseBelowCost(p);
            refuseEnded(p);
        }
        repository.save(p);
        changed();
        audit(p, "UPDATED", before, describe(p), actorId);
        return toResponse(p);
    }

    @Transactional
    public PromotionResponse activate(Long id, Long actorId) {
        Promotion p = load(id);
        refuseEnded(p);
        refuseBelowCost(p);
        PromotionStatus was = p.getStatus();
        p.setStatus(PromotionStatus.ACTIVE);
        repository.save(p);
        changed();
        audit(p, "ACTIVATED", was.name(), PromotionStatus.ACTIVE.name(), actorId);
        return toResponse(p);
    }

    @Transactional
    public PromotionResponse pause(Long id, Long actorId) {
        Promotion p = load(id);
        PromotionStatus was = p.getStatus();
        p.setStatus(PromotionStatus.PAUSED);
        repository.save(p);
        changed();
        audit(p, "PAUSED", was.name(), PromotionStatus.PAUSED.name(), actorId);
        return toResponse(p);
    }

    /** A running discount stops now; one that has not started is paused (it would otherwise end before it began). */
    @Transactional
    public PromotionResponse end(Long id, Long actorId) {
        Promotion p = load(id);
        OffsetDateTime now = now();
        if (p.stateAt(now) == PromotionState.RUNNING) {
            p.setEndsAt(now);
        } else {
            p.setStatus(PromotionStatus.PAUSED);
        }
        repository.save(p);
        changed();
        audit(p, "ENDED", null, p.getEndsAt().toString(), actorId);
        return toResponse(p);
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        Promotion p = load(id);
        if (repository.usedByOrders(id) == 1 || announcementRepository.existsByPromotionId(id)) {
            throw new BusinessException(ErrorCode.PROMOTION_IN_USE);
        }
        String banner = p.getBannerKey();
        String mobile = p.getBannerMobileKey();
        audit(p, "DELETED", describe(p), null, actorId);
        repository.delete(p);
        storageSupport.deleteAfterCommit(banner);
        storageSupport.deleteAfterCommit(mobile);
        changed();
    }

    // --------------------------------------------------------------------- banner

    /** The image is checked by its bytes and size like every other upload; the one it replaces is removed after commit. */
    @Transactional
    public PromotionResponse setBanner(Long id, BannerKind kind, MultipartFile file, Long actorId) {
        Promotion p = load(id);
        StoredFile stored = storageService.store(file, "promotions");
        storageSupport.deleteUnlessCommitted(stored.key());
        String old = kind == BannerKind.MOBILE ? p.getBannerMobileKey() : p.getBannerKey();
        if (kind == BannerKind.MOBILE) {
            p.setBannerMobileKey(stored.key());
        } else {
            p.setBannerKey(stored.key());
        }
        repository.save(p);
        storageSupport.deleteAfterCommit(old);
        audit(p, "BANNER_" + kind, old, stored.key(), actorId);
        return toResponse(p);
    }

    @Transactional
    public PromotionResponse removeBanner(Long id, BannerKind kind, Long actorId) {
        Promotion p = load(id);
        String old = kind == BannerKind.MOBILE ? p.getBannerMobileKey() : p.getBannerKey();
        if (kind == BannerKind.MOBILE) {
            p.setBannerMobileKey(null);
        } else {
            p.setBannerKey(null);
        }
        repository.save(p);
        storageSupport.deleteAfterCommit(old);
        audit(p, "BANNER_REMOVED_" + kind, old, null, actorId);
        return toResponse(p);
    }

    // --------------------------------------------------------------------- impact

    @Transactional(readOnly = true)
    public PromotionImpactResponse impact(Long id) {
        Promotion p = load(id);
        List<Row> rows = affectedVariants(p);
        List<Example> examples = new ArrayList<>();
        List<Example> below = new ArrayList<>();
        Set<Long> products = new java.util.HashSet<>();
        int belowCount = 0;
        for (Row r : rows) {
            products.add(r.productId());
            BigDecimal sale = PromotionPricingService.discounted(r.price(), p.getPercent());
            Example example = new Example(r.sku(), r.name(), r.price(), sale, r.cost());
            if (examples.size() < EXAMPLES) {
                examples.add(example);
            }
            if (r.cost() != null && sale.compareTo(r.cost()) < 0) {
                belowCount++;
                if (below.size() < EXAMPLES) {
                    below.add(example);
                }
            }
        }
        return new PromotionImpactResponse(products.size(), rows.size(), belowCount,
                p.getPercent().compareTo(HIGH_DISCOUNT) > 0, examples, below, overlaps(p));
    }

    // ------------------------------------------------------------------- internals

    private record Row(String sku, BigDecimal price, BigDecimal cost, long productId, String name) {
    }

    private List<Row> affectedVariants(Promotion p) {
        String sql = VARIANT_SELECT;
        List<Object> args = new ArrayList<>();
        switch (p.getScope()) {
            case PRODUCT -> {
                sql += " AND p.id = ?";
                args.add(p.getTargetId());
            }
            case CATEGORY -> {
                Set<Long> covered = PromotionPricingService.descendantsOf(p.getTargetId(), categoryChildren());
                sql += " AND p.category_id IN (" + String.join(",", java.util.Collections.nCopies(covered.size(), "?")) + ")";
                args.addAll(covered);
            }
            default -> { }
        }
        return jdbc.query(sql + " ORDER BY v.id", (rs, n) -> new Row(rs.getString("sku"), rs.getBigDecimal("price"),
                rs.getBigDecimal("cost_price"), rs.getLong("pid"), rs.getString("name")), args.toArray());
    }

    private List<Overlap> overlaps(Promotion mine) {
        Map<Long, List<Long>> children = categoryChildren();
        List<Overlap> out = new ArrayList<>();
        OffsetDateTime now = now();
        for (Promotion other : repository.findAll()) {
            if (other.getId().equals(mine.getId()) || other.getStatus() == PromotionStatus.DRAFT) {
                continue;
            }
            boolean timeOverlap = other.getStartsAt().isBefore(mine.getEndsAt()) && other.getEndsAt().isAfter(mine.getStartsAt());
            if (timeOverlap && scopesOverlap(mine, other, children)) {
                out.add(new Overlap(other.getId(), other.getNameAr(), other.getPercent(), other.getScope().name(),
                        other.stateAt(now).name()));
            }
        }
        return out;
    }

    private boolean scopesOverlap(Promotion a, Promotion b, Map<Long, List<Long>> children) {
        if (a.getScope() == PromotionScope.ALL || b.getScope() == PromotionScope.ALL) {
            return true;
        }
        if (a.getScope() == b.getScope()) {
            if (a.getScope() == PromotionScope.PRODUCT) {
                return a.getTargetId().equals(b.getTargetId());
            }
            Set<Long> left = PromotionPricingService.descendantsOf(a.getTargetId(), children);
            return PromotionPricingService.descendantsOf(b.getTargetId(), children).stream().anyMatch(left::contains);
        }
        Promotion category = a.getScope() == PromotionScope.CATEGORY ? a : b;
        Promotion product = category == a ? b : a;
        Long categoryOfProduct = jdbc.query("SELECT category_id FROM product WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, product.getTargetId());
        return categoryOfProduct != null
                && PromotionPricingService.descendantsOf(category.getTargetId(), children).contains(categoryOfProduct);
    }

    private Map<Long, List<Long>> categoryChildren() {
        Map<Long, List<Long>> children = new HashMap<>();
        jdbc.query("SELECT id, parent_id FROM category", rs -> {
            long parent = rs.getLong(2);
            if (!rs.wasNull()) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(rs.getLong(1));
            }
        });
        return children;
    }

    private void apply(Promotion p, PromotionSaveRequest r) {
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The discount must end after it starts");
        }
        if (r.scope() == PromotionScope.ALL) {
            if (r.targetId() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A discount on all products has no target");
            }
        } else {
            if (r.targetId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetId is required for " + r.scope());
            }
            boolean exists = r.scope() == PromotionScope.CATEGORY
                    ? categoryRepository.existsById(r.targetId()) : productRepository.existsById(r.targetId());
            if (!exists) {
                throw new BusinessException(r.scope() == PromotionScope.CATEGORY
                        ? ErrorCode.CATEGORY_NOT_FOUND : ErrorCode.PRODUCT_NOT_FOUND);
            }
        }
        if (r.percent().compareTo(HIGH_DISCOUNT) > 0 && !Boolean.TRUE.equals(r.confirmHighDiscount())) {
            throw new BusinessException(ErrorCode.PROMOTION_HIGH_DISCOUNT_UNCONFIRMED);
        }
        p.setNameAr(r.nameAr().strip());
        p.setNameEn(blankToNull(r.nameEn()));
        p.setScope(r.scope());
        p.setTargetId(r.scope() == PromotionScope.ALL ? null : r.targetId());
        p.setPercent(r.percent().setScale(2, java.math.RoundingMode.HALF_UP));
        p.setStartsAt(r.startsAt().withOffsetSameInstant(ZoneOffset.UTC));
        p.setEndsAt(r.endsAt().withOffsetSameInstant(ZoneOffset.UTC));
        p.setHeadlineAr(blankToNull(r.headlineAr()));
        p.setHeadlineEn(blankToNull(r.headlineEn()));
    }

    private void refuseBelowCost(Promotion p) {
        for (Row r : affectedVariants(p)) {
            if (r.cost() != null && PromotionPricingService.discounted(r.price(), p.getPercent()).compareTo(r.cost()) < 0) {
                throw new BusinessException(ErrorCode.PROMOTION_BELOW_COST,
                        "Variant " + r.sku() + " would sell below its cost price. Lower the percentage or check the impact preview");
            }
        }
    }

    private void refuseEnded(Promotion p) {
        if (!p.getEndsAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.PROMOTION_ALREADY_ENDED);
        }
    }

    private Promotion load(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));
    }

    /** Prices change the moment staff save: clear the cached copy now and again once the commit is visible. */
    private void changed() {
        pricing.invalidate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pricing.invalidate();
                }
            });
        }
    }

    private void audit(Promotion p, String what, String from, String to, Long actorId) {
        auditService.record(AuditAction.PROMOTION_CHANGED, ENTITY, p.getId(), p.getNameAr(), from, to, what, actorId);
        log.info("Promotion {} {} by {}", p.getId(), what, actorId);
    }

    private static String describe(Promotion p) {
        return p.getPercent() + "% " + p.getScope() + (p.getTargetId() == null ? "" : "#" + p.getTargetId())
                + " " + p.getStartsAt() + " to " + p.getEndsAt();
    }

    private PromotionResponse toResponse(Promotion p) {
        return new PromotionResponse(p.getId(), p.getNameAr(), p.getNameEn(), p.getScope().name(), p.getTargetId(),
                targetName(p), p.getPercent(), p.getStartsAt(), p.getEndsAt(), p.getStatus().name(),
                p.stateAt(now()).name(), p.getHeadlineAr(), p.getHeadlineEn(), urlOf(p.getBannerKey()),
                urlOf(p.getBannerMobileKey()), repository.usedByOrders(p.getId()) == 1, p.getCreatedAt(), p.getUpdatedAt());
    }

    private String targetName(Promotion p) {
        if (p.getScope() == PromotionScope.CATEGORY) {
            return categoryRepository.findById(p.getTargetId()).map(c -> c.nameFor("ar")).orElse(null);
        }
        if (p.getScope() == PromotionScope.PRODUCT) {
            return productRepository.findById(p.getTargetId()).map(pr -> pr.nameFor("ar")).orElse(null);
        }
        return null;
    }

    private String productSlug(Long productId) {
        return jdbc.query("SELECT slug FROM product WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, productId);
    }

    static String landingPath(Promotion p, String productSlug) {
        return switch (p.getScope()) {
            case ALL -> "/products?onSale=true";
            case CATEGORY -> "/products?categoryId=" + p.getTargetId();
            case PRODUCT -> productSlug == null ? "/products" : "/products/" + productSlug;
        };
    }

    private String urlOf(String key) {
        return key == null ? null : storageService.urlFor(key);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.strip() : null;
    }
}
