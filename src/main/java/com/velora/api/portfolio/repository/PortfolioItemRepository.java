package com.velora.api.portfolio.repository;

import com.velora.api.portfolio.domain.PortfolioItem;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortfolioItemRepository extends JpaRepository<PortfolioItem, Long> {

    /** Slugs are unique over archived items too. */
    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Optional<PortfolioItem> findBySlugAndPublishedTrueAndArchivedAtIsNull(String slug);

    @Query("""
            select i from PortfolioItem i
            where i.published = true and i.archivedAt is null
              and (:categoryId is null or i.category.id = :categoryId)
            """)
    Page<PortfolioItem> findLive(@Param("categoryId") Long categoryId, Pageable pageable);

    @Query("""
            select i from PortfolioItem i
            where (:includeArchived = true or i.archivedAt is null)
              and (:categoryId is null or i.category.id = :categoryId)
            """)
    Page<PortfolioItem> findForAdmin(@Param("includeArchived") boolean includeArchived,
                                     @Param("categoryId") Long categoryId, Pageable pageable);
}
