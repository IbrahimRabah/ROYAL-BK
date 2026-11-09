package com.velora.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.importer.service.ImportFulfillmentRules;
import com.velora.api.catalog.importer.service.ImportFulfillmentRules.Decision;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How an imported row becomes "ships from stock" or "made to order", and which shipping size it gets.
 * The size decides what the customer pays for delivery, so the rule is pinned here, boundaries included.
 */
class ImportFulfillmentRulesTest {

    private static final Map<String, String> NO_SPECS = Map.of();

    private static Decision decide(FulfillmentType type, ShippingSizeClass size, boolean priced, String name,
                                   Map<String, String> specs) {
        return ImportFulfillmentRules.decide(type, size, priced, name, specs);
    }

    @Test
    @DisplayName("A priced row with nothing said about it is ready-made, sized from the dimensions in its name")
    void pricedRowIsReadyMade() {
        Decision d = decide(null, null, true, "كرسي سفرة حديد بتنجيد جلد - أسود 40×40×70سم CH108", NO_SPECS);

        assertThat(d.type()).isEqualTo(FulfillmentType.READY_MADE);
        assertThat(d.size()).isEqualTo(ShippingSizeClass.SMALL);
        assertThat(d.sizeUnknown()).isFalse();
    }

    @Test
    @DisplayName("Boundaries: under 90 cm small, 90 to 160 medium, over 160 large (by the longest side)")
    void sizeBoundaries() {
        assertThat(ImportFulfillmentRules.sizeFromName("كرسي 40×40×89سم")).isEqualTo(ShippingSizeClass.SMALL);
        assertThat(ImportFulfillmentRules.sizeFromName("كرسي 40×40×90سم")).isEqualTo(ShippingSizeClass.MEDIUM);
        assertThat(ImportFulfillmentRules.sizeFromName("ترابيزة 80×160×75سم")).isEqualTo(ShippingSizeClass.MEDIUM);
        assertThat(ImportFulfillmentRules.sizeFromName("ترابيزة 80×161×75سم")).isEqualTo(ShippingSizeClass.LARGE);
    }

    @Test
    @DisplayName("Dimensions written with Arabic digits, an x, or a decimal comma are read")
    void dimensionFormats() {
        assertThat(ImportFulfillmentRules.sizeFromName("كرسي ٤٠×٤٠×٧٠ سم")).isEqualTo(ShippingSizeClass.SMALL);
        assertThat(ImportFulfillmentRules.sizeFromName("chair 40x40x70 cm")).isEqualTo(ShippingSizeClass.SMALL);
        assertThat(ImportFulfillmentRules.sizeFromName("ترابيزة 110 × 60 سم")).isEqualTo(ShippingSizeClass.MEDIUM);
        assertThat(ImportFulfillmentRules.sizeFromName("ترابيزة 89,5×40سم")).isEqualTo(ShippingSizeClass.SMALL);
    }

    @Test
    @DisplayName("The dimension specifications of the sheet win over the name, when at least two are filled")
    void specsWinOverName() {
        Decision d = decide(null, null, true, "كرسي 40×40×70سم",
                Map.of("WIDTH_CM", "50", "HEIGHT_CM", "120"));

        assertThat(d.size()).isEqualTo(ShippingSizeClass.MEDIUM);
    }

    @Test
    @DisplayName("One lone number is not enough to size a product: the row is not guessed, it is flagged")
    void tooLittleToSize() {
        Decision d = decide(null, null, true, "كرسي بار بقاعدة حديد ارتفاع 75سم", Map.of("WIDTH_CM", "50"));

        assertThat(d.size()).isNull();
        assertThat(d.type()).isEqualTo(FulfillmentType.MADE_TO_ORDER);
        assertThat(d.sizeUnknown()).isTrue();
    }

    @Test
    @DisplayName("A size written in the sheet is used as it is, even when the name says otherwise")
    void explicitSizeWins() {
        Decision d = decide(null, ShippingSizeClass.LARGE, true, "كرسي 40×40×70سم", NO_SPECS);

        assertThat(d.type()).isEqualTo(FulfillmentType.READY_MADE);
        assertThat(d.size()).isEqualTo(ShippingSizeClass.LARGE);
    }

    @Test
    @DisplayName("A type written in the sheet is used as it is: made-to-order stays made-to-order")
    void explicitTypeWins() {
        Decision d = decide(FulfillmentType.CUSTOM_WORK, null, true, "بوابة حديد 300×200سم", NO_SPECS);

        assertThat(d.type()).isEqualTo(FulfillmentType.CUSTOM_WORK);
        assertThat(d.size()).isNull();
        assertThat(d.sizeUnknown()).isFalse();
    }

    @Test
    @DisplayName("Asked to be ready-made with no size and none findable: not downgraded, the caller reports it")
    void explicitReadyMadeWithoutSize() {
        Decision d = decide(FulfillmentType.READY_MADE, null, true, "كرسي", NO_SPECS);

        assertThat(d.type()).isEqualTo(FulfillmentType.READY_MADE);
        assertThat(d.size()).isNull();
    }

    @Test
    @DisplayName("A row with no price is made to order")
    void noPriceIsMadeToOrder() {
        Decision d = decide(null, null, false, "كرسي 40×40×70سم", NO_SPECS);

        assertThat(d.type()).isEqualTo(FulfillmentType.MADE_TO_ORDER);
        assertThat(d.sizeUnknown()).isFalse();
    }
}
