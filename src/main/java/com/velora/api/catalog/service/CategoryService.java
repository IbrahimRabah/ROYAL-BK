package com.velora.api.catalog.service;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.dto.CategoryDetailResponse;
import com.velora.api.catalog.dto.CategoryTreeResponse;
import com.velora.api.catalog.mapper.CatalogMapper;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CatalogMapper mapper;

    public CategoryService(CategoryRepository categoryRepository, CatalogMapper mapper) {
        this.categoryRepository = categoryRepository;
        this.mapper = mapper;
    }

    /**
     * The whole tree, built in memory from ONE query.
     *
     * <p>Recursing into the database per level would be N+1 for data that changes
     * about once a month and is needed on every single page — the header menu.
     * Fetch it flat, assemble it here, and cache the result.
     */
    public List<CategoryTreeResponse> getTree(String locale) {
        List<Category> all = categoryRepository.findByActiveTrueOrderByDisplayOrderAscIdAsc();

        Map<Long, List<Category>> byParent = all.stream()
                .filter(c -> c.getParent() != null)
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));

        return all.stream()
                .filter(c -> c.getParent() == null)
                .sorted(Comparator.comparing(Category::getDisplayOrder))
                .map(root -> buildNode(root, byParent, locale, 0))
                .toList();
    }

    public CategoryDetailResponse findBySlug(String slug, String locale) {
        Category category = categoryRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Category not found"));

        List<CategoryTreeResponse> children = categoryRepository
                .findByParentIdAndActiveTrueOrderByDisplayOrderAsc(category.getId())
                .stream()
                .map(child -> mapper.toTreeNode(child, locale, List.of()))
                .toList();

        CategoryTranslation translation = category.translationFor(locale);

        return new CategoryDetailResponse(
                category.getId(),
                category.getSlug(),
                category.nameFor(locale),
                translation == null ? null : translation.getDescription(),
                category.getImageUrl(),
                category.getBannerUrl(),
                category.getProductCount() == null ? 0 : category.getProductCount(),
                children,
                mapper.buildBreadcrumb(category, locale),
                translation == null ? null : translation.getMetaTitle(),
                translation == null ? null : translation.getMetaDescription());
    }

    private CategoryTreeResponse buildNode(Category category,
                                           Map<Long, List<Category>> byParent,
                                           String locale,
                                           int depth) {
        // Guard against a cycle introduced by a bad parent_id.
        List<CategoryTreeResponse> children = depth >= 5
                ? List.of()
                : new ArrayList<>(byParent.getOrDefault(category.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(Category::getDisplayOrder))
                        .map(child -> buildNode(child, byParent, locale, depth + 1))
                        .toList());

        return mapper.toTreeNode(category, locale, children);
    }
}
