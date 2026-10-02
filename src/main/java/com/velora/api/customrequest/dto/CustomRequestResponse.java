package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "A custom request as staff see it")
public record CustomRequestResponse(
        Long id,
        String requestNumber,
        String type,
        String status,
        @Schema(description = "Null when the request is not about a specific product")
        ProductRef product,
        @Schema(description = "Null for a guest") Long customerId,
        String contactName,
        @Schema(description = "Local format, for display") String phone,
        String altPhone,
        String email,
        Long governorateId,
        String governorateName,
        @Schema(description = "Whether we deliver to this governorate RIGHT NOW. Worked out on every read, not stored: a request from a governorate that has since been closed is accepted, but staff must know before quoting work they cannot deliver. A governorate that reopens turns this true again.")
        boolean governorateServed,
        String area,
        String streetAddress,
        BigDecimal widthCm,
        BigDecimal heightCm,
        BigDecimal depthCm,
        int quantity,
        String notes,
        List<CustomRequestAttachmentResponse> attachments,
        @Schema(description = "Tax-inclusive total for the whole request. Null until quoted.")
        BigDecimal quotedAmount,
        @Schema(description = "The latest staff note. Earlier ones are in the audit log.")
        String adminNote,
        @Schema(description = "Always null for now — converting a request into an order is "
                + "not built yet")
        Long convertedOrderId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    @Schema(description = "The product a request is about")
    public record ProductRef(Long id, String slug, String name, String fulfillmentType) {
    }
}
