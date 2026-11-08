package com.velora.api.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.marketing.domain.PromotionScope;
import com.velora.api.marketing.service.PromotionPricingService.ActivePromotion;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The money rules of a discount, worked out by hand. No database: the pricing takes the promotions it is given and
 * the clock it is given, so every boundary can be pinned.
 */
class PromotionPricingServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime STARTED = NOW.minusDays(1);
    private static final OffsetDateTime ENDS = NOW.plusDays(1);

    // category tree:  1 (furniture) -> 2 (chairs) -> 3 (bar stools);  9 is elsewhere
    private static final Map<Long, List<Long>> TREE = Map.of(1L, List.of(2L), 2L, List.of(3L));

    private final List<ActivePromotion> promotions = new ArrayList<>();

    private PromotionPricingService service(OffsetDateTime at) {
        return new PromotionPricingService(() -> promotions, Clock.fixed(Instant.from(at), ZoneOffset.UTC));
    }

    private static ProductVariant variant(long id, long productId, long categoryId, String price, String compareAt) {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", categoryId);
        Product product = new Product();
        ReflectionTestUtils.setField(product, "id", productId);
        product.setCategory(category);
        ProductVariant v = new ProductVariant();
        ReflectionTestUtils.setField(v, "id", id);
        v.setProduct(product);
        v.setPrice(new BigDecimal(price));
        v.setCompareAtPrice(compareAt == null ? null : new BigDecimal(compareAt));
        return v;
    }

    private void promo(long id, PromotionScope scope, Long target, String percent, OffsetDateTime from, OffsetDateTime to) {
        Set<Long> categories = scope == PromotionScope.CATEGORY ? PromotionPricingService.descendantsOf(target, TREE) : Set.of();
        promotions.add(new ActivePromotion(id, "خصم " + id, "Sale " + id, scope, target, new BigDecimal(percent), from, to,
                categories));
    }

    @Test
    @DisplayName("No promotion: the price is the list price, and the variant's own «was» price still shows")
    void noPromotion() {
        PriceQuote plain = service(NOW).quote(variant(1, 10, 3, "1000.0000", null));
        assertThat(plain.price()).isEqualByComparingTo("1000");
        assertThat(plain.onSale()).isFalse();
        assertThat(plain.compareAtPrice()).isNull();
        assertThat(plain.discountPercent()).isNull();

        PriceQuote marked = service(NOW).quote(variant(1, 10, 3, "800.0000", "1000.0000"));
        assertThat(marked.price()).isEqualByComparingTo("800");
        assertThat(marked.compareAtPrice()).isEqualByComparingTo("1000");
        assertThat(marked.discountPercent()).isEqualTo(20);
        assertThat(marked.onSale()).isFalse();
    }

    @Test
    @DisplayName("The discount is taken off the tax-inclusive price and rounded to the piastre: 15% of 1234.56 is 185.18")
    void roundingToThePiastre() {
        promo(1, PromotionScope.ALL, null, "15", STARTED, ENDS);
        PriceQuote q = service(NOW).quote(variant(1, 10, 3, "1234.5600", null));
        // 1234.56 x 0.15 = 185.184 -> 185.18 ; price = 1049.38
        assertThat(q.price()).isEqualByComparingTo("1049.38");
        assertThat(q.listPrice()).isEqualByComparingTo("1234.56");
        assertThat(q.compareAtPrice()).isEqualByComparingTo("1234.56");
        assertThat(q.discountPercent()).isEqualTo(15);
        // Half a piastre rounds up: 10% of 0.05 is 0.005 -> 0.01
        assertThat(PromotionPricingService.discounted(new BigDecimal("0.05"), new BigDecimal("10")))
                .isEqualByComparingTo("0.04");
    }

    @Test
    @DisplayName("The tax is still extracted from the DISCOUNTED gross, per line: 800 at 14% holds 98.2456 of tax")
    void taxFromTheDiscountedGross() {
        promo(1, PromotionScope.ALL, null, "20", STARTED, ENDS);
        PriceQuote q = service(NOW).quote(variant(1, 10, 3, "1000.0000", null));
        assertThat(q.price()).isEqualByComparingTo("800");
        assertThat(com.velora.api.common.util.MoneyUtils.taxFromGross(q.price(), new BigDecimal("0.14")))
                .isEqualByComparingTo("98.2456");
    }

    @Test
    @DisplayName("Discounts never stack: the customer gets the single biggest one")
    void biggestWins() {
        promo(1, PromotionScope.ALL, null, "10", STARTED, ENDS);
        promo(2, PromotionScope.CATEGORY, 1L, "30", STARTED, ENDS);
        promo(3, PromotionScope.PRODUCT, 10L, "25", STARTED, ENDS);

        PriceQuote q = service(NOW).quote(variant(1, 10, 3, "1000.0000", null));
        assertThat(q.promotionId()).isEqualTo(2L);
        assertThat(q.price()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("On equal percentages the more specific discount wins, then the older one")
    void tieBreak() {
        promo(5, PromotionScope.ALL, null, "20", STARTED, ENDS);
        promo(6, PromotionScope.CATEGORY, 1L, "20", STARTED, ENDS);
        promo(7, PromotionScope.PRODUCT, 10L, "20", STARTED, ENDS);
        assertThat(service(NOW).quote(variant(1, 10, 3, "1000", null)).promotionId()).isEqualTo(7L);

        promotions.clear();
        promo(9, PromotionScope.ALL, null, "20", STARTED, ENDS);
        promo(8, PromotionScope.ALL, null, "20", STARTED, ENDS);
        assertThat(service(NOW).quote(variant(1, 10, 3, "1000", null)).promotionId()).isEqualTo(8L);
    }

    @Test
    @DisplayName("A category discount reaches its subcategories at any depth, and nothing outside it")
    void categoryScope() {
        promo(1, PromotionScope.CATEGORY, 1L, "20", STARTED, ENDS);
        assertThat(service(NOW).quote(variant(1, 10, 1, "1000", null)).onSale()).isTrue();
        assertThat(service(NOW).quote(variant(1, 10, 2, "1000", null)).onSale()).isTrue();
        assertThat(service(NOW).quote(variant(1, 10, 3, "1000", null)).onSale()).isTrue();
        assertThat(service(NOW).quote(variant(1, 10, 9, "1000", null)).onSale()).isFalse();
    }

    @Test
    @DisplayName("A product discount covers every variant of that product and no other product")
    void productScope() {
        promo(1, PromotionScope.PRODUCT, 10L, "20", STARTED, ENDS);
        assertThat(service(NOW).quote(variant(1, 10, 3, "1000", null)).onSale()).isTrue();
        assertThat(service(NOW).quote(variant(2, 10, 3, "500", null)).onSale()).isTrue();
        assertThat(service(NOW).quote(variant(3, 11, 3, "1000", null)).onSale()).isFalse();
    }

    @Test
    @DisplayName("The window is [start, end): on at the first instant, off at the last")
    void windowBoundaries() {
        promo(1, PromotionScope.ALL, null, "20", NOW, NOW.plusHours(2));
        ProductVariant v = variant(1, 10, 3, "1000", null);
        assertThat(service(NOW.minusNanos(1)).quote(v).onSale()).isFalse();
        assertThat(service(NOW).quote(v).onSale()).isTrue();
        assertThat(service(NOW.plusHours(2).minusNanos(1)).quote(v).onSale()).isTrue();
        assertThat(service(NOW.plusHours(2)).quote(v).onSale()).isFalse();
    }

    @Test
    @DisplayName("A free or zero-priced variant is never discounted")
    void zeroPrice() {
        promo(1, PromotionScope.ALL, null, "20", STARTED, ENDS);
        assertThat(service(NOW).quote(variant(1, 10, 3, "0", null)).onSale()).isFalse();
    }

    @Test
    @DisplayName("The promotions are kept for a few seconds, a fresh quote reads them again, invalidate forgets them")
    void caching() {
        List<List<ActivePromotion>> loads = new ArrayList<>();
        OffsetDateTime[] clock = {NOW};
        PromotionPricingService s = new PromotionPricingService(() -> {
            List<ActivePromotion> list = new ArrayList<>(promotions);
            loads.add(list);
            return list;
        }, new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clock[0].toInstant();
            }
        });
        ProductVariant v = variant(1, 10, 3, "1000", null);

        assertThat(s.quote(v).onSale()).isFalse();
        promo(1, PromotionScope.ALL, null, "20", STARTED, ENDS);
        assertThat(s.quote(v).onSale()).isFalse();          // still the copy from a moment ago
        assertThat(s.quoteFresh(v).onSale()).isTrue();      // the checkout always reads the truth
        promotions.clear();
        assertThat(s.quote(v).onSale()).isTrue();           // the copy now holds the promotion
        s.invalidate();
        assertThat(s.quote(v).onSale()).isFalse();
        assertThat(loads).hasSize(3);
    }

    @Test
    @DisplayName("The badge is the whole-number percentage actually taken off")
    void badge() {
        assertThat(PromotionPricingService.wholePercentOff(new BigDecimal("1000"), new BigDecimal("800"))).isEqualTo(20);
        assertThat(PromotionPricingService.wholePercentOff(new BigDecimal("333"), new BigDecimal("299.70"))).isEqualTo(10);
        assertThat(PromotionPricingService.wholePercentOff(new BigDecimal("1000"), new BigDecimal("1000"))).isNull();
    }
}
