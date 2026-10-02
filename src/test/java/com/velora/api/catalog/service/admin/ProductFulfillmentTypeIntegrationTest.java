package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.cart.dto.AddToCartRequest;
import com.velora.api.cart.security.GuestTokenService;
import com.velora.api.cart.service.CartService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code fulfillmentType} says HOW a product is sold. Only READY_MADE goes through
 * the cart, and only READY_MADE needs a variant to be published.
 *
 * <p>Runs against the real database: the enum columns and their CHECK constraints
 * are part of what is being proven.
 */
@SpringBootTest
class ProductFulfillmentTypeIntegrationTest {

    @Autowired private ProductAdminService productAdminService;
    @Autowired private CartService cartService;
    @Autowired private GuestTokenService guestTokenService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> variantIds = new ArrayList<>();
    private String guestToken;

    @BeforeEach
    void createCategory() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Category category = new Category();
        category.setSlug("fulfillment-cat-" + unique);
        category.setActive(false);
        categoryId = categoryRepository.save(category).getId();
    }

    @AfterEach
    void removeTestData() {
        if (guestToken != null) {
            jdbc.update("DELETE FROM cart_item WHERE cart_id IN "
                    + "(SELECT id FROM cart WHERE guest_token = ?)", guestToken);
            jdbc.update("DELETE FROM cart WHERE guest_token = ?", guestToken);
        }
        variantIds.forEach(id -> {
            jdbc.update("DELETE FROM inventory WHERE variant_id = ?", id);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", id);
        });
        productIds.forEach(id -> {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", id);
            jdbc.update("DELETE FROM product WHERE id = ?", id);
        });
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("Adding a MADE_TO_ORDER product to the cart is rejected with PRODUCT_NOT_PURCHASABLE")
    void madeToOrderCannotBeAddedToCart() {
        Long variantId = createActiveVariantOf(FulfillmentType.MADE_TO_ORDER);
        guestToken = guestTokenService.generate();

        assertThatThrownBy(() -> cartService.addItem(
                null, guestToken, new AddToCartRequest(variantId, 1), "ar"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE));
    }

    @Test
    @DisplayName("Adding a CUSTOM_WORK product to the cart is rejected with PRODUCT_NOT_PURCHASABLE")
    void customWorkCannotBeAddedToCart() {
        Long variantId = createActiveVariantOf(FulfillmentType.CUSTOM_WORK);
        guestToken = guestTokenService.generate();

        assertThatThrownBy(() -> cartService.addItem(
                null, guestToken, new AddToCartRequest(variantId, 1), "ar"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE));
    }

    @Test
    @DisplayName("A READY_MADE product still goes into the cart")
    void readyMadeCanBeAddedToCart() {
        Long variantId = createActiveVariantOf(FulfillmentType.READY_MADE);
        guestToken = guestTokenService.generate();

        var cart = cartService.addItem(
                null, guestToken, new AddToCartRequest(variantId, 1), "ar");

        assertThat(cart.items()).hasSize(1);
    }

    @Test
    @DisplayName("A CUSTOM_WORK product publishes with no variants")
    void customWorkPublishesWithoutVariants() {
        ProductAdminResponse created = createViaAdmin(FulfillmentType.CUSTOM_WORK, null);

        ProductAdminResponse published = productAdminService.publish(created.id());

        assertThat(published.status()).isEqualTo("ACTIVE");
        assertThat(published.variantCount()).isZero();
        assertThat(published.fulfillmentType()).isEqualTo(FulfillmentType.CUSTOM_WORK);
        assertThat(published.shippingSizeClass()).isNull();
    }

    @Test
    @DisplayName("A MADE_TO_ORDER product publishes with no variants")
    void madeToOrderPublishesWithoutVariants() {
        ProductAdminResponse created = createViaAdmin(FulfillmentType.MADE_TO_ORDER, null);

        assertThat(productAdminService.publish(created.id()).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("A READY_MADE product still cannot publish without a variant")
    void readyMadeStillNeedsAVariantToPublish() {
        ProductAdminResponse created = createViaAdmin(FulfillmentType.READY_MADE,
                ShippingSizeClass.SMALL);

        assertThatThrownBy(() -> productAdminService.publish(created.id()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_HAS_NO_VARIANTS));
    }

    @Test
    @DisplayName("fulfillmentType defaults to READY_MADE when omitted")
    void fulfillmentTypeDefaultsToReadyMade() {
        ProductAdminResponse created = createViaAdmin(null, ShippingSizeClass.LARGE);

        assertThat(created.fulfillmentType()).isEqualTo(FulfillmentType.READY_MADE);
        assertThat(created.shippingSizeClass()).isEqualTo(ShippingSizeClass.LARGE);
    }

    @Test
    @DisplayName("A READY_MADE product without shippingSizeClass is rejected")
    void readyMadeRequiresShippingSizeClass() {
        assertThatThrownBy(() -> createViaAdmin(FulfillmentType.READY_MADE, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ------------------------------------------------------------------ helpers

    private ProductAdminResponse createViaAdmin(FulfillmentType type, ShippingSizeClass size) {
        ProductAdminResponse created = productAdminService.create(new ProductCreateRequest(
                categoryId, null, null,
                List.of(new TranslationRequest("ar", "منتج اختبار " + UUID.randomUUID(),
                        null, null, null, null)),
                false, false, null, type, size));
        productIds.add(created.id());
        return created;
    }

    /** An ACTIVE product with one ACTIVE, in-stock variant, inserted directly. */
    private Long createActiveVariantOf(FulfillmentType type) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long[] variantId = new Long[1];

        transactionTemplate.executeWithoutResult(status -> {
            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("fulfillment-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            product.setFulfillmentType(type);
            if (type == FulfillmentType.READY_MADE) {
                product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            }
            Long productId = productRepository.save(product).getId();
            productIds.add(productId);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("FULFIL-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1500.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(150);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId[0] = variantRepository.save(variant).getId();
            variantIds.add(variantId[0]);

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId[0]).orElseThrow());
            inventory.setQtyOnHand(5);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);
        });
        return variantId[0];
    }
}
