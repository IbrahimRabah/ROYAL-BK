package com.velora.api.catalog.dto.admin;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "Create a product. Variants are added separately.")
public record ProductCreateRequest(

        @NotNull(message = "Category is required")
        Long categoryId,

        Long brandId,

        @Schema(description = "Leave empty to generate from the English or Arabic name")
        String slug,

        @Schema(description = "At least Arabic. English is optional but recommended for SEO.")
        @NotEmpty(message = "At least one translation is required")
        @Valid
        List<TranslationRequest> translations,

        boolean featured,

        boolean newArrival,

        @Schema(description = "Informational specifications — movement, water resistance, notes")
        @Valid
        List<SpecificationRequest> specifications,

        @Schema(description = "How the product is sold. Defaults to READY_MADE when omitted.")
        FulfillmentType fulfillmentType,

        @Schema(description = "Required when fulfillmentType is READY_MADE")
        ShippingSizeClass shippingSizeClass
) {

    @Schema(description = "One specification row")
    public record SpecificationRequest(
            @NotNull Long attributeId,
            @Schema(description = "For LIST attributes") Long attributeValueId,
            @Schema(description = "For TEXT and NUMBER attributes") String valueText
    ) {
    }
}
