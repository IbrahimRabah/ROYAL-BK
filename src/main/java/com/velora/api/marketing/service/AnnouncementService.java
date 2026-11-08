package com.velora.api.marketing.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.marketing.domain.Announcement;
import com.velora.api.marketing.domain.Promotion;
import com.velora.api.marketing.dto.AdminAnnouncementResponse;
import com.velora.api.marketing.dto.AnnouncementSaveRequest;
import com.velora.api.marketing.dto.PublicAnnouncementResponse;
import com.velora.api.marketing.repository.AnnouncementRepository;
import com.velora.api.marketing.repository.PromotionRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The message strip above the storefront header.
 *
 * <p>Linked to a promotion, a message shows only while that promotion is RUNNING, can count down to its end, and
 * writes its own text ("Hurry up! 20% off") when none is typed. A message that is not linked shows between its
 * own start and end, when it is switched on. The link is checked: a path on this site or an https address, so an
 * announcement can never carry a {@code javascript:} URL.
 */
@Service
public class AnnouncementService {

    private static final String ENTITY = "ANNOUNCEMENT";
    private static final Pattern SAFE_LINK = Pattern.compile("^(/(?!/)\\S*|https://\\S+)$");

    private final AnnouncementRepository repository;
    private final PromotionRepository promotionRepository;
    private final AuditService auditService;

    public AnnouncementService(AnnouncementRepository repository, PromotionRepository promotionRepository,
                               AuditService auditService) {
        this.repository = repository;
        this.promotionRepository = promotionRepository;
        this.auditService = auditService;
    }

    // --------------------------------------------------------------------- public

    @Transactional(readOnly = true)
    public List<PublicAnnouncementResponse> active(String locale) {
        OffsetDateTime now = now();
        boolean en = "en".equalsIgnoreCase(locale);
        List<PublicAnnouncementResponse> out = new ArrayList<>();
        for (Announcement a : repository.findByActiveTrueOrderByPositionAscIdAsc()) {
            Promotion promotion = a.getPromotionId() == null ? null : promotionRepository.findById(a.getPromotionId()).orElse(null);
            if (!showingNow(a, promotion, now)) {
                continue;
            }
            String text = en && notBlank(a.getTextEn()) ? a.getTextEn() : a.getTextAr();
            if (!notBlank(text) && promotion != null) {
                text = autoText(promotion, en);
            }
            String link = notBlank(a.getLinkUrl()) ? a.getLinkUrl()
                    : promotion == null ? null : PromotionService.landingPath(promotion, null);
            String label = en && notBlank(a.getLinkLabelEn()) ? a.getLinkLabelEn() : a.getLinkLabelAr();
            OffsetDateTime countdown = !a.isShowCountdown() ? null : promotion != null ? promotion.getEndsAt() : a.getEndsAt();
            out.add(new PublicAnnouncementResponse(a.getId(), text, link, notBlank(label) ? label : null,
                    a.getAnimation().name(), a.getTheme().name(), a.isDismissible(), countdown,
                    promotion == null ? null : promotion.getPercent(), a.getUpdatedAt().toInstant().toEpochMilli()));
        }
        return out;
    }

