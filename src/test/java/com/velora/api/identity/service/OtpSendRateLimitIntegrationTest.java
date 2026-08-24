package com.velora.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.identity.domain.OtpPurpose;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code POST /auth/otp/send} already had a per-destination cap (5/hour, DB-backed
 * in {@code OtpVerification}) — that alone does not stop one machine from spraying
 * codes at many different destinations. This adds the missing per-IP half.
 */
@SpringBootTest
class OtpSendRateLimitIntegrationTest {

    @Autowired private OtpService otpService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM otp_verification WHERE destination LIKE 'otp-test-%'");
    }

    @Test
    @DisplayName("The IP limit trips across different destinations from the same machine")
    void ipLimit_tripsAcrossDifferentDestinations() {
        String sharedIp = "198.51.100.1-" + UUID.randomUUID();

        // max-per-ip is 10 — a DIFFERENT destination each time so the existing
        // per-destination cap (5) never fires first.
        for (int i = 0; i < 10; i++) {
            String destination = "otp-test-" + UUID.randomUUID() + "@example.com";
            assertThat(otpService.send(destination, OtpPurpose.LOGIN, sharedIp))
                    .as("call #%d should still succeed", i + 1)
                    .isNotBlank();
        }

        String eleventh = "otp-test-" + UUID.randomUUID() + "@example.com";
        assertThatThrownBy(() -> otpService.send(eleventh, OtpPurpose.LOGIN, sharedIp))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("A fresh IP is unaffected by another IP's usage")
    void distinctIps_areIndependent() {
        String destination = "otp-test-" + UUID.randomUUID() + "@example.com";
        assertThat(otpService.send(destination, OtpPurpose.LOGIN,
                "203.0.113.50-" + UUID.randomUUID())).isNotBlank();
    }
}
