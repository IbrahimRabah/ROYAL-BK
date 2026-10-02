package com.velora.api.identity.web;

import com.velora.api.common.ratelimit.ClientIpResolver;
import com.velora.api.identity.dto.AuthResponse;
import com.velora.api.identity.dto.ForgotPasswordRequest;
import com.velora.api.identity.dto.LoginRequest;
import com.velora.api.identity.dto.MessageResponse;
import com.velora.api.identity.dto.OtpSendRequest;
import com.velora.api.identity.dto.OtpVerifyRequest;
import com.velora.api.identity.dto.RegisterRequest;
import com.velora.api.identity.dto.ResetPasswordRequest;
import com.velora.api.identity.dto.TokenRefreshRequest;
import com.velora.api.identity.dto.UserResponse;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.identity.service.AuthService;
import com.velora.api.identity.service.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Registration, sign-in and tokens")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, OtpService otpService,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.otpService = otpService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Register a new customer",
            description = "Supply a mobile number, an email, or both. "
                    + "Egyptian mobile numbers are normalized to E.164.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created, tokens issued"),
            @ApiResponse(responseCode = "409", description = "Phone or email already registered")
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Sign in with a mobile number or an email",
            description = "One `identifier` field for both. The server works out which it is.",
            security = {})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "401", description = "Wrong identifier or password"),
            @ApiResponse(responseCode = "403", description = "Account suspended")
    })
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest) {
        return authService.login(request, clientIpResolver.resolve(httpRequest));
    }

    @Operation(summary = "Exchange a refresh token for a new access token",
            description = "The presented refresh token is revoked and replaced (rotation).",
            security = {})
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @Operation(summary = "Sign out on this device", security = {})
    @PostMapping("/logout")
    public MessageResponse logout(@Valid @RequestBody TokenRefreshRequest request) {
        authService.logout(request.refreshToken());
        return MessageResponse.of("Signed out");
    }

    @Operation(summary = "Sign out everywhere",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/logout-all")
    public MessageResponse logoutEverywhere(@AuthenticationPrincipal UserPrincipal principal) {
        authService.logoutEverywhere(principal.id());
        return MessageResponse.of("Signed out on all devices");
    }

    @Operation(summary = "Who am I",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return new UserResponse(principal.id(), null, null, null,
                principal.email(), principal.phone(), false, false, "ar",
                principal.authorities().stream()
                        .map(a -> a.getAuthority().replace("ROLE_", ""))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Operation(summary = "Send a one-time code", security = {})
    @PostMapping("/otp/send")
    public MessageResponse sendOtp(@Valid @RequestBody OtpSendRequest request,
                                   HttpServletRequest httpRequest) {
        // The code is returned to the notification module, never to the caller.
        otpService.send(request.destination(), request.purpose(),
                clientIpResolver.resolve(httpRequest));
        return MessageResponse.of("A verification code has been sent");
    }

    @Operation(summary = "Verify a one-time code", security = {})
    @PostMapping("/otp/verify")
    public MessageResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        otpService.verify(request.destination(), request.code(), request.purpose());
        return MessageResponse.of("Verified");
    }

    @Operation(summary = "Start a password reset",
            description = "Always reports success, even for an unknown identifier — "
                    + "otherwise this endpoint becomes a registration checker.",
            security = {})
    @PostMapping("/password/forgot")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.startPasswordReset(request.identifier());
        return MessageResponse.of("If the account exists, reset instructions have been sent");
    }

    @Operation(summary = "Complete a password reset",
            description = "Signs out every existing session on success.",
            security = {})
    @PostMapping("/password/reset")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.completePasswordReset(request.token(), request.newPassword());
        return MessageResponse.of("Password updated. Please sign in again");
    }
}
