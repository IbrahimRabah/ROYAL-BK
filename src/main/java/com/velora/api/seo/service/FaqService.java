package com.velora.api.seo.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.seo.domain.FaqItem;
import com.velora.api.seo.domain.FaqScope;
import com.velora.api.seo.domain.FaqStatus;
import com.velora.api.seo.domain.FaqTopic;
import com.velora.api.seo.dto.AdminFaqResponse;
import com.velora.api.seo.dto.FaqBulkStatusRequest;
import com.velora.api.seo.dto.FaqResponse;
import com.velora.api.seo.dto.FaqSaveRequest;
import com.velora.api.seo.repository.FaqItemRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FAQs: the published ones for the public pages, and the full set for the SEO team.
 *
 * <p>A question is plain text. The same text feeds the visible accordion and the FAQPage structured data, which
 * is why nothing but PUBLISHED items is ever returned publicly: Google penalises markup for questions a visitor
 * cannot see.
 */
@Service
public class FaqService {

    private static final String ENTITY = "FAQ_ITEM";

    private final FaqItemRepository repository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final AuditService auditService;

    public FaqService(FaqItemRepository repository, ProductRepository productRepository,
                      CategoryRepository categoryRepository, AuditService auditService) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
    }

    // -------------------------------------------------------------------- public

    @Transactional(readOnly = true)
    public List<FaqResponse> forProduct(String slug, String locale) {
        Long id = productRepository.findActiveIdBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        return localized(repository.findByScopeAndTargetIdAndStatusOrderByPositionAscIdAsc(
                FaqScope.PRODUCT, id, FaqStatus.PUBLISHED), locale);
    }

    @Transactional(readOnly = true)
    public List<FaqResponse> forCategory(Long categoryId, String locale) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return localized(repository.findByScopeAndTargetIdAndStatusOrderByPositionAscIdAsc(
                FaqScope.CATEGORY, categoryId, FaqStatus.PUBLISHED), locale);
    }

    @Transactional(readOnly = true)
    public List<FaqResponse> general(FaqTopic topic, String locale) {
        List<FaqItem> items = topic == null
                ? repository.findByScopeAndStatusOrderByPositionAscIdAsc(FaqScope.GENERAL, FaqStatus.PUBLISHED)
                : repository.findByScopeAndStatusAndTopicOrderByPositionAscIdAsc(
                        FaqScope.GENERAL, FaqStatus.PUBLISHED, topic);
        return localized(items, locale);
    }

    private static List<FaqResponse> localized(List<FaqItem> items, String locale) {
        boolean en = "en".equalsIgnoreCase(locale);
        return items.stream().map(i -> {
            boolean useEn = en && hasText(i.getQuestionEn()) && hasText(i.getAnswerEn());
            return new FaqResponse(i.getId(), useEn ? i.getQuestionEn() : i.getQuestionAr(),
                    useEn ? i.getAnswerEn() : i.getAnswerAr(), i.getTopic() == null ? null : i.getTopic().name());
        }).toList();
    }

    // --------------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public PageResponse<AdminFaqResponse> list(FaqScope scope, Long targetId, FaqStatus status, FaqTopic topic,
                                               String query, Pageable pageable) {
        Specification<FaqItem> spec = (root, cq, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (scope != null) {
                all.add(cb.equal(root.get("scope"), scope));
            }
            if (targetId != null) {
                all.add(cb.equal(root.get("targetId"), targetId));
            }
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (topic != null) {
                all.add(cb.equal(root.get("topic"), topic));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                all.add(cb.or(cb.like(cb.lower(root.<String>get("questionAr")), like),
                        cb.like(cb.lower(root.<String>get("questionEn")), like)));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
        Page<FaqItem> page = repository.findAll(spec, pageable);
        Map<Long, String> products = new HashMap<>();
        Map<Long, String> categories = new HashMap<>();
        namesFor(page.getContent(), products, categories);
        return PageResponse.from(page, i -> toAdmin(i, products, categories));
    }

    @Transactional
    public AdminFaqResponse create(FaqSaveRequest request, Long actorId) {
        FaqItem item = new FaqItem();
        apply(item, request);
        item.setCreatedBy(actorId);
        if (request.position() == null) {
            item.setPosition(nextPosition(item));
        }
        repository.save(item);
        auditService.record(AuditAction.FAQ_CHANGED, ENTITY, item.getId(), label(item), null, item.getStatus().name(),
                "CREATED", actorId);
        return one(item);
    }

    @Transactional
    public AdminFaqResponse update(Long id, FaqSaveRequest request, Long actorId) {
        FaqItem item = load(id);
        String was = item.getStatus().name();
        apply(item, request);
        repository.save(item);
        auditService.record(AuditAction.FAQ_CHANGED, ENTITY, item.getId(), label(item), was,
                item.getStatus().name(), "UPDATED", actorId);
        return one(item);
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        FaqItem item = load(id);
        String label = label(item);
        String was = item.getStatus().name();
        repository.delete(item);
        auditService.record(AuditAction.FAQ_CHANGED, ENTITY, id, label, was, "DELETED", null, actorId);
    }

    /** The ids in the order they should appear: each takes its index as its position. */
    @Transactional
    public int reorder(List<Long> ids, Long actorId) {
        int position = 0;
        for (Long id : ids) {
            FaqItem item = load(id);
            item.setPosition(position++);
            repository.save(item);
        }
        auditService.record(AuditAction.FAQ_CHANGED, ENTITY, ids.get(0), "reorder", null, null,
                "REORDERED " + ids.size(), actorId);
        return ids.size();
    }

    @Transactional
    public int bulkStatus(FaqBulkStatusRequest request, Long actorId) {
        int changed = 0;
        for (Long id : request.ids().stream().distinct().toList()) {
            FaqItem item = repository.findById(id).orElse(null);
            if (item == null || item.getStatus() == request.status()) {
                continue;
            }
            String was = item.getStatus().name();
            item.setStatus(request.status());
            repository.save(item);
            auditService.record(AuditAction.FAQ_CHANGED, ENTITY, id, label(item), was, request.status().name(),
                    "BULK", actorId);
            changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------ internal

    private FaqItem load(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.FAQ_NOT_FOUND));
    }

    private void apply(FaqItem item, FaqSaveRequest r) {
        if (r.scope() == FaqScope.GENERAL) {
            if (r.targetId() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A general FAQ has no target");
            }
            item.setTargetId(null);
            item.setTopic(r.topic() == null ? FaqTopic.GENERAL : r.topic());
        } else {
            if (r.targetId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetId is required for " + r.scope());
            }
            boolean exists = r.scope() == FaqScope.PRODUCT
                    ? productRepository.existsById(r.targetId())
                    : categoryRepository.existsById(r.targetId());
            if (!exists) {
                throw new BusinessException(r.scope() == FaqScope.PRODUCT
                        ? ErrorCode.PRODUCT_NOT_FOUND : ErrorCode.CATEGORY_NOT_FOUND);
            }
            item.setTargetId(r.targetId());
            item.setTopic(null);
        }
        item.setScope(r.scope());
        item.setQuestionAr(r.questionAr().strip());
        item.setAnswerAr(r.answerAr().strip());
        // English is all or nothing: a question with no answer would be useless on the page.
        boolean en = hasText(r.questionEn()) && hasText(r.answerEn());
        item.setQuestionEn(en ? r.questionEn().strip() : null);
        item.setAnswerEn(en ? r.answerEn().strip() : null);
        if (r.position() != null) {
            item.setPosition(r.position());
        }
        item.setStatus(r.status());
    }

    private int nextPosition(FaqItem item) {
        List<FaqItem> siblings = item.getScope() == FaqScope.GENERAL
                ? repository.findByScopeAndStatusOrderByPositionAscIdAsc(FaqScope.GENERAL, FaqStatus.PUBLISHED)
                : repository.findByScopeAndTargetIdAndStatusOrderByPositionAscIdAsc(item.getScope(),
                        item.getTargetId(), FaqStatus.PUBLISHED);
        return siblings.stream().mapToInt(FaqItem::getPosition).max().orElse(-1) + 1;
    }

    private AdminFaqResponse one(FaqItem item) {
        Map<Long, String> products = new HashMap<>();
        Map<Long, String> categories = new HashMap<>();
        namesFor(List.of(item), products, categories);
        return toAdmin(item, products, categories);
    }

    private void namesFor(List<FaqItem> items, Map<Long, String> products, Map<Long, String> categories) {
        List<Long> productIds = items.stream().filter(i -> i.getScope() == FaqScope.PRODUCT)
                .map(FaqItem::getTargetId).filter(Objects::nonNull).distinct().toList();
        List<Long> categoryIds = items.stream().filter(i -> i.getScope() == FaqScope.CATEGORY)
                .map(FaqItem::getTargetId).filter(Objects::nonNull).distinct().toList();
        for (Product p : productRepository.findAllById(productIds)) {
            products.put(p.getId(), p.nameFor("ar"));
        }
        for (Category c : categoryRepository.findAllById(categoryIds)) {
            categories.put(c.getId(), c.nameFor("ar"));
        }
    }

    private static AdminFaqResponse toAdmin(FaqItem i, Map<Long, String> products, Map<Long, String> categories) {
        String target = i.getScope() == FaqScope.PRODUCT ? products.get(i.getTargetId())
                : i.getScope() == FaqScope.CATEGORY ? categories.get(i.getTargetId()) : null;
        return new AdminFaqResponse(i.getId(), i.getScope().name(), i.getTargetId(), target,
                i.getTopic() == null ? null : i.getTopic().name(), i.getQuestionAr(), i.getAnswerAr(),
                i.getQuestionEn(), i.getAnswerEn(), i.getPosition(), i.getStatus().name(), i.getCreatedAt(),
                i.getUpdatedAt());
    }

    private static String label(FaqItem i) {
        String q = i.getQuestionAr();
        return q.length() > 80 ? q.substring(0, 80) : q;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
