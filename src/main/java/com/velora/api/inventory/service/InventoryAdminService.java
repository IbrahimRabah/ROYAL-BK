package com.velora.api.inventory.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.domain.MovementType;
import com.velora.api.inventory.domain.StockMovement;
import com.velora.api.inventory.dto.InventoryAdminResponse;
import com.velora.api.inventory.dto.StockAdjustRequest;
import com.velora.api.inventory.dto.StockMovementResponse;
import com.velora.api.inventory.dto.StockReceiveRequest;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.inventory.repository.StockMovementRepository;
import com.velora.api.inventory.spec.InventorySpecifications;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock changes made by staff.
 *
 * <p>ONE rule governs this whole class: <b>every quantity change writes a
 * {@link StockMovement}</b>. Silently updating {@code qty_on_hand} makes the number
 * unexplainable, and an unexplainable stock number is indistinguishable from theft.
 *
 * <p>Note this service only touches {@code qtyOnHand}. {@code qtyReserved} belongs
 * to the checkout flow and is changed there with a guarded atomic UPDATE.
 */
@Service
public class InventoryAdminService {

    private static final Logger log = LoggerFactory.getLogger(InventoryAdminService.class);

    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository movementRepository;
    private final ProductVariantRepository variantRepository;
    private final AuditService auditService;

    public InventoryAdminService(InventoryRepository inventoryRepository,
                                 StockMovementRepository movementRepository,
                                 ProductVariantRepository variantRepository,
                                 AuditService auditService) {
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.variantRepository = variantRepository;
        this.auditService = auditService;
    }

    /**
     * Goods in from a supplier.
     *
     * <p>{@code note} is left as the staff member typed it — including absent. The
     * movement type ({@code PURCHASE_RECEIVED}) already says this was a receipt; a
     * hardcoded English "Goods received" filler when nobody wrote anything just
     * makes the ledger read half Arabic, half English for a reason that carries no
     * information the type doesn't already give.
     */
    @Transactional
    public InventoryAdminResponse receive(Long variantId, StockReceiveRequest request,
                                          Long actorId) {
        return applyChange(variantId, request.quantity(), MovementType.PURCHASE_RECEIVED,
                request.note(), "PURCHASE", request.reference(), actorId);
    }

    /**
     * A manual correction — stocktake, breakage, a found box.
     *
     * <p>Rejected if it would push stock below what is already reserved: those units
     * are promised to in-flight orders, and letting the number go negative would
     * oversell them.
     */
    @Transactional
    public InventoryAdminResponse adjust(Long variantId, StockAdjustRequest request,
                                         Long actorId) {
        MovementType type = parseType(request.movementType());
        return applyChange(variantId, request.quantityDelta(), type,
                request.reason(), "ADJUSTMENT", null, actorId);
    }

    @Transactional(readOnly = true)
    public InventoryAdminResponse get(Long variantId) {
        return toResponse(loadInventory(variantId));
    }

    @Transactional(readOnly = true)
    public List<InventoryAdminResponse> lowStock() {
        return inventoryRepository.findLowStock().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The full inventory list — paginated, searchable, filterable. What the low
     * stock report is for a triage view, this is for the everyday stock screen:
     * without it the front end would have to load every product and then every
     * variant per product to build one table, with search and sort done in the
     * browser instead of the database.
     */
    @Transactional(readOnly = true)
    public PageResponse<InventoryAdminResponse> list(String q, Boolean lowStockOnly,
                                                      Boolean outOfStockOnly, Long categoryId,
                                                      String sort, Pageable pageable) {
        Specification<Inventory> spec = Specification
                .where(InventorySpecifications.matchesSkuOrProductName(q))
                .and(InventorySpecifications.isLowStock(lowStockOnly))
                .and(InventorySpecifications.isOutOfStock(outOfStockOnly))
                .and(InventorySpecifications.inCategory(categoryId));

        Page<Inventory> page = inventoryRepository.findAll(spec, applySort(pageable, sort));
        return PageResponse.from(page, this::toResponse);
    }

    /**
     * {@code availableQty} is the one column staff actually triage by, so it is the
     * only sort offered today. Lowest first by default — that is the whole point of
     * an inventory screen: the items closest to running out belong at the top.
     */
    private Pageable applySort(Pageable pageable, String sort) {
        Sort.Direction direction = "available_desc".equals(sort)
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(direction, "availableQty"));
    }

    @Transactional(readOnly = true)
    public PageResponse<StockMovementResponse> movements(Long variantId, Pageable pageable) {
        var page = variantId == null
                ? movementRepository.findAllByOrderByCreatedAtDesc(pageable)
                : movementRepository.findByVariantIdOrderByCreatedAtDesc(variantId, pageable);
        return PageResponse.from(page, this::toMovementResponse);
    }

