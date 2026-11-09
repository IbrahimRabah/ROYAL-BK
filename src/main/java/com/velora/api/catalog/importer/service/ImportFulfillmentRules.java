package com.velora.api.catalog.importer.service;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.common.util.ArabicNormalizer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How an imported row is sold, and its shipping size, when the sheet does not say.
 *
 * <ul>
 *   <li>A type or size written in the sheet is always used as it is.</li>
 *   <li>A row with a price and no type is {@code READY_MADE}: the customer can buy it from the cart.
 *       A row with no price is {@code MADE_TO_ORDER}.</li>
 *   <li>A missing size is read from the product's dimensions: the dimension specifications of the sheet
 *       first, else the "40×40×70" written in the name. The longest side decides: under
 *       {@value #MEDIUM_FROM_CM} cm small, up to {@value #LARGE_ABOVE_CM} cm medium, above that large.
 *       At least two sides are needed; one number alone could be a height and nothing more.</li>
 *   <li>Nothing is guessed. When no size can be found, a row that would have been ready-made stays
 *       {@code MADE_TO_ORDER} and is flagged ({@link Decision#sizeUnknown()}) for a person to size: a
 *       ready-made product with no size cannot be checked out, because delivery is priced by size.</li>
 * </ul>
 */
public final class ImportFulfillmentRules {

    /** From this longest side (cm) a product is medium. */
    public static final int MEDIUM_FROM_CM = 90;
    /** Above this longest side (cm) a product is large. */
    public static final int LARGE_ABOVE_CM = 160;

    private static final Pattern DIMENSIONS = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*[×xX*]\\s*(\\d+(?:[.,]\\d+)?)(?:\\s*[×xX*]\\s*(\\d+(?:[.,]\\d+)?))?");

    private static final List<String> DIMENSION_SPECS = List.of("WIDTH_CM", "HEIGHT_CM", "DEPTH_CM");

    private ImportFulfillmentRules() {
    }

    /**
     * @param type        how the product is sold
     * @param size        its shipping size, null when none was given and none could be found
     * @param sizeUnknown true when the row would have been ready-made but no size could be found, so it was
     *                    kept as made-to-order for review
     */
    public record Decision(FulfillmentType type, ShippingSizeClass size, boolean sizeUnknown) {
    }

    public static Decision decide(FulfillmentType explicitType, ShippingSizeClass explicitSize, boolean priced,
                                  String nameAr, Map<String, String> specs) {
        if (explicitType != null && explicitType != FulfillmentType.READY_MADE) {
            return new Decision(explicitType, explicitSize, false);
        }
        if (explicitType == null && !priced) {
            return new Decision(FulfillmentType.MADE_TO_ORDER, explicitSize, false);
        }

        ShippingSizeClass size = explicitSize != null ? explicitSize : sizeFromSpecs(specs);
        if (size == null) {
            size = sizeFromName(nameAr);
        }
        if (explicitType == FulfillmentType.READY_MADE) {
            return new Decision(FulfillmentType.READY_MADE, size, false);
        }
        return size == null
                ? new Decision(FulfillmentType.MADE_TO_ORDER, null, true)
                : new Decision(FulfillmentType.READY_MADE, size, false);
    }

    static ShippingSizeClass sizeFromSpecs(Map<String, String> specs) {
        List<BigDecimal> sides = new ArrayList<>();
        for (String code : DIMENSION_SPECS) {
            BigDecimal side = number(specs == null ? null : specs.get(code));
            if (side != null) {
                sides.add(side);
            }
        }
        return classify(sides);
    }

    public static ShippingSizeClass sizeFromName(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = DIMENSIONS.matcher(ArabicNormalizer.convertArabicDigits(name));
        if (!m.find()) {
            return null;
        }
        List<BigDecimal> sides = new ArrayList<>();
        for (int i = 1; i <= m.groupCount(); i++) {
            BigDecimal side = number(m.group(i));
            if (side != null) {
                sides.add(side);
            }
        }
        return classify(sides);
    }

    private static ShippingSizeClass classify(List<BigDecimal> sides) {
        if (sides.size() < 2) {
            return null;
        }
        BigDecimal longest = sides.stream().max(BigDecimal::compareTo).orElseThrow();
        if (longest.signum() <= 0) {
            return null;
        }
        if (longest.compareTo(BigDecimal.valueOf(MEDIUM_FROM_CM)) < 0) {
            return ShippingSizeClass.SMALL;
        }
        return longest.compareTo(BigDecimal.valueOf(LARGE_ABOVE_CM)) <= 0
                ? ShippingSizeClass.MEDIUM : ShippingSizeClass.LARGE;
    }

    private static BigDecimal number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(ArabicNormalizer.convertArabicDigits(text).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
