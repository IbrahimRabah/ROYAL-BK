package com.velora.api.marketing.dto;

import com.velora.api.marketing.domain.PromotionScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "Create or replace a discount. It stays a draft until it is activated")
public record PromotionSaveRequest(
        @NotBlank(message = "The Arabic name is required") @Size(max = 150) String nameAr,
        @Size(max = 150) String nameEn,
        @NotNull(message = "scope is required") PromotionScope scope,
        @Schema(description = "The category or product id; absent for ALL") Long targetId,
        @Schema(description = "Percentage off, 0.01 to 95") @NotNull(message = "percent is required")
        @DecimalMin(value = "0.01", message = "percent must be above 0")
        @DecimalMax(value = "95", message = "percent cannot be above 95") BigDecimal percent,
        @NotNull(message = "startsAt is required") OffsetDateTime startsAt,
        @NotNull(message = "endsAt is required") OffsetDateTime endsAt,
        @Schema(description = "The line shown on the banner, Arabic") @Size(max = 200) String headlineAr,
        @Size(max = 200) String headlineEn,
        @Schema(description = "Must be true to save a discount above 70 percent: a typing slip is the usual cause")
        Boolean confirmHighDiscount
) {
}
