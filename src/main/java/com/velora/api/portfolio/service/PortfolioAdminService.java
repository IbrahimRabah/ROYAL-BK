package com.velora.api.portfolio.service;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.SlugGenerator;
import com.velora.api.portfolio.domain.PortfolioItem;
import com.velora.api.portfolio.dto.admin.PortfolioAdminResponse;
import com.velora.api.portfolio.dto.admin.PortfolioCreateRequest;
import com.velora.api.portfolio.dto.admin.PortfolioUpdateRequest;
import com.velora.api.portfolio.mapper.PortfolioMapper;
import com.velora.api.portfolio.repository.PortfolioItemRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portfolio management: create, edit, publish, archive and restore.
 *
 * <p>Nothing here deletes a row. Archiving hides an item and forces it unpublished; restoring
 * brings it back as a draft, so a mistaken archive is undone through the API and not with SQL.
 */
@Service
public class PortfolioAdminService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAdminService.class);
    private static final int SLUG_MAX = 150;
    /** Leaves room for a "-123" suffix inside the column. */
    private static final int SLUG_BASE_MAX = 140;

    private final PortfolioItemRepository repository;
    private final CategoryRepository categoryRepository;
    private final PortfolioMapper mapper;

    public PortfolioAdminService(PortfolioItemRepository repository,
                                 CategoryRepository categoryRepository,
                                 PortfolioMapper mapper) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<PortfolioAdminResponse> list(boolean includeArchived, Long categoryId,
                                                     Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
                Sort.by(Sort.Order.asc("displayOrder"), Sort.Order.desc("id")));
        return PageResponse.from(repository.findForAdmin(includeArchived, categoryId, page),
                mapper::toAdmin);
    }

    @Transactional(readOnly = true)
    public PortfolioAdminResponse get(Long id) {
        return mapper.toAdmin(load(id));
    }

    @Transactional
    public PortfolioAdminResponse create(PortfolioCreateRequest request) {
        PortfolioItem item = new PortfolioItem();
        item.setSlug(newSlug(request.slug(), request.titleAr(), request.titleEn()));
        item.setTitleAr(request.titleAr().trim());
        item.setTitleEn(blankToNull(request.titleEn()));
        item.setDescriptionAr(blankToNull(request.descriptionAr()));
        item.setDescriptionEn(blankToNull(request.descriptionEn()));
        item.setCategory(loadCategory(request.categoryId()));
        item.setCompletedAt(request.completedAt());
        item.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
        item.setPublished(false);

        PortfolioItem saved = save(item);
        log.info("Created portfolio item id={} slug={}", saved.getId(), saved.getSlug());
        return mapper.toAdmin(saved);
    }

    @Transactional
    public PortfolioAdminResponse update(Long id, PortfolioUpdateRequest request) {
        PortfolioItem item = load(id);

        String requested = request.slug();
        if (requested != null && !requested.isBlank()) {
            String slug = normalizeSlug(requested);
            if (!slug.equals(item.getSlug())) {
                if (repository.existsBySlugAndIdNot(slug, id)) {
                    throw new BusinessException(ErrorCode.SLUG_ALREADY_EXISTS);
                }
                // A shared link to the old slug stops working. There is no redirect table for
                // portfolio, so the admin is told in the contract and it is logged here.
                log.warn("Portfolio slug changed for id={}: {} -> {}", id, item.getSlug(), slug);
                item.setSlug(slug);
            }
        }

        item.setTitleAr(request.titleAr().trim());
        item.setTitleEn(blankToNull(request.titleEn()));
        item.setDescriptionAr(blankToNull(request.descriptionAr()));
        item.setDescriptionEn(blankToNull(request.descriptionEn()));
        item.setCategory(loadCategory(request.categoryId()));
        item.setCompletedAt(request.completedAt());
        if (request.displayOrder() != null) {
            item.setDisplayOrder(request.displayOrder());
        }
        return mapper.toAdmin(save(item));
    }

    /** Explicit state, so a repeated call changes nothing. An archived item cannot go live. */
    @Transactional
    public PortfolioAdminResponse setPublished(Long id, boolean published) {
        PortfolioItem item = load(id);
        if (published && item.isArchived()) {
            throw new BusinessException(ErrorCode.PORTFOLIO_ITEM_ARCHIVED,
                    "Restore this item before publishing it");
        }
        item.setPublished(published);
        log.info("Portfolio item id={} published={}", id, published);
        return mapper.toAdmin(repository.save(item));
    }

    /** Archive, never delete. Images stay in storage. Repeating it changes nothing. */
    @Transactional
    public PortfolioAdminResponse archive(Long id) {
        PortfolioItem item = load(id);
        if (!item.isArchived()) {
            item.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
            item.setPublished(false);
            log.info("Archived portfolio item id={}", id);
        }
        return mapper.toAdmin(repository.save(item));
    }

    /** Back from the archive as an UNPUBLISHED draft: going live again is a deliberate step. */
    @Transactional
    public PortfolioAdminResponse restore(Long id) {
        PortfolioItem item = load(id);
        if (item.isArchived()) {
            item.setArchivedAt(null);
            log.info("Restored portfolio item id={}", id);
        }
        return mapper.toAdmin(repository.save(item));
    }

    // ------------------------------------------------------------------ helpers

    private PortfolioItem save(PortfolioItem item) {
        try {
            return repository.saveAndFlush(item);
        } catch (DataIntegrityViolationException ex) {
            // Two admins creating the same slug at once: the unique constraint is the referee.
            throw new BusinessException(ErrorCode.SLUG_ALREADY_EXISTS);
        }
    }

    /**
     * An explicit slug is taken as given (after the same normalisation products use) and
     * refused if taken; a generated one gets -2, -3 ... instead.
     */
    private String newSlug(String requested, String titleAr, String titleEn) {
        if (requested != null && !requested.isBlank()) {
            String slug = normalizeSlug(requested);
            if (repository.existsBySlug(slug)) {
                throw new BusinessException(ErrorCode.SLUG_ALREADY_EXISTS);
            }
            return slug;
        }
        String base = SlugGenerator.generate(titleAr);
        if (base == null) {
            base = SlugGenerator.generate(titleEn);
        }
        if (base == null) {
            throw slugNotDerivable();
        }
        String unique = SlugGenerator.generateUnique(cut(base, SLUG_BASE_MAX),
                candidate -> !repository.existsBySlug(candidate));
        return cut(unique, SLUG_MAX);
    }

    private String normalizeSlug(String requested) {
        String slug = SlugGenerator.generate(requested);
        if (slug == null) {
            throw slugNotDerivable();
        }
        return cut(slug, SLUG_MAX);
    }

    private static BusinessException slugNotDerivable() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "Could not build a URL slug from the title. Provide a 'slug' in Latin characters.");
    }

    private static String cut(String slug, int max) {
        if (slug.length() <= max) {
            return slug;
        }
        return slug.substring(0, max).replaceAll("-+$", "");
    }

    private Category loadCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    private PortfolioItem load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTFOLIO_ITEM_NOT_FOUND));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
