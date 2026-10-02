package com.velora.api.shipping.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.domain.ShippingRate;
import com.velora.api.shipping.domain.ShippingZone;
import com.velora.api.shipping.dto.AdminGovernorateResponse;
import com.velora.api.shipping.dto.MaxShippingCostRequest;
import com.velora.api.shipping.dto.ShippingRateRequest;
import com.velora.api.shipping.dto.ShippingZoneResponse;
import com.velora.api.shipping.dto.SizeRateResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.repository.ShippingRateRepository;
import com.velora.api.shipping.repository.ShippingZoneGovernorateRepository;
import com.velora.api.shipping.repository.ShippingZoneRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing shipping prices without touching SQL.
 *
 * <p>Courier prices change. Doing it through the API keeps the change audited and
 * stops a typo in a hand-written UPDATE from silently making delivery free.
 */
@Service
public class ShippingAdminService {

    private static final Logger log = LoggerFactory.getLogger(ShippingAdminService.class);

    /** What the audit log shows as the zone of a governorate that is in none. */
    private static final String CLOSED = "CLOSED";

    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final GovernorateRepository governorateRepository;
    private final ShippingZoneGovernorateRepository zoneGovernorateRepository;
    private final AuditService auditService;

    public ShippingAdminService(ShippingZoneRepository zoneRepository,
                                ShippingRateRepository rateRepository,
                                GovernorateRepository governorateRepository,
                                ShippingZoneGovernorateRepository zoneGovernorateRepository,
                                AuditService auditService) {
        this.zoneRepository = zoneRepository;
        this.rateRepository = rateRepository;
        this.governorateRepository = governorateRepository;
        this.zoneGovernorateRepository = zoneGovernorateRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ShippingZoneResponse> listZones() {
        List<ShippingZoneResponse> result = new ArrayList<>();

        for (ShippingZone zone : zoneRepository.findByActiveTrueOrderByIdAsc()) {
            List<ShippingRate> rates =
                    rateRepository.findByZoneIdAndActiveTrueOrderBySizeClassAsc(zone.getId());

            List<String> governorates = governorateRepository
                    .findByActiveTrueOrderByDisplayOrderAsc().stream()
                    .filter(g -> rateRepository.findAllForGovernorate(g.getId()).stream()
                            .findFirst()
                            .map(r -> r.getZone().getId().equals(zone.getId()))
                            .orElse(false))
                    .map(g -> g.getNameAr())
                    .toList();

            ShippingRate any = rates.isEmpty() ? null : rates.get(0);

            result.add(new ShippingZoneResponse(
                    zone.getId(),
                    zone.getCode(),
                    zone.getNameAr(),
                    zone.getNameEn(),
                    rates.stream()
                            .map(r -> new SizeRateResponse(
                                    r.getSizeClass(), MoneyUtils.round(r.getBaseCost())))
                            .toList(),
                    zone.getMaxShippingCost(),
                    any == null ? null : any.getCodFee(),
                    any == null ? 0 : any.getDeliveryDaysMin(),
                    any == null ? 0 : any.getDeliveryDaysMax(),
                    zone.isActive(),
                    governorates));
        }
        return result;
    }

    /**
     * Sets the price of ONE size class in a zone. Replaces the existing row for that
     * (zone, size) rather than adding a second, so a governorate can never match two
     * competing prices.
     *
     * <p>The COD fee and delivery days belong to the whole zone, so they are written
     * to every size row of it — {@link ZoneRates} reads them from any one.
     */
    @Transactional
    public Long saveRate(ShippingRateRequest request, Long actorId) {
        ShippingZone zone = loadZone(request.zoneId());

        if (request.deliveryDaysMin() != null && request.deliveryDaysMax() != null
                && request.deliveryDaysMin() > request.deliveryDaysMax()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The minimum delivery time cannot be greater than the maximum");
        }

        List<ShippingRate> zoneRows =
                rateRepository.findByZoneIdAndActiveTrueOrderBySizeClassAsc(zone.getId());

        ShippingRate rate = rateRepository
                .findByZoneIdAndSizeClass(zone.getId(), request.sizeClass())
                .orElseGet(() -> {
                    ShippingRate created = new ShippingRate();
                    created.setZone(zone);
                    created.setSizeClass(request.sizeClass());
                    // A new size row starts from the zone's existing terms.
                    if (!zoneRows.isEmpty()) {
                        created.setCodFee(zoneRows.get(0).getCodFee());
                        created.setDeliveryDaysMin(zoneRows.get(0).getDeliveryDaysMin());
                        created.setDeliveryDaysMax(zoneRows.get(0).getDeliveryDaysMax());
                    }
                    return created;
                });

        BigDecimal previous = rate.getBaseCost();
        String termsBefore = zoneRows.isEmpty() ? null : termsOf(zoneRows.get(0));
        rate.setBaseCost(request.baseCost());
        rate.setActive(true);
        ShippingRate saved = rateRepository.save(rate);

        List<ShippingRate> allRows = new ArrayList<>(zoneRows);
        if (allRows.stream().noneMatch(r -> r.getId().equals(saved.getId()))) {
            allRows.add(saved);
        }
        for (ShippingRate row : allRows) {
            if (request.codFee() != null) {
                row.setCodFee(request.codFee());
            }
            if (request.deliveryDaysMin() != null) {
                row.setDeliveryDaysMin(request.deliveryDaysMin().shortValue());
            }
            if (request.deliveryDaysMax() != null) {
                row.setDeliveryDaysMax(request.deliveryDaysMax().shortValue());
            }
            rateRepository.save(row);
        }

        // Price changes affect what every future customer pays. Worth a log line.
        log.info("Shipping rate for zone {} size {} changed from {} to {}",
                zone.getCode(), request.sizeClass(), previous, saved.getBaseCost());

        // Audited only when something actually moved, like PRICE_CHANGED: a log full of
        // no-op entries is one nobody reads.
        String label = zone.getCode() + " / " + request.sizeClass();
        if (previous == null || previous.compareTo(saved.getBaseCost()) != 0) {
            auditService.recordChange(AuditAction.SHIPPING_RATE_CHANGED, "SHIPPING_RATE",
                    saved.getId(), label,
                    previous == null ? null : MoneyUtils.round(previous),
                    MoneyUtils.round(saved.getBaseCost()), actorId);
        }
        String termsAfter = termsOf(allRows.get(0));
        if (termsBefore != null && !termsBefore.equals(termsAfter)) {
            auditService.recordChange(AuditAction.SHIPPING_RATE_CHANGED, "SHIPPING_ZONE",
                    zone.getId(), zone.getCode() + " terms", termsBefore, termsAfter, actorId);
        }

        return saved.getId();
    }

    /** Sets or clears the zone's shipping cap. */
    @Transactional
    public void saveMaxShippingCost(Long zoneId, MaxShippingCostRequest request,
                                    Long actorId) {
        ShippingZone zone = loadZone(zoneId);
        BigDecimal previous = zone.getMaxShippingCost();
        zone.setMaxShippingCost(request.maxShippingCost());
        zoneRepository.save(zone);
        log.info("Shipping cap for zone {} changed from {} to {}",
                zone.getCode(), previous, request.maxShippingCost());

        if (!sameAmount(previous, request.maxShippingCost())) {
            // "none" rather than null: an empty value reads as "not recorded", and
            // removing the cap is exactly the change someone will ask about.
            auditService.recordChange(AuditAction.SHIPPING_RATE_CHANGED, "SHIPPING_ZONE",
                    zone.getId(), zone.getCode() + " cap",
                    previous == null ? "none" : MoneyUtils.round(previous),
                    request.maxShippingCost() == null
                            ? "none" : MoneyUtils.round(request.maxShippingCost()),
                    actorId);
        }
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String termsOf(ShippingRate row) {
        return "codFee=%s, deliveryDays=%d-%d".formatted(
                MoneyUtils.round(row.getCodFee()), row.getDeliveryDaysMin(),
                row.getDeliveryDaysMax());
    }

    // ------------------------------------------------------------ governorates

    /**
     * Every governorate, served or not — the panel needs the closed ones in order to
     * reopen them, and {@link #listZones()} only shows governorates that are inside a
     * zone.
     */
    @Transactional(readOnly = true)
    public List<AdminGovernorateResponse> listGovernorates() {
        List<AdminGovernorateResponse> result = new ArrayList<>();
        for (Governorate governorate : governorateRepository.findAllByOrderByDisplayOrderAsc()) {
            ShippingZone zone = zoneGovernorateRepository
                    .findZoneIdByGovernorateId(governorate.getId())
                    .flatMap(zoneRepository::findById)
                    .orElse(null);
            boolean served = !rateRepository.findAllForGovernorate(governorate.getId()).isEmpty();

            result.add(new AdminGovernorateResponse(
                    governorate.getId(),
                    governorate.getCode(),
                    governorate.getNameAr(),
                    governorate.getNameEn(),
                    zone == null ? null : zone.getId(),
                    zone == null ? null : zone.getCode(),
                    served));
        }
        return result;
    }

    /**
     * Opens a governorate for delivery by putting it in a zone, or moves it to another
     * zone. Idempotent: assigning it to the zone it is already in changes nothing.
     *
     * <p>Refuses a zone that cannot price every size class. Otherwise the governorate
     * would look open on this screen and still fail at the customer's checkout, which is
     * exactly the half-open state this feature exists to prevent.
     */
    @Transactional
    public void assignGovernorate(Long governorateId, Long zoneId, Long actorId) {
        Governorate governorate = loadGovernorate(governorateId);
        ShippingZone zone = loadZone(zoneId);
        if (!zone.isActive()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shipping zone not found");
        }

        List<ShippingRate> rates =
                rateRepository.findByZoneIdAndActiveTrueOrderBySizeClassAsc(zone.getId());
        if (rates.size() < ShippingSizeClass.values().length) {
            throw new BusinessException(ErrorCode.SHIPPING_RATE_NOT_CONFIGURED,
                    "Zone %s has no active rate for every size class, so it cannot take "
                            .formatted(zone.getCode()) + "governorates yet");
        }

        Long previousZoneId = zoneGovernorateRepository
                .findZoneIdByGovernorateId(governorate.getId()).orElse(null);
        if (previousZoneId == null) {
            zoneGovernorateRepository.insert(governorate.getId(), zone.getId());
        } else if (!previousZoneId.equals(zone.getId())) {
            zoneGovernorateRepository.moveToZone(governorate.getId(), zone.getId());
        }

        log.info("Governorate {} assigned to zone {} (was {})",
                governorate.getCode(), zone.getCode(), previousZoneId);

        // Assigning it to the zone it already had changes nothing, so records nothing.
        if (!zone.getId().equals(previousZoneId)) {
            String before = previousZoneId == null
                    ? CLOSED
                    : zoneRepository.findById(previousZoneId)
                            .map(ShippingZone::getCode).orElse("zone#" + previousZoneId);
            auditService.recordChange(AuditAction.GOVERNORATE_SERVICE_CHANGED, "GOVERNORATE",
                    governorate.getId(), governorateLabel(governorate),
                    before, zone.getCode(), actorId);
        }
    }

    /**
     * Closes a governorate: removes it from its zone, so it is no longer served. The
     * governorate itself stays active and listed (as {@code served: false}). Idempotent.
     */
    @Transactional
    public void closeGovernorate(Long governorateId, Long actorId) {
        Governorate governorate = loadGovernorate(governorateId);
        Long previousZoneId = zoneGovernorateRepository
                .findZoneIdByGovernorateId(governorate.getId()).orElse(null);
        int removed = zoneGovernorateRepository.deleteByGovernorateId(governorate.getId());
        log.info("Governorate {} closed for delivery (was in a zone: {})",
                governorate.getCode(), removed > 0);

        // Closing one that is already closed changes nothing, so records nothing.
        if (previousZoneId != null) {
            String before = zoneRepository.findById(previousZoneId)
                    .map(ShippingZone::getCode).orElse("zone#" + previousZoneId);
            auditService.recordChange(AuditAction.GOVERNORATE_SERVICE_CHANGED, "GOVERNORATE",
                    governorate.getId(), governorateLabel(governorate),
                    before, CLOSED, actorId);
        }
    }

    private static String governorateLabel(Governorate governorate) {
        return governorate.getNameEn() + " (" + governorate.getCode() + ")";
    }

    private Governorate loadGovernorate(Long governorateId) {
        return governorateRepository.findById(governorateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Governorate not found"));
    }

    private ShippingZone loadZone(Long zoneId) {
        return zoneRepository.findById(zoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Shipping zone not found"));
    }
}
