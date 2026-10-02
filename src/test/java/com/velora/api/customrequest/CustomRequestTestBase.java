package com.velora.api.customrequest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.storage.StorageService;
import com.velora.api.customrequest.domain.CustomRequestType;
import com.velora.api.customrequest.dto.CustomRequestCreateRequest;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.customrequest.service.CustomRequestAdminService;
import com.velora.api.customrequest.service.CustomRequestService;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.domain.Inventory;
import com.velora.api.inventory.repository.InventoryRepository;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Shared setup for the custom-request tests, which all run against the real database
 * (the sequence lock, the unique indexes and the attachment limit have no seam a mock
 * could test).
 *
 * <p>Everything a test creates is removed afterwards, including the files it stored and
 * its audit entries, and the year's counter is put back to where it was found.
 */
@SpringBootTest
abstract class CustomRequestTestBase {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    @Autowired protected WebApplicationContext context;
    @Autowired protected CustomRequestService customRequestService;
    @Autowired protected CustomRequestAdminService adminService;
    @Autowired protected StorageService storageService;
    @Autowired protected GovernorateRepository governorateRepository;
    @Autowired protected CategoryRepository categoryRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected ProductVariantRepository variantRepository;
    @Autowired protected InventoryRepository inventoryRepository;
    @Autowired protected AppUserRepository userRepository;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected JdbcTemplate jdbc;

    protected MockMvc mvc;
    protected Long staffId;
    protected String staffName;
    protected int year;

    private final List<Long> requestIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> variantIds = new ArrayList<>();
    private final List<Long> extraUserIds = new ArrayList<>();
    private Long categoryId;
    private Long auditBaseline;
    private Integer sequenceBaseline;

