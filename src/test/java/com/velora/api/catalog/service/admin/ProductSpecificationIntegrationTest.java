package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeDataType;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.AttributeValueTranslation;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.dto.ProductDetailResponse;
import com.velora.api.catalog.dto.SpecificationResponse;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.ProductUpdateRequest;
import com.velora.api.catalog.dto.admin.SpecificationAdminResponse;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.service.ProductQueryService;
import com.velora.api.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ProductAdminService.applySpecifications} used to set {@code valueText} on
 * every specification row regardless of the attribute's data type, and never called
 * {@code setAttributeValue(...)}. For a LIST attribute this saved a row with BOTH
 * {@code attribute_value_id} and {@code value_text} NULL — a 200 OK that looked like
 * it saved, but {@code ProductAttributeValue.displayValue()} then had nothing to
 * return, so {@code buildSpecifications()} filtered it out everywhere it was read
 * back. Confirmed against product 10167 (velora-chrono-classic): a real saved row
 * with {@code attribute_value_id = NULL, value_text = NULL}.
 *
 * <p>Also covers the read side requested alongside the fix: {@code ProductAdminResponse}
 * now exposes {@code specifications[]} in the same shape the save request accepts,
 * and the storefront's {@code GET /products/{slug}} resolves a LIST value to its
 * translated name and a TEXT value to its raw text.
 *
 * <p>Runs against the real database — the point being verified is the actual saved
 * row, which a mocked repository would not surface.
 */
@SpringBootTest
class ProductSpecificationIntegrationTest {

    @Autowired private ProductAdminService productAdminService;
    @Autowired private ProductQueryService productQueryService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AttributeRepository attributeRepository;
    @Autowired private JdbcTemplate jdbc;

    private String unique;
    private Long categoryId;
    private Long listAttributeId;
    private Long listValueId;
    private Long otherListValueId;
    private Long textAttributeId;
    private Long productId;
    private String productSlug;

    @BeforeEach
    void seedCategoryAndAttributes() {
        unique = UUID.randomUUID().toString().substring(0, 8);
        productSlug = "spec-test-" + unique;

        Category category = new Category();
        category.setSlug("spec-test-cat-" + unique);
        category.setActive(true);
        categoryId = categoryRepository.save(category).getId();

        Attribute listAttribute = new Attribute();
        listAttribute.setCode("SPEC_MOVEMENT_" + unique.toUpperCase());
        listAttribute.setDataType(AttributeDataType.LIST);
        listAttribute.setVariantDefining(false);
        listAttribute.setFilterable(false);
        listAttribute.setDisplayOrder((short) 1);

        AttributeValue automatic = new AttributeValue();
        automatic.setAttribute(listAttribute);
        automatic.setCode("AUTOMATIC_" + unique.toUpperCase());
        automatic.setDisplayOrder((short) 1);
        putValueTranslation(automatic, "ar", "أوتوماتيك");
        listAttribute.getValues().add(automatic);

        AttributeValue quartz = new AttributeValue();
        quartz.setAttribute(listAttribute);
        quartz.setCode("QUARTZ_" + unique.toUpperCase());
        quartz.setDisplayOrder((short) 2);
        putValueTranslation(quartz, "ar", "كوارتز");
        listAttribute.getValues().add(quartz);

        Attribute savedListAttribute = attributeRepository.save(listAttribute);
        listAttributeId = savedListAttribute.getId();
        listValueId = savedListAttribute.getValues().get(0).getId();
        otherListValueId = savedListAttribute.getValues().get(1).getId();

        Attribute textAttribute = new Attribute();
        textAttribute.setCode("SPEC_WATER_" + unique.toUpperCase());
        textAttribute.setDataType(AttributeDataType.TEXT);
        textAttribute.setVariantDefining(false);
        textAttribute.setFilterable(false);
        textAttribute.setDisplayOrder((short) 2);
        textAttributeId = attributeRepository.save(textAttribute).getId();
    }

