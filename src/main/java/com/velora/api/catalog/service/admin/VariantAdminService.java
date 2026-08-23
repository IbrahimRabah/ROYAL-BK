package com.velora.api.catalog.service.admin;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantAttributeValue;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.admin.VariantAdminResponse;
import com.velora.api.catalog.dto.admin.VariantMatrixPreviewResponse;
import com.velora.api.catalog.dto.admin.VariantMatrixRequest;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.domain.MovementType;
import com.velora.api.inventory.domain.StockMovement;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.inventory.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Variant management: the matrix generator for bulk creation, and individual
 * add/edit for when a new colour arrives later.
 *
 * <p>THE critical rule in this class: creating a variant ALWAYS creates its
 * inventory row. A variant with no inventory row reports zero available forever and
 * silently never appears in the storefront — a bug that looks like a catalog problem
 * and is actually a missing row.
 */
@Service
public class VariantAdminService {

    private static final Logger log = LoggerFactory.getLogger(VariantAdminService.class);

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final AttributeRepository attributeRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository movementRepository;
    private final VariantMatrixGenerator matrixGenerator;
    private final AuditService auditService;

    public VariantAdminService(ProductRepository productRepository,
                               ProductVariantRepository variantRepository,
                               AttributeRepository attributeRepository,
                               InventoryRepository inventoryRepository,
                               StockMovementRepository movementRepository,
                               VariantMatrixGenerator matrixGenerator,
                               AuditService auditService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.attributeRepository = attributeRepository;
        this.inventoryRepository = inventoryRepository;
        this.movementRepository = movementRepository;
        this.matrixGenerator = matrixGenerator;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------ matrix preview

    /**
     * Generates combinations WITHOUT saving anything. The admin reviews, deletes the
     * ones not being stocked, then calls {@link #saveVariants}.
     */
    @Transactional(readOnly = true)
    public VariantMatrixPreviewResponse previewMatrix(Long productId,
                                                      VariantMatrixRequest request,
                                                      String locale) {
        Product product = loadProduct(productId);

        Map<Long, List<AttributeValue>> selected = new LinkedHashMap<>();

        for (var selection : request.selections()) {
            Attribute attribute = attributeRepository.findById(selection.attributeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ATTRIBUTE_NOT_FOUND,
                            "Attribute not found: " + selection.attributeId()));

            if (!attribute.isVariantDefining()) {
                throw new BusinessException(ErrorCode.ATTRIBUTE_NOT_VARIANT_DEFINING,
                        "'%s' is informational (specification only) and cannot generate variants"
                                .formatted(attribute.getCode()));
            }

            List<AttributeValue> values = attribute.getValues().stream()
                    .filter(v -> selection.valueIds().contains(v.getId()))
                    .sorted(Comparator.comparing(AttributeValue::getDisplayOrder))
                    .toList();

            if (values.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "None of the selected values belong to attribute '%s'"
                                .formatted(attribute.getCode()));
            }
            selected.put(attribute.getId(), values);
        }

        Set<String> existing = existingCombinationKeys(product);

