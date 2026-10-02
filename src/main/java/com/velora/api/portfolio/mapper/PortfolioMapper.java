package com.velora.api.portfolio.mapper;

import com.velora.api.common.storage.StorageService;
import com.velora.api.portfolio.domain.PortfolioImage;
import com.velora.api.portfolio.domain.PortfolioItem;
import com.velora.api.portfolio.dto.PortfolioDetailResponse;
import com.velora.api.portfolio.dto.PortfolioSummaryResponse;
import com.velora.api.portfolio.dto.admin.PortfolioAdminResponse;
import org.springframework.stereotype.Component;

/** Entity to DTO. Call inside a transaction: images and category are lazy. */
@Component
public class PortfolioMapper {

    private final StorageService storageService;

    public PortfolioMapper(StorageService storageService) {
        this.storageService = storageService;
    }

    public PortfolioSummaryResponse toSummary(PortfolioItem item, String locale) {
        PortfolioImage main = item.mainImage();
        return new PortfolioSummaryResponse(
                item.getId(),
                item.getSlug(),
                item.titleFor(locale),
                main == null ? null : storageService.urlFor(main.getUrl()),
                main == null ? null : main.altFor(locale),
                item.getCategory() == null ? null : item.getCategory().getSlug(),
                item.getCategory() == null ? null : item.getCategory().nameFor(locale),
                item.getCompletedAt());
    }

    public PortfolioDetailResponse toDetail(PortfolioItem item, String locale) {
        return new PortfolioDetailResponse(
                item.getId(),
                item.getSlug(),
                item.titleFor(locale),
                item.descriptionFor(locale),
                item.getCategory() == null ? null : item.getCategory().getSlug(),
                item.getCategory() == null ? null : item.getCategory().nameFor(locale),
                item.getCompletedAt(),
                item.getImages().stream()
                        .map(i -> new PortfolioDetailResponse.PortfolioImageResponse(
                                storageService.urlFor(i.getUrl()), i.altFor(locale),
                                i.isMain(), i.getDisplayOrder()))
                        .toList());
    }

    public PortfolioAdminResponse toAdmin(PortfolioItem item) {
        return new PortfolioAdminResponse(
                item.getId(),
                item.getSlug(),
                item.getTitleAr(),
                item.getTitleEn(),
                item.getDescriptionAr(),
                item.getDescriptionEn(),
                item.getCategory() == null ? null : item.getCategory().getId(),
                item.getCategory() == null ? null : item.getCategory().nameFor("ar"),
                item.getCompletedAt(),
                item.getDisplayOrder(),
                item.isPublished(),
                item.getArchivedAt(),
                item.getImages().stream().map(this::toAdminImage).toList(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    public PortfolioAdminResponse.PortfolioImageAdminResponse toAdminImage(PortfolioImage image) {
        return new PortfolioAdminResponse.PortfolioImageAdminResponse(
                image.getId(),
                image.getUrl(),
                storageService.urlFor(image.getUrl()),
                image.getAltTextAr(),
                image.getAltTextEn(),
                image.isMain(),
                image.getDisplayOrder());
    }
}
