package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.AttributeValueTranslation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VariantMatrixGeneratorTest {

    private VariantMatrixGenerator generator;
    private long idSeq;

    @BeforeEach
    void setUp() {
        generator = new VariantMatrixGenerator(new com.velora.api.common.config.BrandingProperties());
        idSeq = 1;
    }

    @Test
    @DisplayName("Two colours and three sizes give six variants")
    void producesCartesianProduct() {
        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        selection.put(1L, List.of(value("GOLD", "ذهبي"), value("SILVER", "فضي")));
        selection.put(2L, List.of(value("38MM", "38 مم"), value("42MM", "42 مم"),
                value("46MM", "46 مم")));

        var result = generator.generate(selection, "classic-watch", Set.of(), "ar");

        assertThat(result.totalCombinations()).isEqualTo(6);
        assertThat(result.combinations()).hasSize(6);
    }

    @Test
    @DisplayName("Every combination is distinct")
    void combinationsAreUnique() {
        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        selection.put(1L, List.of(value("GOLD", "ذهبي"), value("SILVER", "فضي")));
        selection.put(2L, List.of(value("38MM", "38 مم"), value("42MM", "42 مم")));

        var result = generator.generate(selection, "watch", Set.of(), "ar");

        Set<String> keys = result.combinations().stream()
                .map(c -> VariantMatrixGenerator.combinationKey(c.attributeValueIds()))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(keys).hasSize(result.combinations().size());
    }

    @Test
    @DisplayName("A single attribute produces one variant per value")
    void singleAttribute() {
        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        selection.put(1L, List.of(value("GOLD", "ذهبي"), value("SILVER", "فضي"),
                value("BLACK", "أسود")));

        var result = generator.generate(selection, "wallet", Set.of(), "ar");
        assertThat(result.totalCombinations()).isEqualTo(3);
    }

    @Test
    @DisplayName("Existing combinations are flagged, not duplicated")
    void flagsExistingCombinations() {
        AttributeValue gold = value("GOLD", "ذهبي");
        AttributeValue silver = value("SILVER", "فضي");

        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        selection.put(1L, List.of(gold, silver));

        Set<String> existing = Set.of(
                VariantMatrixGenerator.combinationKey(List.of(gold.getId())));

        var result = generator.generate(selection, "watch", existing, "ar");

        assertThat(result.existingCount()).isEqualTo(1);
        assertThat(result.combinations())
                .filteredOn(c -> c.attributeValueIds().contains(gold.getId()))
                .allMatch(c -> c.alreadyExists());
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("A runaway selection is capped, with a warning")
    void capsLargeMatrices() {
        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        for (long attr = 1; attr <= 4; attr++) {
            selection.put(attr, List.of(
                    value("V1_" + attr, "ق1"), value("V2_" + attr, "ق2"),
                    value("V3_" + attr, "ق3"), value("V4_" + attr, "ق4"),
                    value("V5_" + attr, "ق5")));
        }

        // 5^4 = 625 combinations
        var result = generator.generate(selection, "watch", Set.of(), "ar");

        assertThat(result.combinations())
                .hasSizeLessThanOrEqualTo(VariantMatrixGenerator.MAX_COMBINATIONS);
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("combinations"));
    }

    @Test
    @DisplayName("The suggested SKU is readable and derived from the value codes")
    void buildsReadableSku() {
        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        selection.put(1L, List.of(value("GOLD", "ذهبي")));

        var result = generator.generate(selection, "classic-gold-watch", Set.of(), "ar");
        String sku = result.combinations().get(0).suggestedSku();

        assertThat(sku).startsWith("RYL-");
        assertThat(sku).contains("GOLD");
        assertThat(sku).matches("[A-Z0-9-]+");
        assertThat(sku.length()).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("The Arabic summary joins the value names")
    void buildsArabicSummary() {
        Map<Long, List<AttributeValue>> selection = new LinkedHashMap<>();
        selection.put(1L, List.of(value("GOLD", "ذهبي")));
        selection.put(2L, List.of(value("42MM", "42 مم")));

        var result = generator.generate(selection, "watch", Set.of(), "ar");
        assertThat(result.combinations().get(0).summary()).isEqualTo("ذهبي / 42 مم");
    }

    @Test
    @DisplayName("The combination key ignores the order the ids arrive in")
    void combinationKeyIsOrderIndependent() {
        assertThat(VariantMatrixGenerator.combinationKey(List.of(3L, 1L, 2L)))
                .isEqualTo(VariantMatrixGenerator.combinationKey(List.of(1L, 2L, 3L)));
    }

    @Test
    void emptySelectionProducesNothing() {
        var result = generator.generate(new LinkedHashMap<>(), "watch", Set.of(), "ar");
        assertThat(result.combinations()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private AttributeValue value(String code, String arabicName) {
        Attribute attribute = new Attribute();
        attribute.setId(idSeq);
        attribute.setCode("ATTR");
        attribute.setVariantDefining(true);

        AttributeValue value = new AttributeValue();
        value.setId(idSeq++);
        value.setCode(code);
        value.setAttribute(attribute);

        AttributeValueTranslation translation = new AttributeValueTranslation();
        AttributeValueTranslation.Key key = new AttributeValueTranslation.Key();
        key.setAttributeValueId(value.getId());
        key.setLocale("ar");
        translation.setKey(key);
        translation.setName(arabicName);
        value.getTranslations().put("ar", translation);

        return value;
    }
}
