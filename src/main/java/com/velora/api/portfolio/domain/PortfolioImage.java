package com.velora.api.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One picture of a portfolio item. The row holds the storage KEY, never a URL. */
@Entity
@Table(name = "portfolio_image")
@Getter
@Setter
@NoArgsConstructor
public class PortfolioImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_item_id", nullable = false)
    private PortfolioItem item;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "alt_text_ar", length = 255)
    private String altTextAr;

    @Column(name = "alt_text_en", length = 255)
    private String altTextEn;

    @Column(name = "is_main", nullable = false)
    private boolean main;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    public String altFor(String locale) {
        return "en".equalsIgnoreCase(locale) && altTextEn != null && !altTextEn.isBlank()
                ? altTextEn : altTextAr;
    }
}
