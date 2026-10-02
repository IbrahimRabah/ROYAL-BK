package com.velora.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.dto.CartResponse;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.ProductUpdateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.export.service.AccountingExcelWriter;
import com.velora.api.export.service.TemplateRenderer;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.invoice.dto.InvoiceView;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.order.domain.CustomerOrder;
import com.velora.api.order.domain.OrderItem;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.repository.OrderRepository;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.dto.ShippingQuoteResponse;
import com.velora.api.shipping.repository.GovernorateRepository;
import com.velora.api.shipping.service.ShippingService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Assembly fee, end to end on the real database: where it is set, what the order freezes, how it
 * enters the totals and the tax, and where it (and the COD fee that was missing alongside it)
 * is shown.
 *
 * <p>The fee is {@code assemblyFee x quantity} per line, tax-INCLUSIVE and taxed at the line's
 * own rate, outside the cart discount. Every figure below is worked out by hand.
 */
@SpringBootTest
class OrderAssemblyFeeIntegrationTest {

    private static final String GUEST_PREFIX = "assembly-test-";
    private static final String PHONE_LOCAL = "01012345683";
    private static final String PHONE_E164 = "+201012345683";
    private static final BigDecimal VAT = new BigDecimal("0.1400");

    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InvoiceService invoiceService;
    @Autowired private ShippingService shippingService;
    @Autowired private ProductAdminService productAdminService;
    @Autowired private TemplateRenderer templateRenderer;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;
    private Long variantId;
    private String guestToken;
    private final java.util.List<Long> adminCreatedProducts = new java.util.ArrayList<>();
    private int invoiceYear;
    private Integer invoiceSequenceBaseline;

    @BeforeEach
    void setUp() {
        guestToken = GUEST_PREFIX + UUID.randomUUID();
        String unique = UUID.randomUUID().toString().substring(0, 8);

        invoiceYear = LocalDate.now().getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                (rs, n) -> rs.getInt(1), invoiceYear);
        invoiceSequenceBaseline = existing.isEmpty() ? null : existing.get(0);

        transactionTemplate.executeWithoutResult(s -> {
            Category category = new Category();
            category.setSlug("assembly-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("assembly-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("ASSEMBLY-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(VAT);
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(20);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);
        });
    }

    @AfterEach
    void removeTestData() {
        String orders = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        jdbc.update("DELETE FROM invoice WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orders + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM cart WHERE guest_token LIKE ?", GUEST_PREFIX + "%");
        jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        adminCreatedProducts.forEach(id -> {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        });
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);

