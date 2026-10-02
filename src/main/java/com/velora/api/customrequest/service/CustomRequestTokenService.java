package com.velora.api.customrequest.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The proof that a caller created a given custom request, and may therefore attach
 * images to it.
 *
 * <p>Uploading is a public endpoint with no account behind it, and request ids are
 * sequential. Without something the caller must hold, anyone could walk the ids and
 * attach images to other people's requests. The token returned when a request is created
 * is that something.
 *
 * <p>Format: {@code <requestId>.<expiresAtEpochSeconds>.<base64url HMAC-SHA256>}. The
 * signature covers the request id AND the expiry, so a token cannot be pointed at another
 * request or have its expiry extended. The HMAC input is prefixed with a purpose string,
 * which keeps a guest-cart token (signed with the same secret) from ever verifying here.
 *
 * <p>The lifetime is long (24 h by default) on purpose: the customer fills the form on a
 * phone, photographs the part they want changed, gets distracted, comes back. The safety
 * is that the token is signed and bound to a single request, not that it expires quickly.
 */
@Service
public class CustomRequestTokenService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PURPOSE = "custom-request-attachment";
    private static final char SEPARATOR = '.';

    private final SecretKeySpec keySpec;
    private final Duration lifetime;

    public CustomRequestTokenService(
            @Value("${velora.guest-token.secret}") String secret,
            @Value("${velora.custom-request.attachment-token-hours:24}") long lifetimeHours) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "velora.guest-token.secret must be at least 32 characters for HmacSHA256 "
                            + "signing. Current length: " + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
        this.lifetime = Duration.ofHours(lifetimeHours);
    }

    /** A token for one request, valid from {@code now}. */
    public Token issue(Long requestId, Instant now) {
        Instant expiresAt = now.plus(lifetime);
        long expiry = expiresAt.getEpochSecond();
        String value = requestId + String.valueOf(SEPARATOR) + expiry
                + SEPARATOR + sign(requestId, expiry);
        return new Token(value, expiresAt);
    }

    /**
     * @return true only if the token is well-formed, signed by this server, bound to
     *         exactly {@code requestId}, and not yet expired at {@code now}
     */
    public boolean isValid(String token, Long requestId, Instant now) {
        if (token == null || requestId == null) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        long tokenRequestId;
        long expiry;
        try {
            tokenRequestId = Long.parseLong(parts[0]);
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (tokenRequestId != requestId) {
            return false;
        }
        byte[] expected = sign(tokenRequestId, expiry).getBytes(StandardCharsets.UTF_8);
        byte[] presented = parts[2].getBytes(StandardCharsets.UTF_8);
        // Constant-time: a plain equals() leaks how many leading characters matched.
        if (!MessageDigest.isEqual(expected, presented)) {
            return false;
        }
        return now.getEpochSecond() < expiry;
    }

    private String sign(long requestId, long expiry) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            byte[] signature = mac.doFinal(
                    (PURPOSE + ":" + requestId + ":" + expiry).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    public record Token(String value, Instant expiresAt) {
    }
}
