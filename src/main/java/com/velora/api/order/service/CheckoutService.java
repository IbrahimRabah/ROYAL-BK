package com.velora.api.order.service;

import com.velora.api.cart.domain.Cart;
import com.velora.api.cart.domain.CartItem;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.customer.domain.CustomerAddress;
import com.velora.api.customer.service.AddressService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.service.ReservationService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.domain.OrderItem;
import com.velora.api.order.domain.OrderStatusHistory;
import com.velora.api.order.domain.PaymentMethod;
import com.velora.api.order.domain.PaymentStatus;
import com.velora.api.order.domain.StatusKind;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.event.OrderPlacedEvent;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.repository.OrderStatusHistoryRepository;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.service.ShippingBreakdownJson;
import com.velora.api.shipping.service.ShippingCalculator;
import com.velora.api.shipping.service.ShippingService;
import com.velora.api.shipping.service.ZoneRates;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a cart into an order.
 *
 * <p>A Facade on purpose: checkout touches the cart, inventory, shipping, pricing
 * and the order itself. One orchestrator with ONE transaction boundary is far safer
 * than five services calling each other, because a failure at any step must undo
 * everything — most importantly the stock reservations.
 *
 * <p>Three rules govern this class:
 * <ol>
 *   <li><b>Reserve before writing.</b> If stock is gone, nothing else should happen.</li>
 *   <li><b>Snapshot everything.</b> Names, prices, tax rates and the address are
 *       copied, never referenced.</li>
 *   <li><b>Allocate the discount to the lines.</b> Without it, a partial return has
 *       no correct refund amount and it cannot be worked out later.</li>
 * </ol>
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final CartService cartService;
    private final ReservationService reservationService;
    private final ShippingService shippingService;
    private final ShippingCalculator shippingCalculator;
    private final ShippingBreakdownJson shippingBreakdownJson;
    private final AddressService addressService;
    private final GovernorateRepository governorateRepository;
    private final AppUserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;

    public CheckoutService(CartService cartService,
                           ReservationService reservationService,
                           ShippingService shippingService,
                           ShippingCalculator shippingCalculator,
                           ShippingBreakdownJson shippingBreakdownJson,
                           AddressService addressService,
                           GovernorateRepository governorateRepository,
                           AppUserRepository userRepository,
                           OrderRepository orderRepository,
                           OrderStatusHistoryRepository historyRepository,
                           OrderNumberGenerator orderNumberGenerator,
                           StorageService storageService,
                           ApplicationEventPublisher eventPublisher) {
        this.cartService = cartService;
        this.reservationService = reservationService;
        this.shippingService = shippingService;
        this.shippingCalculator = shippingCalculator;
        this.shippingBreakdownJson = shippingBreakdownJson;
        this.addressService = addressService;
        this.governorateRepository = governorateRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.orderNumberGenerator = orderNumberGenerator;
        this.storageService = storageService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Places an order. All of it, or none of it.
     *
     * <p>The single transaction is what makes stock safe: if anything after the
     * reservation throws, the reservation rolls back with it and the units return to
     * available. No compensation logic, no orphaned holds.
     */
    @Transactional
    public CustomerOrder placeOrder(Long userId, String guestToken,
                                    PlaceOrderRequest request, String locale) {

        // 1. Cart — refuses if empty or if anything blocks checkout.
        Cart cart = cartService.loadForCheckout(userId, guestToken);

        // 2. Address, resolved to a snapshot before anything is written.
        AddressSnapshot address = resolveAddress(userId, request);
        Governorate governorate = governorateRepository.findById(address.governorateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Governorate not found"));

        // 3. Shipping. Throws before any stock is touched if we do not deliver there.
        ZoneRates rates = shippingService.requireRatesFor(governorate.getId());

        /*
         * 4. Capture every value the order needs, BEFORE reserving.
         *
         * The reservation runs a native UPDATE with clearAutomatically = true, which
         * empties the persistence context so later reads of inventory see the new
         * numbers. The side effect is that every entity loaded so far — the cart, its
         * variants, their products — becomes detached, and any collection not yet
         * initialised can no longer be read.
         *
         * Reading the snapshot values first sidesteps that entirely, and it is closer
         * to the intent anyway: an order is built from what the customer was shown,
         * not from whatever the catalog says a moment later.
         */
        List<LineSnapshot> lines = captureLines(cart, locale);

        // 4b. Price the shipping now, still before any write: a product with no size
        //     class refuses the order here instead of after stock was reserved.
        ShippingCalculator.Calculation shipping = shippingCalculator.calculate(rates,
                lines.stream()
                        .map(l -> new ShippingCalculator.Line(
                                l.shippingSizeClass(), l.quantity(), l.sku()))
                        .toList(),
                true);

        // 5. Reserve stock. First write of the transaction, so a shortage costs
        //    nothing but a rolled-back read.
        Map<ProductVariant, Integer> quantities = new LinkedHashMap<>();
        for (LineSnapshot line : lines) {
            quantities.merge(line.variant(), line.quantity(), Integer::sum);
        }
        reservationService.reserveAll(quantities, cart.getId());

        // 6. Money.
        Totals totals = calculateTotals(lines, shipping);

        // 7. The order, with everything copied.
        AppUser customer = userId == null ? null : userRepository.findById(userId).orElse(null);

        CustomerOrder order = new CustomerOrder();
        order.setOrderNumber(orderNumberGenerator.generate());
        order.setCustomer(customer);
        order.setPaymentMethod(resolvePaymentMethod(request.paymentMethod()));
        order.setFulfillmentStatus(FulfillmentStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setLocale(locale);
        order.setCustomerNote(request.customerNote());

        applyContactAndAddress(order, address, governorate, customer);

        order.setShippingZoneName(rates.zone().nameFor(locale));
        order.setDeliveryDaysMin(rates.deliveryDaysMin());
        order.setDeliveryDaysMax(rates.deliveryDaysMax());
        // The snapshot carries HOW the cost was built, not just the figure: prices and
        // caps change, and the order must still explain its own shipping afterwards.
        order.setShippingBreakdown(shippingBreakdownJson.toJson(shipping.breakdown()));
        order.setShippingCapApplied(shipping.capApplied());
        order.setShippingUncappedCost(shipping.uncappedCost());

        order.setSubtotalGross(totals.subtotal());
        order.setDiscountTotal(totals.discount());
        order.setShippingCost(totals.shipping());
        order.setCodFee(totals.codFee());
        order.setGrandTotal(totals.grandTotal());
        order.setTaxTotal(totals.tax());
        order.setNetTotal(totals.net());

        buildItems(order, lines, totals);

        CustomerOrder saved = orderRepository.save(order);

        historyRepository.save(OrderStatusHistory.of(saved, StatusKind.FULFILLMENT,
                null, FulfillmentStatus.PENDING.name(), "Order placed", userId));

        // 8. Hand the holds to the order, and retire the cart.
        reservationService.attachToOrder(cart.getId(), saved.getId());
        cartService.markConverted(cart.getId());

        log.info("Order {} placed: {} item(s), {} {}, {} to {}",
                saved.getOrderNumber(), saved.getItems().size(),
                saved.getGrandTotal(), saved.getCurrency(),
                saved.getPaymentMethod(), saved.getShipGovernorateName());

        // AFTER_COMMIT only — OrderConfirmationListener is
        // @TransactionalEventListener(phase = AFTER_COMMIT), so the email fires
        // once this transaction actually commits, never for an order that rolls
        // back afterward.
        eventPublisher.publishEvent(new OrderPlacedEvent(saved.getId()));

        return saved;
    }

    // ------------------------------------------------------------------- money

    /**
     * Reads every value the order will freeze, while the entities are still attached.
     *
     * <p>Must run BEFORE the stock reservation — see the note in {@link #placeOrder}.
     */
    private List<LineSnapshot> captureLines(Cart cart, String locale) {
        List<LineSnapshot> lines = new ArrayList<>();

        for (CartItem item : cart.getItems()) {
            ProductVariant variant = item.getVariant();
            Product product = variant.getProduct();
            ProductImage image = product.mainImage();

            lines.add(new LineSnapshot(
                    variant,
                    product,
                    nameFor(product, "ar"),
                    nameFor(product, "en"),
                    variant.getSku(),
                    summaryFor(variant, locale),
                    image == null ? null : storageService.urlFor(image.getUrl()),
                    variant.getPrice(),
                    variant.getTaxRate(),
                    variant.getWeightGrams(),
                    product.getShippingSizeClass(),
                    item.getQuantity()));
        }
        return lines;
    }

    /**
     * All figures are TAX-INCLUSIVE, so tax is extracted rather than added.
     *
     * <p>Computed per line and then summed. Doing it on the order total instead
     * makes the invoice disagree with its own lines by a piastre or two — the kind
     * of thing an accountant notices immediately.
     */
    private Totals calculateTotals(List<LineSnapshot> lines,
                                   ShippingCalculator.Calculation shipping) {
        BigDecimal subtotal = MoneyUtils.ZERO;
        List<BigDecimal> lineTotals = new ArrayList<>();

        for (LineSnapshot line : lines) {
            BigDecimal lineTotal = MoneyUtils.lineTotal(line.unitPrice(), line.quantity());
            lineTotals.add(lineTotal);
            subtotal = subtotal.add(lineTotal);
        }
        subtotal = MoneyUtils.round(subtotal);

        // TODO(promotion module): resolve the cart's coupon into a real amount.
        // The allocation below already works — only this number is missing.
        BigDecimal discount = MoneyUtils.ZERO;

        // The share of the discount each line carries. Stored on the line, because
        // after the order exists there is no way to derive it again.
        List<BigDecimal> allocations = MoneyUtils.allocate(discount, lineTotals);

        // Tax per line, on the discounted amount, then summed.
        BigDecimal tax = MoneyUtils.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal taxableGross = lineTotals.get(i).subtract(allocations.get(i));
            tax = tax.add(MoneyUtils.taxFromGross(taxableGross, lines.get(i).taxRate()));
        }

        BigDecimal grandTotal = MoneyUtils.round(subtotal
                .subtract(discount)
                .add(shipping.shippingCost())
                .add(shipping.codFee()));

        // Shipping is treated as untaxed here. Confirm with an accountant — if it is
        // taxable, the tax comes out of the shipping charge, it is not added to it.
        BigDecimal net = MoneyUtils.round(grandTotal.subtract(tax));

        return new Totals(subtotal, discount, shipping.shippingCost(), shipping.codFee(),
                grandTotal, MoneyUtils.round(tax), net, lineTotals, allocations);
    }

    /** Copies every line from the snapshot. Nothing here reads the catalog. */
    private void buildItems(CustomerOrder order, List<LineSnapshot> lines, Totals totals) {
        for (int i = 0; i < lines.size(); i++) {
            LineSnapshot line = lines.get(i);

            BigDecimal lineTotal = totals.lineTotals().get(i);
            BigDecimal allocated = totals.allocations().get(i);
            BigDecimal taxableGross = lineTotal.subtract(allocated);

            OrderItem item = new OrderItem();
            // References, for reporting and reorder only.
            item.setVariant(line.variant());
            item.setProduct(line.product());

            // ---- the snapshot: everything below is frozen ----
            item.setProductNameAr(line.nameAr());
            item.setProductNameEn(line.nameEn());
            item.setSku(line.sku());
            item.setVariantSummary(line.variantSummary());
            item.setImageUrl(line.imageUrl());
            item.setUnitPriceGross(line.unitPrice());
            item.setQuantity(line.quantity());
            item.setLineDiscount(MoneyUtils.ZERO);
            item.setAllocatedCartDiscount(allocated);
            item.setTaxRate(line.taxRate());
            item.setLineTotalGross(lineTotal);
            item.setLineTaxAmount(MoneyUtils.taxFromGross(taxableGross, line.taxRate()));

            order.addItem(item);
        }
    }

    // ----------------------------------------------------------------- address

    private AddressSnapshot resolveAddress(Long userId, PlaceOrderRequest request) {
        if (request.addressId() != null) {
            if (userId == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED,
                        "Sign in to use a saved address");
            }
            // Ownership is enforced inside requireOwned.
            CustomerAddress saved = addressService.requireOwned(userId, request.addressId());

            return new AddressSnapshot(
                    saved.getRecipientName(), saved.getPhoneE164(), saved.getAltPhoneE164(),
                    null, saved.getGovernorate().getId(), saved.getArea(),
                    saved.getStreetAddress(), saved.getBuilding(), saved.getFloor(),
                    saved.getApartment(), saved.getLandmark());
        }

        PlaceOrderRequest.AddressInput input = request.address();
        if (input == null) {
            throw new BusinessException(ErrorCode.INVALID_ADDRESS,
                    "Provide a saved addressId or a full address");
        }

        String phone = PhoneNormalizer.toE164(input.phone());
        if (phone == null) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT);
        }
        String altPhone = input.altPhone() == null || input.altPhone().isBlank()
                ? null : PhoneNormalizer.toE164(input.altPhone());

        return new AddressSnapshot(
                input.recipientName().trim(), phone, altPhone, input.email(),
                input.governorateId(), input.area(), input.streetAddress().trim(),
                input.building(), input.floor(), input.apartment(), input.landmark());
    }

    private void applyContactAndAddress(CustomerOrder order, AddressSnapshot address,
                                        Governorate governorate, AppUser customer) {
        order.setContactName(address.recipientName());
        order.setContactPhone(address.phone());
        order.setContactAltPhone(address.altPhone());
        /*
         * A saved address (CustomerAddress) has no email column at all — only the
         * inline guest/one-off address input does. Without this fallback, the order
         * confirmation email would never send for the most common path: a signed-in
         * customer checking out with a saved address. Email is optional at
         * registration, so this can still legitimately be null.
         */
        order.setContactEmail(address.email() != null ? address.email()
                : customer == null ? null : customer.getEmail());

        order.setShipGovernorate(governorate);
        order.setShipGovernorateName(governorate.getNameAr());
        // City is free text in V1. Falls back to the governorate so the NOT NULL
        // column always has something meaningful for the courier label.
        order.setShipCityName(address.area() != null && !address.area().isBlank()
                ? address.area() : governorate.getNameAr());
        order.setShipArea(address.area());
        order.setShipStreetAddress(address.streetAddress());
        order.setShipBuilding(address.building());
        order.setShipFloor(address.floor());
        order.setShipApartment(address.apartment());
        order.setShipLandmark(address.landmark());
    }

    // ------------------------------------------------------------------ helpers

    private PaymentMethod resolvePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentMethod.COD;
        }
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_UNAVAILABLE,
                    "Unknown payment method: " + raw);
        }
        if (method != PaymentMethod.COD) {
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_UNAVAILABLE,
                    "Only cash on delivery is available at the moment");
        }
        return method;
    }

    private String nameFor(Product product, String locale) {
        ProductTranslation translation = product.getTranslations().get(locale);
        if (translation != null) {
            return translation.getName();
        }
        // The snapshot columns are NOT NULL, so fall back rather than fail an order
        // over a missing translation.
        return product.nameFor("ar");
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

    /**
     * One cart line, read while its entities were still attached.
     *
     * <p>Holds the variant and product references the order needs as foreign keys,
     * plus every value that gets frozen onto the order item.
     */
    private record LineSnapshot(
            ProductVariant variant, Product product,
            String nameAr, String nameEn, String sku, String variantSummary,
            String imageUrl, BigDecimal unitPrice, BigDecimal taxRate,
            int weightGrams, ShippingSizeClass shippingSizeClass, int quantity) {
    }

    /** The address, flattened, before anything is written. */
    private record AddressSnapshot(
            String recipientName, String phone, String altPhone, String email,
            Long governorateId, String area, String streetAddress,
            String building, String floor, String apartment, String landmark) {
    }

    /** Every money figure for one order, computed once. */
    private record Totals(
            BigDecimal subtotal, BigDecimal discount, BigDecimal shipping, BigDecimal codFee,
            BigDecimal grandTotal, BigDecimal tax, BigDecimal net,
            List<BigDecimal> lineTotals, List<BigDecimal> allocations) {
    }
}