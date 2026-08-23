package com.velora.api.catalog.repository;

import com.velora.api.catalog.domain.Attribute;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttributeRepository extends JpaRepository<Attribute, Long> {

    @EntityGraph(attributePaths = {"translations", "values", "values.translations"})
    List<Attribute> findByFilterableTrueOrderByDisplayOrderAsc();

    /**
     * Admin listing: every attribute regardless of {@code filterable}, since staff
     * need to see specification-only attributes too, not just the storefront facets.
     */
    @EntityGraph(attributePaths = {"translations", "values", "values.translations"})
    List<Attribute> findAllByOrderByDisplayOrderAsc();

    /** Same as above, narrowed to variant-defining or specification-only attributes. */
    @EntityGraph(attributePaths = {"translations", "values", "values.translations"})
    List<Attribute> findByVariantDefiningOrderByDisplayOrderAsc(boolean variantDefining);

    Optional<Attribute> findByCode(String code);

    /** Checked before insert so the user gets ATTRIBUTE_CODE_EXISTS, not a raw 500. */
    boolean existsByCode(String code);

    /** True when the attribute is already used to define at least one variant. */
    @Query("""
            select count(vav) > 0 from VariantAttributeValue vav
            where vav.attribute.id = :attributeId
            """)
    boolean isUsedByVariants(@Param("attributeId") Long attributeId);

    /**
     * Filter facets for a category: only the attribute values that actually occur on
     * products in that category. Showing a colour with zero results is a dead end
     * for the customer.
     *
     * <p>An attribute value is only ever linked one of two ways, never both: via
     * {@code VariantAttributeValue} for variant-defining attributes (colour, size),
     * or via {@code ProductAttributeValue} for specification-only ones (strap
     * material, movement). Checking only the variant table would silently exclude
     * every specification-only attribute from the filter sidebar, even when
     * products in the category genuinely have the value set.
     */
    @Query("""
            select distinct a from Attribute a
            join a.values v
            where a.filterable = true
              and (
                exists (
                    select 1 from VariantAttributeValue vav
                    join vav.variant var
                    join var.product p
                    where vav.attributeValue.id = v.id
                      and p.status = com.velora.api.catalog.domain.ProductStatus.ACTIVE
                      and p.archivedAt is null
                      and (p.category.id = :categoryId or p.category.parent.id = :categoryId)
                )
                or exists (
                    select 1 from ProductAttributeValue pav
                    join pav.product p
                    where pav.attributeValue.id = v.id
                      and p.status = com.velora.api.catalog.domain.ProductStatus.ACTIVE
                      and p.archivedAt is null
                      and (p.category.id = :categoryId or p.category.parent.id = :categoryId)
                )
              )
            order by a.displayOrder asc
            """)
    List<Attribute> findFacetsForCategory(@Param("categoryId") Long categoryId);
}
