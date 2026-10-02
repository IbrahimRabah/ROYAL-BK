package com.velora.api.catalog.service.admin;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeDataType;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.domain.Brand;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductAttributeValue;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.dto.admin.ProductAdminResponse;
import com.velora.api.catalog.dto.admin.ProductCreateRequest;
import com.velora.api.catalog.dto.admin.ProductUpdateRequest;
import com.velora.api.catalog.dto.admin.SpecificationAdminResponse;
import com.velora.api.catalog.dto.admin.TranslationRequest;
import com.velora.api.catalog.dto.admin.TranslationResponse;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.BrandRepository;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.ArabicNormalizer;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.common.util.SlugGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product create, update, publish and archive.
 *
 * <p>Two things happen automatically here and must never be delegated to the client:
 * <ul>
 *   <li><b>slug generation</b> — unique, transliterated, and stable once published</li>
 *   <li><b>search_text</b> — the Arabic-normalized copy used for searching. It is
 *       derived with the SAME function the query uses. If a client could supply it,
 *       the two would eventually diverge and search would silently stop matching.</li>
 * </ul>
 */
@Service
public class ProductAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdminService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final AttributeRepository attributeRepository;

    public ProductAdminService(ProductRepository productRepository,
                               CategoryRepository categoryRepository,
                               BrandRepository brandRepository,
                               AttributeRepository attributeRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.attributeRepository = attributeRepository;
    }

    // -------------------------------------------------------------------- create

    @Transactional
    public ProductAdminResponse create(ProductCreateRequest request) {
        Product product = new Product();
        product.setCategory(loadCategory(request.categoryId()));
        product.setBrand(loadBrand(request.brandId()));
        product.setFeatured(request.featured());
        product.setNewArrival(request.newArrival());
        product.setStatus(ProductStatus.DRAFT);
        if (request.fulfillmentType() != null) {
            product.setFulfillmentType(request.fulfillmentType());
        }
        product.setShippingSizeClass(request.shippingSizeClass());
        product.setRequiresAssembly(Boolean.TRUE.equals(request.requiresAssembly()));
        product.setAssemblyFee(request.assemblyFee() == null
                ? MoneyUtils.ZERO : MoneyUtils.round(request.assemblyFee()));
        requireShippingSizeForReadyMade(product);

        String slug = resolveSlug(request.slug(), request.translations(), null);
        product.setSlug(slug);

        applyTranslations(product, request.translations());
        applySpecifications(product, request.specifications());

        Product saved = productRepository.save(product);
        log.info("Created product id={} slug={}", saved.getId(), saved.getSlug());
        return toResponse(saved);
    }

    // -------------------------------------------------------------------- update

    @Transactional
    public ProductAdminResponse update(Long id, ProductUpdateRequest request) {
        Product product = load(id);

        product.setCategory(loadCategory(request.categoryId()));
        product.setBrand(loadBrand(request.brandId()));
        product.setFeatured(request.featured());
        product.setNewArrival(request.newArrival());
        // Omitted means unchanged, so existing admin clients that do not send these
        // fields keep working.
        if (request.fulfillmentType() != null) {
            product.setFulfillmentType(request.fulfillmentType());
        }
        if (request.shippingSizeClass() != null) {
            product.setShippingSizeClass(request.shippingSizeClass());
        }
        if (request.requiresAssembly() != null) {
            product.setRequiresAssembly(request.requiresAssembly());
        }
        if (request.assemblyFee() != null) {
            product.setAssemblyFee(MoneyUtils.round(request.assemblyFee()));
        }
        requireShippingSizeForReadyMade(product);

        String requestedSlug = request.slug();
        if (requestedSlug != null && !requestedSlug.equals(product.getSlug())) {
            String newSlug = resolveSlug(requestedSlug, request.translations(), product.getId());
            // TODO(SEO): write the old slug into url_redirect so existing links survive.
            log.warn("Slug changed for product id={}: {} -> {}",
                    id, product.getSlug(), newSlug);
            product.setSlug(newSlug);
        }

        if (request.translations() != null && !request.translations().isEmpty()) {
            applyTranslations(product, request.translations());
        }
        if (request.specifications() != null) {
            applySpecifications(product, request.specifications());
        }

        return toResponse(productRepository.save(product));
    }

    // ------------------------------------------------------------- status changes

    /**
     * A READY_MADE product cannot go live without at least one variant — it would
     * render as a page with nothing to buy. MADE_TO_ORDER and CUSTOM_WORK products are
     * not bought through the cart, so they publish without variants.
     */
    @Transactional
    public ProductAdminResponse publish(Long id) {
        Product product = load(id);

        if (product.isReadyMade() && product.getVariants().isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_HAS_NO_VARIANTS);
        }
        if (product.getTranslations().get("ar") == null) {
            throw new BusinessException(ErrorCode.PRODUCT_MISSING_ARABIC_NAME);
        }

        product.setStatus(ProductStatus.ACTIVE);
        if (product.getPublishedAt() == null) {
            product.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        product.setArchivedAt(null);
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductAdminResponse unpublish(Long id) {
        Product product = load(id);
        product.setStatus(ProductStatus.DRAFT);
        return toResponse(productRepository.save(product));
    }

    /**
     * Archive, never delete. Order lines reference this product for reporting and
     * reorder, so the row has to survive even when the product is off sale forever.
     */
    @Transactional
    public ProductAdminResponse archive(Long id) {
        Product product = load(id);
        product.setStatus(ProductStatus.ARCHIVED);
        product.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        log.info("Archived product id={}", id);
        return toResponse(productRepository.save(product));
    }

    /** Copies everything except the SKUs and stock, which must be unique. */
    @Transactional
    public ProductAdminResponse duplicate(Long id) {
        Product source = load(id);

        Product copy = new Product();
        copy.setCategory(source.getCategory());
        copy.setBrand(source.getBrand());
        copy.setFeatured(false);
        copy.setNewArrival(false);
        copy.setFulfillmentType(source.getFulfillmentType());
        copy.setShippingSizeClass(source.getShippingSizeClass());
        copy.setRequiresAssembly(source.isRequiresAssembly());
        copy.setAssemblyFee(source.getAssemblyFee());
        copy.setStatus(ProductStatus.DRAFT);
        copy.setSlug(SlugGenerator.generateUnique(
                source.getSlug() + "-copy", s -> !productRepository.existsBySlug(s)));

        source.getTranslations().forEach((locale, t) -> {
            ProductTranslation copied = new ProductTranslation();
            copied.attachTo(copy, locale);
            copied.setName(t.getName() + " (copy)");
            copied.setShortDescription(t.getShortDescription());
            copied.setDescription(t.getDescription());
            copied.setSearchText(buildSearchText(t.getName(), t.getShortDescription()));
            copy.getTranslations().put(locale, copied);
        });

        Product saved = productRepository.save(copy);
        log.info("Duplicated product id={} into id={}", id, saved.getId());
        return toResponse(saved);
    }

    // --------------------------------------------------------------------- query

    @Transactional(readOnly = true)
    public PageResponse<ProductAdminResponse> list(Pageable pageable) {
        Page<Product> page = productRepository.findAll(pageable);
        return PageResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductAdminResponse get(Long id) {
        return toResponse(load(id));
    }

    // ------------------------------------------------------------------ internal

    private void applyTranslations(Product product, List<TranslationRequest> translations) {
        if (translations == null) {
            return;
        }
        /*
         * Merged IN PLACE, never cleared and rebuilt.
         *
         * Clearing and re-adding the same locale makes Hibernate schedule the INSERT
         * before the DELETE within one flush. Both rows share the composite primary
         * key, so the insert violates it — and the failure surfaces as a baffling 409
         * on an ordinary edit.
         */
        Set<String> incoming = translations.stream()
                .map(TranslationRequest::locale)
                .collect(Collectors.toSet());
        product.getTranslations().keySet().removeIf(locale -> !incoming.contains(locale));

        for (TranslationRequest request : translations) {
            ProductTranslation translation = product.getTranslations().get(request.locale());
            if (translation == null) {
                translation = new ProductTranslation();
                // attachTo sets the parent association; @MapsId derives product_id from
                // it. Setting the id half of the key by hand does not work before the
                // product is persisted.
                translation.attachTo(product, request.locale());
                product.getTranslations().put(request.locale(), translation);
            }

            translation.setName(request.name());
            translation.setShortDescription(request.shortDescription());
            translation.setDescription(request.description());
            translation.setMetaTitle(request.metaTitle());
            translation.setMetaDescription(request.metaDescription());

            // Derived here with the SAME normalizer the search query uses, and never
            // accepted from the client — the two must not drift apart.
            translation.setSearchText(
                    buildSearchText(request.name(), request.shortDescription()));
        }
    }

    /**
     * Normalizes with the SAME function {@code ProductSpecifications.matches()} uses
     * on the incoming query. These two must never drift apart.
     */
    static String buildSearchText(String name, String shortDescription) {
        String combined = name + " " + (shortDescription == null ? "" : shortDescription);
        String normalized = ArabicNormalizer.normalize(combined);
        if (normalized == null) {
            return null;
        }
        return normalized.length() > MAX_SEARCH_TEXT_LENGTH
                ? normalized.substring(0, MAX_SEARCH_TEXT_LENGTH) : normalized;
    }

    /**
     * The longest {@code search_text} that is stored, and the guard that keeps a product
     * save from failing on the {@code ix_prod_search} index.
     *
     * <p>That index is on {@code (locale, search_text)}. {@code search_text} is NVARCHAR, two
     * bytes per character, and SQL Server limits an index entry to 1700 bytes: 849 characters
     * fit, 850 fail with error 1946 (measured on the real schema). 800 leaves a margin.
     *
     * <p>The 255 / 500 character limits on name and short description keep real input to 756, so
     * this cap is not normally reached. It is here for the day those limits are raised:
     * truncating costs a little search coverage at the far end of a long description; failing
     * the save would cost the product. Keep it below 849 if the column or the index change.
     */
    static final int MAX_SEARCH_TEXT_LENGTH = 800;

    private void applySpecifications(Product product,
                                     List<ProductCreateRequest.SpecificationRequest> specs) {
        if (specs == null) {
            return;
        }

        /*
         * Merged IN PLACE, never cleared and rebuilt — same reasoning as
         * applyTranslations.
         *
         * Clearing and re-adding a specification for an attribute that already had
         * one schedules the INSERT of the new row before the DELETE of the old one
         * within the same flush. Both carry the same composite primary key
         * (product_id, attribute_id), and Hibernate cannot reconcile two different
         * object instances at that identity — it throws NonUniqueObjectException,
         * surfacing as a raw 500 on an ordinary re-save that changed nothing about
         * the specification at all. (The comment this replaced claimed there was
         * "no natural key collision risk" here; there is — it is the exact
         * translations problem, just for a different table.)
         */
        Map<Long, ProductAttributeValue> existingByAttribute = product.getSpecifications().stream()
                .collect(Collectors.toMap(pav -> pav.getAttribute().getId(), pav -> pav));

        Set<Long> incomingAttributeIds = specs.stream()
                .map(ProductCreateRequest.SpecificationRequest::attributeId)
                .collect(Collectors.toSet());
        product.getSpecifications()
                .removeIf(pav -> !incomingAttributeIds.contains(pav.getAttribute().getId()));

        for (ProductCreateRequest.SpecificationRequest spec : specs) {
            Attribute attribute = attributeRepository.findById(spec.attributeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ATTRIBUTE_NOT_FOUND,
                            "Attribute not found: " + spec.attributeId()));

            ProductAttributeValue pav = existingByAttribute.get(spec.attributeId());
            if (pav == null) {
                pav = new ProductAttributeValue();
                // @MapsId derives both halves of the composite key from these
                // associations. Setting them by hand would put a null product id in
                // the key on create.
                pav.setKey(new ProductAttributeValue.Key());
                pav.setProduct(product);
                pav.setAttribute(attribute);
                product.getSpecifications().add(pav);
            }

            /*
             * A LIST attribute's value is a reference to AttributeValue, never free
             * text — ProductAttributeValue.displayValue() only falls back to
             * valueText when attributeValue is null. Setting valueText alone for a
             * LIST attribute (the previous behaviour here) saved a row with BOTH
             * fields null: a specification that looked like it saved (200 OK) but
             * carried no value at all, and was silently dropped everywhere it was
             * read back (buildSpecifications() filters out a null display value).
             */
            if (attribute.getDataType() == AttributeDataType.LIST) {
                if (spec.attributeValueId() == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "'%s' is a list attribute — attributeValueId is required"
                                    .formatted(attribute.getCode()));
                }
                AttributeValue value = attribute.getValues().stream()
                        .filter(v -> v.getId().equals(spec.attributeValueId()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.ATTRIBUTE_VALUE_NOT_FOUND,
                                "Value %d does not belong to attribute '%s'"
                                        .formatted(spec.attributeValueId(), attribute.getCode())));
                pav.setAttributeValue(value);
                pav.setValueText(null);
            } else {
                pav.setValueText(spec.valueText());
                pav.setAttributeValue(null);
            }
        }
    }

    private String resolveSlug(String requested, List<TranslationRequest> translations,
                               Long excludeProductId) {
        String source = requested;
        if (source == null || source.isBlank()) {
            source = translations.stream()
                    .filter(t -> "en".equals(t.locale()))
                    .map(TranslationRequest::name)
                    .findFirst()
                    .orElseGet(() -> translations.get(0).name());
        }

        String slug = SlugGenerator.generateUnique(source, candidate -> {
            if (excludeProductId == null) {
                return !productRepository.existsBySlug(candidate);
            }
            return productRepository.findBySlugAndArchivedAtIsNull(candidate)
                    .map(existing -> existing.getId().equals(excludeProductId))
                    .orElse(true);
        });

        if (slug == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Could not build a URL slug from the given name. "
                            + "Provide a 'slug' in Latin characters.");
        }
        return slug;
    }

    private void requireShippingSizeForReadyMade(Product product) {
        if (product.isReadyMade() && product.getShippingSizeClass() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "shippingSizeClass is required for READY_MADE products");
        }
    }

    private Product load(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private Category loadCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                        "Category not found: " + id));
    }

    private Brand loadBrand(Long id) {
        if (id == null) {
            return null;
        }
        return brandRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND,
                        "Brand not found: " + id));
    }

    private ProductAdminResponse toResponse(Product product) {
        List<String> warnings = new ArrayList<>();
        if (product.isReadyMade() && product.getVariants().isEmpty()) {
            warnings.add("No variants — this product cannot be published or bought");
        }
        if (product.getImages().isEmpty()) {
            warnings.add("No images");
        }
        if (product.getTranslations().get("en") == null) {
            warnings.add("No English translation — weakens SEO");
        }
        if (product.getStatus() == ProductStatus.ACTIVE && !product.isInStock()) {
            warnings.add("Published but out of stock");
        }

        ProductTranslation ar = product.getTranslations().get("ar");
        ProductTranslation en = product.getTranslations().get("en");

        List<TranslationResponse> translations = product.getTranslations().values().stream()
                .sorted(Comparator.comparing(t -> t.getKey().getLocale()))
                .map(t -> new TranslationResponse(
                        t.getKey().getLocale(),
                        t.getName(),
                        t.getShortDescription(),
                        t.getDescription(),
                        t.getMetaTitle(),
                        t.getMetaDescription()))
                .toList();

        List<SpecificationAdminResponse> specifications = product.getSpecifications().stream()
                .sorted(Comparator.comparing(pav -> pav.getAttribute().getDisplayOrder()))
                .map(pav -> new SpecificationAdminResponse(
                        pav.getAttribute().getId(),
                        pav.getAttributeValue() == null ? null : pav.getAttributeValue().getId(),
                        pav.getValueText()))
                .toList();

        return new ProductAdminResponse(
                product.getId(),
                product.getSlug(),
                product.getStatus().name(),
                ar == null ? null : ar.getName(),
                en == null ? null : en.getName(),
                translations,
                product.getCategory() == null ? null : product.getCategory().getId(),
                product.getCategory() == null ? null : product.getCategory().nameFor("ar"),
                product.getBrand() == null ? null : product.getBrand().getId(),
                product.getBrand() == null ? null : product.getBrand().getNameAr(),
                product.isFeatured(),
                product.isNewArrival(),
                product.getFulfillmentType(),
                product.getShippingSizeClass(),
                product.isRequiresAssembly(),
                product.getAssemblyFee(),
                specifications,
                product.getVariants().size(),
                product.getImages().size(),
                product.getMinPrice(),
                product.getMaxPrice(),
                product.getAvailableQty(),
                product.getPublishedAt(),
                product.getArchivedAt(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                warnings);
    }
}