        if (invoiceSequenceBaseline == null) {
            jdbc.update("DELETE FROM invoice_sequence WHERE fiscal_year = ?", invoiceYear);
        } else {
            jdbc.update("UPDATE invoice_sequence SET last_number = ? WHERE fiscal_year = ? "
                    + "AND last_number > ?", invoiceSequenceBaseline, invoiceYear, invoiceSequenceBaseline);
        }
    }

    // ================================================================== the order

    @Test
    @DisplayName("No assembly: the order is exactly what it was before the feature — assemblyTotal zero")
    void noAssemblyChangesNothing() {
        Long orderId = placeOrder(2);

        CustomerOrder order = load(orderId);
        assertThat(order.getAssemblyTotal()).isEqualByComparingTo("0");
        assertThat(order.getItems().get(0).getAssemblyFee()).isEqualByComparingTo("0");
        // 2 x 1000, Cairo shipping is free: the courier collects the goods, nothing else.
        assertThat(order.getGrandTotal()).isEqualByComparingTo("2000.00");
        assertThat(order.getTaxTotal()).isEqualByComparingTo(MoneyUtils.taxFromGross(
                new BigDecimal("2000.00"), VAT));
    }

    @Test
    @DisplayName("Assembly is per piece: fee 300 x 2 pieces = 600, inside grandTotal, taxed inclusive at the line rate")
    void assemblyIsPerPieceAndTaxedInclusive() {
        setAssembly(true, "300");

        Long orderId = placeOrder(2);

        CustomerOrder order = load(orderId);
        OrderItem item = order.getItems().get(0);
        assertThat(item.getAssemblyFee()).as("the PER-PIECE fee is what is frozen").isEqualByComparingTo("300");
        assertThat(order.getAssemblyTotal()).isEqualByComparingTo("600.00");

        BigDecimal shippingAndCod = order.getShippingCost().add(order.getCodFee());
        assertThat(order.getGrandTotal())
                .as("goods 2000 + assembly 600 + shipping/COD")
                .isEqualByComparingTo(new BigDecimal("2600.00").add(shippingAndCod));

        // Tax is EXTRACTED from the fee (14% inclusive), per line, and summed — never added on top.
        BigDecimal expectedTax = MoneyUtils.taxFromGross(new BigDecimal("2000.00"), VAT)
                .add(MoneyUtils.taxFromGross(new BigDecimal("600.00"), VAT));
        assertThat(order.getTaxTotal()).isEqualByComparingTo(MoneyUtils.round(expectedTax));
        assertThat(order.getNetTotal()).isEqualByComparingTo(order.getGrandTotal().subtract(order.getTaxTotal()));
        assertThat(order.getSubtotalGross()).as("the goods subtotal does not absorb it")
                .isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("A stored fee is not charged while requiresAssembly is false")
    void feeIgnoredWhenAssemblyNotRequired() {
        setAssembly(false, "300");

        Long orderId = placeOrder(1);

        assertThat(load(orderId).getAssemblyTotal()).isEqualByComparingTo("0");
        assertThat(load(orderId).getGrandTotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("The order keeps the fee it was bought at: changing the product afterwards changes nothing")
    void orderSnapshotsTheFee() {
        setAssembly(true, "300");
        Long orderId = placeOrder(1);

        setAssembly(true, "999");

        OrderResponse response = orderService.getForAdmin(orderId, "ar");
        assertThat(response.assemblyTotal()).isEqualByComparingTo("300.00");
        assertThat(response.items().get(0).assemblyFee()).isEqualByComparingTo("300");
        assertThat(response.items().get(0).assemblyTotal()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("The cart discount does not touch assembly: it is not allocated to lines or reduced")
    void assemblyIsOutsideTheDiscount() {
        setAssembly(true, "300");
        Long orderId = placeOrder(1);

        // Discounts are zero until the promotion module lands, so assert the structure instead:
        // the allocated discount is a property of the goods line only.
        OrderItem item = load(orderId).getItems().get(0);
        assertThat(item.getAllocatedCartDiscount()).isEqualByComparingTo("0");
        assertThat(item.getLineTotalGross()).as("the line total is goods only").isEqualByComparingTo("1000.00");
    }

    // ============================================================== estimates

    @Test
    @DisplayName("The cart and the shipping quote include assembly in their estimate, by the same rule")
    void estimatesIncludeAssembly() {
        setAssembly(true, "300");
        CartResponse cart = cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");

        assertThat(cart.assemblyTotal()).isEqualByComparingTo("600.00");
        assertThat(cart.estimatedTotal()).isEqualByComparingTo("2600.00");
        BigDecimal expectedTax = MoneyUtils.round(MoneyUtils.taxFromGross(new BigDecimal("2000.00"), VAT)
                .add(MoneyUtils.taxFromGross(new BigDecimal("600.00"), VAT)));
        assertThat(cart.taxIncluded()).isEqualByComparingTo(expectedTax);

        ShippingQuoteResponse quote = shippingService.quote(
                governorateId("CAI"), null, guestToken, null, true, "ar");
        assertThat(quote.assemblyTotal()).isEqualByComparingTo("600.00");
        assertThat(quote.estimatedTotal())
                .isEqualByComparingTo(new BigDecimal("2600.00").add(quote.shippingCost()).add(quote.codFee()));
    }

    @Test
    @DisplayName("With nothing to assemble the estimates are unchanged")
    void estimatesUnchangedWithoutAssembly() {
        CartResponse cart = cartService.addItem(null, guestToken, new AddToCartRequest(variantId, 2), "ar");

        assertThat(cart.assemblyTotal()).isEqualByComparingTo("0");
        assertThat(cart.estimatedTotal()).isEqualByComparingTo("2000.00");
    }

    // ================================================================= invoice

    @Test
    @DisplayName("The invoice freezes assembly and COD fee from the order, so its totals balance")
    void invoiceFreezesAssemblyAndCodFee() {
        setAssembly(true, "300");
        Long orderId = placeOrder(2);
        jdbc.update("UPDATE customer_order SET cod_fee = 25, grand_total = grand_total + 25, "
                + "net_total = net_total + 25 WHERE id = ?", orderId);

        for (String to : List.of("CONFIRMED", "PROCESSING", "SHIPPED", "OUT_FOR_DELIVERY", "DELIVERED")) {
            orderService.changeFulfillmentStatus(orderId, to, null, null, "ar");
        }
        var invoice = invoiceService.issueForOrder(orderId);

        assertThat(invoice.getAssemblyTotal()).isEqualByComparingTo("600.00");
        assertThat(invoice.getCodFee()).isEqualByComparingTo("25");
        assertThat(invoice.getGrandTotal()).isEqualByComparingTo(load(orderId).getGrandTotal());
        assertThat(invoice.getNetTotal().add(invoice.getTaxTotal())).isEqualByComparingTo(invoice.getGrandTotal());

        // Later changes to the order cannot reach an issued invoice.
        jdbc.update("UPDATE customer_order SET assembly_total = 0 WHERE id = ?", orderId);
        assertThat(invoiceService.get(invoice.getId()).grandTotal()).isEqualByComparingTo(invoice.getGrandTotal());
    }

    @Test
    @DisplayName("The invoice shows the assembly and COD rows only when they are above zero")
    void invoiceRowsAppearOnlyWhenPositive() {
        String withBoth = templateRenderer.render("invoice", "view", view("600.00", "25.00"));
        assertThat(withBoth).contains("التركيب").contains("رسوم الدفع عند الاستلام");
        assertThat(withBoth.indexOf("الشحن")).as("assembly sits after shipping")
                .isLessThan(withBoth.indexOf("التركيب"));

        String withNeither = templateRenderer.render("invoice", "view", view("0.00", "0.00"));
        assertThat(withNeither).doesNotContain("التركيب").doesNotContain("رسوم الدفع عند الاستلام");
    }

    @Test
    @DisplayName("The confirmation email shows the same rows by the same rule")
    void emailRowsAppearOnlyWhenPositive() {
        setAssembly(true, "300");
        Long orderId = placeOrder(1);
        // The listener renders the ENTITY, so the test does too.
        assertThat(templateRenderer.render("order-confirmation", "order", load(orderId)))
                .contains("التركيب");

        setAssembly(false, "0");
        Long plain = placeOrder(1);
        assertThat(templateRenderer.render("order-confirmation", "order", load(plain)))
                .doesNotContain("التركيب").doesNotContain("رسوم الدفع عند الاستلام");
    }

    // ================================================================== export

    @Test
    @DisplayName("The accounting export has Assembly right after Shipping, then COD fee, and every column still lines up")
    void exportColumns() throws Exception {
        setAssembly(true, "300");
        Long orderId = placeOrder(2);
        jdbc.update("UPDATE customer_order SET cod_fee = 25, grand_total = grand_total + 25, "
                + "net_total = net_total + 25 WHERE id = ?", orderId);

        byte[] bytes = transactionTemplate.execute(s -> new AccountingExcelWriter()
                .write(List.of(orderRepository.findWithItemsById(orderId).orElseThrow()), "test"));

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(2);
            int shipping = columnOf(header, "الشحن");
            assertThat(columnOf(header, "التركيب")).as("right after Shipping").isEqualTo(shipping + 1);
            assertThat(columnOf(header, "رسوم الدفع عند الاستلام")).isEqualTo(shipping + 2);
            assertThat(columnOf(header, "الإجمالي")).isEqualTo(shipping + 3);

            var data = sheet.getRow(3);
            assertThat(data.getCell(shipping + 1).getNumericCellValue()).isEqualTo(600.0);
            assertThat(data.getCell(shipping + 2).getNumericCellValue()).isEqualTo(25.0);
            CustomerOrder order = load(orderId);
            assertThat(BigDecimal.valueOf(data.getCell(shipping + 3).getNumericCellValue()))
                    .isEqualByComparingTo(order.getGrandTotal());

            // The totals row sums the same columns.
            var totals = sheet.getRow(4);
            assertThat(totals.getCell(shipping + 1).getNumericCellValue()).isEqualTo(600.0);
            assertThat(totals.getCell(shipping + 2).getNumericCellValue()).isEqualTo(25.0);
            assertThat(BigDecimal.valueOf(totals.getCell(shipping + 3).getNumericCellValue()))
                    .isEqualByComparingTo(order.getGrandTotal());
            assertThat(header.getLastCellNum()).as("no column was dropped").isEqualTo((short) 19);
        }
    }

    // ================================================================== product

    @Test
    @DisplayName("Admin: create defaults to no assembly, saves the fee when given, update leaves it alone when omitted")
    void adminSavesAssemblyFields() {
        ProductAdminResponse plain = create(null, null);
        assertThat(plain.requiresAssembly()).isFalse();
        assertThat(plain.assemblyFee()).isEqualByComparingTo("0");

        ProductAdminResponse with = create(true, new BigDecimal("450.5"));
        assertThat(with.requiresAssembly()).isTrue();
        assertThat(with.assemblyFee()).isEqualByComparingTo("450.50");

        ProductAdminResponse untouched = productAdminService.update(with.id(), new ProductUpdateRequest(
                categoryId, null, null, translations(), false, false, null, null, null));
        assertThat(untouched.requiresAssembly()).as("omitted means unchanged").isTrue();
        assertThat(untouched.assemblyFee()).isEqualByComparingTo("450.50");

        ProductAdminResponse copy = productAdminService.duplicate(with.id());
        adminCreatedProducts.add(copy.id());
        assertThat(copy.requiresAssembly()).as("a copy keeps assembly").isTrue();
        assertThat(copy.assemblyFee()).isEqualByComparingTo("450.50");

        ProductAdminResponse changed = productAdminService.update(with.id(), new ProductUpdateRequest(
                categoryId, null, null, translations(), false, false, null, null, null,
                false, new BigDecimal("0")));
        assertThat(changed.requiresAssembly()).isFalse();
        assertThat(changed.assemblyFee()).isEqualByComparingTo("0");

    }

    @Test
    @DisplayName("The database refuses a negative fee")
    void databaseRefusesNegativeFee() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE product SET assembly_fee = -1 WHERE id = ?", productId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // =================================================================== helpers

    private ProductAdminResponse create(Boolean requires, BigDecimal fee) {
        ProductAdminResponse created = productAdminService.create(new ProductCreateRequest(
                categoryId, null, null, translations(), false, false, null, null,
                ShippingSizeClass.SMALL, requires, fee));
        adminCreatedProducts.add(created.id());
        return created;
    }

    private static List<TranslationRequest> translations() {
        return List.of(new TranslationRequest("ar", "منتج التركيب " + UUID.randomUUID(),
                null, null, null, null));
    }

    private void setAssembly(boolean requires, String fee) {
        jdbc.update("UPDATE product SET requires_assembly = ?, assembly_fee = ? WHERE id = ?",
                requires, new BigDecimal(fee), productId);
    }

    private Long placeOrder(int quantity) {
        cartService.addItem(null, guestToken, new AddToCartRequest(variantId, quantity), "ar");
        return checkoutService.placeOrder(null, guestToken, new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "عميل الاختبار", PHONE_LOCAL, null, null, governorateId("CAI"),
                        "منطقة الاختبار", "شارع الاختبار", "1", null, null, null),
                "COD", null, null, null), "ar").getId();
    }

    private CustomerOrder load(Long orderId) {
        return transactionTemplate.execute(s -> {
            CustomerOrder order = orderRepository.findWithItemsById(orderId).orElseThrow();
            order.getItems().size();
            return order;
        });
    }

    private Long governorateId(String code) {
        return governorateRepository.findByCode(code).orElseThrow().getId();
    }

    private static int columnOf(org.apache.poi.ss.usermodel.Row header, String title) {
        for (int i = 0; i < header.getLastCellNum(); i++) {
            if (title.equals(header.getCell(i).getStringCellValue())) {
                return i;
            }
        }
        throw new AssertionError("No column titled " + title);
    }

    private static InvoiceView view(String assembly, String codFee) {
        return new InvoiceView("INV-1", "2026-01-01", "ORD-1", "2026-01-01", false,
                "بائع", "عنوان", "01000000000", "a@b.c", "1", "1", null,
                "مشتري", "01000000001", "عنوان",
                List.of(), new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(codFee), new BigDecimal(assembly),
                new BigDecimal("1500.00"), new BigDecimal("200.00"), new BigDecimal("1700.00"),
                "الدفع عند الاستلام", "EGP");
    }
}
