package com.sahaj.platform.web;

import com.sahaj.platform.application.AuthService;
import com.sahaj.platform.application.RefreshInvalidException;
import com.sahaj.platform.application.VerifyOutcome;
import com.sahaj.platform.domain.TenantSummary;
import com.sahaj.platform.infrastructure.security.RefreshTokenCookie;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SAH-3: sign up and log in with a mobile one-time code. No tenant is ever read from the request. */
@RestController
@RequestMapping("/api/v1/platform/auth")
public class AuthController {

    private final AuthService authService;

    private final boolean secureCookie;

    public AuthController(AuthService authService,
                           @Value("${sahaj.auth.refresh-cookie-secure:true}") boolean secureCookie) {
        this.authService = authService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpRequestResponse requestOtp(@Valid @RequestBody OtpRequestRequest request) {
        return new OtpRequestResponse(authService.requestOtp(request.mobile()));
    }

    @PostMapping("/otp/verify")
    public AuthResultResponse verify(@Valid @RequestBody OtpVerifyRequest request, HttpServletRequest httpRequest,
                                      HttpServletResponse httpResponse) {
        AuthService.Consent consent = request.consent() == null ? null
                : new AuthService.Consent(request.consent().termsVersion(), request.consent().privacyVersion(),
                        httpRequest.getRemoteAddr());
        VerifyOutcome outcome = authService.verify(request.mobile(), request.code(), request.mode(), consent);
        return toResponse(outcome, httpResponse);
    }

    @PostMapping("/select-clinic")
    public AuthResultResponse selectClinic(@Valid @RequestBody SelectClinicRequest request,
                                            HttpServletResponse httpResponse) {
        VerifyOutcome outcome = authService.selectClinic(request.selectionToken(), request.tenantId());
        return toResponse(outcome, httpResponse);
    }

    @PostMapping("/refresh")
    public AuthResultResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawRefreshToken = RefreshTokenCookie.read(httpRequest)
                .orElseThrow(() -> new RefreshInvalidException("This session has ended. Please log in again."));
        AuthService.RefreshOutcome outcome = authService.refresh(rawRefreshToken);
        writeRefreshCookie(httpResponse, outcome.rawRefreshToken(), outcome.refreshExpiresAt());
        return AuthResultResponse.session("APP", outcome.accessToken().token(), outcome.accessToken().expiresInSeconds());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal TenantPrincipal principal, HttpServletRequest httpRequest,
                        HttpServletResponse httpResponse) {
        String rawRefreshToken = RefreshTokenCookie.read(httpRequest).orElse(null);
        authService.logout(principal.tenantId(), principal.userId(), rawRefreshToken);
        RefreshTokenCookie.clear(httpResponse, secureCookie);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal TenantPrincipal principal, HttpServletResponse httpResponse) {
        authService.logoutAll(principal.userId());
        RefreshTokenCookie.clear(httpResponse, secureCookie);
    }

    private AuthResultResponse toResponse(VerifyOutcome outcome, HttpServletResponse httpResponse) {
        switch (outcome.next()) {
            case NOT_REGISTERED:
                return AuthResultResponse.notRegistered();
            case CHOOSE_CLINIC:
                return AuthResultResponse.chooseClinic(outcome.selectionToken(), toClinicOptions(outcome.clinics()));
            case SETUP:
            case APP:
            default:
                writeRefreshCookie(httpResponse, outcome.rawRefreshToken(), outcome.refreshExpiresAt());
                return AuthResultResponse.session(outcome.next().name(), outcome.accessToken().token(),
                        outcome.accessToken().expiresInSeconds());
        }
    }

    private static List<ClinicOptionResponse> toClinicOptions(List<TenantSummary> clinics) {
        return clinics.stream().map(c -> new ClinicOptionResponse(c.tenantId(), c.name(), null)).toList();
    }

    private void writeRefreshCookie(HttpServletResponse response, String rawRefreshToken,
                                             java.time.Instant expiresAt) {
        Duration maxAge = Duration.between(java.time.Instant.now(), expiresAt);
        RefreshTokenCookie.write(response, rawRefreshToken, maxAge.isNegative() ? Duration.ZERO : maxAge, secureCookie);
    }
}
