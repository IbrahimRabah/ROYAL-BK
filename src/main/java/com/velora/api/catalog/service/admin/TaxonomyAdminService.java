package com.velora.api.catalog.service.admin;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeDataType;
import com.velora.api.catalog.domain.AttributeTranslation;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.AttributeValueTranslation;
import com.velora.api.catalog.domain.Brand;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.dto.admin.AttributeAdminResponse;
import com.velora.api.catalog.dto.admin.AttributeSaveRequest;
import com.velora.api.catalog.dto.admin.BrandAdminResponse;
import com.velora.api.catalog.dto.admin.BrandSaveRequest;
import com.velora.api.catalog.dto.admin.CategoryAdminResponse;
import com.velora.api.catalog.dto.admin.CategorySaveRequest;
import com.velora.api.catalog.dto.admin.CategoryTranslationResponse;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.BrandRepository;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.SlugGenerator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categories, brands and attributes — the structures products hang off.
 *
 * <p>These change rarely and are set up once, but getting the
 * {@code variantDefining} flag wrong on an attribute is expensive to undo, so the
 * service refuses to flip it once variants depend on it.
 */
@Service
public class TaxonomyAdminService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyAdminService.class);

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final AttributeRepository attributeRepository;

    public TaxonomyAdminService(CategoryRepository categoryRepository,
                                BrandRepository brandRepository,
                                AttributeRepository attributeRepository) {
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.attributeRepository = attributeRepository;
    }

    // ------------------------------------------------------------------- reads

    /**
     * Every attribute, with its values and translations, ready for staff to build a
     * variant matrix. Each attribute's {@code id} and each value's {@code id} can be
     * sent straight to {@code POST /admin/products/{productId}/variants/preview} as
     * {@code attributeId} / {@code valueIds} — no lookup step in between.
     *
     * <p>Unlike the storefront facet query, this returns every attribute regardless
     * of {@code filterable}: specification-only attributes (movement, water
     * resistance) never appear as a public filter but still need to be visible here.
     */
    @Transactional(readOnly = true)
    public List<AttributeAdminResponse> listAttributes(Boolean variantDefining) {
        List<Attribute> attributes = variantDefining == null
                ? attributeRepository.findAllByOrderByDisplayOrderAsc()
                : attributeRepository.findByVariantDefiningOrderByDisplayOrderAsc(variantDefining);
        return attributes.stream().map(this::toAttributeAdminResponse).toList();
    }

    /**
     * The full category tree, including inactive categories — staff need to find
     * what they turned off in order to turn it back on. Unlike the storefront tree
     * ({@code CategoryService.getTree}), every node carries {@code active} (so staff
     * can actually tell an inactive category apart from an active one — the whole
     * point of including inactive ones here) and the full {@code translations[]},
     * since {@code PUT /admin/categories/{id}} replaces them wholesale.
     */
    @Transactional(readOnly = true)
    public List<CategoryAdminResponse> getCategoryTree() {
        List<Category> all = categoryRepository.findAllByOrderByDisplayOrderAscIdAsc();

        Map<Long, List<Category>> byParent = all.stream()
                .filter(c -> c.getParent() != null)
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));

        return all.stream()
                .filter(c -> c.getParent() == null)
                .sorted(Comparator.comparing(Category::getDisplayOrder))
                .map(root -> buildCategoryAdminNode(root, byParent, 0))
                .toList();
    }

    /**
     * Every brand, including inactive ones, for the same reason as the category
     * tree. {@code nameAr}/{@code nameEn} are both always returned — {@code Brand}
     * has no separate translation table, so there is no per-locale view to pick.
     */
    @Transactional(readOnly = true)
    public List<BrandAdminResponse> listBrands() {
        return brandRepository.findAllByOrderByNameArAsc().stream()
                .map(this::toBrandAdminResponse)
                .toList();
    }

    private BrandAdminResponse toBrandAdminResponse(Brand brand) {
        return new BrandAdminResponse(
                brand.getId(),
                brand.getSlug(),
                brand.getNameAr(),
                brand.getNameEn(),
                brand.getLogoUrl(),
                brand.isActive());
    }

    private CategoryAdminResponse buildCategoryAdminNode(Category category,
                                                          Map<Long, List<Category>> byParent,
                                                          int depth) {
        // Guard against a cycle introduced by a bad parent_id, same as the storefront tree.
        List<CategoryAdminResponse> children = depth >= 5
                ? List.of()
                : byParent.getOrDefault(category.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(Category::getDisplayOrder))
                        .map(child -> buildCategoryAdminNode(child, byParent, depth + 1))
                        .toList();

        List<CategoryTranslationResponse> translations = category.getTranslations().values().stream()
                .sorted(Comparator.comparing(t -> t.getKey().getLocale()))
                .map(t -> new CategoryTranslationResponse(
                        t.getKey().getLocale(),
                        t.getName(),
                        t.getDescription(),
                        t.getMetaTitle(),
                        t.getMetaDescription()))
                .toList();

        return new CategoryAdminResponse(
                category.getId(),
                category.getSlug(),
                category.getParent() == null ? null : category.getParent().getId(),
                translations,
                category.getImageUrl(),
                category.getBannerUrl(),
                category.getDisplayOrder(),
                category.isActive(),
                category.getProductCount() == null ? 0 : category.getProductCount(),
                children);
    }

    private AttributeAdminResponse toAttributeAdminResponse(Attribute attribute) {
        AttributeTranslation ar = attribute.getTranslations().get("ar");
        AttributeTranslation en = attribute.getTranslations().get("en");

        /*
         * The entity graph joins "translations", "values" and "values.translations"
         * in one query. Fetch-joining a bag (List) alongside another collection is a
         * cartesian product at the row level: with 2 translations and 2 values,
         * Hibernate hydrates the SAME "values" list once per translation row, so each
         * value ends up added twice. distinct() collapses it back — the duplicates
         * are the same managed entity instance (Hibernate's first-level cache), so
         * reference equality is enough.
         */
        List<AttributeAdminResponse.ValueResponse> values = attribute.getValues().stream()
                .distinct()
                .map(this::toValueAdminResponse)
                .toList();

        return new AttributeAdminResponse(
                attribute.getId(),
                attribute.getCode(),
                attribute.getDataType().name(),
                attribute.isVariantDefining(),
                attribute.isFilterable(),
                attribute.getDisplayOrder(),
                ar == null ? null : ar.getName(),
                en == null ? null : en.getName(),
                values);
    }

    private AttributeAdminResponse.ValueResponse toValueAdminResponse(AttributeValue value) {
        AttributeValueTranslation ar = value.getTranslations().get("ar");
        AttributeValueTranslation en = value.getTranslations().get("en");

        return new AttributeAdminResponse.ValueResponse(
                value.getId(),
                value.getCode(),
                value.getHexColor(),
                value.getDisplayOrder(),
                ar == null ? null : ar.getName(),
                en == null ? null : en.getName());
    }

    // ----------------------------------------------------------------- category

    @Transactional
    public Long createCategory(CategorySaveRequest request) {
        Category category = new Category();
        applyCategory(category, request, true);
        Category saved = categoryRepository.save(category);
        log.info("Created category id={} slug={}", saved.getId(), saved.getSlug());
        return saved.getId();
    }

    @Transactional
    public Long updateCategory(Long id, CategorySaveRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
        applyCategory(category, request, false);
        return categoryRepository.save(category).getId();
    }

    private void applyCategory(Category category, CategorySaveRequest request, boolean isNew) {
        if (request.parentId() != null) {
            Category parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                            "Parent category not found"));
            // A category cannot be its own ancestor.
            if (category.getId() != null && parent.getId().equals(category.getId())) {
                throw new BusinessException(ErrorCode.CATEGORY_CYCLE);
            }
            category.setParent(parent);
        } else {
            category.setParent(null);
        }

        if (isNew || request.slug() != null) {
            String source = request.slug() != null && !request.slug().isBlank()
                    ? request.slug()
                    : request.translations().get(0).name();
            category.setSlug(SlugGenerator.generateUnique(source,
                    s -> !categoryRepository.existsBySlug(s)));
        }

        category.setImageUrl(request.imageUrl());
        category.setBannerUrl(request.bannerUrl());
        if (request.displayOrder() != null) {
            category.setDisplayOrder(request.displayOrder().shortValue());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }

        mergeCategoryTranslations(category, request.translations());
    }

    // -------------------------------------------------------------------- brand

    @Transactional
    public Long saveBrand(Long id, BrandSaveRequest request) {
        Brand brand = id == null
                ? new Brand()
                : brandRepository.findById(id).orElseThrow(
                        () -> new BusinessException(ErrorCode.BRAND_NOT_FOUND));

        if (id == null || request.slug() != null) {
            String source = request.slug() != null && !request.slug().isBlank()
                    ? request.slug() : request.nameEn();
            String slug = SlugGenerator.generate(source);
            if (slug == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Could not build a URL slug from the given name");
            }
            // Checked here so the user sees BRAND_SLUG_EXISTS instead of a raw
            // database constraint error.
            boolean taken = brandRepository.existsBySlug(slug)
                    && (id == null || !slug.equals(brand.getSlug()));
            if (taken) {
                throw new BusinessException(ErrorCode.BRAND_SLUG_EXISTS,
                        "A brand already uses the slug '" + slug + "'");
            }
            brand.setSlug(slug);
        }
        brand.setNameAr(request.nameAr());
        brand.setNameEn(request.nameEn());
        brand.setLogoUrl(request.logoUrl());
        if (request.active() != null) {
            brand.setActive(request.active());
        }
        return brandRepository.save(brand).getId();
    }

    // ---------------------------------------------------------------- attribute

    @Transactional
    public Long saveAttribute(Long id, AttributeSaveRequest request) {
        Attribute attribute = id == null
                ? new Attribute()
                : attributeRepository.findById(id).orElseThrow(
                        () -> new BusinessException(ErrorCode.ATTRIBUTE_NOT_FOUND));

        // The most common admin mistake: trying to create COLOR again instead of
        // adding a value to the existing one. Caught here with a message that says
        // what to do, rather than surfacing a unique-index violation.
        if (id == null && attributeRepository.existsByCode(request.code())) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_CODE_EXISTS,
                    "Attribute '" + request.code() + "' already exists. "
                            + "Use PUT /api/v1/admin/attributes/{id} to add values to it.");
        }
        if (id != null && !attribute.getCode().equals(request.code())
                && attributeRepository.existsByCode(request.code())) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_CODE_EXISTS);
        }

        // Flipping this after variants exist would orphan every SKU built from it.
        if (id != null && attribute.isVariantDefining() != request.variantDefining()) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_IN_USE,
                    "variantDefining cannot be changed once the attribute is in use. "
                            + "Create a new attribute instead.");
        }

        attribute.setCode(request.code());
        attribute.setDataType(request.dataType() == null
                ? AttributeDataType.LIST : AttributeDataType.valueOf(request.dataType()));
        attribute.setVariantDefining(request.variantDefining());
        attribute.setFilterable(request.filterable());
        if (request.displayOrder() != null) {
            attribute.setDisplayOrder(request.displayOrder().shortValue());
        }

        mergeAttributeTranslations(attribute, request.translations());

        if (request.values() != null) {
            applyValues(attribute, request);
        }

        Attribute saved = attributeRepository.save(attribute);
        log.info("Saved attribute id={} code={} variantDefining={}",
                saved.getId(), saved.getCode(), saved.isVariantDefining());
        return saved.getId();
    }

    private void applyValues(Attribute attribute, AttributeSaveRequest request) {
        // Two values with the same code in one payload would violate uq_av_code at
        // flush time, long after the useful context is gone.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var valueRequest : request.values()) {
            if (!seen.add(valueRequest.code())) {
                throw new BusinessException(ErrorCode.ATTRIBUTE_VALUE_CODE_EXISTS,
                        "Value code '" + valueRequest.code() + "' appears twice in the request");
            }
        }

        for (var valueRequest : request.values()) {
            AttributeValue value = attribute.getValues().stream()
                    .filter(v -> valueRequest.id() != null && valueRequest.id().equals(v.getId()))
                    .findFirst()
                    .orElseGet(() -> {
                        AttributeValue created = new AttributeValue();
                        created.setAttribute(attribute);
                        attribute.getValues().add(created);
                        return created;
                    });

            value.setCode(valueRequest.code());
            value.setHexColor(valueRequest.hexColor());
            if (valueRequest.displayOrder() != null) {
                value.setDisplayOrder(valueRequest.displayOrder().shortValue());
            }

            mergeValueTranslations(value, valueRequest.translations());
        }
    }

    // ---------------------------------------------------------------- merging

    /*
     * Translations are merged IN PLACE, never cleared and rebuilt.
     *
     * Calling clear() and re-adding the same locale looks equivalent, but Hibernate
     * schedules the INSERT of the new row before the DELETE of the old one inside a
     * single flush. Both carry the same composite primary key, so the insert hits a
     * constraint violation — which surfaces as a confusing 409 on what looks like a
     * simple edit.
     *
     * Updating the existing row and removing only the locales that genuinely
     * disappeared avoids the collision entirely.
     */

    private void mergeCategoryTranslations(Category category,
                                           List<TranslationRequest> requests) {
        Set<String> incoming = requests.stream()
                .map(TranslationRequest::locale)
                .collect(Collectors.toSet());

        // Only drop locales the caller actually removed.
        category.getTranslations().keySet().removeIf(locale -> !incoming.contains(locale));

        for (TranslationRequest t : requests) {
            CategoryTranslation translation = category.getTranslations().get(t.locale());
            if (translation == null) {
                translation = new CategoryTranslation();
                translation.attachTo(category, t.locale());
                category.getTranslations().put(t.locale(), translation);
            }
            translation.setName(t.name());
            translation.setDescription(t.description());
            translation.setMetaTitle(t.metaTitle());
            translation.setMetaDescription(t.metaDescription());
        }
    }

    private void mergeAttributeTranslations(Attribute attribute,
                                            List<AttributeSaveRequest.NameTranslation> requests) {
        Set<String> incoming = requests.stream()
                .map(AttributeSaveRequest.NameTranslation::locale)
                .collect(Collectors.toSet());

        attribute.getTranslations().keySet().removeIf(locale -> !incoming.contains(locale));

        for (var t : requests) {
            AttributeTranslation translation = attribute.getTranslations().get(t.locale());
            if (translation == null) {
                translation = new AttributeTranslation();
                translation.attachTo(attribute, t.locale());
                attribute.getTranslations().put(t.locale(), translation);
            }
            translation.setName(t.name());
        }
    }

    private void mergeValueTranslations(AttributeValue value,
                                        List<AttributeSaveRequest.NameTranslation> requests) {
        Set<String> incoming = requests.stream()
                .map(AttributeSaveRequest.NameTranslation::locale)
                .collect(Collectors.toSet());

        value.getTranslations().keySet().removeIf(locale -> !incoming.contains(locale));

        for (var t : requests) {
            AttributeValueTranslation translation = value.getTranslations().get(t.locale());
            if (translation == null) {
                translation = new AttributeValueTranslation();
                translation.attachTo(value, t.locale());
                value.getTranslations().put(t.locale(), translation);
            }
            translation.setName(t.name());
        }
    }
}
