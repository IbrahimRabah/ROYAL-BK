package com.velora.api.shipping.service;

import com.velora.api.cart.domain.Cart;
import com.velora.api.cart.domain.CartItem;
import com.velora.api.cart.domain.CartStatus;
import com.velora.api.cart.repository.CartRepository;
import com.velora.api.cart.security.GuestTokenService;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.domain.ShippingRate;
import com.velora.api.shipping.dto.GovernorateResponse;
import com.velora.api.shipping.dto.ShippingQuoteResponse;
import com.velora.api.shipping.dto.SizeRateResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.repository.ShippingRateRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shipping quotes and the governorate list.
 *
 * <p>Everything is driven by the governorate. Postal codes are not usable in this
 * market, so the governorate is both what the customer picks and what determines
 * the price.
 */
@Service
@Transactional(readOnly = true)
public class ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);

    private final GovernorateRepository governorateRepository;
    private final ShippingRateRepository rateRepository;
    private final CartRepository cartRepository;
    private final ShippingCalculator calculator;
    private final GuestTokenService guestTokenService;

    public ShippingService(GovernorateRepository governorateRepository,
                           ShippingRateRepository rateRepository,
                           CartRepository cartRepository,
                           ShippingCalculator calculator,
                           GuestTokenService guestTokenService) {
        this.governorateRepository = governorateRepository;
        this.rateRepository = rateRepository;
        this.cartRepository = cartRepository;
        this.calculator = calculator;
        this.guestTokenService = guestTokenService;
    }

    /**
     * All governorates with their rate, for the address form and the shipping
     * estimator.
     *
     * <p>A governorate with no configured rate is returned with {@code served =
     * false} rather than hidden — the customer should see their governorate and be
     * told we do not deliver there, not silently fail to find it in the list.
     */
    public List<GovernorateResponse> listGovernorates(String locale) {
        List<Governorate> governorates =
                governorateRepository.findByActiveTrueOrderByDisplayOrderAsc();

        List<GovernorateResponse> result = new ArrayList<>();
        for (Governorate governorate : governorates) {
            List<ShippingRate> found = rateRepository.findAllForGovernorate(governorate.getId());
            if (found.isEmpty()) {
                result.add(new GovernorateResponse(governorate.getId(), governorate.getCode(),
                        governorate.nameFor(locale), null, List.of(), null, null, null, false));
                continue;
            }

            ZoneRates rates = new ZoneRates(found);
            List<SizeRateResponse> sizeRates = found.stream()
                    .map(r -> new SizeRateResponse(r.getSizeClass(), MoneyUtils.round(r.getBaseCost())))
                    .toList();

            result.add(new GovernorateResponse(
                    governorate.getId(),
                    governorate.getCode(),
                    governorate.nameFor(locale),
                    rates.zone().nameFor(locale),
                    sizeRates,
                    rates.maxShippingCost(),
                    (int) rates.deliveryDaysMin(),
                    (int) rates.deliveryDaysMax(),
                    true));
        }
        return result;
    }

    /**
     * What delivery will cost for this cart to this governorate.
     *
     * <p>Called from the cart page estimator and again at checkout. Cheap enough to
     * call on every governorate change.
     */
    public ShippingQuoteResponse quote(Long governorateId, Long userId, String guestToken,
                                       Long explicitCartId, boolean codApplies,
                                       String locale) {

        Governorate governorate = governorateRepository.findById(governorateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Governorate not found"));

        List<ShippingRate> found = rateRepository.findAllForGovernorate(governorateId);
        if (found.isEmpty()) {
            log.warn("No shipping rate configured for governorate {} ({})",
                    governorateId, governorate.getCode());
            throw new BusinessException(ErrorCode.GOVERNORATE_NOT_SERVED,
                    "We do not deliver to %s yet".formatted(governorate.nameFor(locale)));
        }
        ZoneRates rates = new ZoneRates(found);

        Cart cart = resolveCart(userId, guestToken, explicitCartId);
        BigDecimal subtotal = subtotalOf(cart);
        int weight = weightOf(cart);

        var calculation = calculator.calculate(rates, shippingLinesOf(cart), codApplies);

        return new ShippingQuoteResponse(
                governorate.getId(),
                governorate.nameFor(locale),
                rates.zone().nameFor(locale),
                calculation.shippingCost(),
                calculation.capApplied(),
                calculation.uncappedCost(),
                calculation.breakdown(),
                calculation.codFee(),
                calculation.freeShippingApplied(),
                null,
                null,
                rates.deliveryDaysMin(),
                rates.deliveryDaysMax(),
                subtotal,
                weight,
                MoneyUtils.round(subtotal.add(calculation.totalDeliveryCharge())));
    }

    /**
     * Whether we deliver to a governorate right now: it is in an active zone that has rates.
     * The same test the quote, checkout and address form apply.
     */
    public boolean isServed(Long governorateId) {
        return !rateRepository.findAllForGovernorate(governorateId).isEmpty();
    }

    /**
     * The rate for an order being created. Throws rather than returning empty,
     * because an order cannot be priced without it.
     */
    public ZoneRates requireRatesFor(Long governorateId) {
        List<ShippingRate> found = rateRepository.findAllForGovernorate(governorateId);
        if (found.isEmpty()) {
            // A governorate in no zone (or in a zone with no rates) is simply one we do
            // not deliver to — the same answer the quote and the address form give.
            // A zone that has rates but lacks one SIZE is a different problem, and
            // ShippingCalculator reports that as SHIPPING_RATE_NOT_CONFIGURED.
            throw new BusinessException(ErrorCode.GOVERNORATE_NOT_SERVED);
        }
        return new ZoneRates(found);
    }

    // ------------------------------------------------------------------ internal

    private Cart resolveCart(Long userId, String guestToken, Long explicitCartId) {
        if (explicitCartId != null) {
            return cartRepository.findByIdAndStatus(explicitCartId, CartStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY));
        }
        if (userId != null) {
            return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY));
        }
        if (guestToken != null && !guestToken.isBlank()) {
            // Resolves a cart straight from the guest token, the same as
            // CartService.findOrCreate() — same verification for the same reason: a
            // quote leaks cart subtotal and weight to anyone holding the token.
            guestTokenService.verify(guestToken);
            return cartRepository.findByGuestTokenAndStatus(guestToken, CartStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY));
        }
        throw new BusinessException(ErrorCode.CART_EMPTY,
                "Sign in, or send an X-Guest-Token header");
    }

    /** Each cart line's product size class and quantity — what shipping is priced from. */
    private List<ShippingCalculator.Line> shippingLinesOf(Cart cart) {
        List<ShippingCalculator.Line> lines = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            lines.add(new ShippingCalculator.Line(
                    item.getVariant().getProduct().getShippingSizeClass(),
                    item.getQuantity(),
                    item.getVariant().getSku()));
        }
        return lines;
    }

    /** Priced from the CURRENT variant price, exactly like the cart does. */
    private BigDecimal subtotalOf(Cart cart) {
        BigDecimal subtotal = MoneyUtils.ZERO;
        for (CartItem item : cart.getItems()) {
            subtotal = subtotal.add(
                    MoneyUtils.lineTotal(item.getVariant().getPrice(), item.getQuantity()));
        }
        return MoneyUtils.round(subtotal);
    }

    /**
     * Total weight, from the variants. This is why {@code weightGrams} sits on the
     * variant and not the product: a 42 mm steel watch and a 38 mm one do not weigh
     * the same, and the courier bills the difference.
     */
    private int weightOf(Cart cart) {
        int total = 0;
        for (CartItem item : cart.getItems()) {
            total += item.getVariant().getWeightGrams() * item.getQuantity();
        }
        return total;
    }
}
