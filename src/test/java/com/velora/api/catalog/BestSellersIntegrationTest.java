package com.velora.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.ProductSummaryResponse;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.catalog.service.ProductQueryService;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.order.dto.PlaceOrderRequest;
import com.velora.api.order.service.CheckoutService;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /products/best-sellers: ranked by net units on orders that still count, visible products
 * only, ties newest first, and filled with the rest of the catalog so the strip is never empty.
 * Other sales in the test database are tolerated: only the relative order of this test's own
 * products is asserted.
 */
@SpringBootTest
class BestSellersIntegrationTest {

    private static final String TOKEN_PREFIX = "best-sellers-test-";
    private static final String PHONE_LOCAL = "01012345681";
    private static final String PHONE_E164 = "+201012345681";

    @Autowired private ProductQueryService queryService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private GovernorateRepository governorateRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> variantIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Category category = new Category();
        category.setSlug("best-sellers-cat-" + UUID.randomUUID().toString().substring(0, 8));
        category.setActive(false);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void cleanUp() {
        String orderFilter = "SELECT id FROM customer_order WHERE contact_phone = '" + PHONE_E164 + "'";
        jdbc.update("DELETE FROM order_status_history WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM order_item WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM stock_reservation WHERE order_id IN (" + orderFilter + ")");
        jdbc.update("DELETE FROM customer_order WHERE contact_phone = ?", PHONE_E164);
        for (Long variantId : variantIds) {
            jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM cart_item WHERE variant_id = ?", variantId);
        }
        jdbc.update("DELETE FROM cart WHERE guest_token LIKE ?", TOKEN_PREFIX + "%");
        for (Long variantId : variantIds) {
            jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("Ranks by units, newest first on a tie; cancelled orders, drafts and archived products do not count")
    void ranking() {
        Long a = product(ProductStatus.ACTIVE);
        Long b = product(ProductStatus.ACTIVE);
        Long c = product(ProductStatus.ACTIVE);   // sold 9, but the order is cancelled
        Long d = product(ProductStatus.ACTIVE);   // never sold
        Long draft = product(ProductStatus.ACTIVE);
        Long archived = product(ProductStatus.ACTIVE);

        order(a, 3);
        order(b, 3);                              // tie with a, created later: must rank first
        Long cancelled = order(c, 9);
        order(draft, 50);
        order(archived, 50);
        jdbc.update("UPDATE customer_order SET fulfillment_status = 'CANCELLED' WHERE id = ?", cancelled);
        jdbc.update("UPDATE product SET status = 'DRAFT' WHERE id = ?", draft);   // unpublished after selling
        jdbc.update("UPDATE product SET archived_at = SYSDATETIMEOFFSET() WHERE id = ?", archived);

        List<Long> ids = bestSellerIds(60);

        assertThat(ids).contains(a, b, c, d).doesNotContain(draft, archived);
        assertThat(ids.indexOf(b)).as("tie goes to the newer product").isLessThan(ids.indexOf(a));
        assertThat(ids.indexOf(a)).as("sold products precede unsold ones")
                .isLessThan(ids.indexOf(c)).isLessThan(ids.indexOf(d));
    }

    @Test
    @DisplayName("Returned units are subtracted")
    void partialReturnsAreNetted() {
        Long a = product(ProductStatus.ACTIVE);
        Long b = product(ProductStatus.ACTIVE);
        Long orderA = order(a, 5);
        order(b, 3);
        jdbc.update("UPDATE order_item SET quantity_returned = 4 WHERE order_id = ?", orderA);   // a nets 1

        List<Long> ids = bestSellerIds(60);

        assertThat(ids.indexOf(b)).isLessThan(ids.indexOf(a));
    }

    @Test
    @DisplayName("A page is full while the catalog has enough products, and pages do not overlap")
    void fillsAndPages() {
        for (int i = 0; i < 5; i++) {
            product(ProductStatus.ACTIVE);
        }
        order(productIds.get(0), 2);

        PageResponse<ProductSummaryResponse> first = queryService.findBestSellers(PageRequest.of(0, 4), "en");
        PageResponse<ProductSummaryResponse> second = queryService.findBestSellers(PageRequest.of(1, 4), "en");

        assertThat(first.content()).hasSize(4);
        assertThat(first.size()).isEqualTo(4);
        assertThat(first.totalElements()).isGreaterThanOrEqualTo(5);
        assertThat(second.content()).extracting(ProductSummaryResponse::id)
                .doesNotContainAnyElementsOf(first.content().stream().map(ProductSummaryResponse::id).toList());
    }

    // --------------------------------------------------------------------- helpers

    private List<Long> bestSellerIds(int size) {
        return queryService.findBestSellers(PageRequest.of(0, size), "en").content().stream()
                .map(ProductSummaryResponse::id).toList();
    }

    /** Places a real COD order for {@code quantity} units; returns the order id. */
    private Long order(Long productId, int quantity) {
        Long variantId = variantRepository.findByProductIdAndArchivedAtIsNullOrderByPositionAsc(productId)
                .get(0).getId();
        String token = TOKEN_PREFIX + UUID.randomUUID();
        cartService.addItem(null, token, new AddToCartRequest(variantId, quantity), "ar");
        Long governorateId = governorateRepository.findByCode("CAI").orElseThrow().getId();
        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                new PlaceOrderRequest.AddressInput(
                        "Test customer", PHONE_LOCAL, null, null, governorateId,
                        "Test area", "Test street", "1", null, null, null),
                "COD",
                null);
        return checkoutService.placeOrder(null, token, request, "ar").getId();
    }

    private Long product(ProductStatus status) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long[] id = new Long[1];
        tx.executeWithoutResult(s -> {
            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("best-sellers-product-" + unique);
            product.setStatus(status);
            product.setShippingSizeClass(ShippingSizeClass.SMALL);
            id[0] = productRepository.save(product).getId();
            productIds.add(id[0]);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(id[0]).orElseThrow());
            variant.setSku("BEST-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            Long variantId = variantRepository.save(variant).getId();
            variantIds.add(variantId);

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId).orElseThrow());
            inventory.setQtyOnHand(500);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);
        });
        return id[0];
    }
}
