package com.velora.api.identity.service;

import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.ratelimit.RateLimiter;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.domain.PasswordResetToken;
import com.velora.api.identity.domain.RefreshToken;
import com.velora.api.identity.domain.Role;
import com.velora.api.identity.dto.AuthResponse;
import com.velora.api.identity.dto.LoginRequest;
import com.velora.api.identity.dto.RegisterRequest;
import com.velora.api.identity.dto.UserResponse;
import com.velora.api.identity.mapper.UserMapper;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.repository.PasswordResetTokenRepository;
import com.velora.api.identity.repository.RefreshTokenRepository;
import com.velora.api.identity.repository.RoleRepository;
import com.velora.api.identity.security.JwtService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, sign-in, token refresh and password reset.
 *
 * <p>Login accepts a mobile number OR an email in a single {@code identifier}
 * field. Phone numbers are normalized to E.164 before every lookup — skip that on
 * either side and {@code 01012345678} and {@code +201012345678} become two accounts.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final RateLimiter rateLimiter;

    private final int loginMaxPerIp;
    private final int loginMaxPerAccount;
    private final Duration loginWindow;

    public AuthService(AppUserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       UserMapper userMapper,
                       RateLimiter rateLimiter,
                       @Value("${velora.rate-limit.login.max-per-ip}") int loginMaxPerIp,
                       @Value("${velora.rate-limit.login.max-per-account}") int loginMaxPerAccount,
                       @Value("${velora.rate-limit.login.window-minutes}") long loginWindowMinutes) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.rateLimiter = rateLimiter;
        this.loginMaxPerIp = loginMaxPerIp;
        this.loginMaxPerAccount = loginMaxPerAccount;
        this.loginWindow = Duration.ofMinutes(loginWindowMinutes);
    }

    // ------------------------------------------------------------------ register

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String phone = normalizePhoneOrFail(request.phone());
        String email = normalizeEmail(request.email());

        if (phone == null && email == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Provide a mobile number or an email address");
        }

        if (phone != null && userRepository.existsByPhoneE164(phone)) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS);
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        AppUser user = new AppUser();
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName() == null ? null : request.lastName().trim());
        user.setPhoneE164(phone);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPreferredLocale(request.locale() == null ? "ar" : request.locale());

        Role customerRole = roleRepository.findByCode(Role.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException(
                        "Role CUSTOMER is missing. Run the schema seed data."));
        user.addRole(customerRole);

        AppUser saved = userRepository.save(user);
        log.info("Registered user id={} phone={} email={}",
                saved.getId(), PhoneNormalizer.mask(phone), email);

        return issueTokens(saved, "registration");
    }

    // --------------------------------------------------------------------- login

    /**
     * Two independent limits, both checked before the password is even compared:
     * one keyed by IP (an attacker spraying many accounts from one machine), one
     * keyed by the account itself (credential stuffing against one target,
     * rotating IPs to dodge the first limit). Either one tripping is enough to
     * refuse the request — this is deliberately stricter than either alone.
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String clientIp) {
        if (!rateLimiter.tryAcquire("login:ip:" + clientIp, loginMaxPerIp, loginWindow)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many login attempts from this network. Try again later");
        }

        String identifier = resolveIdentifier(request.identifier());

        if (!rateLimiter.tryAcquire("login:account:" + identifier, loginMaxPerAccount, loginWindow)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many login attempts for this account. Try again later");
        }

        AppUser user = userRepository.findByEmailOrPhone(identifier)
                // Same error whether the account is missing or the password is wrong,
                // so the response cannot be used to discover which numbers are registered.
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login attempt for identifier ending {}",
                    identifier.substring(Math.max(0, identifier.length() - 4)));
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        user.setLastLoginAt(OffsetDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        return issueTokens(user, request.deviceInfo());
    }

    // ------------------------------------------------------------------- refresh

    /**
     * Rotates the refresh token: the presented one is revoked and a new one issued.
     * If a stolen token is replayed after the legitimate client has rotated, the
     * replay fails — the old hash is already revoked.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));

        if (!stored.isUsable()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        AppUser user = stored.getUser();
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        stored.revoke();
        refreshTokenRepository.save(stored);

        return issueTokens(user, stored.getDeviceInfo());
    }

    // -------------------------------------------------------------------- logout

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(jwtService.hashToken(rawRefreshToken))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
        // Deliberately silent if the token is unknown: logout is idempotent.
    }

    @Transactional
    public void logoutEverywhere(Long userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(
                userId, OffsetDateTime.now(ZoneOffset.UTC));
        log.info("Revoked {} refresh tokens for user id={}", revoked, userId);
    }

    // ------------------------------------------------------------ password reset

    /**
     * Always reports success, even for an unknown identifier. Reporting "no such
     * account" would turn this endpoint into a registration checker.
     *
     * @return the raw token, for the notification module to deliver. Never returned
     *         to the caller of the HTTP endpoint.
     */
    @Transactional
    public String startPasswordReset(String identifier) {
        String resolved = resolveIdentifier(identifier);
        var maybeUser = userRepository.findByEmailOrPhone(resolved);
        if (maybeUser.isEmpty()) {
            log.info("Password reset requested for unknown identifier");
            return null;
        }

        AppUser user = maybeUser.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);

        String raw = jwtService.generateRefreshToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(jwtService.hashToken(raw));
        token.setExpiresAt(now.plusHours(1));
        passwordResetTokenRepository.save(token);

        // TODO(notification module): send by SMS or email instead of logging.
        log.info("Password reset token issued for user id={}", user.getId());
        return raw;
    }

    @Transactional
    public void completePasswordReset(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(jwtService.hashToken(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));

        if (!token.isUsable()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        AppUser user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.markUsed();
        passwordResetTokenRepository.save(token);

        // A password change invalidates every existing session.
        logoutEverywhere(user.getId());
    }

    // ------------------------------------------------------------------ internal

    private AuthResponse issueTokens(AppUser user, String deviceInfo) {
        Set<String> roleCodes = user.getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());

        String accessToken = jwtService.generateAccessToken(
                user.getId(), user.getEmail(), user.getPhoneE164(), roleCodes);

        String rawRefresh = jwtService.generateRefreshToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(jwtService.hashToken(rawRefresh));
        refreshToken.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(jwtService.getRefreshTokenDays()));
        refreshToken.setDeviceInfo(truncate(deviceInfo));
        refreshTokenRepository.save(refreshToken);

        UserResponse userResponse = userMapper.toResponse(user);
        return AuthResponse.of(accessToken, rawRefresh,
                jwtService.getAccessTokenSeconds(), userResponse);
    }

    /** Decides whether the input is a phone or an email and normalizes accordingly. */
    private String resolveIdentifier(String identifier) {
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            return trimmed.toLowerCase();
        }
        String phone = PhoneNormalizer.toE164(trimmed);
        return phone != null ? phone : trimmed;
    }

    private String normalizePhoneOrFail(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String normalized = PhoneNormalizer.toE164(phone);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT);
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
