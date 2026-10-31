package com.velora.api.catalog.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An image belongs to a product, and optionally to one specific variant.
 * A null {@code variant} means the image is shared across all variants — that is
 * how the gallery swaps when the customer picks a different colour.
 */
@Entity
@Table(name = "product_image")
@Getter
@Setter
@NoArgsConstructor
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "thumb_url", length = 500)
    private String thumbUrl;

    @Column(name = "alt_text_ar", length = 255)
    private String altTextAr;

    @Column(name = "alt_text_en", length = 255)
    private String altTextEn;

    @Column(name = "is_main", nullable = false)
    private boolean main;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    /** SHA-256 of the stored bytes; null for images uploaded one by one. Unique per product when set. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** The bulk image import run that added this image; null for images added any other way. */
    @Column(name = "import_run_id", length = 36)
    private String importRunId;

    /** Alt text is an accessibility AND an SEO requirement, so it is translated. */
    public String altFor(String locale) {
        return "en".equalsIgnoreCase(locale) && altTextEn != null ? altTextEn : altTextAr;
    }
}
