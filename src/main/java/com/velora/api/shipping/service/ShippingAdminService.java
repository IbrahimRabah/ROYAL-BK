package com.velora.api.shipping.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.shipping.domain.ShippingRate;
import com.velora.api.shipping.domain.ShippingZone;
import com.velora.api.shipping.dto.MaxShippingCostRequest;
import com.velora.api.shipping.dto.ShippingRateRequest;
import com.velora.api.shipping.dto.ShippingZoneResponse;
import com.velora.api.shipping.dto.SizeRateResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.repository.ShippingRateRepository;
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

    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final GovernorateRepository governorateRepository;

    public ShippingAdminService(ShippingZoneRepository zoneRepository,
                                ShippingRateRepository rateRepository,
                                GovernorateRepository governorateRepository) {
        this.zoneRepository = zoneRepository;
        this.rateRepository = rateRepository;
        this.governorateRepository = governorateRepository;
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
    public Long saveRate(ShippingRateRequest request) {
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

        return saved.getId();
    }

    /** Sets or clears the zone's shipping cap. */
    @Transactional
    public void saveMaxShippingCost(Long zoneId, MaxShippingCostRequest request) {
        ShippingZone zone = loadZone(zoneId);
        BigDecimal previous = zone.getMaxShippingCost();
        zone.setMaxShippingCost(request.maxShippingCost());
        zoneRepository.save(zone);
        log.info("Shipping cap for zone {} changed from {} to {}",
                zone.getCode(), previous, request.maxShippingCost());
    }

    private ShippingZone loadZone(Long zoneId) {
        return zoneRepository.findById(zoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Shipping zone not found"));
    }
}
