package com.velora.api.seo.domain;

import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A question and its answer. A PRODUCT or CATEGORY item belongs to that one page ({@code targetId}); a GENERAL
 * item belongs to the shop and is grouped by {@link FaqTopic}. Only PUBLISHED items ever reach the public API.
 * Answers are plain text: no HTML is stored, so none can be injected into a page or into its structured data.
 */
@Entity
@Table(name = "faq_item")
@Getter
@Setter
@NoArgsConstructor
public class FaqItem extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private FaqScope scope;

    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic", length = 30)
    private FaqTopic topic;

    @Column(name = "question_ar", nullable = false, length = 500)
    private String questionAr;

    @Column(name = "answer_ar", nullable = false, length = 2000)
    private String answerAr;

    @Column(name = "question_en", length = 500)
    private String questionEn;

    @Column(name = "answer_en", length = 2000)
    private String answerEn;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FaqStatus status = FaqStatus.DRAFT;

    @Column(name = "created_by")
    private Long createdBy;
}
