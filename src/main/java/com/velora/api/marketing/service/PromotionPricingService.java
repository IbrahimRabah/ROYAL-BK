package com.velora.api.marketing.service;

import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.marketing.domain.Promotion;
import com.velora.api.marketing.domain.PromotionScope;
import com.velora.api.marketing.domain.PromotionStatus;
import com.velora.api.marketing.repository.PromotionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The one place that decides what a variant costs.
 *
 * <p>The variant's regular price is never rewritten. A running promotion lowers the price at the moment it is
 * read, so a promotion that starts or ends changes prices by itself, on time, with no job and nothing to undo.
 *
 * <p>Rules, all pinned by tests:
 * <ul>
 *   <li>Only an ACTIVE promotion inside {@code [startsAt, endsAt)} applies.</li>
 *   <li>The customer gets the single BIGGEST discount; discounts never stack. On a tie the more specific
 *       one wins (product, then category, then all products), then the older one.</li>
 *   <li>The discount is a percentage of the TAX-INCLUSIVE price, rounded to the piastre, and subtracted. Tax is
 *       still extracted from the resulting gross price, per line, by {@link MoneyUtils}.</li>
 *   <li>A category promotion covers the category and every category below it.</li>
 * </ul>
 *
 * <p>The active promotions are a handful of rows, so they are kept in memory for a few seconds instead of being
 * queried for every product of a list. Staff edits clear that copy at once; the checkout never uses it
 * ({@link #quoteFresh}), so an order is always priced from the database as it is at that moment.
 */
@Service
public class PromotionPricingService {

    private static final Duration TTL = Duration.ofSeconds(15);

    /** A promotion as the pricing needs it, with the categories it reaches already worked out. */
    record ActivePromotion(Long id, String nameAr, String nameEn, PromotionScope scope, Long targetId,
                           BigDecimal percent, OffsetDateTime startsAt, OffsetDateTime endsAt, Set<Long> categoryIds) {

        boolean runningAt(OffsetDateTime now) {
            return !now.isBefore(startsAt) && now.isBefore(endsAt);
        }

        boolean covers(long productId, Long categoryId) {
            return switch (scope) {
                case ALL -> true;
                case PRODUCT -> targetId != null && targetId == productId;
                case CATEGORY -> categoryId != null && categoryIds.contains(categoryId);
            };
        }

        /** Product beats category beats all, when two discounts are equal. */
        int specificity() {
            return switch (scope) {
                case PRODUCT -> 3;
                case CATEGORY -> 2;
                case ALL -> 1;
            };
        }
    }

    private record Snapshot(Instant loadedAt, List<ActivePromotion> promotions) {
    }

    private final Supplier<List<ActivePromotion>> loader;
    private final Clock clock;
    private volatile Snapshot cached;

    @Autowired
    public PromotionPricingService(PromotionRepository repository, JdbcTemplate jdbc) {
        this(() -> load(repository, jdbc), Clock.systemUTC());
    }

    PromotionPricingService(Supplier<List<ActivePromotion>> loader, Clock clock) {
        this.loader = loader;
        this.clock = clock;
    }

    /** A pricing service for tests and tools that have no database: nothing is ever discounted. */
    public static PromotionPricingService noPromotions() {
        return new PromotionPricingService(List::of, Clock.systemUTC());
    }

    // --------------------------------------------------------------------- quotes

    public PriceQuote quote(ProductVariant variant) {
        return quote(variant, snapshot(false), now());
    }

    /** Priced from the database as it is now, never from the in-memory copy: what the checkout uses. */
    public PriceQuote quoteFresh(ProductVariant variant) {
        return quote(variant, snapshot(true), now());
    }

    public Map<Long, PriceQuote> quoteAll(Collection<ProductVariant> variants) {
        List<ActivePromotion> promotions = snapshot(false);
        OffsetDateTime now = now();
        Map<Long, PriceQuote> out = new LinkedHashMap<>();
        for (ProductVariant v : variants) {
            out.put(v.getId(), quote(v, promotions, now));
        }
        return out;
    }

    /** Forgets the copy of the promotions: called after every staff change so it shows at once. */
    public void invalidate() {
        cached = null;
    }

    PriceQuote quote(ProductVariant variant, List<ActivePromotion> promotions, OffsetDateTime now) {
        BigDecimal list = variant.getPrice();
        long productId = variant.getProduct().getId();
        Long categoryId = variant.getProduct().getCategory() == null ? null : variant.getProduct().getCategory().getId();

        Optional<ActivePromotion> best = list.signum() <= 0 ? Optional.empty() : promotions.stream()
                .filter(p -> p.runningAt(now) && p.covers(productId, categoryId))
                .max(Comparator.comparing(ActivePromotion::percent)
                        .thenComparingInt(ActivePromotion::specificity)
                        .thenComparing(p -> -p.id()));

        if (best.isEmpty()) {
            BigDecimal wasPrice = variant.hasDiscount() ? variant.getCompareAtPrice() : null;
            return new PriceQuote(list, list, null, null, null, null, null, wasPrice, variant.discountPercent());
        }
        ActivePromotion p = best.get();
        BigDecimal price = discounted(list, p.percent());
        return new PriceQuote(list, price, p.percent(), p.id(), p.nameAr(), p.nameEn(), p.endsAt(),
                list, wholePercentOff(list, price));
    }

    // ----------------------------------------------------------------------- math

    /**
     * {@code list} less {@code percent} percent of it, with the discount rounded to the piastre. The price stays
     * on the storage scale like every other amount.
     */
    public static BigDecimal discounted(BigDecimal list, BigDecimal percent) {
        BigDecimal discount = MoneyUtils.percentOfForDisplay(list, percent);
        return MoneyUtils.round(list.subtract(discount));
    }

    /** How much of the list price the discount takes off, as a whole number for a badge. */
    static Integer wholePercentOff(BigDecimal list, BigDecimal price) {
        if (list.signum() <= 0 || price.compareTo(list) >= 0) {
            return null;
        }
        return list.subtract(price).multiply(BigDecimal.valueOf(100)).divide(list, 0, RoundingMode.HALF_UP).intValue();
    }

    // ------------------------------------------------------------------- internals

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private List<ActivePromotion> snapshot(boolean fresh) {
        Snapshot current = cached;
        Instant now = clock.instant();
        if (!fresh && current != null && Duration.between(current.loadedAt(), now).compareTo(TTL) < 0) {
            return current.promotions();
        }
        Snapshot loaded = new Snapshot(now, loader.get());
        cached = loaded;
        return loaded.promotions();
    }

    private static List<ActivePromotion> load(PromotionRepository repository, JdbcTemplate jdbc) {
        List<Promotion> active = repository.findByStatus(PromotionStatus.ACTIVE);
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> children = new HashMap<>();
        jdbc.query("SELECT id, parent_id FROM category", rs -> {
            long parent = rs.getLong(2);
            if (!rs.wasNull()) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(rs.getLong(1));
            }
        });
        List<ActivePromotion> out = new ArrayList<>();
        for (Promotion p : active) {
            Set<Long> categories = p.getScope() == PromotionScope.CATEGORY
                    ? descendantsOf(p.getTargetId(), children) : Set.of();
            out.add(new ActivePromotion(p.getId(), p.getNameAr(), p.getNameEn(), p.getScope(), p.getTargetId(),
                    p.getPercent(), p.getStartsAt(), p.getEndsAt(), categories));
        }
        return out;
    }

    /** The category itself and everything below it, at any depth. */
    public static Set<Long> descendantsOf(Long root, Map<Long, List<Long>> children) {
        Set<Long> seen = new HashSet<>();
        List<Long> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Long next = queue.remove(queue.size() - 1);
            if (seen.add(next)) {
                queue.addAll(children.getOrDefault(next, List.of()));
            }
        }
        return seen;
    }
}
