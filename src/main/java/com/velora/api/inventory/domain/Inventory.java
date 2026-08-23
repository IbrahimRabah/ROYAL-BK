package com.velora.api.inventory.domain;

import com.velora.api.catalog.domain.ProductVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

/**
 * Stock position for one variant.
 *
 * <p>Three numbers, not one:
 * <ul>
 *   <li>{@code qtyOnHand} — units physically in possession</li>
 *   <li>{@code qtyReserved} — units committed to in-flight checkouts and unshipped orders</li>
 *   <li>available = on hand − reserved — <b>derived, never stored</b></li>
 * </ul>
 *
 * <p>Reservation MUST use a guarded atomic UPDATE, never read-then-write:
 * <pre>
 * UPDATE inventory SET qty_reserved = qty_reserved + :qty
 * WHERE variant_id = :id AND (qty_on_hand - qty_reserved) &gt;= :qty
 * </pre>
 * If the affected row count is 0, another checkout took the stock — fail cleanly.
 * The {@code @Version} column is the second line of defence.
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    /** Single warehouse in V1. The column exists so multi-warehouse is a data change. */
    @Column(name = "location_code", nullable = false, length = 20)
    private String locationCode = "MAIN";

    @Column(name = "qty_on_hand", nullable = false)
    private int qtyOnHand;

    @Column(name = "qty_reserved", nullable = false)
    private int qtyReserved;

    @Column(name = "min_stock_level", nullable = false)
    private int minStockLevel = 3;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

    /**
     * Same value as {@link #getAvailable()}, computed in SQL rather than Java so it
     * can be used directly in a {@code Pageable} sort or a {@code Specification}
     * filter — same reasoning as {@code Product.minPrice}/{@code maxPrice}.
     */
    @Formula("(qty_on_hand - qty_reserved)")
    private Integer availableQty;

    /** The only number a customer should ever see. */
    public int getAvailable() {
        return qtyOnHand - qtyReserved;
    }

    public boolean isInStock() {
        return getAvailable() > 0;
    }

    public boolean isLowStock() {
        return getAvailable() <= minStockLevel;
    }
}
