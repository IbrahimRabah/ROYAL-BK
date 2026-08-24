package com.velora.api.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.order.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code payment-status} and {@code audit action} both used to 500 on an invalid
 * value: they called {@code Enum.valueOf()} directly, which throws a bare
 * {@code IllegalArgumentException} that {@code GlobalExceptionHandler}'s
 * {@code BusinessException} handler never sees. Every enum-from-query-param call
 * site now goes through this instead.
 */
class EnumParamTest {

    @Test
    @DisplayName("A valid value (any case) parses to the enum constant")
    void validValueParses() {
        assertThat(EnumParam.parse(PaymentStatus.class, "PENDING", "status"))
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(EnumParam.parse(PaymentStatus.class, "pending", "status"))
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("An invalid value throws a BusinessException, not a bare IllegalArgumentException")
    void invalidValueThrowsBusinessException() {
        assertThatThrownBy(() -> EnumParam.parse(PaymentStatus.class, "NOT_A_STATUS", "status"))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("The error message names the bad value, the field, and every valid value")
    void messageIsActionable() {
        assertThatThrownBy(() -> EnumParam.parse(PaymentStatus.class, "spent_desc", "status"))
                .hasMessageContaining("spent_desc")
                .hasMessageContaining("status")
                .hasMessageContaining(PaymentStatus.PENDING.name());
    }
}
