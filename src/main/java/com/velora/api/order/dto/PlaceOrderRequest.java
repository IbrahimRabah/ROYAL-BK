package com.velora.api.order.dto;

import com.velora.api.order.domain.DeliverySlot;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Place an order.
 *
 * <p>Supply EITHER {@code addressId} (a saved address, signed-in customers) OR
 * {@code address} inline (guest checkout). Either way the address is COPIED onto the
 * order, never referenced.
 */
@Schema(description = "Create an order from the current cart")
public record PlaceOrderRequest(

        @Schema(description = "A saved address. Signed-in customers only.")
        Long addressId,

        @Schema(description = "Inline address. Required for guest checkout.")
        @Valid AddressInput address,

        @Schema(example = "COD", allowableValues = {"COD"},
                description = "Cash on delivery is the only method in V1")
        String paymentMethod,

        @Schema(example = "اتصل قبل التوصيل")
        @Size(max = 500) String customerNote,

        @Schema(example = "2026-10-20", description = "The day the customer would like delivery. "
                + "A request, not a promise: staff agree the real appointment with them. Not in "
                + "the past, and no more than 90 days ahead (Cairo date).")
        LocalDate preferredDeliveryDate,

        @Schema(description = "MORNING, AFTERNOON or EVENING. Needs preferredDeliveryDate.")
        DeliverySlot preferredDeliverySlot
) {

    /** An order with no delivery preference, which is most of them. */
    public PlaceOrderRequest(Long addressId, AddressInput address, String paymentMethod,
                             String customerNote) {
        this(addressId, address, paymentMethod, customerNote, null, null);
    }

    @Schema(description = "A delivery address supplied at checkout")
    public record AddressInput(

            @NotBlank(message = "Recipient name is required")
            @Size(max = 150) String recipientName,

            @Schema(example = "01012345678")
            @NotBlank(message = "Phone number is required")
            String phone,

            @Schema(description = "A failed delivery is usually an unanswered phone")
            String altPhone,

            @Schema(description = "Only used for guest orders, to send confirmation")
            String email,

            @NotNull(message = "Governorate is required")
            Long governorateId,

            @Schema(example = "المنيا الجديدة", description = "Free text")
            @Size(max = 150) String area,

            @NotBlank(message = "Street address is required")
            @Size(max = 255) String streetAddress,

            @Size(max = 50) String building,
            @Size(max = 20) String floor,
            @Size(max = 20) String apartment,

            @Schema(example = "بجوار مسجد النور")
            @Size(max = 255) String landmark
    ) {
    }
}
