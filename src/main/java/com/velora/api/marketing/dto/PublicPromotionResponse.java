package com.velora.api.marketing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A running discount, for the sale banners of the storefront. */
public record PublicPromotionResponse(
        Long id,
        String name,
        String headline,
        BigDecimal percent,
        String scope,
        Long targetId,
        String targetSlug,
        String bannerUrl,
        String bannerMobileUrl,
        OffsetDateTime endsAt,
        String landingPath
) {
}
