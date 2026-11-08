package com.velora.api.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * All money arithmetic for VELORA.
 *
 * <p>VELORA prices are <b>TAX-INCLUSIVE</b>: the price shown to the customer is the
 * final price. Tax is therefore <b>extracted</b> from the gross amount, never added
 * on top of it.
 *
 * <pre>
 *   net = gross / (1 + taxRate)
 *   tax = gross - net
 * </pre>
 *
 * <p>Always compute tax <b>per order line, then sum</b>. Computing it on the order
 * total makes the invoice disagree with its own lines by a piastre or two.
 */
public final class MoneyUtils {

    private MoneyUtils() {
        // utility class
    }

    /** Storage scale — matches DECIMAL(19,4) in the database. */
    public static final int SCALE = 4;

    /** Scale for amounts presented to a customer or printed on an invoice. */
    public static final int DISPLAY_SCALE = 2;

    public static final RoundingMode MODE = RoundingMode.HALF_UP;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, MODE);

    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, MODE);
    }

    public static BigDecimal roundForDisplay(BigDecimal value) {
        return value == null ? null : value.setScale(DISPLAY_SCALE, MODE);
    }

    public static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    // ---------------------------------------------------------------- tax

    /**
     * Extracts the net (pre-tax) portion of a tax-inclusive gross amount.
     *
     * @param gross   tax-inclusive amount
     * @param taxRate e.g. 0.1400 for 14%
     */
    public static BigDecimal netFromGross(BigDecimal gross, BigDecimal taxRate) {
        requireNonNegative(gross, "gross");
        requireNonNegative(taxRate, "taxRate");
        BigDecimal divisor = BigDecimal.ONE.add(taxRate);
        return gross.divide(divisor, SCALE, MODE);
    }

    /** The tax contained inside a tax-inclusive gross amount. */
    public static BigDecimal taxFromGross(BigDecimal gross, BigDecimal taxRate) {
        return round(gross.subtract(netFromGross(gross, taxRate)));
    }

    /**
     * Adds tax to a net amount. Only for suppliers or imports quoted excluding tax —
     * never for VELORA storefront pricing.
     */
    public static BigDecimal grossFromNet(BigDecimal net, BigDecimal taxRate) {
        requireNonNegative(net, "net");
        requireNonNegative(taxRate, "taxRate");
        return round(net.multiply(BigDecimal.ONE.add(taxRate)));
    }

    // ----------------------------------------------------------- line math

    /** unitPrice * quantity, rounded to storage scale. */
    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        requireNonNegative(unitPrice, "unitPrice");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
        return round(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        requireNonNegative(amount, "amount");
        requireNonNegative(percent, "percent");
        return round(amount.multiply(percent).divide(BigDecimal.valueOf(100), SCALE, MODE));
    }

    /**
     * {@code percent} percent of {@code amount}, rounded ONCE, to the piastre, from the exact value. Rounding to the
     * storage scale first and to the piastre after would round twice and could differ by a piastre from the same
     * calculation done in SQL (see {@code dbo.fn_best_discount_percent}).
     */
    public static BigDecimal percentOfForDisplay(BigDecimal amount, BigDecimal percent) {
        requireNonNegative(amount, "amount");
        requireNonNegative(percent, "percent");
        return amount.multiply(percent).divide(BigDecimal.valueOf(100), DISPLAY_SCALE, MODE);
    }

    // ------------------------------------------------------------ allocation

    /**
     * Distributes a cart-level discount across order lines in proportion to their
     * weights (normally the line totals).
     *
     * <p>This must be done <b>at order creation</b> and stored on
     * {@code order_item.allocated_cart_discount}. Without it, a partial return has
     * no correct refund amount: if a 3-item order carried a 20% cart coupon and the
     * customer returns one item, that item's refundable value is not its list price.
     *
     * <p>The final element absorbs any rounding remainder, so the returned list always
     * sums to exactly {@code total}.
     *
     * @return one amount per weight, in the same order; empty list if weights is empty
     */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        List<BigDecimal> result = new ArrayList<>();
        if (weights == null || weights.isEmpty()) {
            return result;
        }

        BigDecimal amount = nullSafe(total);
        BigDecimal weightSum = ZERO;
        for (BigDecimal w : weights) {
            weightSum = weightSum.add(nullSafe(w));
        }

        // Nothing to divide by — give everything to the first line rather than losing it.
        if (weightSum.signum() == 0) {
            result.add(round(amount));
            for (int i = 1; i < weights.size(); i++) {
                result.add(ZERO);
            }
            return result;
        }

        BigDecimal running = ZERO;
        for (int i = 0; i < weights.size() - 1; i++) {
            BigDecimal share = amount
                    .multiply(nullSafe(weights.get(i)))
                    .divide(weightSum, SCALE, MODE);
            result.add(share);
            running = running.add(share);
        }
        result.add(round(amount.subtract(running)));
        return result;
    }

    // ---------------------------------------------------------------- guard

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
