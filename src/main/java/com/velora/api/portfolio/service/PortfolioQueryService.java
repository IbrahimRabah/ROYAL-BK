package com.velora.api.portfolio.service;

import com.velora.api.common.dto.PageResponse;
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

    public PortfolioQueryService(PortfolioItemRepository repository, PortfolioMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
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
