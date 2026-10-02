package com.velora.api.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.AttributeValueTranslation;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductAttributeValue;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.domain.VariantStatus;
import com.velora.api.catalog.dto.FilterFacetsResponse;
import com.velora.api.catalog.dto.ProductFilterRequest;
import com.velora.api.catalog.dto.VariantOptionResponse;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two frontend-reported bugs on {@code GET /categories/filters} and
 * {@code GET /products?attributeValueIds=...}, both confirmed against the real
 * database because both are query-shape bugs a mocked repository would not surface.
 *
 * <ol>
 *   <li>Every attribute value came back duplicated once per translation —
 *       {@code AttributeRepository.findByFilterableTrueOrderByDisplayOrderAsc}
 *       fetch-joins {@code values} alongside {@code values.translations}, the same
 *       cartesian-product pattern already fixed elsewhere in this codebase
 *       (see {@code ProductVariantRepository}, {@code AttributeRepository}'s other
 *       consumers). {@link FacetService#getFacets} was the one caller missing the
 *       {@code distinct()} everyone else already has.</li>
 *   <li>Specification-only attributes (e.g. strap material — informational, never a
 *       SKU) store their value via {@code ProductAttributeValue}, never
 *       {@code VariantAttributeValue}. {@code findFacetsForCategory} and
 *       {@code ProductSpecifications.hasAnyAttributeValue} used to check only the
 *       variant table, so a specification-only value could be offered as a filter
 *       (via the unscoped facets path) that then always returned zero products.</li>
 * </ol>
 */
@SpringBootTest
class FacetServiceIntegrationTest {

    @Autowired private FacetService facetService;
    @Autowired private ProductQueryService productQueryService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AttributeRepository attributeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private String unique;
    private Long translatedAttributeId;
    private Long translatedValueId;

    private Long categoryId;
    private Long specAttributeId;
    private Long specValueId;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void setUp() {
        unique = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        if (variantId != null) {
            jdbc.update("DELETE FROM inventory WHERE variant_id = ?", variantId);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", variantId);
        }
        if (productId != null) {
            jdbc.update("DELETE FROM product_attribute_value WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product_translation WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        if (categoryId != null) {
            jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        }
        if (specAttributeId != null) {
            jdbc.update("DELETE FROM attribute_value WHERE attribute_id = ?", specAttributeId);
            jdbc.update("DELETE FROM attribute WHERE id = ?", specAttributeId);
        }
        if (translatedAttributeId != null) {
            jdbc.update("DELETE FROM attribute_value_translation WHERE attribute_value_id = ?",
                    translatedValueId);
            jdbc.update("DELETE FROM attribute_value WHERE attribute_id = ?",
                    translatedAttributeId);
            jdbc.update("DELETE FROM attribute WHERE id = ?", translatedAttributeId);
        }
    }

    @Test
    @DisplayName("getFacets(null, ...) does not duplicate a value that has two translations")
    void unscopedFacets_doesNotDuplicateTranslatedValue() {
        transactionTemplate.executeWithoutResult(status -> {
            Attribute attribute = new Attribute();
            attribute.setCode("DUP_FACET_" + unique.toUpperCase());
            attribute.setVariantDefining(true);
            attribute.setFilterable(true);
            attribute.setDisplayOrder((short) 1);

            AttributeValue value = new AttributeValue();
            value.setAttribute(attribute);
            value.setCode("VAL_" + unique.toUpperCase());
            value.setDisplayOrder((short) 1);
            putValueTranslation(value, "ar", "قيمة تجريبية");
            putValueTranslation(value, "en", "Test Value");
            attribute.getValues().add(value);

            Attribute saved = attributeRepository.save(attribute);
            translatedAttributeId = saved.getId();
            translatedValueId = saved.getValues().get(0).getId();
        });

        FilterFacetsResponse facets = facetService.getFacets(null, "ar");

        VariantOptionResponse facet = facets.attributes().stream()
                .filter(a -> a.attributeId().equals(translatedAttributeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seeded attribute missing from facets"));

        assertThat(facet.values())
                .as("one AttributeValue with 2 translations must appear once, not 2 or 4 times")
                .hasSize(1);
        assertThat(facet.values().get(0).id()).isEqualTo(translatedValueId);
    }

    @Test
    @DisplayName("A specification-only attribute with real product data is offered and filters correctly")
    void specificationOnlyAttribute_isOfferedAndFilters() {
        transactionTemplate.executeWithoutResult(status -> {
            Category category = new Category();
            category.setSlug("facet-test-cat-" + unique);
            category.setActive(true);
            categoryId = categoryRepository.save(category).getId();

            // Specification-only: never a variant SKU, stored via ProductAttributeValue.
            Attribute specAttribute = new Attribute();
            specAttribute.setCode("TEST_STRAP_" + unique.toUpperCase());
            specAttribute.setVariantDefining(false);
            specAttribute.setFilterable(true);
            specAttribute.setDisplayOrder((short) 1);

            AttributeValue specValue = new AttributeValue();
            specValue.setAttribute(specAttribute);
            specValue.setCode("METAL_" + unique.toUpperCase());
            specValue.setDisplayOrder((short) 1);
            putValueTranslation(specValue, "ar", "معدني");
            specAttribute.getValues().add(specValue);

            Attribute savedAttribute = attributeRepository.save(specAttribute);
            specAttributeId = savedAttribute.getId();
            specValueId = savedAttribute.getValues().get(0).getId();

            Product product = new Product();
            product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
            product.setSlug("facet-test-product-" + unique);
            product.setStatus(ProductStatus.ACTIVE);
            productId = productRepository.save(product).getId();

            Product persistedProduct = productRepository.findById(productId).orElseThrow();
            Attribute persistedSpecAttribute = attributeRepository.findById(specAttributeId)
                    .orElseThrow();

            ProductAttributeValue pav = new ProductAttributeValue();
            pav.setKey(new ProductAttributeValue.Key());
            pav.setProduct(persistedProduct);
            pav.setAttribute(persistedSpecAttribute);
            pav.setAttributeValue(persistedSpecAttribute.getValues().get(0));
            persistedProduct.getSpecifications().add(pav);
            productRepository.save(persistedProduct);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(persistedProduct);
            variant.setSku("FACET-TEST-" + unique.toUpperCase());
            variant.setPrice(new BigDecimal("1000.0000"));
            variant.setTaxRate(new BigDecimal("0.1400"));
            variant.setWeightGrams(100);
            variant.setStatus(VariantStatus.ACTIVE);
            variantId = variantRepository.save(variant).getId();
        });

        // 1. The filter sidebar must offer this specification-only attribute.
        FilterFacetsResponse facets = facetService.getFacets(categoryId, "ar");
        assertThat(facets.attributes())
                .as("a specification-only attribute with real product data must be offered")
                .anyMatch(a -> a.attributeId().equals(specAttributeId)
                        && a.values().stream().anyMatch(v -> v.id().equals(specValueId)));

        // 2. Filtering by its value must actually return the product, not zero results —
        //    this is the exact "customer filters and finds an empty page" bug.
        var results = productQueryService.search(
                new ProductFilterRequest(null, categoryId, null, null, null,
                        List.of(specValueId), null, null, null, null, "newest"),
                PageRequest.of(0, 20), "ar");

        assertThat(results.content())
                .as("filtering by a specification-only value must return the product that has it")
                .anyMatch(p -> p.id().equals(productId));
    }

    private void putValueTranslation(AttributeValue value, String locale, String name) {
        AttributeValueTranslation translation = new AttributeValueTranslation();
        translation.attachTo(value, locale);
        translation.setName(name);
        value.getTranslations().put(locale, translation);
    }
}
