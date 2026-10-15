package com.velora.api.order.service;

import com.velora.api.common.config.BrandingProperties;
import com.velora.api.order.repository.OrderRepository;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds order numbers of the form {@code RYL-260809-4821} (prefix from {@code velora.branding}).
 *
 * <p>Date plus four random digits, deliberately NOT a running sequence.
 *
 * <p>A sequential number publishes your volume: anyone who places two orders a week
 * apart can read how many you sold in between, and so can a competitor. The random
 * suffix also means an order number cannot be guessed, which matters because the
 * customer-facing lookup accepts one.
 *
 * <p>This is separate from the INVOICE number, which must be gapless and sequential
 * for accounting. Two different requirements, two different generators.
 */
@Component
public class OrderNumberGenerator {

    private static final Logger log = LoggerFactory.getLogger(OrderNumberGenerator.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final int SUFFIX_BOUND = 10_000;
    private static final int MAX_ATTEMPTS = 20;

    private final OrderRepository orderRepository;
    private final SecureRandom random = new SecureRandom();

    private final BrandingProperties branding;

    public OrderNumberGenerator(OrderRepository orderRepository, BrandingProperties branding) {
        this.orderRepository = orderRepository;
        this.branding = branding;
    }

    public String generate() {
        String prefix = branding.getOrderNumberPrefix();
        String datePart = LocalDate.now().format(DATE);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = "%s-%s-%04d".formatted(
                    prefix, datePart, random.nextInt(SUFFIX_BOUND));

            // 10,000 suffixes per day is plenty at this volume; a collision just
            // means trying again. The unique index is the real guarantee.
            if (!orderRepository.existsByOrderNumber(candidate)) {
                return candidate;
            }
            log.debug("Order number collision on {}, retrying", candidate);
        }

        // Past 20 collisions the day's space is genuinely crowded. Widen it rather
        // than fail the customer's order.
        String fallback = "%s-%s-%06d".formatted(
                prefix, datePart, random.nextInt(1_000_000));
        log.warn("Order number space crowded for {}; widened the suffix", datePart);
        return fallback;
    }
}
