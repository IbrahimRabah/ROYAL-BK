package com.velora.api.customrequest.dto;

import com.velora.api.customrequest.domain.CustomRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Move a request to a new status")
public record CustomRequestStatusRequest(

        @Schema(description = "CONTACTED, ACCEPTED or REJECTED. QUOTED is reached by giving a "
                + "quote; CONVERTED is not available yet.")
        @NotNull(message = "Status is required")
        CustomRequestStatus status,

        @Schema(description = "Required when rejecting. Kept as the request's latest note "
                + "and recorded in the audit log.")
        @Size(max = 1000)
        String note
) {
}
