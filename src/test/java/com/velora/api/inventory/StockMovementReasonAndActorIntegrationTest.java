package com.velora.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.dto.admin.VariantAdminResponse;
import com.velora.api.catalog.dto.admin.VariantSaveRequest;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.service.admin.ProductAdminService;
import com.velora.api.catalog.service.admin.VariantAdminService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.inventory.dto.StockMovementResponse;
import com.velora.api.inventory.dto.StockReceiveRequest;
import com.velora.api.inventory.service.InventoryAdminService;
import java.math.BigDecimal;
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

/**
 * Two frontend-reported problems with the stock movement ledger:
 *
 * <ol>
 *   <li>{@code InventoryAdminService.receive()} and
 *       {@code VariantAdminService.createInventory()} hardcoded an English
 *       {@code reason} ("Goods received" / "Opening stock") whenever staff left the
 *       note empty — even though the movement type and reference columns already
 *       say exactly that. The ledger read half Arabic, half English for a field
 *       that carried no information beyond what the structured columns already
 *       give.</li>
 *   <li>{@code StockMovementResponse} exposed {@code actorId} with no name, so the
 *       admin screen could only show "#1" — useless the moment more than one staff
 *       account exists. {@code AuditLog} already solved this by copying the actor's
 *       name at write time (an account may be renamed or removed later); the ledger
 *       now does the same, via the same {@code AuditService.resolveActorName()}.</li>
 * </ol>
 */
@SpringBootTest
class StockMovementReasonAndActorIntegrationTest {

    @Autowired private InventoryAdminService inventoryAdminService;
    @Autowired private ProductAdminService productAdminService;
    @Autowired private VariantAdminService variantAdminService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private String unique;
    private Long categoryId;
    private Long userId;
    private String expectedActorName;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void seedCategoryAndStaffUser() {
        unique = UUID.randomUUID().toString().substring(0, 8);

        Category category = new Category();
        category.setSlug("movement-test-cat-" + unique);
        category.setActive(true);
        categoryId = categoryRepository.save(category).getId();

        AppUser user = new AppUser();
        user.setEmail("movement-test-" + unique + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName("Sara");
        user.setLastName("Ahmed");
        userId = userRepository.save(user).getId();
        expectedActorName = "Sara Ahmed";
    }

    @AfterEach
    void tearDown() {
        if (variantId != null) {
            jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        }
        if (productId != null) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    @DisplayName("receive() with no note leaves reason null, but names the actor")
    void receive_withoutNote_leavesReasonNullAndNamesActor() {
        createProductWithVariant(null);

        inventoryAdminService.receive(variantId,
                new StockReceiveRequest(5, "PO-" + unique, null), userId);

        StockMovementResponse movement = latestMovement();
        assertThat(movement.reason())
                .as("no hardcoded 'Goods received' filler when staff left no note")
                .isNull();
        assertThat(movement.actorName()).isEqualTo(expectedActorName);
        assertThat(movement.actorId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("receive() still keeps a real note when staff actually wrote one")
    void receive_withNote_keepsIt() {
        createProductWithVariant(null);

        inventoryAdminService.receive(variantId,
                new StockReceiveRequest(5, "PO-" + unique, "دفعة أولى من المورد"), userId);

        assertThat(latestMovement().reason()).isEqualTo("دفعة أولى من المورد");
    }

    @Test
    @DisplayName("Opening stock on variant creation leaves reason null, but names the actor")
    void openingStock_leavesReasonNullAndNamesActor() {
        createProductWithVariant(7);

        StockMovementResponse movement = latestMovement();
        assertThat(movement.reason())
                .as("referenceType/referenceId already explain this — no 'Opening stock' filler")
                .isNull();
        assertThat(movement.referenceType()).isEqualTo("VARIANT_CREATE");
        assertThat(movement.referenceId()).isEqualTo(String.valueOf(variantId));
        assertThat(movement.actorName()).isEqualTo(expectedActorName);
    }

    private void createProductWithVariant(Integer initialStock) {
        ProductAdminResponse product = productAdminService.create(new ProductCreateRequest(
                categoryId, null, "movement-test-" + unique,
                List.of(new TranslationRequest("ar", "منتج اختبار السجل " + unique,
                        null, null, null, null)),
                false, false, null));
        productId = product.id();

        List<VariantAdminResponse> variants = variantAdminService.saveVariants(productId,
                new VariantSaveRequest(List.of(new VariantSaveRequest.VariantItem(
                        null, "MOV-TEST-" + unique.toUpperCase(), null,
                        new BigDecimal("1000.0000"), null, null,
                        new BigDecimal("0.1400"), 100, null, initialStock, null))),
                userId);
        variantId = variants.get(0).id();
    }

    private StockMovementResponse latestMovement() {
        var page = inventoryAdminService.movements(variantId, PageRequest.of(0, 1));
        assertThat(page.content()).hasSize(1);
        return page.content().get(0);
    }
}
