package com.velora.api.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TeamRolesRequest(
        @Schema(description = "Exactly the staff roles the account should have: any of ADMIN, SEO_EDITOR. "
                + "An empty list makes it a plain customer again")
        @NotNull(message = "roles is required") List<String> roles
) {
}
