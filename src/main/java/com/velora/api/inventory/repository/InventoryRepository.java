package com.velora.api.inventory.repository;

import com.velora.api.inventory.domain.Inventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository
        extends JpaRepository<Inventory, Long>, JpaSpecificationExecutor<Inventory> {

    Optional<Inventory> findByVariantId(Long variantId);

    List<Inventory> findByVariantIdIn(List<Long> variantIds);

    /**
     * THE concurrency guard.
     *
     * <p>The availability check lives INSIDE the UPDATE, so the database decides,
     * not the application. Two requests racing for the last unit both reach this
     * statement; the row lock serialises them, and the second one's WHERE clause no
     * longer matches. It affects zero rows and the caller must fail the checkout.
     *
     * <p>Never replace this with read-then-write. Under load two customers buy the
     * last watch within the same millisecond, both succeed, and one of them gets an
     * apology instead of a product.
     *
     * <p>{@code flushAutomatically} pushes pending JPA changes before the native
     * statement runs; {@code clearAutomatically} discards the now-stale persistence
     * context so later reads see the new numbers.
     *
     * @return rows affected: 1 on success, 0 when the stock is gone
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE inventory
            SET qty_reserved = qty_reserved + :qty, version = version + 1
            WHERE variant_id = :variantId
              AND (qty_on_hand - qty_reserved) >= :qty
            """, nativeQuery = true)
    int tryReserve(@Param("variantId") Long variantId, @Param("qty") int qty);

    /** Returns held units to available. Never drops below zero, even if called twice. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE inventory
            SET qty_reserved = CASE WHEN qty_reserved >= :qty THEN qty_reserved - :qty ELSE 0 END,
                version = version + 1
            WHERE variant_id = :variantId
            """, nativeQuery = true)
    int releaseReservation(@Param("variantId") Long variantId, @Param("qty") int qty);

    /**
     * Shipment: the hold becomes a real reduction. On hand and reserved both drop,
     * so available is unchanged — the units were never available to anyone else.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE inventory
            SET qty_on_hand = qty_on_hand - :qty,
                qty_reserved = CASE WHEN qty_reserved >= :qty THEN qty_reserved - :qty ELSE 0 END,
                version = version + 1
            WHERE variant_id = :variantId AND qty_on_hand >= :qty
            """, nativeQuery = true)
    int commitReservation(@Param("variantId") Long variantId, @Param("qty") int qty);

    /** Low-stock report for the admin dashboard. */
    @Query("""
            select i from Inventory i
            where (i.qtyOnHand - i.qtyReserved) <= i.minStockLevel
            order by (i.qtyOnHand - i.qtyReserved) asc
            """)
    List<Inventory> findLowStock();
}
