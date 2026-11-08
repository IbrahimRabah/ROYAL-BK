package com.velora.api.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One message of the strip above the header, in the language asked for. */
public record PublicAnnouncementResponse(
        Long id,
        String text,
        String linkUrl,
        String linkLabel,
        String animation,
        String theme,
        boolean dismissible,
        @Schema(description = "When to count down to; absent when there is no countdown") OffsetDateTime countdownEndsAt,
        BigDecimal promotionPercent,
        @Schema(description = "Changes whenever the message is edited, so a dismissal of the old text does not hide the new one")
        long version
) {
}
