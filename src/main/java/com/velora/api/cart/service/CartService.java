package com.velora.api.cart.service;

import com.velora.api.cart.domain.Cart;
import com.velora.api.cart.domain.CartItem;
import com.velora.api.cart.domain.CartStatus;
import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.dto.CartItemResponse;
import com.velora.api.cart.dto.CartResponse;
import com.velora.api.cart.dto.CartWarning;
import com.velora.api.cart.repository.CartItemRepository;
import com.velora.api.cart.repository.CartRepository;
import com.velora.api.cart.security.GuestTokenService;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cart.
 *
 * <p>Core rule: <b>the cart is a proposal, never a promise.</b> Every read
 * recalculates from the current variant prices and current stock, and reports what
 * changed. Nothing here holds inventory — that happens at checkout, in
 * {@code ReservationService}.
 *
 * <p>A cart belongs to a signed-in user OR to an anonymous guest token. Guest
 * checkout is supported, so both paths are first-class.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    private static final int MAX_LINES = 50;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final AppUserRepository userRepository;
    private final GuestTokenService guestTokenService;
    private final StorageService storageService;

    public CartService(CartRepository cartRepository,
                       CartItemRepository cartItemRepository,
                       ProductVariantRepository variantRepository,
                       AppUserRepository userRepository,
                       GuestTokenService guestTokenService,
                       StorageService storageService) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.userRepository = userRepository;
        this.guestTokenService = guestTokenService;
        this.storageService = storageService;
    }

    // --------------------------------------------------------------------- read

    @Transactional
    public CartResponse getCart(Long userId, String guestToken, String locale) {
        Cart cart = findOrCreate(userId, guestToken);
        return toResponse(cart, locale);
    }

    // ---------------------------------------------------------------- mutations

    @Transactional
    public CartResponse addItem(Long userId, String guestToken,
                                AddToCartRequest request, String locale) {
        Cart cart = findOrCreate(userId, guestToken);
        ProductVariant variant = loadSellableVariant(request.variantId());

        CartItem existing = cart.getItems().stream()
                .filter(item -> item.getVariant().getId().equals(variant.getId()))
                .findFirst()
                .orElse(null);

        int requested = request.quantity() + (existing == null ? 0 : existing.getQuantity());
        int available = variant.getAvailable();

        // Refuse rather than silently trim: the customer asked for a number and
        // deserves to know they cannot have it.
        if (requested > available) {
            throw new BusinessException(ErrorCode.STOCK_UNAVAILABLE,
                    available == 0
                            ? "This item is out of stock"
                            : "Only %d available".formatted(available));
        }

        if (existing != null) {
            existing.setQuantity(requested);
            existing.setPriceAtAdd(variant.getPrice());
        } else {
            if (cart.getItems().size() >= MAX_LINES) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A cart can hold at most %d different items".formatted(MAX_LINES));
            }
            CartItem item = new CartItem();
            item.setVariant(variant);
            item.setQuantity(request.quantity());
            item.setPriceAtAdd(variant.getPrice());
            cart.addItem(item);
        }

        cart.touch();
        cartRepository.save(cart);
        return toResponse(cart, locale);
    }

    @Transactional
    public CartResponse updateQuantity(Long userId, String guestToken,
                                       Long itemId, int quantity, String locale) {
        Cart cart = findOrCreate(userId, guestToken);
        CartItem item = findItem(cart, itemId);

        int available = item.getVariant().getAvailable();
        if (quantity > available) {
            throw new BusinessException(ErrorCode.STOCK_UNAVAILABLE,
                    "Only %d available".formatted(available));
        }

        item.setQuantity(quantity);
        cart.touch();
        cartRepository.save(cart);
        return toResponse(cart, locale);
    }

    @Transactional
    public CartResponse removeItem(Long userId, String guestToken, Long itemId, String locale) {
        Cart cart = findOrCreate(userId, guestToken);
        CartItem item = findItem(cart, itemId);
        cart.removeItem(item);
        cartRepository.save(cart);
        return toResponse(cart, locale);
    }

    @Transactional
    public CartResponse clear(Long userId, String guestToken, String locale) {
        Cart cart = findOrCreate(userId, guestToken);
        cart.getItems().clear();
        cart.setCouponCode(null);
        cart.touch();
        cartRepository.save(cart);
        return toResponse(cart, locale);
    }

    // -------------------------------------------------------------------- merge

    /**
     * Folds a guest cart into the account cart after sign-in.
     *
     * <p>Quantities are added, not replaced: someone who put two wallets in as a
     * guest and already had one saved wants three, not one. Anything that would
     * exceed available stock is capped, and the cap is reported.
     */
    @Transactional
    public CartResponse mergeGuestCart(Long userId, String guestToken, String locale) {
        // Resolved separately from findOrCreate() — verified here for the same reason.
        guestTokenService.verify(guestToken);

        Cart accountCart = findOrCreate(userId, null);

        Optional<Cart> guestCartOpt =
                cartRepository.findByGuestTokenAndStatus(guestToken, CartStatus.ACTIVE);
        if (guestCartOpt.isEmpty()) {
            return toResponse(accountCart, locale);
        }

        Cart guestCart = guestCartOpt.get();
        if (guestCart.getId().equals(accountCart.getId())) {
            return toResponse(accountCart, locale);
        }

        for (CartItem guestItem : guestCart.getItems()) {
            ProductVariant variant = guestItem.getVariant();
            if (!isSellable(variant)) {
                continue;
            }

            CartItem target = accountCart.getItems().stream()
                    .filter(i -> i.getVariant().getId().equals(variant.getId()))
                    .findFirst()
                    .orElse(null);

            int combined = guestItem.getQuantity() + (target == null ? 0 : target.getQuantity());
            int capped = Math.min(combined, variant.getAvailable());
            if (capped <= 0) {
                continue;
            }

            if (target != null) {
                target.setQuantity(capped);
            } else {
                CartItem item = new CartItem();
                item.setVariant(variant);
                item.setQuantity(capped);
                item.setPriceAtAdd(guestItem.getPriceAtAdd());
                accountCart.addItem(item);
            }
        }

        guestCart.setStatus(CartStatus.MERGED);
        guestCart.touch();
        cartRepository.save(guestCart);
        cartRepository.save(accountCart);

        log.info("Merged guest cart {} into account cart {}",
                guestCart.getId(), accountCart.getId());
        return toResponse(accountCart, locale);
    }

    // ----------------------------------------------------------------- checkout

    /**
     * Loads the cart for checkout and refuses if anything blocks it.
     *
     * <p>Called by the order module immediately before reserving stock.
     */
    @Transactional
    public Cart loadForCheckout(Long userId, String guestToken) {
        Cart cart = findOrCreate(userId, guestToken);

        if (cart.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        List<CartWarning> blocking = collectWarnings(cart).stream()
                .filter(w -> !"PRICE_CHANGED".equals(w.code()))
                .toList();

        if (!blocking.isEmpty()) {
            CartWarning first = blocking.get(0);
            throw new BusinessException(ErrorCode.STOCK_UNAVAILABLE,
                    first.detail() + " (" + first.sku() + ")");
        }
        return cart;
    }

    @Transactional
    public void markConverted(Long cartId) {
        cartRepository.findById(cartId).ifPresent(cart -> {
            cart.setStatus(CartStatus.CONVERTED);
            cart.touch();
            cartRepository.save(cart);
        });
    }

    // ------------------------------------------------------------------ internal

    private Cart findOrCreate(Long userId, String guestToken) {
        if (userId != null) {
            Optional<Cart> existing =
                    cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE);
            if (existing.isPresent()) {
                return existing.get();
            }
            AppUser user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            Cart cart = new Cart();
            cart.setUser(user);
            return cartRepository.save(cart);
        }

        if (guestToken == null || guestToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Sign in, or send an X-Guest-Token header");
        }
        // Every guest-identified cart lookup or creation goes through here — this is
        // the single choke point that keeps one guest from reading or editing
        // another guest's cart by guessing or reusing their token.
        guestTokenService.verify(guestToken);

        return cartRepository.findByGuestTokenAndStatus(guestToken, CartStatus.ACTIVE)
                .orElseGet(() -> {
                    Cart cart = new Cart();
                    cart.setGuestToken(guestToken);
                    return cartRepository.save(cart);
                });
    }

    private CartItem findItem(Cart cart, Long itemId) {
        return cart.getItems().stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                // Object-level check: an item id from another customer's cart must
                // look exactly like one that does not exist.
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
    }

    private ProductVariant loadSellableVariant(Long variantId) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));

        if (!variant.getProduct().isReadyMade()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE);
        }
        if (!isSellable(variant)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_ACTIVE);
        }
        return variant;
    }

    private boolean isSellable(ProductVariant variant) {
        Product product = variant.getProduct();
        return variant.getStatus() == VariantStatus.ACTIVE
                && variant.getArchivedAt() == null
                && product.getStatus() == ProductStatus.ACTIVE
                && product.getArchivedAt() == null
                && product.isReadyMade();
    }

    /** Everything that changed since the items were added. */
    private List<CartWarning> collectWarnings(Cart cart) {
        List<CartWarning> warnings = new ArrayList<>();

        for (CartItem item : cart.getItems()) {
            ProductVariant variant = item.getVariant();

            if (!isSellable(variant)) {
                warnings.add(CartWarning.unavailable(item.getId(), variant.getSku()));
                continue;
            }

            int available = variant.getAvailable();
            if (available <= 0) {
                warnings.add(CartWarning.outOfStock(item.getId(), variant.getSku()));
            } else if (item.getQuantity() > available) {
                warnings.add(CartWarning.quantityReduced(item.getId(), variant.getSku(),
                        "Only %d of %d requested are available"
                                .formatted(available, item.getQuantity())));
            }

            if (item.priceChanged()) {
                boolean cheaper = variant.getPrice().compareTo(item.getPriceAtAdd()) < 0;
                warnings.add(CartWarning.priceChanged(item.getId(), variant.getSku(),
                        cheaper ? "The price went down since you added this"
                                : "The price went up since you added this"));
            }
        }
        return warnings;
    }

    private CartResponse toResponse(Cart cart, String locale) {
        List<CartItemResponse> items = new ArrayList<>();
        BigDecimal subtotal = MoneyUtils.ZERO;
        BigDecimal taxIncluded = MoneyUtils.ZERO;
        BigDecimal assembly = MoneyUtils.ZERO;

        for (CartItem item : cart.getItems()) {
            ProductVariant variant = item.getVariant();
            Product product = variant.getProduct();

            // Priced from the CURRENT variant price, never from priceAtAdd.
            BigDecimal unitPrice = variant.getPrice();
            BigDecimal lineTotal = MoneyUtils.lineTotal(unitPrice, item.getQuantity());

            subtotal = subtotal.add(lineTotal);
            taxIncluded = taxIncluded.add(
                    MoneyUtils.taxFromGross(lineTotal, variant.getTaxRate()));

            // Same rule the order applies: per-piece fee x quantity, taxed at the line rate.
            BigDecimal lineAssembly = MoneyUtils.lineTotal(
                    product.effectiveAssemblyFee(), item.getQuantity());
            assembly = assembly.add(lineAssembly);
            taxIncluded = taxIncluded.add(
                    MoneyUtils.taxFromGross(lineAssembly, variant.getTaxRate()));

            ProductImage image = product.mainImage();

            items.add(new CartItemResponse(
                    item.getId(),
                    variant.getId(),
                    product.getId(),
                    product.getSlug(),
                    product.nameFor(locale),
                    summaryFor(variant, locale),
                    variant.getSku(),
                    image == null ? null : storageService.urlFor(image.getUrl()),
                    unitPrice,
                    item.getPriceAtAdd(),
                    item.priceChanged(),
                    item.getQuantity(),
                    variant.getAvailable(),
                    variant.isInStock(),
                    lineTotal));
        }

        // TODO(promotion module): apply the coupon and allocate it to the lines.
        BigDecimal discount = MoneyUtils.ZERO;
        BigDecimal estimated = MoneyUtils.round(subtotal.subtract(discount).add(assembly));

        List<CartWarning> warnings = collectWarnings(cart);
        boolean ready = !cart.isEmpty()
                && warnings.stream().allMatch(w -> "PRICE_CHANGED".equals(w.code()));

        return new CartResponse(
                cart.getId(),
                items,
                items.size(),
                cart.totalQuantity(),
                MoneyUtils.round(subtotal),
                discount,
                MoneyUtils.round(assembly),
                estimated,
                MoneyUtils.round(taxIncluded),
                cart.getCouponCode(),
                warnings,
                ready);
    }

    private String summaryFor(ProductVariant variant, String locale) {
        if (variant.getAttributeValues().isEmpty()) {
            return null;
        }
        return variant.getAttributeValues().stream()
                .map(vav -> vav.getAttributeValue().nameFor(locale))
                .reduce((a, b) -> a + " / " + b)
                .orElse(null);
    }
}
