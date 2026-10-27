package com.velora.api.customrequest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A custom request as its OWNER sees it, in their list. Deliberately small: nothing here is
 * internal. Never add the staff note, the contact details or the customer id.
 */
@Schema(description = "One of the signed-in customer's own custom requests")
public record MyCustomRequestSummaryResponse(
        @Schema(description = "The reference to quote on the phone, and the key for the other calls",
                example = "REQ-2026-000014")
        String requestNumber,
        @Schema(allowableValues = {"SIZE_VARIANT", "MADE_TO_ORDER", "CUSTOM_WORK"}) String type,
        @Schema(allowableValues = {"NEW", "CONTACTED", "QUOTED", "ACCEPTED", "REJECTED",
                "CONVERTED", "COMPLETED"})
        String status,
        OffsetDateTime submittedAt,
        @Schema(description = "The product the request is about; absent when it is not about one")
        ProductRef product,
        @Schema(description = "Tax-inclusive total for the whole request, all units. Absent until quoted")
        BigDecimal quotedAmount,
        @Schema(description = "When this amount was quoted; moves when the price is revised. Absent until quoted")
        OffsetDateTime quotedAt,
        @Schema(description = "Decided by the SERVER: true while the quote is waiting for an answer. "
                + "Do not work this out from the status.")
        boolean canAccept,
        @Schema(description = "Decided by the SERVER, like canAccept")
        boolean canReject
) {

    @Schema(description = "The product a request is about")
    public record ProductRef(String slug, String name) {
    }
}
