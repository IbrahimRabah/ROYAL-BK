package com.velora.api.shipping.service;

import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.shipping.domain.ShippingRate;
import com.velora.api.shipping.dto.ShippingBreakdownLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a zone's rates plus an order's lines into a delivery charge.
 *
 * <p>{@code shipping = sum( unit cost of (zone, size class) x quantity )}, then
 * capped at the zone's {@code maxShippingCost} when it has one. The COD fee is
 * added on top and is never capped.
 *
 * <p>Deliberately NOT a Strategy hierarchy: the Strategy pattern earns its place
 * when the calculation <em>source</em> differs — a courier API quoting live prices
 * is a genuinely different thing, and that is when this becomes an interface.
 *
 * <p>Weight and the old free-shipping threshold are no longer read here. The rate
 * columns still exist; the size class replaced them.
 */
@Component
public class ShippingCalculator {

    /** One cart or order line, reduced to what shipping needs. */
    public record Line(ShippingSizeClass sizeClass, int quantity, String sku) {
    }

    /**
     * @param rates      the destination zone's rates
     * @param lines      what is being shipped
     * @param codApplies whether cash will be collected on delivery
     * @throws BusinessException {@code SHIPPING_SIZE_MISSING} for a line whose product
     *         has no size class — never guessed, because a guessed price is
     *         indistinguishable from a real one afterwards;
     *         {@code SHIPPING_RATE_NOT_CONFIGURED} when the zone has no rate for a size
     */
    public Calculation calculate(ZoneRates rates, List<Line> lines, boolean codApplies) {
        Map<ShippingSizeClass, Integer> quantityBySize = new EnumMap<>(ShippingSizeClass.class);

        for (Line line : lines) {
            if (line.sizeClass() == null) {
                throw new BusinessException(ErrorCode.SHIPPING_SIZE_MISSING,
                        "Item %s has no shipping size, so delivery cannot be priced"
                                .formatted(line.sku()));
            }
            quantityBySize.merge(line.sizeClass(), line.quantity(), Integer::sum);
        }

        List<ShippingBreakdownLine> breakdown = new ArrayList<>();
        BigDecimal uncapped = MoneyUtils.ZERO;

        for (Map.Entry<ShippingSizeClass, Integer> entry : quantityBySize.entrySet()) {
            ShippingRate rate = rates.rateFor(entry.getKey());
            if (rate == null) {
                throw new BusinessException(ErrorCode.SHIPPING_RATE_NOT_CONFIGURED,
                        "No %s shipping rate is configured for %s"
                                .formatted(entry.getKey(), rates.zone().getCode()));
            }
            BigDecimal unitCost = MoneyUtils.round(rate.getBaseCost());
            BigDecimal lineCost = MoneyUtils.lineTotal(unitCost, entry.getValue());
            breakdown.add(new ShippingBreakdownLine(
                    entry.getKey(), entry.getValue(), unitCost, lineCost));
            uncapped = uncapped.add(lineCost);
        }

        // Largest size first, as the customer reads it: the expensive line leads.
        breakdown.sort(Comparator.comparing(ShippingBreakdownLine::sizeClass).reversed());
        uncapped = MoneyUtils.round(uncapped);

        BigDecimal cap = rates.maxShippingCost();
        boolean capApplied = cap != null && uncapped.compareTo(cap) > 0;
        BigDecimal shipping = capApplied ? MoneyUtils.round(cap) : uncapped;

        BigDecimal codFee = codApplies ? MoneyUtils.round(rates.codFee()) : MoneyUtils.ZERO;

        return new Calculation(shipping, uncapped, capApplied, breakdown, codFee);
    }

    /** The outcome of one shipping calculation. */
    public record Calculation(
            BigDecimal shippingCost,
            BigDecimal uncappedCost,
            boolean capApplied,
            List<ShippingBreakdownLine> breakdown,
            BigDecimal codFee
    ) {

        /** Delivery is free exactly when the price works out to zero. */
        public boolean freeShippingApplied() {
            return shippingCost.signum() == 0;
        }

        public BigDecimal totalDeliveryCharge() {
            return MoneyUtils.round(shippingCost.add(codFee));
        }
    }
}
