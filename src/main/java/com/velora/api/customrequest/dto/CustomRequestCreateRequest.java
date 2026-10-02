package com.velora.api.customrequest.dto;

import com.velora.api.customrequest.domain.CustomRequestType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Ask for a size, a made-to-order piece or custom work. No account needed.")
public record CustomRequestCreateRequest(

        @Schema(description = "SIZE_VARIANT needs productId (a ready-made product) and at "
                + "least one dimension")
        @NotNull(message = "Type is required")
        CustomRequestType type,

        @Schema(description = "Required for SIZE_VARIANT; optional for the other types")
        Long productId,

        @NotBlank(message = "Contact name is required")
        @Size(max = 150)
        String contactName,

        @Schema(example = "01012345678", description = "Normalized to E.164 by the server")
        @NotBlank(message = "Phone number is required")
        String phone,

        String altPhone,

        @Email(message = "Email is not valid")
        @Size(max = 255)
        String email,

        @NotNull(message = "Governorate is required")
        Long governorateId,

        @Size(max = 150) String area,
        @Size(max = 255) String streetAddress,

        @Schema(description = "Centimetres") @Positive @DecimalMax("10000") BigDecimal widthCm,
        @Schema(description = "Centimetres") @Positive @DecimalMax("10000") BigDecimal heightCm,
        @Schema(description = "Centimetres") @Positive @DecimalMax("10000") BigDecimal depthCm,

        @Schema(description = "Defaults to 1") @Min(1) @Max(10000) Integer quantity,

        @Size(max = 1000, message = "Notes can be at most 1000 characters")
        String notes
) {
}
