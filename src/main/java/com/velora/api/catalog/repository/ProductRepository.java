package com.velora.api.catalog.repository;

import com.velora.api.catalog.domain.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository
        extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * Product detail page. The entity graph loads translations, brand, category and
     * images in one round trip instead of four — with {@code open-in-view: false}
     * a lazy access here would throw, which is exactly the early warning we want.
     */
    @EntityGraph(attributePaths = {"translations", "brand", "category", "category.translations",
            "images"})
    Optional<Product> findBySlugAndArchivedAtIsNull(String slug);

    @EntityGraph(attributePaths = {"translations", "brand", "category", "images"})
    Optional<Product> findByIdAndArchivedAtIsNull(Long id);

    boolean existsBySlug(String slug);

    /** Archived products count too: they still reference their category. */
    boolean existsByCategoryId(Long categoryId);

    /** Every row counts, archived or not: uq_prod_slug covers them all. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /** "You may also like" — same category, excluding the product being viewed. */
    @Query("""
            select p from Product p
            where p.category.id = :categoryId
              and p.id <> :excludeId
              and p.status = com.velora.api.catalog.domain.ProductStatus.ACTIVE
              and p.archivedAt is null
            order by p.featured desc, p.createdAt desc
            """)
    java.util.List<Product> findRelated(@Param("categoryId") Long categoryId,
                                        @Param("excludeId") Long excludeId,
                                        org.springframework.data.domain.Pageable pageable);
}