    // ------------------------------------------------------------------ internal

    private InventoryAdminResponse applyChange(Long variantId, int delta, MovementType type,
                                               String reason, String refType, String refId,
                                               Long actorId) {
        if (delta == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The quantity change cannot be zero");
        }

        Inventory inventory = loadInventory(variantId);
        int newOnHand = inventory.getQtyOnHand() + delta;

        if (newOnHand < 0) {
            throw new BusinessException(ErrorCode.NEGATIVE_STOCK,
                    "On hand is %d, requested change is %d"
                            .formatted(inventory.getQtyOnHand(), delta));
        }
        if (newOnHand < inventory.getQtyReserved()) {
            throw new BusinessException(ErrorCode.STOCK_BELOW_RESERVED,
                    "%d unit(s) are reserved for orders in progress"
                            .formatted(inventory.getQtyReserved()));
        }

        inventory.setQtyOnHand(newOnHand);
        inventory.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        inventoryRepository.save(inventory);

        StockMovement movement = new StockMovement();
        movement.setVariant(inventory.getVariant());
        movement.setMovementType(type);
        movement.setQuantityDelta(delta);
        movement.setQtyAfter(newOnHand);
        movement.setReferenceType(refType);
        movement.setReferenceId(refId);
        movement.setReason(reason);
        movement.setActorId(actorId);
        // Copied at write time, same as AuditLog.actorName — the account may be
        // renamed or removed later, and the ledger must still say who did this.
        movement.setActorName(auditService.resolveActorName(actorId));
        movementRepository.save(movement);

        log.info("Stock {} for variant id={} by {} -> {} ({})",
                type, variantId, delta, newOnHand, reason);

        /*
         * The movement ledger already records WHAT changed. The audit log records
         * WHO — and those are different questions asked by different people. An
         * accountant reconciling stock reads the ledger; an owner asking why six
         * units vanished reads this.
         */
        auditService.record(
                type == MovementType.PURCHASE_RECEIVED
                        ? AuditAction.STOCK_RECEIVED : AuditAction.STOCK_ADJUSTED,
                "INVENTORY",
                variantId,
                inventory.getVariant().getSku(),
                inventory.getQtyOnHand() - delta,
                newOnHand,
                reason,
                actorId);

        return toResponse(inventory);
    }

    private MovementType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return MovementType.MANUAL_ADJUSTMENT;
        }
        try {
            MovementType type = MovementType.valueOf(raw);
            // Sales and returns are written by the order flow, never by hand.
            if (type == MovementType.SALE || type == MovementType.RETURN_SELLABLE
                    || type == MovementType.RETURN_DAMAGED
                    || type == MovementType.CANCELLATION_RESTOCK) {
                throw new BusinessException(ErrorCode.MOVEMENT_TYPE_NOT_MANUAL,
                        "%s is written by the order flow and cannot be set manually"
                                .formatted(type));
            }
            return type;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Unknown movement type: " + raw);
        }
    }

    private Inventory loadInventory(Long variantId) {
        return inventoryRepository.findByVariantId(variantId)
                .orElseThrow(() -> {
                    // Almost always means the variant was created without an
                    // inventory row — see VariantAdminService.createInventory.
                    variantRepository.findById(variantId).orElseThrow(
                            () -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));
                    return new BusinessException(ErrorCode.INVENTORY_RECORD_MISSING);
                });
    }

    private InventoryAdminResponse toResponse(Inventory inventory) {
        ProductVariant variant = inventory.getVariant();

        String summary = variant.getAttributeValues().stream()
                .map(vav -> vav.getAttributeValue().nameFor("ar"))
                .reduce((a, b) -> a + " / " + b)
                .orElse(null);

        return new InventoryAdminResponse(
                variant.getId(),
                variant.getSku(),
                variant.getProduct().nameFor("ar"),
                summary,
                inventory.getQtyOnHand(),
                inventory.getQtyReserved(),
                inventory.getAvailable(),
                inventory.getMinStockLevel(),
                inventory.isLowStock(),
                inventory.getAvailable() <= 0,
                inventory.getUpdatedAt());
    }

    private StockMovementResponse toMovementResponse(StockMovement movement) {
        return new StockMovementResponse(
                movement.getId(),
                movement.getVariant().getId(),
                movement.getVariant().getSku(),
                movement.getMovementType().name(),
                movement.getQuantityDelta(),
                movement.getQtyAfter(),
                movement.getReferenceType(),
                movement.getReferenceId(),
                movement.getReason(),
                movement.getActorId(),
                movement.getActorName(),
                movement.getCreatedAt());
    }
}
