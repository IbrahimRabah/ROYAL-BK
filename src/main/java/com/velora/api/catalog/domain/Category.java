package com.velora.api.catalog.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKey;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

/**
 * Self-referencing hierarchy. Two levels are used at launch (Watches -> Men Watches),
 * but the schema allows any depth.
 */
@Entity
@Table(name = "category")
@Getter
@Setter
@NoArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(name = "slug", nullable = false, length = 150)
    private String slug;

    /** Storage key (not a URL) for the {@code CARD} image — see {@code StorageService}. */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Storage key (not a URL) for the {@code BANNER} image — see {@code StorageService}. */
    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

    /** Keyed by locale ("ar", "en") so lookup is a map get, not a stream filter. */
    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true)
    @MapKey(name = "key.locale")
    private Map<String, CategoryTranslation> translations = new LinkedHashMap<>();

    /**
     * Live products in this category OR its direct children — the same
     * "self or direct child" reach used everywhere else a category filters products
     * (see {@code ProductSpecifications.inCategory},
     * {@code AttributeRepository.findFacetsForCategory}): browsing "Watches" counts
     * everything in "Men Watches" too.
     */
    @Formula("(select count(*) from product p "
            + "where (p.category_id = id or p.category_id in "
            + "(select c2.id from category c2 where c2.parent_id = id)) "
            + "and p.status = 'ACTIVE' and p.archived_at is null)")
    private Integer productCount;

    public String nameFor(String locale) {
        CategoryTranslation t = translations.get(locale);
        if (t == null) {
            t = translations.get("ar");
        }
        return t == null ? slug : t.getName();
    }

    public CategoryTranslation translationFor(String locale) {
        CategoryTranslation t = translations.get(locale);
        return t != null ? t : translations.get("ar");
    }
}
