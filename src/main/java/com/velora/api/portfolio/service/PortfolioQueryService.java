package com.velora.api.portfolio.service;

import com.velora.api.catalog.domain.CategoryScope;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.storage.StorageService;
import com.velora.api.portfolio.dto.PortfolioCategoryResponse;
import java.util.List;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.portfolio.dto.PortfolioDetailResponse;
import com.velora.api.portfolio.dto.PortfolioSummaryResponse;
import com.velora.api.portfolio.mapper.PortfolioMapper;
import com.velora.api.portfolio.repository.PortfolioItemRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public side. Only published, non-archived items exist here: anything else is a plain 404,
 * the same answer as a slug that was never used.
 */
@Service
public class PortfolioQueryService {

    /** Fixed order: the curator's order, then newest work first. The client cannot re-sort. */
    static final Sort PUBLIC_ORDER = Sort.by(
            Sort.Order.asc("displayOrder"),
            Sort.Order.desc("completedAt").nullsLast(),
            Sort.Order.desc("id"));

    private static final int MAX_PAGE_SIZE = 50;

    private final PortfolioItemRepository repository;
    private final PortfolioMapper mapper;
    private final CategoryRepository categoryRepository;
    private final StorageService storageService;

    public PortfolioQueryService(PortfolioItemRepository repository, PortfolioMapper mapper,
                                 CategoryRepository categoryRepository,
                                 StorageService storageService) {
        this.repository = repository;
        this.mapper = mapper;
        this.categoryRepository = categoryRepository;
        this.storageService = storageService;
    }

    /**
     * The portfolio's own categories: PORTFOLIO scope, active, flat. The business keeps them one
     * level deep (doors, windows, stairs); if they ever nest, this becomes a tree like the
     * shop's. Product categories never appear here, and these never appear in the shop.
     */
    @Transactional(readOnly = true)
    public List<PortfolioCategoryResponse> categories(String locale) {
        return categoryRepository
                .findByScopeAndActiveTrueOrderByDisplayOrderAscIdAsc(CategoryScope.PORTFOLIO)
                .stream()
                .map(c -> new PortfolioCategoryResponse(c.getId(), c.getSlug(), c.nameFor(locale),
                        storageService.urlFor(c.getImageUrl()), c.getDisplayOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PortfolioSummaryResponse> list(Long categoryId, Pageable pageable,
                                                       String locale) {
        Pageable page = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), PUBLIC_ORDER);
        return PageResponse.from(repository.findLive(categoryId, page),
                item -> mapper.toSummary(item, locale));
    }

    @Transactional(readOnly = true)
    public PortfolioDetailResponse getBySlug(String slug, String locale) {
        return repository.findBySlugAndPublishedTrueAndArchivedAtIsNull(slug)
                .map(item -> mapper.toDetail(item, locale))
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTFOLIO_ITEM_NOT_FOUND));
    }
}
