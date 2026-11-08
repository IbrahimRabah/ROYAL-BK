package com.velora.api.marketing.domain;

import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A percentage discount on every product, one category (with its subcategories) or one product (all its
 * variants), between {@code startsAt} and {@code endsAt}. Only an ACTIVE promotion inside its window changes a
 * price; DRAFT and PAUSED ones never do. The regular price of a variant is never rewritten: the discounted price
 * is worked out when it is read (see {@code PromotionPricingService}), so ending a promotion needs no clean-up.
 */
@Entity
@Table(name = "promotion")
@Getter
@Setter
@NoArgsConstructor
public class Promotion extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_ar", nullable = false, length = 150)
    private String nameAr;

    @Column(name = "name_en", length = 150)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private PromotionScope scope;

    /** The category or product id; null for ALL. */
    @Column(name = "target_id")
    private Long targetId;

    /** 0.01 to 95. */
    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PromotionStatus status = PromotionStatus.DRAFT;

    @Column(name = "headline_ar", length = 200)
    private String headlineAr;

    @Column(name = "headline_en", length = 200)
    private String headlineEn;

    /** Storage key of the banner image; null when there is none. */
    @Column(name = "banner_key", length = 500)
    private String bannerKey;

    @Column(name = "banner_mobile_key", length = 500)
    private String bannerMobileKey;

    @Column(name = "created_by")
    private Long createdBy;

    /** Where this promotion is in time and in its life cycle, at {@code now}. */
    public PromotionState stateAt(OffsetDateTime now) {
        if (status == PromotionStatus.DRAFT) {
            return PromotionState.DRAFT;
        }
        if (status == PromotionStatus.PAUSED) {
            return PromotionState.PAUSED;
        }
        if (now.isBefore(startsAt)) {
            return PromotionState.SCHEDULED;
        }
        return now.isBefore(endsAt) ? PromotionState.RUNNING : PromotionState.ENDED;
    }

    public boolean isRunningAt(OffsetDateTime now) {
        return stateAt(now) == PromotionState.RUNNING;
    }
}