    @AfterEach
    void tearDown() {
        if (productId != null) {
            jdbc.update("DELETE FROM product_attribute_value WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM attribute_value_translation WHERE attribute_value_id IN "
                + "(SELECT id FROM attribute_value WHERE attribute_id = ?)", listAttributeId);
        jdbc.update("DELETE FROM attribute_value WHERE attribute_id = ?", listAttributeId);
        jdbc.update("DELETE FROM attribute WHERE id = ?", listAttributeId);
        jdbc.update("DELETE FROM attribute WHERE id = ?", textAttributeId);
    }

    @Test
    @DisplayName("Saving a LIST spec actually persists attribute_value_id, not two NULLs")
    void savingListSpecification_persistsAttributeValueId() {
        ProductAdminResponse created = createProductWithSpecs(List.of(
                new ProductCreateRequest.SpecificationRequest(listAttributeId, listValueId, null),
                new ProductCreateRequest.SpecificationRequest(textAttributeId, null, "50 متر")));

        // 1. Verify the raw row, exactly like the reported product 10167.
        var row = jdbc.queryForMap(
                "SELECT attribute_value_id, value_text FROM product_attribute_value "
                        + "WHERE product_id = ? AND attribute_id = ?",
                productId, listAttributeId);
        assertThat(row.get("attribute_value_id"))
                .as("the LIST spec must persist a real attribute_value_id, not NULL")
                .isEqualTo(listValueId);
        assertThat(row.get("value_text")).isNull();

        // 2. Verify the admin response — the exact shape the edit form saves back.
        assertThat(created.specifications()).hasSize(2);
        SpecificationAdminResponse listSpec = created.specifications().stream()
                .filter(s -> s.attributeId().equals(listAttributeId))
                .findFirst().orElseThrow();
        assertThat(listSpec.attributeValueId()).isEqualTo(listValueId);
        assertThat(listSpec.valueText()).isNull();

        SpecificationAdminResponse textSpec = created.specifications().stream()
                .filter(s -> s.attributeId().equals(textAttributeId))
                .findFirst().orElseThrow();
        assertThat(textSpec.attributeValueId()).isNull();
        assertThat(textSpec.valueText()).isEqualTo("50 متر");

        // 3. Verify the storefront resolves a LIST spec to its translated name and a
        //    TEXT spec to its raw text — GET /products/{slug}.
        // New products are DRAFT and invisible to the storefront until published;
        // that flow (requires a variant) is unrelated to what this test verifies.
        jdbc.update("UPDATE product SET status = 'ACTIVE' WHERE id = ?", productId);
        ProductDetailResponse detail = productQueryService.findBySlug(productSlug, "ar");
        assertThat(detail.specifications())
                .as("the storefront specification table must not be empty")
                .hasSize(2);

        SpecificationResponse listOnStorefront = detail.specifications().stream()
                .filter(s -> s.code().equals("SPEC_MOVEMENT_" + unique.toUpperCase()))
                .findFirst().orElseThrow();
        assertThat(listOnStorefront.value()).isEqualTo("أوتوماتيك");

        SpecificationResponse textOnStorefront = detail.specifications().stream()
                .filter(s -> s.code().equals("SPEC_WATER_" + unique.toUpperCase()))
                .findFirst().orElseThrow();
        assertThat(textOnStorefront.value()).isEqualTo("50 متر");
    }

    @Test
    @DisplayName("Round-tripping get() specifications through update() does not wipe them")
    void updateRoundTrip_preservesSpecifications() {
        ProductAdminResponse created = createProductWithSpecs(List.of(
                new ProductCreateRequest.SpecificationRequest(listAttributeId, listValueId, null)));

        // Simulate the admin form: load, change nothing about the spec, send the
        // WHOLE specifications array back — exactly what the response now enables.
        List<SpecificationAdminResponse> loaded = productAdminService.get(productId).specifications();
        List<ProductCreateRequest.SpecificationRequest> resubmitted = loaded.stream()
                .map(s -> new ProductCreateRequest.SpecificationRequest(
                        s.attributeId(), s.attributeValueId(), s.valueText()))
                .toList();

        productAdminService.update(productId, new ProductUpdateRequest(
                categoryId, null, null, null, false, false, resubmitted, null, null));

        List<SpecificationAdminResponse> afterUpdate =
                productAdminService.get(productId).specifications();
        assertThat(afterUpdate).hasSize(1);
        assertThat(afterUpdate.get(0).attributeValueId()).isEqualTo(listValueId);
    }

    @Test
    @DisplayName("A LIST attribute with no attributeValueId is rejected, not silently half-saved")
    void listAttributeWithoutValueId_isRejected() {
        assertThatThrownBy(() -> createProductWithSpecs(List.of(
                new ProductCreateRequest.SpecificationRequest(listAttributeId, null, null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("An attributeValueId belonging to a different attribute is rejected")
    void mismatchedAttributeValueId_isRejected() {
        // otherListValueId belongs to listAttributeId, so pair it with textAttributeId
        // — a value that cannot possibly belong to a TEXT attribute.
        assertThatThrownBy(() -> createProductWithSpecs(List.of(
                new ProductCreateRequest.SpecificationRequest(
                        listAttributeId, otherListValueId + 999_999L, null))))
                .isInstanceOf(BusinessException.class);
    }

    private ProductAdminResponse createProductWithSpecs(
            List<ProductCreateRequest.SpecificationRequest> specs) {
        ProductAdminResponse created = productAdminService.create(new ProductCreateRequest(
                categoryId, null, productSlug,
                List.of(new TranslationRequest("ar", "منتج اختبار المواصفات " + unique,
                        null, null, null, null)),
                false, false, specs, null, ShippingSizeClass.MEDIUM));
        productId = created.id();
        return created;
    }

    private void putValueTranslation(AttributeValue value, String locale, String name) {
        AttributeValueTranslation translation = new AttributeValueTranslation();
        translation.attachTo(value, locale);
        translation.setName(name);
        value.getTranslations().put(locale, translation);
    }
}
