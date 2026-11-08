package com.velora.api.marketing.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What one variant costs right now: the regular price, the price the customer pays, and the discount that explains
 * the difference. Every screen and every calculation takes its price from here, so the product list, the product
 * page, the cart, the checkout and the order can never disagree.
 *
 * @param listPrice       the variant's regular, tax-inclusive price
 * @param price           what is charged: the list price less the best running promotion
 * @param percent         the promotion's percentage, null when no promotion applies
 * @param promotionId     null when no promotion applies
 * @param endsAt          when the promotion ends, null when none applies
 * @param compareAtPrice  the struck-through price to show: the list price under a promotion, otherwise the
 *                        variant's own «was» price when it is higher than the price
 * @param discountPercent the whole-number percentage to show on the badge, null when nothing is struck through
 */
public record PriceQuote(
        BigDecimal listPrice,
        BigDecimal price,
        BigDecimal percent,
        Long promotionId,
        String promotionNameAr,
        String promotionNameEn,
        OffsetDateTime endsAt,
        BigDecimal compareAtPrice,
        Integer discountPercent
) {

    public boolean onSale() {
        return promotionId != null;
    }

    public String promotionName(String locale) {
        return "en".equalsIgnoreCase(locale) && promotionNameEn != null && !promotionNameEn.isBlank()
                ? promotionNameEn : promotionNameAr;
    }
}
