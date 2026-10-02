package com.velora.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.shipping.domain.ShippingRate;
import com.velora.api.shipping.domain.ShippingZone;
import com.velora.api.shipping.dto.ShippingBreakdownLine;
import com.velora.api.shipping.service.ShippingCalculator;
import com.velora.api.shipping.service.ShippingCalculator.Line;
import com.velora.api.shipping.service.ZoneRates;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.velora.api.catalog.domain.ShippingSizeClass.LARGE;
import static com.velora.api.catalog.domain.ShippingSizeClass.MEDIUM;
import static com.velora.api.catalog.domain.ShippingSizeClass.SMALL;

/**
 * Shipping = sum( unit cost of (zone, size) x quantity ), capped per zone.
 * The calculator is pure, so no database is involved.
 */
class ShippingCalculatorTest {

    private ShippingCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ShippingCalculator();
    }

    @Test
    @DisplayName("Different size classes are priced separately and summed")
    void mixedSizesAreSummed() {
        ZoneRates delta = zone("DELTA", "1500", "100", "200", "400");

        var result = calculator.calculate(delta,
                List.of(line(LARGE, 1), line(SMALL, 2), line(MEDIUM, 1)), true);

        // 400 + 2*100 + 200
        assertThat(result.shippingCost()).isEqualByComparingTo("800.00");
        assertThat(result.uncappedCost()).isEqualByComparingTo("800.00");
        assertThat(result.capApplied()).isFalse();
    }

    @Test
    @DisplayName("The breakdown lists the largest size first, with unit and line cost")
    void breakdownIsLargestFirst() {
        ZoneRates delta = zone("DELTA", "1500", "100", "200", "400");

        var result = calculator.calculate(delta, List.of(line(SMALL, 2), line(LARGE, 1)), true);

        assertThat(result.breakdown()).extracting(ShippingBreakdownLine::sizeClass)
                .containsExactly(LARGE, SMALL);
        ShippingBreakdownLine small = result.breakdown().get(1);
        assertThat(small.quantity()).isEqualTo(2);
        assertThat(small.unitCost()).isEqualByComparingTo("100.00");
        assertThat(small.lineCost()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("Two lines of the same size class collapse into one breakdown line")
    void sameSizeLinesAreMerged() {
        ZoneRates delta = zone("DELTA", "1500", "100", "200", "400");

        var result = calculator.calculate(delta,
                List.of(line(SMALL, 2), line(SMALL, 3)), true);

        assertThat(result.breakdown()).hasSize(1);
        assertThat(result.breakdown().get(0).quantity()).isEqualTo(5);
        assertThat(result.shippingCost()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("A total above the zone cap is replaced by the cap, and says so")
    void capIsApplied() {
        ZoneRates upperEgypt = zone("UPPER_EGYPT", "1500", "150", "300", "550");

        // 4 x 550 + 5 x 150 = 2950
        var result = calculator.calculate(upperEgypt,
                List.of(line(LARGE, 4), line(SMALL, 5)), true);

        assertThat(result.uncappedCost()).isEqualByComparingTo("2950.00");
        assertThat(result.shippingCost()).isEqualByComparingTo("1500.00");
        assertThat(result.capApplied()).isTrue();
        // The breakdown still shows the real, uncapped lines.
        assertThat(result.breakdown()).extracting(ShippingBreakdownLine::lineCost)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("2200.00"), new BigDecimal("750.00"));
    }

    @Test
    @DisplayName("A total exactly equal to the cap is not 'capped'")
    void totalEqualToCapIsNotCapped() {
        ZoneRates delta = zone("DELTA", "1500", "100", "200", "400");

        var result = calculator.calculate(delta, List.of(line(SMALL, 15)), true);

        assertThat(result.shippingCost()).isEqualByComparingTo("1500.00");
        assertThat(result.capApplied()).isFalse();
    }

    @Test
    @DisplayName("A zone with no cap charges the full total")
    void noCapChargesEverything() {
        ZoneRates uncapped = zone("DELTA", null, "100", "200", "400");

        var result = calculator.calculate(uncapped, List.of(line(LARGE, 10)), true);

        assertThat(result.shippingCost()).isEqualByComparingTo("4000.00");
        assertThat(result.capApplied()).isFalse();
    }

    @Test
    @DisplayName("Greater Cairo is free whatever is in the cart, with no minimum order")
    void cairoIsFree() {
        ZoneRates cairo = zone("GREATER_CAIRO", "1500", "0", "0", "0");

        var result = calculator.calculate(cairo,
                List.of(line(SMALL, 1), line(LARGE, 7)), true);

        assertThat(result.shippingCost()).isEqualByComparingTo("0.00");
        assertThat(result.uncappedCost()).isEqualByComparingTo("0.00");
        assertThat(result.capApplied()).isFalse();
        assertThat(result.freeShippingApplied()).isTrue();
    }

    @Test
    @DisplayName("A paid zone is not reported as free shipping")
    void paidZoneIsNotFree() {
        ZoneRates delta = zone("DELTA", "1500", "100", "200", "400");

        assertThat(calculator.calculate(delta, List.of(line(SMALL, 1)), true)
                .freeShippingApplied()).isFalse();
    }

    @Test
    @DisplayName("A line whose product has no size class is refused, naming the SKU")
    void nullSizeIsRefused() {
        ZoneRates delta = zone("DELTA", "1500", "100", "200", "400");

        assertThatThrownBy(() -> calculator.calculate(delta,
                List.of(line(SMALL, 1), new Line(null, 2, "WCH-NO-SIZE")), true))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIPPING_SIZE_MISSING);
                    assertThat(e.getMessage()).contains("WCH-NO-SIZE");
                });
    }

    @Test
    @DisplayName("A size with no configured rate in the zone is refused, not priced at zero")
    void missingRateForSizeIsRefused() {
        ZoneRates smallOnly = new ZoneRates(List.of(rate(zoneEntity("DELTA", "1500"), SMALL, "100")));

        assertThatThrownBy(() -> calculator.calculate(smallOnly, List.of(line(LARGE, 1)), true))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.SHIPPING_RATE_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("The COD fee is added on top and is never capped")
    void codFeeIsNotCapped() {
        ShippingZone zone = zoneEntity("UPPER_EGYPT", "1500");
        List<ShippingRate> rates = new ArrayList<>();
        for (var entry : List.of(new Object[] {SMALL, "150"}, new Object[] {LARGE, "550"})) {
            ShippingRate r = rate(zone, (ShippingSizeClass) entry[0], (String) entry[1]);
            r.setCodFee(new BigDecimal("25"));
            rates.add(r);
        }

        var result = calculator.calculate(new ZoneRates(rates),
                List.of(line(LARGE, 4), line(SMALL, 5)), true);

        assertThat(result.shippingCost()).isEqualByComparingTo("1500.00");
        assertThat(result.codFee()).isEqualByComparingTo("25.00");
        assertThat(result.totalDeliveryCharge()).isEqualByComparingTo("1525.00");
    }

    @Test
    @DisplayName("No COD fee when cash is not collected")
    void noCodFeeWithoutCod() {
        ShippingZone zone = zoneEntity("DELTA", "1500");
        ShippingRate r = rate(zone, SMALL, "100");
        r.setCodFee(new BigDecimal("25"));

        var result = calculator.calculate(new ZoneRates(List.of(r)), List.of(line(SMALL, 1)), false);

        assertThat(result.codFee()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ helpers

    private static Line line(ShippingSizeClass size, int quantity) {
        return new Line(size, quantity, "SKU-" + size);
    }

    private static ZoneRates zone(String code, String cap, String small, String medium,
                                  String large) {
        ShippingZone zone = zoneEntity(code, cap);
        return new ZoneRates(List.of(
                rate(zone, SMALL, small), rate(zone, MEDIUM, medium), rate(zone, LARGE, large)));
    }

    private static ShippingZone zoneEntity(String code, String cap) {
        ShippingZone zone = new ShippingZone();
        zone.setCode(code);
        zone.setNameAr(code);
        zone.setNameEn(code);
        zone.setMaxShippingCost(cap == null ? null : new BigDecimal(cap));
        return zone;
    }

    private static ShippingRate rate(ShippingZone zone, ShippingSizeClass size, String cost) {
        ShippingRate rate = new ShippingRate();
        rate.setZone(zone);
        rate.setSizeClass(size);
        rate.setBaseCost(new BigDecimal(cost));
        return rate;
    }
}
