package com.velora.api.customrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.customrequest.service.CustomRequestTokenService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The token is the only thing standing between a public upload endpoint and anyone who
 * can count: request ids are sequential. It has to be bound to one request, signed, and
 * unforgeable — and, by decision, long-lived (24 h), because the customer fills the form
 * on a phone and comes back later.
 */
class CustomRequestTokenServiceTest {

    private static final String SECRET = "a-secret-that-is-comfortably-longer-than-32-chars";
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private CustomRequestTokenService tokens;

    @BeforeEach
    void setUp() {
        tokens = new CustomRequestTokenService(SECRET, 24);
    }

    @Test
    @DisplayName("A fresh token is valid for the request it was issued for")
    void validForItsRequest() {
        var token = tokens.issue(42L, NOW);

        assertThat(tokens.isValid(token.value(), 42L, NOW)).isTrue();
    }

    @Test
    @DisplayName("It is bound to ONE request: it does not work for the next id")
    void notValidForAnotherRequest() {
        var token = tokens.issue(42L, NOW);

        assertThat(tokens.isValid(token.value(), 43L, NOW)).isFalse();
        assertThat(tokens.isValid(token.value(), 41L, NOW)).isFalse();
    }

    @Test
    @DisplayName("It lasts a day: still valid after 23 hours, not after 24")
    void lastsTwentyFourHours() {
        var token = tokens.issue(42L, NOW);

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(tokens.isValid(token.value(), 42L, NOW.plus(Duration.ofHours(23)))).isTrue();
        assertThat(tokens.isValid(token.value(), 42L, NOW.plus(Duration.ofHours(24)))).isFalse();
        assertThat(tokens.isValid(token.value(), 42L, NOW.plus(Duration.ofDays(3)))).isFalse();
    }

    @Test
    @DisplayName("Extending the expiry, or changing the id, breaks the signature")
    void tamperingIsDetected() {
        var token = tokens.issue(42L, NOW);
        String[] parts = token.value().split("\\.");

        String laterExpiry = parts[0] + "." + (Long.parseLong(parts[1]) + 86_400) + "." + parts[2];
        String otherId = "43." + parts[1] + "." + parts[2];

        assertThat(tokens.isValid(laterExpiry, 42L, NOW)).isFalse();
        assertThat(tokens.isValid(otherId, 43L, NOW)).isFalse();
    }

    @Test
    @DisplayName("A token signed with another secret is rejected")
    void foreignSignatureIsRejected() {
        var other = new CustomRequestTokenService("another-secret-also-longer-than-32-characters", 24);

        assertThat(tokens.isValid(other.issue(42L, NOW).value(), 42L, NOW)).isFalse();
    }

    @Test
    @DisplayName("Missing, empty and malformed tokens are rejected without throwing")
    void malformedTokensAreRejected() {
        assertThat(tokens.isValid(null, 42L, NOW)).isFalse();
        assertThat(tokens.isValid("", 42L, NOW)).isFalse();
        assertThat(tokens.isValid("garbage", 42L, NOW)).isFalse();
        assertThat(tokens.isValid("42.notanumber.sig", 42L, NOW)).isFalse();
        assertThat(tokens.isValid("a.b.c.d", 42L, NOW)).isFalse();
        assertThat(tokens.isValid(tokens.issue(42L, NOW).value(), null, NOW)).isFalse();
    }

    @Test
    @DisplayName("A guest-cart style token (uuid.signature) can never pass as a request token")
    void guestTokenShapeIsRejected() {
        assertThat(tokens.isValid("3f2b8c1e-aaaa-bbbb-cccc-1234567890ab.c2lnbmF0dXJl", 42L, NOW))
                .isFalse();
    }

    @Test
    @DisplayName("A secret shorter than 32 characters is refused at startup")
    void shortSecretIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new CustomRequestTokenService("too-short", 24))
                .isInstanceOf(IllegalStateException.class);
    }
}
