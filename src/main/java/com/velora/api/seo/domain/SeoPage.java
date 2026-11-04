package com.velora.api.seo.domain;

import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The title and description of a static page (home, about …), and whether to keep it out of search. */
@Entity
@Table(name = "seo_page")
@Getter
@Setter
@NoArgsConstructor
public class SeoPage extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_key", nullable = false, length = 40)
    private String pageKey;

    @Column(name = "title_ar", length = 255)
    private String titleAr;

    @Column(name = "description_ar", length = 500)
    private String descriptionAr;

    @Column(name = "title_en", length = 255)
    private String titleEn;

    @Column(name = "description_en", length = 500)
    private String descriptionEn;

    @Column(name = "noindex", nullable = false)
    private boolean noindex;

    @Column(name = "updated_by")
    private Long updatedBy;
}
