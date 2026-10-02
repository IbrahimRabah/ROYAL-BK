package com.velora.api.shipping.service;

import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.shipping.domain.ShippingRate;
import com.velora.api.shipping.domain.ShippingZone;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every active rate of one zone — one per size class.
 *
 * <p>Delivery days and the COD fee are written identically to every row of a zone
 * (see {@code ShippingAdminService.saveRate}), so they are read from whichever row
 * comes first.
 */
public final class ZoneRates {

    private final ShippingZone zone;
    private final Map<ShippingSizeClass, ShippingRate> bySize = new EnumMap<>(ShippingSizeClass.class);
    private final ShippingRate any;

    /** @param rates not empty — callers decide what "no rates" means for them */
    public ZoneRates(List<ShippingRate> rates) {
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("A zone needs at least one rate");
        }
        this.any = rates.get(0);
        this.zone = any.getZone();
        rates.forEach(r -> bySize.put(r.getSizeClass(), r));
    }

    public ShippingZone zone() {
        return zone;
    }

    public ShippingRate rateFor(ShippingSizeClass sizeClass) {
        return bySize.get(sizeClass);
    }

    public Map<ShippingSizeClass, ShippingRate> bySize() {
        return bySize;
    }

    /** Ceiling on the shipping total, or null when the zone has none. */
    public BigDecimal maxShippingCost() {
        return zone.getMaxShippingCost();
    }

    public BigDecimal codFee() {
        return any.getCodFee();
    }

    public short deliveryDaysMin() {
        return any.getDeliveryDaysMin();
    }

    public short deliveryDaysMax() {
        return any.getDeliveryDaysMax();
    }
}