    @BeforeEach
    void setUpBase() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        year = LocalDate.now(CAIRO).getYear();
        List<Integer> existing = jdbc.query(
                "SELECT last_number FROM custom_order_request_sequence WHERE fiscal_year = ?",
                (rs, n) -> rs.getInt(1), year);
        sequenceBaseline = existing.isEmpty() ? null : existing.get(0);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);

        String unique = UUID.randomUUID().toString().substring(0, 8);
        Category category = new Category();
        category.setSlug("custom-req-cat-" + unique);
        category.setActive(false);
        categoryId = categoryRepository.save(category).getId();

        staffId = newUser("staff-" + unique, "Request", "Staff " + unique);
        staffName = "Request Staff " + unique;
    }

    @AfterEach
    void removeTestDataBase() {
        jdbc.update("DELETE FROM audit_log WHERE id > ? AND entity_type = 'CUSTOM_REQUEST'", auditBaseline);
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", staffId);

        for (Long id : requestIds) {
            List<String> keys = jdbc.queryForList(
                    "SELECT storage_key FROM custom_order_request_attachment WHERE request_id = ?",
                    String.class, id);
            keys.forEach(storageService::delete);
            jdbc.update("DELETE FROM custom_order_request_attachment WHERE request_id = ?", id);
            jdbc.update("DELETE FROM custom_order_request WHERE id = ?", id);
        }

        // Anything stored by requests this test did not track (e.g. rolled back) is orphaned
        // on disk only; no row points at it any more.
        for (Long variantId : variantIds) {
            jdbc.update("DELETE FROM stock_reservation WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM stock_movement WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }

        if (sequenceBaseline == null) {
            jdbc.update("DELETE FROM custom_order_request_sequence WHERE fiscal_year = ?", year);
        } else {
            jdbc.update("MERGE custom_order_request_sequence AS t "
                            + "USING (SELECT ? AS fiscal_year, ? AS last_number) AS s "
                            + "ON t.fiscal_year = s.fiscal_year "
                            + "WHEN MATCHED THEN UPDATE SET last_number = s.last_number "
                            + "WHEN NOT MATCHED THEN INSERT (fiscal_year, last_number) "
                            + "VALUES (s.fiscal_year, s.last_number);",
                    year, sequenceBaseline);
        }

        jdbc.update("DELETE FROM cart WHERE user_id = ?", staffId);
        for (Long userId : extraUserIds) {
            jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    // ------------------------------------------------------------------ requests

    /** A request that is valid on its own; tests override what they are about. */
    protected CustomRequestCreateRequest customWork() {
        return new CustomRequestCreateRequest(
                CustomRequestType.CUSTOM_WORK, null, "محمد أحمد", "01012345678", null,
                "customer@example.com", governorateId("CAI"), "مدينة نصر", "12 شارع التسعين",
                null, null, null, null, "ورق حائط بمقاس خاص");
    }

    protected CustomRequestCreateRequest sizeVariant(Long productId) {
        return new CustomRequestCreateRequest(
                CustomRequestType.SIZE_VARIANT, productId, "محمد أحمد", "01012345678", null,
                null, governorateId("CAI"), null, null,
                new BigDecimal("120"), new BigDecimal("80"), null, 2, "أريده أعرض قليلاً");
    }

    /** Creates a request as a guest, from an IP address nothing else is using. */
    protected CustomRequestCreatedResponse createAsGuest(CustomRequestCreateRequest body) {
        return track(customRequestService.create(body, null, newIp()));
    }

    protected CustomRequestCreatedResponse createAsGuest() {
        return createAsGuest(customWork());
    }

    protected CustomRequestCreatedResponse track(CustomRequestCreatedResponse created) {
        requestIds.add(created.id());
        return created;
    }

    /** For a request that exists but was created some other way. */
    protected void trackRequest(Long id) {
        requestIds.add(id);
    }

    /**
     * An address no other test has used. The rate limiter is in memory and shared by every
     * test in the run, so tests that are not about limits must not share a bucket.
     */
    protected static String newIp() {
        int n = IP_COUNTER.getAndIncrement();
        return "198.51.%d.%d".formatted(100 + (n / 250), 1 + (n % 250));
    }

    protected Long governorateId(String code) {
        return governorateRepository.findByCode(code).orElseThrow().getId();
    }

    // ------------------------------------------------------------------ catalog

    protected Long product(FulfillmentType type, ProductStatus status) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long[] id = new Long[1];
        transactionTemplate.executeWithoutResult(s -> {
            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("custom-req-product-" + unique);
            product.setStatus(status);
            product.setFulfillmentType(type);
            if (type == FulfillmentType.READY_MADE) {
                product.setShippingSizeClass(ShippingSizeClass.MEDIUM);
            }
            id[0] = productRepository.save(product).getId();
        });
        productIds.add(id[0]);
        return id[0];
    }

    protected Long readyMadeProduct() {
        return product(FulfillmentType.READY_MADE, ProductStatus.ACTIVE);
    }

    /** A sellable ready-made product with ten units on the shelf; returns [productId, variantId]. */
    protected Long[] productWithStock() {
        Long productId = readyMadeProduct();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long[] variantId = new Long[1];
        transactionTemplate.executeWithoutResult(s -> {
            ProductVariant variant = new ProductVariant();
            variant.setProduct(productRepository.findById(productId).orElseThrow());
            variant.setSku("CUSTOM-REQ-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(200);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId[0] = variantRepository.save(variant).getId();

            Inventory inventory = new Inventory();
            inventory.setVariant(variantRepository.findById(variantId[0]).orElseThrow());
            inventory.setQtyOnHand(10);
            inventory.setQtyReserved(0);
            inventory.setMinStockLevel(1);
            inventoryRepository.save(inventory);
        });
        variantIds.add(variantId[0]);
        return new Long[] {productId, variantId[0]};
    }

    // ------------------------------------------------------------------- people

    protected Long newUser(String emailPrefix, String first, String last) {
        AppUser user = new AppUser();
        user.setEmail(emailPrefix + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setFirstName(first);
        user.setLastName(last);
        Long id = userRepository.save(user).getId();
        extraUserIds.add(id);
        return id;
    }

    /** A signed-in caller. Controllers read the id from a real UserPrincipal. */
    protected static RequestPostProcessor signedIn(Long userId, String... roles) {
        UserPrincipal principal = UserPrincipal.of(userId, "user@example.com", null, List.of(roles));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }

    protected RequestPostProcessor admin() {
        return signedIn(staffId, "ADMIN");
    }

    /** Sets the TCP peer address MockMvc reports, which is what the rate limit sees. */
    protected static RequestPostProcessor from(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }
}
