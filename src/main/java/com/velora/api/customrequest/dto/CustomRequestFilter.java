package com.velora.api.customrequest.dto;

import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.domain.CustomRequestType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Every field is optional; a null filter contributes nothing to the query. */
@Schema(description = "Admin list filters")
public record CustomRequestFilter(

        CustomRequestStatus status,

        CustomRequestType type,

        Long governorateId,

        @Schema(description = "A phone number (any local or international form) matches it "
                + "exactly; other text matches the contact name or request number")
        String q,

        @Schema(description = "Created on or after this day (Cairo time)", example = "2026-10-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @Schema(description = "Created on or before this day (Cairo time)", example = "2026-10-31")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to
) {
}
