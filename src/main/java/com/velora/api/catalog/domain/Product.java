package com.velora.api.catalog.domain;

import com.velora.api.common.audit.BaseAuditEntity;
import com.velora.api.common.util.MoneyUtils;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKey;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

/**
 * The marketing entity — one page, one description, one gallery.
 *
 * <p>A product is NOT sellable. {@link ProductVariant} is. Cart lines, order lines,
 * stock and price rules all reference the variant.
 *
 * <p>Never hard-deleted: {@code archivedAt} is set instead, because order lines
 * reference this row for reporting and reorder.
 */
@Entity
@Table(name = "product")
@Getter
@Setter
@NoArgsConstructor
public class Product extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "slug", nullable = false, length = 200)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type", nullable = false, length = 20)
    private FulfillmentType fulfillmentType = FulfillmentType.READY_MADE;

    /**
     * Required when {@link #fulfillmentType} is READY_MADE, null otherwise.
     *
     * <p>That rule is enforced in {@code ProductAdminService} ONLY — there is no CHECK
     * constraint behind it. Anything that writes the table directly (a SQL script,
     * a migration, a test inserting a {@code Product}) can store a READY_MADE row
     * with no size, and the database will accept it. Products that predate this
     * column are exactly that: READY_MADE with a null size, until staff set one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_size_class", length = 10)
    private ShippingSizeClass shippingSizeClass;

    /** The piece has to be assembled or installed when it is delivered. */
    @Column(name = "requires_assembly", nullable = false)
    private boolean requiresAssembly;

    /**
     * What assembly costs, per piece, tax-INCLUSIVE like every price here. Only charged while
     * {@link #requiresAssembly} is true, and zero until a product is priced. Kept on the product
     * rather than the variant on purpose: it is a property of the piece, not of its size or
     * colour. What an order paid is snapshotted on the order item, never read back from here.
     */
    @Column(name = "assembly_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal assemblyFee = BigDecimal.ZERO;

    @Column(name = "is_featured", nullable = false)
    private boolean featured;

    @Column(name = "is_new_arrival", nullable = false)
    private boolean newArrival;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @MapKey(name = "key.locale")
    private Map<String, ProductTranslation> translations = new LinkedHashMap<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private List<ProductImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductAttributeValue> specifications = new ArrayList<>();

    /*
     * The three fields below are computed by the database, not stored.
     *
     * Using @Formula rather than denormalized columns means they can never drift out
     * of sync with the variants — and, importantly, they can be used in Pageable
     * sorts and in Specification predicates, which a Java-side calculation cannot.
     */

    @Formula("(select min(v.price) from product_variant v "
            + "where v.product_id = id and v.status = 'ACTIVE' and v.archived_at is null)")
    private BigDecimal minPrice;

    @Formula("(select max(v.price) from product_variant v "
            + "where v.product_id = id and v.status = 'ACTIVE' and v.archived_at is null)")
    private BigDecimal maxPrice;

    /** on_hand minus reserved, summed across active variants. */
    @Formula("(select coalesce(sum(i.qty_on_hand - i.qty_reserved), 0) from inventory i "
            + "inner join product_variant v on v.id = i.variant_id "
            + "where v.product_id = id and v.status = 'ACTIVE' and v.archived_at is null)")
    private Integer availableQty;

    // ------------------------------------------------------------------ helpers

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && archivedAt == null && isInStock();
    }

    /**
     * The one rule for what assembling ONE of this product costs: the fee if the product needs
     * assembly, zero if it does not — whatever the stored fee says. The cart, the shipping quote
     * and checkout all go through this, so they cannot disagree about it.
     */
    public BigDecimal effectiveAssemblyFee() {
        return requiresAssembly ? MoneyUtils.round(assemblyFee) : MoneyUtils.ZERO;
    }

    public boolean isReadyMade() {
        return fulfillmentType == FulfillmentType.READY_MADE;
    }

    public boolean isInStock() {
        return availableQty != null && availableQty > 0;
    }

    public boolean hasDiscount() {
        return variants.stream().anyMatch(ProductVariant::hasDiscount);
    }

    public String nameFor(String locale) {
        ProductTranslation t = translationFor(locale);
        return t == null ? slug : t.getName();
    }

    public ProductTranslation translationFor(String locale) {
        ProductTranslation t = translations.get(locale);
        // Fallback to Arabic rather than rendering an empty field.
        return t != null ? t : translations.get("ar");
    }

    public ProductImage mainImage() {
        return images.stream()
                .filter(ProductImage::isMain)
                .findFirst()
                .orElseGet(() -> images.isEmpty() ? null : images.get(0));
    }

    /**
     * The second image by {@code displayOrder} — what a product card swaps to on
     * hover. Null when the product has zero or one image, not the main image again.
     */
    public ProductImage hoverImage() {
        return images.size() > 1 ? images.get(1) : null;
    }
}
