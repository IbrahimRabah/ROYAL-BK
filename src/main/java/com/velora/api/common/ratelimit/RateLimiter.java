package com.velora.api.common.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A fixed-window request counter, keyed by whatever the caller wants to throttle —
 * an IP address, an account identifier, a phone number.
 *
 * <p>In-memory and per-instance, same trade-off as {@code LocalStorageService}: fine
 * for a single-server deployment, and moving to a shared store (Redis) later touches
 * no caller — they only ever see {@link #tryAcquire}.
 *
 * <p>Not a general-purpose cache: keys are never explicitly removed, only swept on a
 * schedule, so this must never be used for anything where a stale hit matters.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @return true if this call is within the limit and should proceed; false if the
     *         key has already used up its budget for the current window
     */
    public boolean tryAcquire(String key, int maxRequests, Duration window) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (k, existing) ->
                existing == null || existing.expiresAt().isBefore(now)
                        ? new Window(now.plus(window), 1)
                        : new Window(existing.expiresAt(), existing.count() + 1));
        return updated.count() <= maxRequests;
    }

    /**
     * Without this, every distinct key ever seen (every attacker IP, in particular)
     * stays in memory forever. Expired entries carry no information, so they are
     * simply dropped rather than reset — the next {@link #tryAcquire} recreates the
     * key from scratch.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    void evictExpired() {
        Instant now = Instant.now();
        int before = windows.size();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        int removed = before - windows.size();
        if (removed > 0) {
            log.debug("Rate limiter swept {} expired window(s), {} remaining", removed, windows.size());
        }
    }

    private record Window(Instant expiresAt, int count) {
    }
}
