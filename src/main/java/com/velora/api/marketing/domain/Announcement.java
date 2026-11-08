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
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One message of the strip above the storefront header. Linked to a promotion it shows only while that promotion
 * is running, can count down to its end, and writes its own text ("Hurry up! 20% off") when none is given.
 */
@Entity
@Table(name = "announcement")
@Getter
@Setter
@NoArgsConstructor
public class Announcement extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "text_ar", length = 300)
    private String textAr;

    @Column(name = "text_en", length = 300)
    private String textEn;

    /** A path on this site («/products?onSale=true») or an https link. */
    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "link_label_ar", length = 60)
    private String linkLabelAr;

    @Column(name = "link_label_en", length = 60)
    private String linkLabelEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "animation", nullable = false, length = 20)
    private AnnouncementAnimation animation = AnnouncementAnimation.MARQUEE;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    private AnnouncementTheme theme = AnnouncementTheme.GOLD;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "dismissible", nullable = false)
    private boolean dismissible = true;

    @Column(name = "show_countdown", nullable = false)
    private boolean showCountdown;

    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "created_by")
    private Long createdBy;
}
