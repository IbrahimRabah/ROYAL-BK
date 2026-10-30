package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Publish or unpublish several products at once")
public record ProductBulkStatusRequest(

        @Schema(description = "Product ids. At most 500 per request.")
        @NotEmpty(message = "At least one product is required")
        @Size(max = 500, message = "At most 500 products per request")
        List<Long> ids,

        @Schema(description = "PUBLISH takes DRAFT products live. UNPUBLISH returns ACTIVE products to draft.")
        @NotNull(message = "action is required")
        Action action
) {

    public enum Action {
        PUBLISH,
        UNPUBLISH
    }
}
