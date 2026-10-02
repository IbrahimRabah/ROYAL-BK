package com.velora.api.portfolio.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Show or hide an item on the storefront. Explicit, not a toggle: sending it "
        + "twice leaves the same state.")
public record PortfolioPublishRequest(@NotNull(message = "published is required") Boolean published) {
}
