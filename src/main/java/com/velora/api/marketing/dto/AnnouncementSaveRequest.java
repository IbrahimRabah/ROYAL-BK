package com.velora.api.marketing.dto;

import com.velora.api.marketing.domain.AnnouncementAnimation;
import com.velora.api.marketing.domain.AnnouncementTheme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

@Schema(description = "Create or replace an announcement")
public record AnnouncementSaveRequest(
        @Schema(description = "Required unless the announcement is linked to a promotion, which then writes it") @Size(max = 300) String textAr,
        @Size(max = 300) String textEn,
        @Schema(description = "A path on this site such as /products?onSale=true, or an https link") @Size(max = 500) String linkUrl,
        @Size(max = 60) String linkLabelAr,
        @Size(max = 60) String linkLabelEn,
        @NotNull(message = "animation is required") AnnouncementAnimation animation,
        @NotNull(message = "theme is required") AnnouncementTheme theme,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Boolean active,
        Boolean dismissible,
        @Schema(description = "Count down to the end of the linked promotion, or to endsAt") Boolean showCountdown,
        Long promotionId
) {
}
