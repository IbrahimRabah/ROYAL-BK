package com.velora.api.portfolio.domain;

import com.velora.api.catalog.domain.Category;
import com.velora.api.common.audit.BaseAuditEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

/**
 * A finished piece of custom work, shown on the storefront.
 *
 * <p>Deliberately not a product: it has no price, no variants and no stock, and putting it in
 * the product table would distort catalogue reports, the dashboard and stock turnover.
 *
 * <p>Archived, never deleted, like the rest of the project. An archived item is hidden
 * everywhere public, cannot be published, and can be restored.
 */
@Entity
@Table(name = "portfolio_item")
@Getter
@Setter
@NoArgsConstructor
public class PortfolioItem extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The public URL. Unique across archived items too, so a shared link never changes meaning. */
    @Column(name = "slug", nullable = false, length = 150)
    private String slug;

    @Column(name = "title_ar", nullable = false, length = 255)
    private String titleAr;

    @Column(name = "title_en", length = 255)
    private String titleEn;

    @Column(name = "description_ar", columnDefinition = "NVARCHAR(MAX)")
    private String descriptionAr;

    @Column(name = "description_en", columnDefinition = "NVARCHAR(MAX)")
    private String descriptionEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "completed_at")
    private LocalDate completedAt;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    @BatchSize(size = 50)
    private List<PortfolioImage> images = new ArrayList<>();

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String titleFor(String locale) {
        return "en".equalsIgnoreCase(locale) && titleEn != null && !titleEn.isBlank()
                ? titleEn : titleAr;
    }

    public String descriptionFor(String locale) {
        return "en".equalsIgnoreCase(locale) && descriptionEn != null && !descriptionEn.isBlank()
                ? descriptionEn : descriptionAr;
    }

    public PortfolioImage mainImage() {
        return images.stream().filter(PortfolioImage::isMain).findFirst()
                .orElseGet(() -> images.isEmpty() ? null : images.get(0));
    }
}
