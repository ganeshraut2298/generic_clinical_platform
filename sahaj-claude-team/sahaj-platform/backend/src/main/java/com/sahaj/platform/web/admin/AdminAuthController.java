package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.admin.AdminAuthException;
import com.sahaj.platform.application.admin.AdminAuthService;
import com.sahaj.platform.application.admin.AdminIdentity;
import com.sahaj.platform.application.admin.Totp;
import com.sahaj.platform.infrastructure.admin.AdminRefreshCookie;
import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminRealm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADM-1: the Sahaj admin sign-in and the signed-in admin (docs/api/admin.yaml). The realm shares no
 * code with clinic sign-in. The four public endpoints validate their own pre-auth token or refresh
 * cookie; sign-out and {@code /me} need role PLATFORM_ADMIN. Request bodies are never logged.
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnAdminRealm
public class AdminAuthController {

    private final AdminAuthService service;
    private final boolean secureCookie;

    public AdminAuthController(AdminAuthService service,
                               @Value("${sahaj.auth.refresh-cookie-secure:true}") boolean secureCookie) {
        this.service = service;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/auth/login")
    @PublicEndpoint
    public ResponseEntity<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request,
                                                    HttpServletRequest http) {
        AdminAuthService.LoginResult result = service.login(request.email(), request.password(), context(http));
        return noStore(new AdminLoginResponse(result.next().name(), result.preAuthToken(), result.expiresInSeconds()));
    }

    @PostMapping("/auth/totp/enrol")
    @PublicEndpoint
    public ResponseEntity<AdminEnrolResponse> enrol(@Valid @RequestBody AdminEnrolRequest request) {
        AdminAuthService.Enrolment enrolment = service.enrol(request.preAuthToken());
        return noStore(new AdminEnrolResponse(enrolment.secretKey(), enrolment.otpauthUri(), enrolment.accountName(),
                enrolment.issuer(), Totp.DIGITS, Totp.PERIOD_SECONDS, "SHA1"));
    }

    @PostMapping("/auth/totp/verify")
    @PublicEndpoint
    public ResponseEntity<AdminAuthResultResponse> verify(@Valid @RequestBody AdminVerifyRequest request,
                                                          HttpServletRequest http, HttpServletResponse response) {
        AdminAuthService.SignedIn signedIn = service.verify(request.preAuthToken(), request.code(),
                request.recoveryCode(), context(http));
        return signedInResponse(signedIn, response);
    }

    @PostMapping("/auth/refresh")
    @PublicEndpoint
    public ResponseEntity<AdminAuthResultResponse> refresh(HttpServletRequest http, HttpServletResponse response) {
        String presented = AdminRefreshCookie.read(http)
                .orElseThrow(() -> new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED));
        return signedInResponse(service.refresh(presented), response);
    }

    @PostMapping("/auth/sign-out")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletResponse response) {
        service.signOut();
        AdminRefreshCookie.clear(response, secureCookie);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public AdminMeResponse me() {
        return AdminMeResponse.of(service.me());
    }

    private ResponseEntity<AdminAuthResultResponse> signedInResponse(AdminAuthService.SignedIn signedIn,
                                                                     HttpServletResponse response) {
        AdminRefreshCookie.write(response, signedIn.refreshToken(), AdminAuthService.SESSION_COOKIE_LIFE, secureCookie);
        AdminIdentity admin = signedIn.admin();
        return noStore(new AdminAuthResultResponse(signedIn.accessToken(), signedIn.expiresInSeconds(),
                AdminMeResponse.of(admin), signedIn.recoveryCodes()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The servlet remote address only (forwarding headers are ignored) and the request id of the log context. */
    private static AdminAuthService.ClientContext context(HttpServletRequest http) {
        return new AdminAuthService.ClientContext(http.getRemoteAddr(), MDC.get("requestId"));
    }
}
