package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeTranslation;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.AttributeValueTranslation;
import com.velora.api.catalog.domain.Brand;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.dto.admin.AttributeAdminResponse;
import com.velora.api.catalog.dto.admin.BrandAdminResponse;
import com.velora.api.catalog.dto.admin.CategoryAdminResponse;
import com.velora.api.catalog.dto.admin.VariantMatrixRequest;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.BrandRepository;
import com.velora.api.catalog.repository.CategoryRepository;
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
 * The three admin taxonomy read endpoints: they exist so staff can see — and find
 * again to reactivate — categories, brands and attributes that the public storefront
 * endpoints deliberately hide.
 *
 * <p>Runs against the real database, like {@code PurchaseJourneyIntegrationTest}: the
 * point being verified is that inactive/non-filterable rows survive the round trip
 * through the repository query and the mapper, which a mocked repository would not
 * catch if the query method name were subtly wrong (e.g. accidentally reusing the
 * {@code ...ActiveTrue...} finder).
 */
@SpringBootTest
class TaxonomyAdminServiceIntegrationTest {

    @Autowired private TaxonomyAdminService taxonomyService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private AttributeRepository attributeRepository;
    @Autowired private JdbcTemplate jdbc;

    private String unique;

    private Long variantDefiningAttributeId;
    private Long goldValueId;
    private Long silverValueId;
    private Long specOnlyAttributeId;

    private Long parentCategoryId;
    private Long childCategoryId;

    private Long activeBrandId;
    private Long inactiveBrandId;

    @BeforeEach
    void setUp() {
        unique = UUID.randomUUID().toString().substring(0, 8);
        seedAttributes();
        seedCategories();
        seedBrands();
    }

