package com.velora.api.export.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order export filters used to call {@code FulfillmentStatus.valueOf()} /
 * {@code PaymentStatus.valueOf()} directly — a bad query param there 500'd instead
 * of returning a 400.
 */
class OrderExportSpecificationsTest {

    @Test
    @DisplayName("An unrecognised fulfillment status is a 400, not a 500")
    void invalidFulfillmentStatusIsABusinessException() {
        assertThatThrownBy(() -> OrderExportSpecifications.hasFulfillmentStatus("NOT_A_STATUS"))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("An unrecognised payment status is a 400, not a 500")
    void invalidPaymentStatusIsABusinessException() {
        assertThatThrownBy(() -> OrderExportSpecifications.hasPaymentStatus("NOT_A_STATUS"))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("A valid status builds a Specification without throwing")
    void validStatusesBuildSpecifications() {
        assertThat(OrderExportSpecifications.hasFulfillmentStatus("delivered")).isNotNull();
        assertThat(OrderExportSpecifications.hasPaymentStatus("paid")).isNotNull();
    }

    @Test
    @DisplayName("A null or blank status is ignored rather than treated as invalid")
    void blankStatusIsIgnored() {
        assertThat(OrderExportSpecifications.hasFulfillmentStatus(null)).isNotNull();
        assertThat(OrderExportSpecifications.hasFulfillmentStatus("  ")).isNotNull();
    }
}
