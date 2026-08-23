package com.velora.api.catalog.service;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeValue;
import com.velora.api.catalog.dto.AttributeValueResponse;
import com.velora.api.catalog.dto.BrandResponse;
import com.velora.api.catalog.dto.FilterFacetsResponse;
import com.velora.api.catalog.dto.VariantOptionResponse;
import com.velora.api.catalog.mapper.CatalogMapper;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.BrandRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the filter sidebar.
 *
 * <p>Only attributes that actually occur on products in the category are returned.
 * Offering "Gold" when no gold watch is in stock sends the customer to an empty
 * results page, which is worse than not offering it at all.
 */
@Service
@Transactional(readOnly = true)
public class FacetService {

    private final AttributeRepository attributeRepository;
    private final BrandRepository brandRepository;
    private final CatalogMapper mapper;

    public FacetService(AttributeRepository attributeRepository,
                        BrandRepository brandRepository,
                        CatalogMapper mapper) {
        this.attributeRepository = attributeRepository;
        this.brandRepository = brandRepository;
        this.mapper = mapper;
    }

    public FilterFacetsResponse getFacets(Long categoryId, String locale) {
        List<Attribute> attributes = categoryId != null
                ? attributeRepository.findFacetsForCategory(categoryId)
                : attributeRepository.findByFilterableTrueOrderByDisplayOrderAsc();

        List<VariantOptionResponse> attributeFacets = attributes.stream()
                .sorted(Comparator.comparing(Attribute::getDisplayOrder))
                .map(attribute -> new VariantOptionResponse(
                        attribute.getId(),
                        attribute.getCode(),
                        attribute.nameFor(locale),
                        attribute.getValues().stream()
                                // AttributeRepository fetch-joins values.translations
                                // alongside values — a value with 2 translations (ar+en)
                                // makes Hibernate hydrate the SAME AttributeValue row
                                // twice per translation, doubling again if the attribute
                                // itself has 2 translations. Same cartesian-product
                                // pattern as ProductVariantRepository / AttributeRepository
                                // elsewhere in this codebase; distinct() collapses the
                                // duplicates back since they're the same managed entity.
                                .distinct()
                                .sorted(Comparator.comparing(AttributeValue::getDisplayOrder))
                                .map(v -> AttributeValueResponse.of(
                                        v.getId(), v.getCode(),
                                        v.nameFor(locale), v.getHexColor()))
                                .toList()))
                .toList();

        List<BrandResponse> brands = brandRepository.findByActiveTrueOrderByNameArAsc().stream()
                .map(brand -> mapper.toBrand(brand, locale))
                .toList();

        // TODO: derive the real min/max from the category once volumes justify the query.
        return new FilterFacetsResponse(brands, attributeFacets, null, null);
    }
}
