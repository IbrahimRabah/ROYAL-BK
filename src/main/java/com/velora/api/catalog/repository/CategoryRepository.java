package com.velora.api.catalog.repository;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryScope;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** The storefront reads only PRODUCT categories: a PORTFOLIO slug is "not found" there. */
    @EntityGraph(attributePaths = {"translations"})
    Optional<Category> findBySlugAndScopeAndActiveTrue(String slug, CategoryScope scope);

    /**
     * The whole tree of one scope in one query. The header mega-menu needs it on every page, so
     * fetching level by level would be several round trips for data that changes once a month.
     * Scope is the only visibility rule besides {@code active}: an empty PRODUCT category stays.
     */
    @EntityGraph(attributePaths = {"translations"})
    List<Category> findByScopeAndActiveTrueOrderByDisplayOrderAscIdAsc(CategoryScope scope);

    /** Admin variant of the tree query: includes inactive categories too, of both scopes. */
    @EntityGraph(attributePaths = {"translations"})
    List<Category> findAllByOrderByDisplayOrderAscIdAsc();

    /** Admin tree of one scope, inactive categories included. */
    @EntityGraph(attributePaths = {"translations"})
    List<Category> findByScopeOrderByDisplayOrderAscIdAsc(CategoryScope scope);

    List<Category> findByParentIdAndScopeAndActiveTrueOrderByDisplayOrderAsc(Long parentId,
                                                                             CategoryScope scope);

    boolean existsByParentId(Long parentId);

    boolean existsBySlug(String slug);

    /** Whether a DIFFERENT category already uses this slug (the row being edited does not count). */
    boolean existsBySlugAndIdNot(String slug, Long id);
}
