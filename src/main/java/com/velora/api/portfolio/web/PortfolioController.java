package com.velora.api.portfolio.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.portfolio.dto.PortfolioDetailResponse;
import com.velora.api.portfolio.dto.PortfolioSummaryResponse;
import com.velora.api.portfolio.service.PortfolioQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Portfolio", description = "Completed custom work, public")
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PortfolioQueryService queryService;

    public PortfolioController(PortfolioQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "List published portfolio items",
            description = "Curator order, then newest first. The sort parameter is ignored.",
            security = {})
    @GetMapping
    public PageResponse<PortfolioSummaryResponse> list(
            @Parameter(description = "Only items in this category") @RequestParam(required = false)
            Long categoryId,
            @ParameterObject @PageableDefault(size = 12) Pageable pageable,
            HttpServletRequest request) {
        return queryService.list(categoryId, pageable, LocaleResolver.resolve(request));
    }

    @Operation(summary = "One portfolio item by slug",
            description = "404 when the slug is unknown, unpublished or archived.", security = {})
    @GetMapping("/{slug}")
    public PortfolioDetailResponse get(@PathVariable String slug, HttpServletRequest request) {
        return queryService.getBySlug(slug, LocaleResolver.resolve(request));
    }
}