    @AfterEach
    void tearDown() {
        String attrIds = "(" + variantDefiningAttributeId + "," + specOnlyAttributeId + ")";
        jdbc.update("DELETE FROM attribute_value_translation WHERE attribute_value_id IN "
                + "(SELECT id FROM attribute_value WHERE attribute_id IN " + attrIds + ")");
        jdbc.update("DELETE FROM attribute_value WHERE attribute_id IN " + attrIds);
        jdbc.update("DELETE FROM attribute_translation WHERE attribute_id IN " + attrIds);
        jdbc.update("DELETE FROM attribute WHERE id IN " + attrIds);

        jdbc.update("DELETE FROM category_translation WHERE category_id IN (?, ?)",
                childCategoryId, parentCategoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", childCategoryId);
        jdbc.update("DELETE FROM category WHERE id = ?", parentCategoryId);

        jdbc.update("DELETE FROM brand WHERE id IN (?, ?)", activeBrandId, inactiveBrandId);
    }

    // ------------------------------------------------------------------ attributes

    @Test
    @DisplayName("Lists every attribute regardless of filterable, feeding straight into the matrix preview")
    void listAttributes_includesNonFilterableAndFeedsThePreview() {
        List<AttributeAdminResponse> all = taxonomyService.listAttributes(null);

        AttributeAdminResponse variantDefining = all.stream()
                .filter(a -> a.id().equals(variantDefiningAttributeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variant-defining attribute missing"));
        AttributeAdminResponse specOnly = all.stream()
                .filter(a -> a.id().equals(specOnlyAttributeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Non-filterable, specification-only attribute missing from admin list — "
                                + "the admin query must not reuse the storefront's filterable=true finder"));

        assertThat(specOnly.filterable()).isFalse();
        assertThat(specOnly.variantDefining()).isFalse();
        assertThat(specOnly.nameAr()).isNotBlank();

        assertThat(variantDefining.nameAr()).isEqualTo("لون تجريبي " + unique);
        assertThat(variantDefining.nameEn()).isEqualTo("Test Color " + unique);
        assertThat(variantDefining.values())
                .extracting(AttributeAdminResponse.ValueResponse::id)
                .containsExactlyInAnyOrder(goldValueId, silverValueId);

        // The ids returned here must be directly usable as a variant matrix selection —
        // no extra lookup step for the admin UI.
        var selection = new VariantMatrixRequest.AttributeSelection(
                variantDefining.id(),
                variantDefining.values().stream().map(AttributeAdminResponse.ValueResponse::id).toList());
        assertThat(selection.attributeId()).isEqualTo(variantDefiningAttributeId);
        assertThat(selection.valueIds()).containsExactlyInAnyOrder(goldValueId, silverValueId);
    }

    @Test
    @DisplayName("Filters by variantDefining when the query param is supplied")
    void listAttributes_filtersByVariantDefining() {
        List<AttributeAdminResponse> variantDefiningOnly = taxonomyService.listAttributes(true);
        assertThat(variantDefiningOnly)
                .extracting(AttributeAdminResponse::id)
                .contains(variantDefiningAttributeId)
                .doesNotContain(specOnlyAttributeId);

        List<AttributeAdminResponse> specOnlyList = taxonomyService.listAttributes(false);
        assertThat(specOnlyList)
                .extracting(AttributeAdminResponse::id)
                .contains(specOnlyAttributeId)
                .doesNotContain(variantDefiningAttributeId);
    }

    // ------------------------------------------------------------------- categories

    @Test
    @DisplayName("Category tree includes inactive categories, unlike the storefront tree, "
            + "and carries the active flag and full translations")
    void getCategoryTree_includesInactiveCategories() {
        List<CategoryAdminResponse> tree = taxonomyService.getCategoryTree();

        CategoryAdminResponse parentNode = tree.stream()
                .filter(n -> n.id().equals(parentCategoryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Inactive parent category missing from the admin tree"));

        assertThat(parentNode.children())
                .extracting(CategoryAdminResponse::id)
                .contains(childCategoryId);

        // The whole point of exposing this here: without it, staff cannot tell an
        // inactive category (already hidden from the storefront) from an active one.
        assertThat(parentNode.active()).isFalse();
        assertThat(parentNode.translations())
                .filteredOn(t -> t.locale().equals("ar"))
                .extracting(com.velora.api.catalog.dto.admin.CategoryTranslationResponse::name)
                .containsExactly("قسم تجريبي غير نشط " + unique);
    }

    // ---------------------------------------------------------------------- brands

    @Test
    @DisplayName("Brand list includes inactive brands and carries both names plus active")
    void listBrands_includesInactiveBrands() {
        List<BrandAdminResponse> brands = taxonomyService.listBrands();

        assertThat(brands)
                .extracting(BrandAdminResponse::id)
                .contains(activeBrandId, inactiveBrandId);

        BrandAdminResponse active = brands.stream()
                .filter(b -> b.id().equals(activeBrandId)).findFirst().orElseThrow();
        BrandAdminResponse inactive = brands.stream()
                .filter(b -> b.id().equals(inactiveBrandId)).findFirst().orElseThrow();

        assertThat(active.active()).isTrue();
        assertThat(active.nameAr()).isEqualTo("براند نشط " + unique);
        assertThat(active.nameEn()).isEqualTo("Active Brand " + unique);
        assertThat(inactive.active()).isFalse();
    }

    // ------------------------------------------------------------------------ seed

    private void seedAttributes() {
        Attribute variantDefining = new Attribute();
        variantDefining.setCode("TEST_COLOR_" + unique.toUpperCase());
        variantDefining.setVariantDefining(true);
        variantDefining.setFilterable(true);
        variantDefining.setDisplayOrder((short) 1);
        putAttributeTranslation(variantDefining, "ar", "لون تجريبي " + unique);
        putAttributeTranslation(variantDefining, "en", "Test Color " + unique);

        AttributeValue gold = new AttributeValue();
        gold.setAttribute(variantDefining);
        gold.setCode("GOLD_" + unique.toUpperCase());
        gold.setHexColor("#C9A227");
        gold.setDisplayOrder((short) 1);
        putValueTranslation(gold, "ar", "ذهبي تجريبي");
        variantDefining.getValues().add(gold);

        AttributeValue silver = new AttributeValue();
        silver.setAttribute(variantDefining);
        silver.setCode("SILVER_" + unique.toUpperCase());
        silver.setHexColor("#C0C0C0");
        silver.setDisplayOrder((short) 2);
        putValueTranslation(silver, "ar", "فضي تجريبي");
        variantDefining.getValues().add(silver);

        Attribute saved = attributeRepository.save(variantDefining);
        variantDefiningAttributeId = saved.getId();
        goldValueId = gold.getId();
        silverValueId = silver.getId();

        // Specification-only: never a storefront filter facet, but staff still need
        // to see and edit it — this is what the admin query must not silently drop.
        Attribute specOnly = new Attribute();
        specOnly.setCode("TEST_MOVEMENT_" + unique.toUpperCase());
        specOnly.setVariantDefining(false);
        specOnly.setFilterable(false);
        specOnly.setDisplayOrder((short) 2);
        putAttributeTranslation(specOnly, "ar", "حركة تجريبية " + unique);
        specOnlyAttributeId = attributeRepository.save(specOnly).getId();
    }

    private void seedCategories() {
        Category parent = new Category();
        parent.setSlug("test-parent-" + unique);
        parent.setActive(false);
        parent.setDisplayOrder((short) 1);
        putCategoryTranslation(parent, "ar", "قسم تجريبي غير نشط " + unique);
        parentCategoryId = categoryRepository.save(parent).getId();

        Category child = new Category();
        child.setParent(categoryRepository.findById(parentCategoryId).orElseThrow());
        child.setSlug("test-child-" + unique);
        child.setActive(true);
        child.setDisplayOrder((short) 1);
        putCategoryTranslation(child, "ar", "قسم فرعي تجريبي " + unique);
        childCategoryId = categoryRepository.save(child).getId();
    }

    private void seedBrands() {
        Brand active = new Brand();
        active.setSlug("test-active-brand-" + unique);
        active.setNameAr("براند نشط " + unique);
        active.setNameEn("Active Brand " + unique);
        active.setActive(true);
        activeBrandId = brandRepository.save(active).getId();

        Brand inactive = new Brand();
        inactive.setSlug("test-inactive-brand-" + unique);
        inactive.setNameAr("براند غير نشط " + unique);
        inactive.setNameEn("Inactive Brand " + unique);
        inactive.setActive(false);
        inactiveBrandId = brandRepository.save(inactive).getId();
    }

    private void putAttributeTranslation(Attribute attribute, String locale, String name) {
        AttributeTranslation translation = new AttributeTranslation();
        translation.attachTo(attribute, locale);
        translation.setName(name);
        attribute.getTranslations().put(locale, translation);
    }

    private void putValueTranslation(AttributeValue value, String locale, String name) {
        AttributeValueTranslation translation = new AttributeValueTranslation();
        translation.attachTo(value, locale);
        translation.setName(name);
        value.getTranslations().put(locale, translation);
    }

    private void putCategoryTranslation(Category category, String locale, String name) {
        CategoryTranslation translation = new CategoryTranslation();
        translation.attachTo(category, locale);
        translation.setName(name);
        category.getTranslations().put(locale, translation);
    }
}
