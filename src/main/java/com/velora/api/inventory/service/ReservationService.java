package com.velora.api.inventory.service;

import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.inventory.domain.MovementType;
import com.velora.api.inventory.domain.ReservationStatus;
import com.velora.api.inventory.domain.StockReservation;
import com.velora.api.inventory.domain.StockMovement;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.inventory.repository.StockMovementRepository;
import com.velora.api.inventory.repository.StockReservationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds stock while a customer completes checkout.
 *
 * <p>This is the class that prevents overselling. Everything else in the cart is a
 * convenience; this is correctness.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;
    private final StockMovementRepository movementRepository;
    private final AuditService auditService;

    @Value("${velora.business.reservation-ttl-minutes:20}")
    private int reservationTtlMinutes;

    public ReservationService(InventoryRepository inventoryRepository,
                              StockReservationRepository reservationRepository,
                              StockMovementRepository movementRepository,
                              AuditService auditService) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
    }

    /**
     * Reserves every line of a cart, all or nothing.
     *
     * <p>If any line fails, the exception rolls the transaction back and the
     * successful holds from the same call disappear with it. That is deliberate: a
     * customer who ordered three items and can only get two has not bought anything
     * yet, and a partial hold would quietly freeze stock nobody asked for.
     *
     * @param quantitiesByVariant variant id to quantity, in a stable order
     * @throws BusinessException {@code STOCK_UNAVAILABLE} naming the failing variant
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<StockReservation> reserveAll(Map<ProductVariant, Integer> quantitiesByVariant,
                                             Long cartId) {
        if (quantitiesByVariant.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC)
                .plusMinutes(reservationTtlMinutes);
        List<StockReservation> created = new ArrayList<>();

        // Ordering the variants consistently keeps two concurrent multi-line
        // checkouts from taking row locks in opposite orders and deadlocking.
        Map<ProductVariant, Integer> ordered = new LinkedHashMap<>(quantitiesByVariant);

        for (Map.Entry<ProductVariant, Integer> entry : ordered.entrySet()) {
            ProductVariant variant = entry.getKey();
            int quantity = entry.getValue();

            int affected = inventoryRepository.tryReserve(variant.getId(), quantity);

            if (affected == 0) {
                // Someone else took the stock between the customer viewing it and
                // submitting. Roll back everything reserved in this call.
                int available = currentAvailable(variant.getId());
                log.info("Reservation refused for variant {} ({}): wanted {}, available {}",
                        variant.getId(), variant.getSku(), quantity, available);

                throw new BusinessException(ErrorCode.STOCK_UNAVAILABLE,
                        available == 0
                                ? "'%s' is now out of stock".formatted(variant.getSku())
                                : "Only %d left of '%s'".formatted(available, variant.getSku()));
            }

            StockReservation reservation = new StockReservation();
            reservation.setVariant(variant);
            reservation.setCartId(cartId);
            reservation.setQuantity(quantity);
            reservation.setStatus(ReservationStatus.HELD);
            reservation.setExpiresAt(expiry);
            created.add(reservationRepository.save(reservation));
        }

        log.info("Reserved {} line(s) for cart {} until {}", created.size(), cartId, expiry);
        return created;
    }

    /** Single-line hold. Used by tests and by "buy now" flows. */
    @Transactional
    public boolean tryReserveOne(ProductVariant variant, int quantity, Long cartId) {
        if (inventoryRepository.tryReserve(variant.getId(), quantity) == 0) {
            return false;
        }
        StockReservation reservation = new StockReservation();
        reservation.setVariant(variant);
        reservation.setCartId(cartId);
        reservation.setQuantity(quantity);
        reservation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                .plusMinutes(reservationTtlMinutes));
        reservationRepository.save(reservation);
        return true;
    }

    /** Attaches held reservations to the order that was just created. */
    @Transactional
    public void attachToOrder(Long cartId, Long orderId) {
        List<StockReservation> holds =
                reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.HELD);
        for (StockReservation hold : holds) {
            hold.setOrderId(orderId);
        }
        reservationRepository.saveAll(holds);
        log.info("Attached {} reservation(s) from cart {} to order {}",
                holds.size(), cartId, orderId);
    }

    /** Checkout failed or was abandoned — give the units back immediately. */
    @Transactional
    public void releaseForCart(Long cartId) {
        release(reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.HELD),
                ReservationStatus.RELEASED);
    }

    /** Order cancelled before shipment. */
    @Transactional
    public void releaseForOrder(Long orderId) {
        release(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.HELD),
                ReservationStatus.RELEASED);
    }

    /**
     * Shipment. On hand and reserved both drop, so available does not move — the
     * units were never available to anyone else while held.
     */
    @Transactional
    public void commitForOrder(Long orderId, Long actorId) {
        List<StockReservation> holds =
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.HELD);

        for (StockReservation hold : holds) {
            int affected = inventoryRepository.commitReservation(
                    hold.getVariant().getId(), hold.getQuantity());

            if (affected == 0) {
                // On hand is lower than the hold: stock was written off manually
                // while the order was in flight. Loud, because it needs a human.
                log.error("Cannot commit reservation {} for order {}: on hand is below "
                        + "the reserved quantity for variant {}",
                        hold.getId(), orderId, hold.getVariant().getId());
                throw new BusinessException(ErrorCode.STOCK_UNAVAILABLE,
                        "Stock records for '%s' are inconsistent. Check inventory."
                                .formatted(hold.getVariant().getSku()));
            }
            hold.setStatus(ReservationStatus.COMMITTED);

            // Every change to qty_on_hand writes its ledger row in the same transaction. The
            // native UPDATE cleared the persistence context, so this read sees the new number.
            int onHandAfter = inventoryRepository.findByVariantId(hold.getVariant().getId())
                    .map(inv -> inv.getQtyOnHand())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_RECORD_MISSING));

            StockMovement movement = new StockMovement();
            movement.setVariant(hold.getVariant());
            movement.setMovementType(MovementType.SALE);
            movement.setQuantityDelta(-hold.getQuantity());
            movement.setQtyAfter(onHandAfter);
            movement.setReferenceType("ORDER");
            movement.setReferenceId(String.valueOf(orderId));
            movement.setActorId(actorId);
            movement.setActorName(auditService.resolveActorName(actorId));
            movementRepository.save(movement);
        }
        reservationRepository.saveAll(holds);
        log.info("Committed {} reservation(s) for order {}", holds.size(), orderId);
    }

    /**
     * Releases holds that timed out. Called on a schedule.
     *
     * @return how many were released
     */
    @Transactional
    public int releaseExpired(int batchSize) {
        List<StockReservation> expired = reservationRepository.findExpiredHolds(
                OffsetDateTime.now(ZoneOffset.UTC),
                org.springframework.data.domain.PageRequest.of(0, batchSize));

        if (expired.isEmpty()) {
            return 0;
        }
        release(expired, ReservationStatus.EXPIRED);
        return expired.size();
    }

    @Transactional(readOnly = true)
    public long countExpiredHolds() {
        return reservationRepository.countExpiredHolds(OffsetDateTime.now(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ internal

    private void release(List<StockReservation> holds, ReservationStatus newStatus) {
        for (StockReservation hold : holds) {
            inventoryRepository.releaseReservation(
                    hold.getVariant().getId(), hold.getQuantity());
            hold.setStatus(newStatus);
        }
        if (!holds.isEmpty()) {
            reservationRepository.saveAll(holds);
            log.info("{} {} reservation(s)", newStatus, holds.size());
        }
    }

    private int currentAvailable(Long variantId) {
        return inventoryRepository.findByVariantId(variantId)
                .map(inv -> inv.getAvailable())
                .orElse(0);
    }
}
