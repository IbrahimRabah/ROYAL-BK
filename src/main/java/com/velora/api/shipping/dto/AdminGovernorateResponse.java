package com.velora.api.shipping.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A governorate as staff see it: which zone it is in, and whether "
        + "we deliver there")
public record AdminGovernorateResponse(
        Long id,
        String code,
        String nameAr,
        String nameEn,
        @Schema(description = "Null when the governorate is closed") Long zoneId,
        String zoneCode,
        @Schema(description = "True when it is in an active zone that has rates. False means "
                + "quote, checkout and saved addresses all refuse it.")
        boolean served
) {
}