        return matrixGenerator.generate(selected, product.getSlug(), existing, locale);
    }

    // --------------------------------------------------------------------- save

    /**
     * Creates and updates variants in one call. Used by the matrix flow after review,
     * and by the single-variant form when a new colour arrives.
     */
    @Transactional
    public List<VariantAdminResponse> saveVariants(Long productId,
                                                   VariantSaveRequest request,
                                                   Long actorId) {
        Product product = loadProduct(productId);
        Set<String> existing = existingCombinationKeys(product);
        List<VariantAdminResponse> saved = new ArrayList<>();

        for (var item : request.variants()) {
            if (item.id() != null) {
                saved.add(updateVariant(item, actorId));
                continue;
            }

            List<Long> valueIds = item.attributeValueIds() == null
                    ? List.of() : item.attributeValueIds();
            String key = VariantMatrixGenerator.combinationKey(valueIds);

            if (!key.isEmpty() && existing.contains(key)) {
                log.debug("Skipping existing combination {} for product {}", key, productId);
                continue;
            }

            saved.add(createVariant(product, item, actorId));
            existing.add(key);
        }

        return saved;
    }

    private VariantAdminResponse createVariant(Product product,
                                               VariantSaveRequest.VariantItem item,
                                               Long actorId) {
        String sku = item.sku();
        if (sku == null || sku.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "SKU is required");
        }
        if (variantRepository.existsBySku(sku)) {
            throw new BusinessException(ErrorCode.SKU_ALREADY_EXISTS, "SKU in use: " + sku);
        }
        String barcode = blankToNull(item.barcode());
        if (barcode != null && variantRepository.findByBarcode(barcode).isPresent()) {
            throw new BusinessException(ErrorCode.BARCODE_ALREADY_EXISTS,
                    "Barcode in use: " + barcode);
        }

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku(sku.trim().toUpperCase());
        variant.setBarcode(barcode);
        variant.setPrice(item.price());
        variant.setCompareAtPrice(item.compareAtPrice());
        variant.setCostPrice(item.costPrice());
        variant.setTaxRate(item.taxRate() == null ? new BigDecimal("0.1400") : item.taxRate());
        variant.setWeightGrams(item.weightGrams() == null ? 0 : item.weightGrams());
        variant.setStatus(VariantStatus.ACTIVE);
        variant.setPosition((short) product.getVariants().size());

        applyAttributeValues(variant, item.attributeValueIds());

        ProductVariant persisted = variantRepository.save(variant);

        // NEVER skip this. No inventory row means availableQty is 0 forever and the
        // variant silently never appears in the storefront.
        createInventory(persisted, item, actorId);

        log.info("Created variant id={} sku={} for product id={}",
                persisted.getId(), persisted.getSku(), product.getId());
        return toResponse(persisted);
    }

    private VariantAdminResponse updateVariant(VariantSaveRequest.VariantItem item,
                                               Long actorId) {
        ProductVariant variant = variantRepository.findById(item.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));

        if (item.sku() != null && !item.sku().equalsIgnoreCase(variant.getSku())) {
            if (variantRepository.existsBySku(item.sku())) {
                throw new BusinessException(ErrorCode.SKU_ALREADY_EXISTS);
            }
            variant.setSku(item.sku().trim().toUpperCase());
        }

        BigDecimal previousPrice = variant.getPrice();

        variant.setBarcode(blankToNull(item.barcode()));
        variant.setPrice(item.price());
        variant.setCompareAtPrice(item.compareAtPrice());
        variant.setCostPrice(item.costPrice());
        if (item.taxRate() != null) {
            variant.setTaxRate(item.taxRate());
        }
        if (item.weightGrams() != null) {
            variant.setWeightGrams(item.weightGrams());
        }

        log.info("Updated variant id={} sku={} price={}",
                variant.getId(), variant.getSku(), variant.getPrice());

        /*
         * Price is the most disputed field in any catalog. Six months from now
         * someone will ask why this product costs what it does, and the only honest
         * answer comes from a record of who changed it and when.
         *
         * Only recorded when the number actually moved — an audit log full of
         * no-op entries is one nobody reads.
         */
        if (previousPrice != null && previousPrice.compareTo(variant.getPrice()) != 0) {
            auditService.recordChange(AuditAction.PRICE_CHANGED, "PRODUCT_VARIANT",
                    variant.getId(), variant.getSku(),
                    previousPrice, variant.getPrice(), actorId);
        }

        return toResponse(variantRepository.save(variant));
    }

    @Transactional
    public void archive(Long variantId) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));

        // Archive, never delete: order lines reference this row, and the SKU must
        // never be reused.
        variant.setStatus(VariantStatus.ARCHIVED);
        variant.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        variantRepository.save(variant);
        log.info("Archived variant id={} sku={}", variantId, variant.getSku());
    }

    @Transactional(readOnly = true)
    public List<VariantAdminResponse> listForProduct(Long productId) {
        return variantRepository
                .findByProductIdAndArchivedAtIsNullOrderByPositionAsc(productId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ internal

    private void createInventory(ProductVariant variant,
                                 VariantSaveRequest.VariantItem item,
                                 Long actorId) {
        int opening = item.initialStock() == null ? 0 : item.initialStock();

        Inventory inventory = new Inventory();
        inventory.setVariant(variant);
        inventory.setLocationCode("MAIN");
        inventory.setQtyOnHand(opening);
        inventory.setQtyReserved(0);
        inventory.setMinStockLevel(item.minStockLevel() == null ? 3 : item.minStockLevel());
        inventoryRepository.save(inventory);

        if (opening > 0) {
            StockMovement movement = new StockMovement();
            movement.setVariant(variant);
            movement.setMovementType(MovementType.PURCHASE_RECEIVED);
            movement.setQuantityDelta(opening);
            movement.setQtyAfter(opening);
            movement.setReferenceType("VARIANT_CREATE");
            movement.setReferenceId(String.valueOf(variant.getId()));
            // No reason text: referenceType + referenceId already say this was the
            // opening stock for a newly created variant. A hardcoded English
            // "Opening stock" filler here made the ledger read half Arabic, half
            // English for information the reference columns already carry.
            movement.setActorId(actorId);
            movement.setActorName(auditService.resolveActorName(actorId));
            movementRepository.save(movement);
        }
    }

    private void applyAttributeValues(ProductVariant variant, List<Long> valueIds) {
        if (valueIds == null || valueIds.isEmpty()) {
            return;   // a product with a single variant needs no defining attributes
        }
        for (Long valueId : valueIds) {
            AttributeValue value = findAttributeValue(valueId);

            VariantAttributeValue vav = new VariantAttributeValue();
            // @MapsId fills both halves of the key from the associations below.
            vav.setKey(new VariantAttributeValue.Key());
            vav.setVariant(variant);
            vav.setAttribute(value.getAttribute());
            vav.setAttributeValue(value);
            variant.getAttributeValues().add(vav);
        }
    }

    private AttributeValue findAttributeValue(Long valueId) {
        return attributeRepository.findAll().stream()
                .flatMap(a -> a.getValues().stream())
                .filter(v -> v.getId().equals(valueId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTRIBUTE_VALUE_NOT_FOUND,
                        "Attribute value not found: " + valueId));
    }

    private Set<String> existingCombinationKeys(Product product) {
        Set<String> keys = new HashSet<>();
        for (ProductVariant variant : product.getVariants()) {
            if (variant.getArchivedAt() != null) {
                continue;
            }
            List<Long> ids = variant.getAttributeValues().stream()
                    .map(vav -> vav.getAttributeValue().getId())
                    .toList();
            keys.add(VariantMatrixGenerator.combinationKey(ids));
        }
        return keys;
    }

    private Product loadProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private VariantAdminResponse toResponse(ProductVariant variant) {
        Inventory inventory = inventoryRepository.findByVariantId(variant.getId()).orElse(null);

        /*
         * findByProductIdAndArchivedAtIsNullOrderByPositionAsc fetch-joins
         * attributeValues alongside attributeValue.translations. Fetch-joining two
         * bags in one query is a cartesian product at the row level: an attribute
         * value with 2 translations makes Hibernate hydrate that SAME
         * VariantAttributeValue row twice. distinct() collapses it back — the
         * duplicates are the same managed entity instance (Hibernate's
         * first-level cache), so reference equality is enough. Same issue as
         * AttributeRepository / TaxonomyAdminService.
         */
        List<Long> valueIds = variant.getAttributeValues().stream()
                .distinct()
                .map(vav -> vav.getAttributeValue().getId())
                .toList();

        String summary = variant.getAttributeValues().stream()
                .distinct()
                .map(vav -> vav.getAttributeValue().nameFor("ar"))
                .reduce((a, b) -> a + " / " + b)
                .orElse(null);

        return new VariantAdminResponse(
                variant.getId(),
                variant.getProduct().getId(),
                variant.getSku(),
                variant.getBarcode(),
                summary,
                variant.getPrice(),
                variant.getCompareAtPrice(),
                variant.getCostPrice(),
                variant.getTaxRate(),
                variant.getWeightGrams(),
                variant.getStatus().name(),
                valueIds,
                inventory == null ? 0 : inventory.getQtyOnHand(),
                inventory == null ? 0 : inventory.getQtyReserved(),
                inventory == null ? 0 : inventory.getAvailable(),
                inventory == null ? 0 : inventory.getMinStockLevel(),
                inventory != null && inventory.isLowStock());
    }
}