    // ---------------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public List<AdminAnnouncementResponse> list() {
        OffsetDateTime now = now();
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(a -> toAdmin(a, now)).toList();
    }

    @Transactional
    public AdminAnnouncementResponse create(AnnouncementSaveRequest request, Long actorId) {
        Announcement a = new Announcement();
        apply(a, request);
        a.setCreatedBy(actorId);
        a.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(Announcement::getPosition).max().orElse(-1) + 1);
        repository.save(a);
        audit(a, "CREATED", null, label(a), actorId);
        return toAdmin(a, now());
    }

    @Transactional
    public AdminAnnouncementResponse update(Long id, AnnouncementSaveRequest request, Long actorId) {
        Announcement a = load(id);
        String before = label(a);
        apply(a, request);
        repository.save(a);
        audit(a, "UPDATED", before, label(a), actorId);
        return toAdmin(a, now());
    }

    @Transactional
    public AdminAnnouncementResponse setActive(Long id, boolean active, Long actorId) {
        Announcement a = load(id);
        a.setActive(active);
        repository.save(a);
        audit(a, active ? "SWITCHED_ON" : "SWITCHED_OFF", String.valueOf(!active), String.valueOf(active), actorId);
        return toAdmin(a, now());
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        Announcement a = load(id);
        audit(a, "DELETED", label(a), null, actorId);
        repository.delete(a);
    }

    /** The ids in the order they should rotate: each takes its index as its position. */
    @Transactional
    public int reorder(List<Long> ids, Long actorId) {
        int position = 0;
        for (Long id : ids) {
            Announcement a = load(id);
            a.setPosition(position++);
            repository.save(a);
        }
        auditService.record(AuditAction.ANNOUNCEMENT_CHANGED, ENTITY, ids.get(0), "reorder", null, null,
                "REORDERED " + ids.size(), actorId);
        return ids.size();
    }

    // ------------------------------------------------------------------- internals

    private void apply(Announcement a, AnnouncementSaveRequest r) {
        Promotion promotion = null;
        if (r.promotionId() != null) {
            promotion = promotionRepository.findById(r.promotionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));
        }
        if (promotion == null && !notBlank(r.textAr())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The Arabic text is required unless the announcement is linked to a discount");
        }
        if (notBlank(r.linkUrl()) && !SAFE_LINK.matcher(r.linkUrl().strip()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The link must be a path on this site (starting with /) or an https address");
        }
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The announcement must end after it starts");
        }
        a.setTextAr(blankToNull(r.textAr()));
        a.setTextEn(blankToNull(r.textEn()));
        a.setLinkUrl(blankToNull(r.linkUrl()));
        a.setLinkLabelAr(blankToNull(r.linkLabelAr()));
        a.setLinkLabelEn(blankToNull(r.linkLabelEn()));
        a.setAnimation(r.animation());
        a.setTheme(r.theme());
        a.setStartsAt(r.startsAt());
        a.setEndsAt(r.endsAt());
        a.setActive(r.active() == null || r.active());
        a.setDismissible(r.dismissible() == null || r.dismissible());
        a.setShowCountdown(Boolean.TRUE.equals(r.showCountdown()));
        a.setPromotionId(promotion == null ? null : promotion.getId());
    }

    private static boolean showingNow(Announcement a, Promotion promotion, OffsetDateTime now) {
        if (!a.isActive()) {
            return false;
        }
        if (a.getStartsAt() != null && now.isBefore(a.getStartsAt())) {
            return false;
        }
        if (a.getEndsAt() != null && !now.isBefore(a.getEndsAt())) {
            return false;
        }
        return a.getPromotionId() == null || (promotion != null && promotion.isRunningAt(now));
    }

    /** «Hurry up! 20% off - Summer sale», in the language of the visitor. */
    static String autoText(Promotion p, boolean en) {
        String percent = p.getPercent().stripTrailingZeros().toPlainString();
        String name = en && notBlank(p.getNameEn()) ? p.getNameEn() : p.getNameAr();
        return en ? "Hurry up! " + percent + "% off - " + name : "سارع! خصم " + percent + "% - " + name;
    }

    private AdminAnnouncementResponse toAdmin(Announcement a, OffsetDateTime now) {
        Promotion promotion = a.getPromotionId() == null ? null : promotionRepository.findById(a.getPromotionId()).orElse(null);
        return new AdminAnnouncementResponse(a.getId(), a.getTextAr(), a.getTextEn(), a.getLinkUrl(), a.getLinkLabelAr(),
                a.getLinkLabelEn(), a.getAnimation().name(), a.getTheme().name(), a.getStartsAt(), a.getEndsAt(),
                a.isActive(), a.getPosition(), a.isDismissible(), a.isShowCountdown(), a.getPromotionId(),
                promotion == null ? null : promotion.getNameAr(),
                promotion == null ? null : promotion.stateAt(now).name(), showingNow(a, promotion, now),
                a.getUpdatedAt());
    }

    private Announcement load(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
    }

    private void audit(Announcement a, String what, String from, String to, Long actorId) {
        auditService.record(AuditAction.ANNOUNCEMENT_CHANGED, ENTITY, a.getId(), label(a), from, to, what, actorId);
    }

    private static String label(Announcement a) {
        String text = notBlank(a.getTextAr()) ? a.getTextAr() : "(from discount " + a.getPromotionId() + ")";
        return text.length() > 80 ? text.substring(0, 80) : text;
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

    /** Kept for tests: the percentage as the message prints it. */
    static String percentText(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString();
    }
}
