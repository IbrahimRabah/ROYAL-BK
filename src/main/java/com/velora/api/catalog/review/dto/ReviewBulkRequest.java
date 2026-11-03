package com.velora.api.catalog.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Approve or reject several reviews at once")
public record ReviewBulkRequest(
        @NotNull(message = "action is required") Action action,
        @NotEmpty(message = "At least one review is required")
        @Size(max = 500, message = "At most 500 reviews per request") List<Long> ids,
        @Size(max = 500, message = "The note is at most 500 characters") String note
) {

    public enum Action { APPROVE, REJECT }
}
