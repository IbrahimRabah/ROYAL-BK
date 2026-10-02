package com.velora.api.catalog.dto.admin;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Update a product")
public record ProductUpdateRequest(

        @NotNull Long categoryId,

        Long brandId,

        @Schema(description = """
                Changing a published slug breaks existing links and search rankings.
                The server records the old value in url_redirect automatically.
                """)
        String slug,

        @Valid List<TranslationRequest> translations,

        boolean featured,

        boolean newArrival,

        @Valid List<ProductCreateRequest.SpecificationRequest> specifications,

        @Schema(description = "Omit to leave unchanged")
        FulfillmentType fulfillmentType,

        @Schema(description = "Omit to leave unchanged. Must end up set when the product is "
                + "READY_MADE")
        ShippingSizeClass shippingSizeClass,

        @Schema(description = "Omit to leave unchanged")
        Boolean requiresAssembly,

        @Schema(description = "Assembly fee PER PIECE, tax-inclusive. Omit to leave unchanged. Only "
                + "charged while requiresAssembly is true.")
        @DecimalMin(value = "0", message = "The assembly fee cannot be negative")
        @Digits(integer = 15, fraction = 4)
        BigDecimal assemblyFee
) {

    /** An update that leaves assembly alone: what every caller did before the fee existed. */
    public ProductUpdateRequest(Long categoryId, Long brandId, String slug,
                                List<TranslationRequest> translations, boolean featured,
                                boolean newArrival,
                                List<ProductCreateRequest.SpecificationRequest> specifications,
                                FulfillmentType fulfillmentType, ShippingSizeClass shippingSizeClass) {
        this(categoryId, brandId, slug, translations, featured, newArrival, specifications,
                fulfillmentType, shippingSizeClass, null, null);
    }
}
