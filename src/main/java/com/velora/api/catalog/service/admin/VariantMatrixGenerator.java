package com.velora.api.catalog.service.admin;

import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.dto.admin.VariantMatrixPreviewResponse;
import com.velora.api.common.config.BrandingProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds every combination of the selected attribute values — the cartesian product.
 *
 * <p>Two colours and three sizes give six variants. Four attributes with five values
 * each give 625, and almost none of them will ever be stocked. That is why this
 * class produces a PREVIEW: the admin deletes what they are not carrying, and only
 * then does anything get saved.
 *
 * <p>Pure logic, no database and no Spring dependencies, so it is trivially testable.
 */
@Component
public class VariantMatrixGenerator {

    /** Above this the preview is unusable and the admin has picked the wrong tool. */
    public static final int MAX_COMBINATIONS = 200;

    private final BrandingProperties branding;

    public VariantMatrixGenerator(BrandingProperties branding) {
        this.branding = branding;
    }

    /**
     * @param valuesByAttribute selected values, keyed by attribute id, in display order
     * @param productSlug       used to build a readable SKU suggestion
     * @param existingKeys      combinations already saved, so they can be flagged
     */
    public VariantMatrixPreviewResponse generate(
            Map<Long, List<AttributeValue>> valuesByAttribute,
            String productSlug,
            Set<String> existingKeys,
            String locale) {

        List<String> warnings = new ArrayList<>();

        long total = 1;
        for (List<AttributeValue> values : valuesByAttribute.values()) {
            total *= values.size();
            if (total > MAX_COMBINATIONS * 10L) {
                break;   // stop multiplying, the number is already meaningless
            }
        }

        List<List<AttributeValue>> product = cartesian(
                new ArrayList<>(valuesByAttribute.values()));

        if (product.size() > MAX_COMBINATIONS) {
            warnings.add(("The selection produces %d combinations. Showing the first %d — "
                    + "select fewer values, or add the rest individually.")
                    .formatted(product.size(), MAX_COMBINATIONS));
            product = product.subList(0, MAX_COMBINATIONS);
        }

        String skuPrefix = skuPrefix(productSlug);
        List<VariantMatrixPreviewResponse.Combination> combinations = new ArrayList<>();
        int existingCount = 0;

        for (List<AttributeValue> combination : product) {
            List<Long> valueIds = combination.stream().map(AttributeValue::getId).toList();
            List<String> valueNames = combination.stream()
                    .map(v -> v.nameFor(locale))
                    .toList();

            boolean exists = existingKeys.contains(combinationKey(valueIds));
            if (exists) {
                existingCount++;
            }

            combinations.add(new VariantMatrixPreviewResponse.Combination(
                    buildSku(skuPrefix, combination),
                    String.join(" / ", valueNames),
                    valueIds,
                    valueNames,
                    exists));
        }

        if (existingCount > 0) {
            warnings.add("%d combination(s) already exist and will be skipped on save."
                    .formatted(existingCount));
        }

        return new VariantMatrixPreviewResponse(
                combinations.size(), existingCount, combinations, warnings);
    }

    /**
     * A stable identity for a combination, independent of the order the value ids
     * arrive in. Used to detect duplicates.
     */
    public static String combinationKey(List<Long> attributeValueIds) {
        return attributeValueIds.stream()
                .sorted()
                .map(String::valueOf)
                .reduce((a, b) -> a + "-" + b)
                .orElse("");
    }

    // ------------------------------------------------------------------ internal

    /**
     * Iterative cartesian product. Recursion would blow the stack on a pathological
     * selection, and this is just as readable.
     */
    private List<List<AttributeValue>> cartesian(List<List<AttributeValue>> lists) {
        List<List<AttributeValue>> result = new ArrayList<>();
        if (lists.isEmpty()) {
            return result;
        }
        result.add(new ArrayList<>());

        for (List<AttributeValue> values : lists) {
            List<List<AttributeValue>> next = new ArrayList<>();
            for (List<AttributeValue> partial : result) {
                for (AttributeValue value : values) {
                    List<AttributeValue> extended = new ArrayList<>(partial);
                    extended.add(value);
                    next.add(extended);
                }
            }
            result = next;
            if (result.size() > MAX_COMBINATIONS * 10) {
                break;
            }
        }
        return result;
    }

    /** {@code classic-gold-watch} -> {@code <sku-prefix>-CLASSICGOLD} */
    private String skuPrefix(String productSlug) {
        if (productSlug == null || productSlug.isBlank()) {
            return branding.getSkuPrefix();
        }
        String compact = productSlug.replaceAll("[^a-zA-Z0-9]", "").toUpperCase(Locale.ENGLISH);
        return branding.getSkuPrefix() + "-" + compact.substring(0, Math.min(10, compact.length()));
    }

    /** {@code <sku-prefix>-CLASSICGOLD-GOLD-42MM} */
    private String buildSku(String prefix, List<AttributeValue> combination) {
        StringBuilder sb = new StringBuilder(prefix);
        for (AttributeValue value : combination) {
            String code = value.getCode().replaceAll("[^a-zA-Z0-9]", "")
                    .toUpperCase(Locale.ENGLISH);
            sb.append('-').append(code, 0, Math.min(8, code.length()));
        }
        return sb.length() > 60 ? sb.substring(0, 60) : sb.toString();
    }

    /** Keeps the caller's map ordering (attribute display order) explicit. */
    public static Map<Long, List<AttributeValue>> orderedMap() {
        return new LinkedHashMap<>();
    }
}
