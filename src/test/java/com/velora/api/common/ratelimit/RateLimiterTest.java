package com.velora.api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    @DisplayName("Allows exactly maxRequests calls, then blocks")
    void allowsUpToTheLimitThenBlocks() {
        String key = "test-" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, 5, Duration.ofMinutes(1)))
                    .as("call #%d should be within the limit", i + 1)
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(key, 5, Duration.ofMinutes(1)))
                .as("the 6th call must be refused")
                .isFalse();
    }

    @Test
    @DisplayName("Distinct keys are throttled independently")
    void distinctKeysAreIndependent() {
        String keyA = "a-" + UUID.randomUUID();
        String keyB = "b-" + UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(keyA, 3, Duration.ofMinutes(1));
        }
        assertThat(limiter.tryAcquire(keyA, 3, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire(keyB, 3, Duration.ofMinutes(1)))
                .as("a different key must not be affected by keyA's usage")
                .isTrue();
    }

    @Test
    @DisplayName("The count resets once the window elapses")
    void resetsAfterTheWindowElapses() throws InterruptedException {
        String key = "window-" + UUID.randomUUID();

        assertThat(limiter.tryAcquire(key, 1, Duration.ofMillis(100))).isTrue();
        assertThat(limiter.tryAcquire(key, 1, Duration.ofMillis(100))).isFalse();

        Thread.sleep(150);

        assertThat(limiter.tryAcquire(key, 1, Duration.ofMillis(100)))
                .as("a new window must grant a fresh budget")
                .isTrue();
    }
}
