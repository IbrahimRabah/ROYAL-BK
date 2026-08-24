package com.velora.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.dto.LoginRequest;
import com.velora.api.identity.repository.AppUserRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@code POST /auth/login} had no rate limiting at all — repeated password
 * guessing against one account, or one machine spraying many accounts, was
 * unthrottled. Two independent limits now guard it; either tripping refuses the
 * request with {@code ErrorCode.RATE_LIMITED} before the password is even checked
 * against the second limit.
 *
 * <p>Runs against the real database — {@code AuthService} needs a real
 * {@code AppUser} row to distinguish "wrong password" from "rate limited".
 */
@SpringBootTest
class AuthLoginRateLimitIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String unique;
    private String email;
    private Long userId;

    @BeforeEach
    void seedUser() {
        unique = UUID.randomUUID().toString().substring(0, 8);
        email = "ratelimit-" + unique + "@example.com";

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("CorrectPass123!"));
        user.setFirstName("Test");
        userId = userRepository.save(user).getId();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("The account limit trips after repeated failures, even from a fresh IP each time")
    void accountLimit_tripsIndependentlyOfIp() {
        LoginRequest wrongPassword = new LoginRequest(email, "WrongPassword!", null);

        // The configured max-per-account is 5 — exhaust it, each attempt from a
        // DIFFERENT synthetic IP so the IP-side limit (10) never interferes.
        for (int i = 0; i < 5; i++) {
            String ip = "10.0.0." + i + "-" + UUID.randomUUID();
            assertThatThrownBy(() -> authService.login(wrongPassword, ip))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .as("attempt #%d should still be a normal credentials failure", i + 1)
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        // The 6th attempt, again from a brand new IP, must be refused by the
        // account limit — not even reach the password comparison.
        assertThatThrownBy(() -> authService.login(wrongPassword, "10.0.0.99-" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("The IP limit trips across different accounts from the same machine")
    void ipLimit_tripsAcrossDifferentAccounts() {
        String sharedIp = "192.168.1.1-" + UUID.randomUUID();

        // max-per-ip is 10 — use a DIFFERENT bogus account each time so the
        // per-account limit (5) never fires first.
        for (int i = 0; i < 10; i++) {
            LoginRequest request = new LoginRequest(
                    "nonexistent-" + UUID.randomUUID() + "@example.com", "whatever", null);
            assertThatThrownBy(() -> authService.login(request, sharedIp))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .as("attempt #%d should still reach the (missing-account) credentials check", i + 1)
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        LoginRequest eleventh = new LoginRequest(
                "nonexistent-" + UUID.randomUUID() + "@example.com", "whatever", null);
        assertThatThrownBy(() -> authService.login(eleventh, sharedIp))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("A correct password still signs in when neither limit has been exhausted")
    void withinLimits_correctPasswordStillSignsIn() {
        LoginRequest correct = new LoginRequest(email, "CorrectPass123!", null);
        assertThat(authService.login(correct, "203.0.113.1-" + UUID.randomUUID()).accessToken())
                .isNotBlank();
    }
}
