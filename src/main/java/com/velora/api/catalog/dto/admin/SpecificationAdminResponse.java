package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One saved specification row, as seen by staff — the read-side counterpart of
 * {@link ProductCreateRequest.SpecificationRequest}, same shape, so the admin form
 * can load a product's specifications, edit them, and send the array straight back.
 */
@Schema(description = "One specification row, as seen by staff")
public record SpecificationAdminResponse(
        Long attributeId,
        @Schema(description = "Set for LIST attributes") Long attributeValueId,
        @Schema(description = "Set for TEXT, NUMBER and BOOLEAN attributes") String valueText
) {
}
