package com.velora.api.cart.domain;

import com.velora.api.catalog.domain.ProductVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line in a cart. Always references a VARIANT — the sellable unit — never a
 * product.
 *
 * <p>{@code priceAtAdd} is kept only to detect and report a change. It is NOT the
 * price the customer pays: that is read fresh from the variant at checkout.
 */
@Entity
@Table(name = "cart_item")
@Getter
@Setter
@NoArgsConstructor
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** The price the customer saw when they added it — for comparison only. */
    @Column(name = "price_at_add", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAtAdd;

    @Column(name = "added_at", nullable = false, updatable = false)
    private OffsetDateTime addedAt = OffsetDateTime.now(ZoneOffset.UTC);

    /** Whether the price now (discounts included) differs from the one the customer saw when adding. */
    public boolean priceChanged(BigDecimal currentPrice) {
        return currentPrice.compareTo(priceAtAdd) != 0;
    }
}
