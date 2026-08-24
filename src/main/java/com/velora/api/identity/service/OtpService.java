package com.velora.api.identity.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.ratelimit.RateLimiter;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.domain.OtpChannel;
import com.velora.api.identity.domain.OtpPurpose;
import com.velora.api.identity.domain.OtpVerification;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.repository.OtpVerificationRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time codes for phone and email verification.
 *
 * <p>Six digits is only a million combinations, so three protections are required
 * together: a short expiry, an attempt cap per code, and a request cap per
 * destination. Any one of them alone is not enough.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private static final int CODE_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 10;
    private static final int MAX_REQUESTS_PER_HOUR = 5;

    private final OtpVerificationRepository otpRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();

    private final int maxRequestsPerIp;
    private final Duration ipWindow;

    public OtpService(OtpVerificationRepository otpRepository,
                      AppUserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      RateLimiter rateLimiter,
                      @Value("${velora.rate-limit.otp-send.max-per-ip}") int maxRequestsPerIp,
                      @Value("${velora.rate-limit.otp-send.window-minutes}") long windowMinutes) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.maxRequestsPerIp = maxRequestsPerIp;
        this.ipWindow = Duration.ofMinutes(windowMinutes);
    }

    /**
     * @return the raw code, for the notification module to deliver.
     *         Never return this from an HTTP endpoint.
     */
    @Transactional
    public String send(String destination, OtpPurpose purpose, String clientIp) {
        // Per-destination throttling already existed (below, DB-backed — it must
        // stay accurate across restarts and instances). This adds the other half:
        // one IP requesting codes for many different destinations in a row, which
        // the per-destination check alone cannot see.
        if (!rateLimiter.tryAcquire("otp-send:ip:" + clientIp, maxRequestsPerIp, ipWindow)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many codes requested from this network. Try again later");
        }

        String normalized = normalizeDestination(destination);
        OtpChannel channel = normalized.contains("@") ? OtpChannel.EMAIL : OtpChannel.SMS;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long recent = otpRepository.countRecentRequests(normalized, now.minusHours(1));
        if (recent >= MAX_REQUESTS_PER_HOUR) {
            throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
                    "Too many codes requested. Try again in an hour");
        }

        // A newly issued code invalidates any earlier one for the same purpose.
        otpRepository.consumeOutstanding(normalized, purpose, now);

        String code = generateCode();

        OtpVerification otp = new OtpVerification();
        otp.setDestination(normalized);
        otp.setChannel(channel);
        otp.setPurpose(purpose);
        otp.setCodeHash(passwordEncoder.encode(code));
        otp.setExpiresAt(now.plusMinutes(EXPIRY_MINUTES));
        userRepository.findByEmailOrPhone(normalized).ifPresent(otp::setUser);
        otpRepository.save(otp);

        // TODO(notification module): dispatch by SMS or email.
        log.info("OTP issued for {} purpose={} channel={}",
                channel == OtpChannel.SMS ? PhoneNormalizer.mask(normalized) : normalized,
                purpose, channel);

        return code;
    }

    @Transactional
    public void verify(String destination, String code, OtpPurpose purpose) {
        String normalized = normalizeDestination(destination);

        OtpVerification otp = otpRepository
                .findFirstByDestinationAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                        normalized, purpose)
                .orElseThrow(() -> new BusinessException(ErrorCode.OTP_INVALID));

        if (otp.isExpired()) {
            throw new BusinessException(ErrorCode.OTP_EXPIRED);
        }
        if (!otp.hasAttemptsLeft()) {
            throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS);
        }

        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            otp.recordFailedAttempt();
            otpRepository.save(otp);
            throw new BusinessException(ErrorCode.OTP_INVALID);
        }

        otp.consume();
        otpRepository.save(otp);

        markVerified(normalized, otp);
    }

    private void markVerified(String destination, OtpVerification otp) {
        AppUser user = otp.getUser() != null
                ? otp.getUser()
                : userRepository.findByEmailOrPhone(destination).orElse(null);

        if (user == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (otp.getChannel() == OtpChannel.SMS) {
            user.setPhoneVerifiedAt(now);
        } else {
            user.setEmailVerifiedAt(now);
        }
        userRepository.save(user);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private String normalizeDestination(String destination) {
        String trimmed = destination.trim();
        if (trimmed.contains("@")) {
            return trimmed.toLowerCase();
        }
        String phone = PhoneNormalizer.toE164(trimmed);
        if (phone == null) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT);
        }
        return phone;
    }
}
