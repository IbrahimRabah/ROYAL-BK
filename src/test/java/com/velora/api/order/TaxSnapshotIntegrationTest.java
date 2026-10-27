package com.velora.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.admin.VariantAdminResponse;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.service.admin.VariantAdminService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.invoice.service.InvoiceService;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.domain.FulfillmentStatus;
import com.velora.api.order.dto.OrderResponse;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.order.service.OrderService;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tax belongs to the variant and is frozen on the order line. The application config carries no
 * tax setting, and changing a variant's rate later never touches an order or an issued invoice.
 */
@SpringBootTest
class TaxSnapshotIntegrationTest {

    private static final String PHONE_LOCAL = "01012345688";
    private static final String PHONE_E164 = "+201012345688";

    @Autowired private Environment environment;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderService orderService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private VariantAdminService variantAdminService;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long staffId;
    private Long customerId;
    private int invoiceYear;
    private Integer invoiceSequenceBaseline;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        invoiceYear = LocalDate.now().getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM invoice_sequence WHERE fiscal_year = ?",
                (rs, n) -> rs.getInt(1), invoiceYear);
        invoiceSequenceBaseline = existing.isEmpty() ? null : existing.get(0);

        transactionTemplate.executeWithoutResult(s -> {
            Category category = new Category();
            category.setSlug("tax-cat-" + unique);
            category.setActive(false);
            categoryId = categoryRepository.save(category).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("tax-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            productId = productRepository.save(product).getId();

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("TAX-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1140.0000"));
            // Explicit: the default is zero while the company is not VAT-registered.
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(20);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);

            staffId = newUser("tax-staff-" + unique);
            customerId = newUser("tax-customer-" + unique);
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
        jdbc.update("DELETE FROM stock_reservation WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM stock_movement WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM cart_item WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM cart WHERE user_id = ?", customerId);
        jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
        jdbc.update("DELETE FROM product_variant WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", staffId, customerId);
        if (invoiceSequenceBaseline == null) {
            jdbc.update("DELETE FROM invoice_sequence WHERE fiscal_year = ?", invoiceYear);
        } else {
            jdbc.update("UPDATE invoice_sequence SET last_number = ? WHERE fiscal_year = ? "
                    + "AND last_number > ?", invoiceSequenceBaseline, invoiceYear, invoiceSequenceBaseline);
        }
    }

    @Test
    @DisplayName("No tax setting exists in the configuration")
    void noTaxSettingInConfig() {
        assertThat(environment.containsProperty("velora.business.default-tax-rate")).isFalse();
        assertThat(environment.containsProperty("velora.business.prices-include-tax")).isFalse();
    }

    @Test
    @DisplayName("A variant created without a rate gets the zero default; an explicit rate wins and stays editable")
    void defaultRate() {
        List<VariantAdminResponse> saved = variantAdminService.saveVariants(productId,
                new VariantSaveRequest(List.of(
                        item("TAX-DEF-" + productId, null),
                        item("TAX-EXP-" + productId, new BigDecimal("0.1400")))), staffId);

        assertThat(ProductVariant.DEFAULT_TAX_RATE).isEqualByComparingTo("0");
        assertThat(saved.get(0).taxRate()).isEqualByComparingTo("0");
        assertThat(saved.get(1).taxRate()).as("switching VAT back on is just a rate").isEqualByComparingTo("0.14");
    }

    @Test
    @DisplayName("V20's data change zeroes the variants but an order and invoice made at 14% keep their "
            + "tax, before and after a new zero-rate order is placed")
    void zeroRateMigrationLeavesHistoryAlone() throws Exception {
        Long oldOrder = placeOrder();
        for (FulfillmentStatus to : List.of(FulfillmentStatus.CONFIRMED, FulfillmentStatus.PROCESSING,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.OUT_FOR_DELIVERY, FulfillmentStatus.DELIVERED)) {
            orderService.changeFulfillmentStatus(oldOrder, to.name(), null, staffId, "ar");
        }
        OrderResponse before = orderService.getForAdmin(oldOrder, "ar");
        BigDecimal invoiceTaxBefore = jdbc.queryForObject(
                "SELECT tax_total FROM invoice WHERE order_id = ?", BigDecimal.class, oldOrder);
        assertThat(before.taxTotal()).as("made at 14%: 1140 gross is 140 tax").isEqualByComparingTo("140");
        assertThat(invoiceTaxBefore).isEqualByComparingTo("140");

        runMigrationData();
        runMigrationData();   // safe to run again

        assertThat(jdbc.queryForObject("SELECT tax_rate FROM product_variant WHERE id = ?",
                BigDecimal.class, variantId)).as("the variant is now zero").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_variant WHERE tax_rate <> 0",
                Integer.class)).as("every variant").isZero();

        assertOrderStillTaxedAt14(oldOrder, before, invoiceTaxBefore);

        // A new order after the migration is zero-rated, and still does not disturb the old one.
        Long newOrder = placeOrder();
        OrderResponse fresh = orderService.getForAdmin(newOrder, "ar");
        assertThat(fresh.taxTotal()).isEqualByComparingTo("0");
        assertThat(fresh.items().get(0).taxAmount()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT tax_rate FROM order_item WHERE order_id = ?",
                BigDecimal.class, newOrder)).isEqualByComparingTo("0");
        assertThat(fresh.grandTotal()).as("prices are tax-inclusive: zeroing the rate does not change what is paid")
                .isEqualByComparingTo(before.grandTotal());

        assertOrderStillTaxedAt14(oldOrder, before, invoiceTaxBefore);
    }

    @Test
    @DisplayName("The issued invoice PDF prints Latin digits (1,140.00) and a Gregorian yyyy-MM-dd date")
    void issuedInvoicePdfUsesLatinDigits() throws Exception {
        Long orderId = placeOrder();
        for (FulfillmentStatus to : List.of(FulfillmentStatus.CONFIRMED, FulfillmentStatus.PROCESSING,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.OUT_FOR_DELIVERY, FulfillmentStatus.DELIVERED)) {
            orderService.changeFulfillmentStatus(orderId, to.name(), null, staffId, "ar");
        }
        Long invoiceId = jdbc.queryForObject("SELECT id FROM invoice WHERE order_id = ?", Long.class, orderId);

        StringBuilder visual = new StringBuilder();
        try (PDDocument document = PDDocument.load(invoiceService.renderPdf(invoiceId))) {
            new PDFTextStripper() {
                // Characters in the order they sit on the page, left to right: what a reader sees,
                // not the order the file stores them in or the one PDFBox's own bidi step prints.
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    positions.stream().sorted(Comparator.comparing(TextPosition::getXDirAdj))
                            .forEach(p -> visual.append(p.getUnicode()));
                    visual.append('\n');
                }
            }.getText(document);
        }
        String text = visual.toString();

        assertThat(text).doesNotContainPattern("[\\u0660-\\u0669\\u06F0-\\u06F9\\u066B\\u066C]");
        assertThat(text).contains("1,140.00");

        // The date: yyyy-MM-dd in Latin digits and Gregorian, i.e. today's date, not a Hijri year.
        Matcher date = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text);
        assertThat(date.find()).as("a date is printed").isTrue();
        assertThat(LocalDate.parse(date.group()))
                .isBetween(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
    }

    private void assertOrderStillTaxedAt14(Long orderId, OrderResponse before, BigDecimal invoiceTaxBefore) {
        OrderResponse after = orderService.getForAdmin(orderId, "ar");
        assertThat(after.taxTotal()).isEqualByComparingTo(before.taxTotal());
        assertThat(after.netTotal()).isEqualByComparingTo(before.netTotal());
        assertThat(after.items().get(0).taxAmount()).isEqualByComparingTo("140");
        assertThat(jdbc.queryForObject("SELECT tax_rate FROM order_item WHERE order_id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo("0.14");
        assertThat(jdbc.queryForObject("SELECT tax_total FROM customer_order WHERE id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo("140");
        assertThat(jdbc.queryForObject("SELECT tax_total FROM invoice WHERE order_id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo(invoiceTaxBefore);
    }

    /**
     * What V20 does to the data, run for real: its one UPDATE. The test login cannot alter the
     * schema (by design: it can only read and write {@code royal_test}), so the DDL half - the
     * default constraint - is only parsed here, to catch syntax errors; it is applied by
     * {@code create-test-db.ps1} or by hand with sqlcmd. The script is also held to writing
     * nothing but {@code product_variant}: that is the promise that orders and invoices are safe.
     */
    private void runMigrationData() throws Exception {
        String script = new String(new ClassPathResource("db/V20__zero_tax_rate.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        String code = script.replaceAll("(?s)/\\*.*?\\*/", "");

        Matcher targets = Pattern.compile("(?i)\\b(?:UPDATE|INSERT\\s+INTO|DELETE\\s+FROM|ALTER\\s+TABLE|"
                + "DROP\\s+TABLE|TRUNCATE\\s+TABLE)\\s+([\\w.\\[\\]]+)").matcher(code);
        int writes = 0;
        while (targets.find()) {
            writes++;
            assertThat(targets.group(1)).as("V20 may only write product_variant").isEqualTo("dbo.product_variant");
        }
        assertThat(writes).isGreaterThanOrEqualTo(2);

        Matcher update = Pattern.compile("(?is)UPDATE\\s+dbo\\.product_variant\\b[^;]*;").matcher(code);
        assertThat(update.find()).as("V20 has its UPDATE").isTrue();
        String updateStatement = update.group();

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                // Syntax check of the whole script, nothing executed.
                statement.execute("SET PARSEONLY ON");
                try {
                    for (String batch : code.split("(?m)^GO\\s*$")) {
                        if (!batch.isBlank()) {
                            statement.execute(batch);
                        }
                    }
                } finally {
                    statement.execute("SET PARSEONLY OFF");
                }
                statement.executeUpdate(updateStatement);
            }
            return null;
        });
    }

    private Long placeOrder() {
        cartService.addItem(customerId, null, new AddToCartRequest(variantId, 1), "ar");
        return checkoutService.placeOrder(customerId, null,
                new PlaceOrderRequest(null,
                        new PlaceOrderRequest.AddressInput("عميل", PHONE_LOCAL, null, null,
                                governorateRepository.findByCode("CAI").orElseThrow().getId(),
                                "منطقة", "شارع", "1", null, null, null),
                        "COD", null, null, null), "ar").getId();
    }

    @Test
    @DisplayName("Changing a variant's rate afterwards leaves the order and its invoice untouched")
    void rateChangeDoesNotTouchHistory() {
        Long orderId = placeOrder();
        OrderResponse before = orderService.getForAdmin(orderId, "ar");
        // 1140 gross at 14%: net 1000, tax 140.
        assertThat(before.items().get(0).taxAmount()).isEqualByComparingTo("140");

        jdbc.update("UPDATE product_variant SET tax_rate = 0.0500 WHERE id = ?", variantId);

        for (FulfillmentStatus to : List.of(FulfillmentStatus.CONFIRMED, FulfillmentStatus.PROCESSING,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.OUT_FOR_DELIVERY, FulfillmentStatus.DELIVERED)) {
            orderService.changeFulfillmentStatus(orderId, to.name(), null, staffId, "ar");
        }

        OrderResponse after = orderService.getForAdmin(orderId, "ar");
        assertThat(after.taxTotal()).isEqualByComparingTo(before.taxTotal());
        assertThat(after.netTotal()).isEqualByComparingTo(before.netTotal());
        assertThat(after.items().get(0).taxAmount()).isEqualByComparingTo("140");
        assertThat(jdbc.queryForObject("SELECT tax_rate FROM order_item WHERE order_id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo("0.14");

        // The invoice, issued after the rate change, carries the order's frozen tax.
        BigDecimal invoiceTax = jdbc.queryForObject(
                "SELECT tax_total FROM invoice WHERE order_id = ?", BigDecimal.class, orderId);
        assertThat(invoiceTax).isEqualByComparingTo(before.taxTotal());
    }

    private VariantSaveRequest.VariantItem item(String sku, BigDecimal taxRate) {
        return new VariantSaveRequest.VariantItem(null, sku, null, new BigDecimal("100.00"),
                null, null, taxRate, 100, null, null, null);
    }

    private Long newUser(String emailPrefix) {
        AppUser user = new AppUser();
        user.setEmail(emailPrefix + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Tax");
        user.setLastName("Test");
        return userRepository.save(user).getId();
    }
}
