package com.sahaj.platform.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.application.admin.AdminAuthException;
import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminAuthService;
import com.sahaj.platform.application.admin.AdminIdentity;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The admin endpoints through the real admin security chain: the exact shapes and problem types of
 * docs/api/admin.yaml, the cookie, and the separation from the clinic realm.
 */
@WebMvcTest(controllers = AdminAuthController.class, properties = {
        "sahaj.admin.enabled=true", "sahaj.admin.jwt-secret=" + AdminChainTestConfig.ADMIN_SECRET,
        "sahaj.auth.refresh-cookie-secure=true",
        "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw=="})
@Import({SecurityConfig.class, AdminSecurityConfig.class, AdminChainTestConfig.class})
class AdminAuthControllerTest {

    private static final String BASE = "/api/v1/admin";
    private static final String TOKEN = "A".repeat(43);
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminTokenService adminTokens;
    @Autowired
    private TokenService clinicTokens;

    @MockBean
    private AdminAuthService service;
    @MockBean
    private AdminAuthRepository repository;

    /** Every request comes from its own address so the 20-per-address limit never leaks between tests. */
    private static MockHttpServletRequestBuilder fromNewAddress(MockHttpServletRequestBuilder builder) {
        int n = NEXT_ADDRESS.getAndIncrement();
        return builder.with(request -> {
            request.setRemoteAddr("10.77." + (n / 250) + "." + (n % 250 + 1));
            return request;
        });
    }

    private String adminBearer() {
        return "Bearer " + adminTokens.issue(ADMIN_ID, SESSION_ID, List.of("pwd", "otp")).token();
    }

    private void sessionIsLive(boolean live) {
        when(repository.sessionTouch(SESSION_ID, ADMIN_ID)).thenReturn(live);
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginReturnsTheContractShapeWithNoStoreAndNoCookie() throws Exception {
        when(service.login(eq("dev.admin@example.test"), eq("a-password"), any()))
                .thenReturn(new AdminAuthService.LoginResult(AdminAuthService.Next.ENROL, TOKEN, 300));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"a-password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.next").value("ENROL"))
                .andExpect(jsonPath("$.preAuthToken").value(TOKEN))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void loginPassesTheServletRemoteAddressAndIgnoresForwardedHeaders() throws Exception {
        when(service.login(any(), any(), any()))
                .thenReturn(new AdminAuthService.LoginResult(AdminAuthService.Next.TOTP, TOKEN, 300));

        mockMvc.perform(post(BASE + "/auth/login").contentType(JSON).header("X-Forwarded-For", "8.8.8.8")
                        .with(request -> {
                            request.setRemoteAddr("10.55.0.7");
                            return request;
                        })
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"a-password\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminAuthService.ClientContext> context = ArgumentCaptor.forClass(AdminAuthService.ClientContext.class);
        verify(service).login(any(), any(), context.capture());
        assertThat(context.getValue().remoteAddress()).isEqualTo("10.55.0.7");
        assertThat(context.getValue().requestId()).isNotBlank();
    }

    @Test
    void loginValidationNamesTheFieldAndNeverEchoesTheValue() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"not-an-email-secret-xyz\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='email')].message").value("Enter a valid work email"))
                .andExpect(content().string(not(containsString("not-an-email-secret-xyz"))));
        verify(service, never()).login(any(), any(), any());
    }

    @Test
    void everyAdminRequestBodyRefusesAnUnknownFieldWithoutEchoingItsName() throws Exception {
        String[][] bodies = {
                {"/auth/login", "{\"email\":\"dev.admin@example.test\",\"password\":\"a-password\",\"extra-field-xyz\":1}"},
                {"/auth/totp/enrol", "{\"preAuthToken\":\"" + TOKEN + "\",\"extra-field-xyz\":true}"},
                {"/auth/totp/verify", "{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\",\"extra-field-xyz\":\"x\"}"}};
        for (String[] body : bodies) {
            mockMvc.perform(fromNewAddress(post(BASE + body[0])).contentType(JSON).content(body[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/malformed-request"))
                    .andExpect(content().string(not(containsString("extra-field-xyz"))));
        }
        verify(service, never()).login(any(), any(), any());
        verify(service, never()).enrol(any());
        verify(service, never()).verify(any(), any(), any(), any());
    }

    @Test
    void loginRefusesAPasswordOver128Characters() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"" + "p".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginAcceptsSurroundingSpacesInTheEmail() throws Exception {
        when(service.login(any(), any(), any()))
                .thenReturn(new AdminAuthService.LoginResult(AdminAuthService.Next.TOTP, TOKEN, 300));
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"  Dev.Admin@Example.test \",\"password\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void credentialsInvalidProblem() throws Exception {
        when(service.login(any(), any(), any()))
                .thenThrow(new AdminAuthException(AdminAuthException.Kind.CREDENTIALS_INVALID));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-credentials-invalid"))
                .andExpect(jsonPath("$.title").value("Email or password is not right."))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(content().string(not(containsString("dev.admin@example.test"))));
    }

    @Test
    void lockedProblemCarriesRetryAfterSecondsAndTheHeader() throws Exception {
        when(service.login(any(), any(), any()))
                .thenThrow(new AdminAuthException(AdminAuthException.Kind.SIGN_IN_LOCKED, 840));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"x\"}"))
                .andExpect(status().isLocked())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-sign-in-locked"))
                .andExpect(jsonPath("$.title").value("Too many attempts."))
                .andExpect(jsonPath("$.retryAfterSeconds").value(840));
    }

    @Test
    void perEmailRateLimitProblem() throws Exception {
        when(service.login(any(), any(), any()))
                .thenThrow(new AdminAuthException(AdminAuthException.Kind.RATE_LIMITED, 60));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(60));
    }

    @Test
    void theTwentyFirstSignInRequestFromOneAddressIs429() throws Exception {
        when(service.login(any(), any(), any()))
                .thenReturn(new AdminAuthService.LoginResult(AdminAuthService.Next.TOTP, TOKEN, 300));
        String body = "{\"email\":\"dev.admin@example.test\",\"password\":\"x\"}";
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post(BASE + "/auth/login").contentType(JSON).content(body).with(request -> {
                request.setRemoteAddr("10.66.0.1");
                return request;
            })).andExpect(status().isOk());
        }

        mockMvc.perform(post(BASE + "/auth/login").contentType(JSON).content(body).with(request -> {
                    request.setRemoteAddr("10.66.0.1");
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"));
        // The same family of endpoints shares the bucket.
        mockMvc.perform(post(BASE + "/auth/totp/verify").contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\"}").with(request -> {
                            request.setRemoteAddr("10.66.0.1");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
        // Another address is unaffected.
        mockMvc.perform(post(BASE + "/auth/login").contentType(JSON).content(body).with(request -> {
            request.setRemoteAddr("10.66.0.2");
            return request;
        })).andExpect(status().isOk());
    }

    @Test
    void theThirtyFirstRefreshFromOneAddressIs429() throws Exception {
        when(service.refresh(any())).thenThrow(new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED));
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post(BASE + "/auth/refresh").cookie(new Cookie("sahaj_admin_rt", "x")).with(request -> {
                request.setRemoteAddr("10.66.0.9");
                return request;
            })).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(BASE + "/auth/refresh").cookie(new Cookie("sahaj_admin_rt", "x")).with(request -> {
                    request.setRemoteAddr("10.66.0.9");
                    return request;
                }))
                .andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------------ enrol

    @Test
    void enrolReturnsTheContractShape() throws Exception {
        when(service.enrol(TOKEN)).thenReturn(new AdminAuthService.Enrolment("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ",
                "otpauth://totp/Sahaj%20admin:a%40b.co?secret=X", "a@b.co", "Sahaj admin"));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/enrol")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.secretKey").value("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ"))
                .andExpect(jsonPath("$.otpauthUri").value("otpauth://totp/Sahaj%20admin:a%40b.co?secret=X"))
                .andExpect(jsonPath("$.accountName").value("a@b.co"))
                .andExpect(jsonPath("$.issuer").value("Sahaj admin"))
                .andExpect(jsonPath("$.digits").value(6))
                .andExpect(jsonPath("$.periodSeconds").value(30))
                .andExpect(jsonPath("$.algorithm").value("SHA1"));
    }

    @Test
    void enrolRefusesAMalformedPreAuthToken() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/enrol")).contentType(JSON)
                        .content("{\"preAuthToken\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("preAuthToken"));
    }

    @Test
    void enrolConflictsAndExpiry() throws Exception {
        when(service.enrol(TOKEN)).thenThrow(new AdminAuthException(AdminAuthException.Kind.TOTP_ALREADY_ENROLLED));
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/enrol")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/totp-already-enrolled"));

        org.mockito.Mockito.reset(service);
        when(service.enrol(TOKEN)).thenThrow(new AdminAuthException(AdminAuthException.Kind.PREAUTH_EXPIRED));
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/enrol")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/preauth-expired"))
                .andExpect(jsonPath("$.title").value("Your sign-in took too long. Start again."));
    }

    // ------------------------------------------------------------------ verify

    @Test
    void verifySetsTheScopedRefreshCookieAndReturnsTheResult() throws Exception {
        when(service.verify(eq(TOKEN), eq("123456"), eq(null), any())).thenReturn(new AdminAuthService.SignedIn(
                "the-access-token", 600, new AdminIdentity(ADMIN_ID, "Dev admin"), null, "the-refresh-token"));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Set-Cookie", containsString("sahaj_admin_rt=the-refresh-token")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/admin/auth")))
                .andExpect(jsonPath("$.accessToken").value("the-access-token"))
                .andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.admin.id").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.admin.name").value("Dev admin"))
                .andExpect(jsonPath("$.admin.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.admin.roleLabel").value("Platform admin"))
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist())
                .andExpect(content().string(not(containsString("the-refresh-token"))));
    }

    @Test
    void verifyAtEnrolmentCarriesTheRecoveryCodesOnce() throws Exception {
        List<String> codes = List.of("AAAA-BBBB-CCCC-DDDD", "AAAA-BBBB-CCCC-DDDE", "AAAA-BBBB-CCCC-DDDF",
                "AAAA-BBBB-CCCC-DDDG", "AAAA-BBBB-CCCC-DDDH", "AAAA-BBBB-CCCC-DDDI", "AAAA-BBBB-CCCC-DDDJ",
                "AAAA-BBBB-CCCC-DDDK", "AAAA-BBBB-CCCC-DDDL", "AAAA-BBBB-CCCC-DDDM");
        when(service.verify(any(), any(), any(), any())).thenReturn(new AdminAuthService.SignedIn(
                "t", 600, new AdminIdentity(ADMIN_ID, "Dev admin"), codes, "r"));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andExpect(jsonPath("$.recoveryCodes[0]").value("AAAA-BBBB-CCCC-DDDD"));
    }

    @Test
    void verifyAcceptsARecoveryCodeInPlaceOfACode() throws Exception {
        when(service.verify(eq(TOKEN), eq(null), eq("abcd-efgh-2345-67ab"), any())).thenReturn(
                new AdminAuthService.SignedIn("t", 600, new AdminIdentity(ADMIN_ID, "Dev admin"), null, "r"));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"recoveryCode\":\"abcd-efgh-2345-67ab\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void verifyValidatesTheCodeFormats() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"))
                .andExpect(content().string(not(containsString("12345\""))));
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"recoveryCode\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("recoveryCode"));
    }

    @Test
    void verifyWrongCodeAndExpiredTokenProblems() throws Exception {
        when(service.verify(any(), any(), any(), any()))
                .thenThrow(new AdminAuthException(AdminAuthException.Kind.TOTP_INVALID));
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/totp-invalid"))
                .andExpect(jsonPath("$.title").value("That code is not right."))
                .andExpect(header().doesNotExist("Set-Cookie"));

        org.mockito.Mockito.reset(service);
        when(service.verify(any(), any(), any(), any()))
                .thenThrow(new AdminAuthException(AdminAuthException.Kind.TOTP_ENROLMENT_NOT_STARTED));
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/totp-enrolment-not-started"));
    }

    @Test
    void anUnexpectedFailureIsAGenericProblemWithoutDetail() throws Exception {
        when(service.verify(any(), any(), any(), any()))
                .thenThrow(new TotpSecretCipher.TotpSecretUnreadableException());

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/totp/verify")).contentType(JSON)
                        .content("{\"preAuthToken\":\"" + TOKEN + "\",\"code\":\"123456\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/internal-error"));
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void refreshRotatesTheCookie() throws Exception {
        when(service.refresh("old-refresh")).thenReturn(new AdminAuthService.SignedIn("new-access", 600,
                new AdminIdentity(ADMIN_ID, "Dev admin"), null, "new-refresh"));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/refresh")).cookie(new Cookie("sahaj_admin_rt", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("sahaj_admin_rt=new-refresh")))
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist());
    }

    @Test
    void refreshWithoutTheCookieIsSessionEnded() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-session-ended"));
        verify(service, never()).refresh(any());
    }

    @Test
    void refreshIgnoresTheClinicCookie() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/refresh")).cookie(new Cookie("sahaj_rt", "clinic-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-session-ended"));
        verify(service, never()).refresh(any());
    }

    @Test
    void aReusedRefreshTokenIsSessionEnded() throws Exception {
        when(service.refresh("reused")).thenThrow(new AdminAuthException(AdminAuthException.Kind.SESSION_ENDED));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/refresh")).cookie(new Cookie("sahaj_admin_rt", "reused")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-session-ended"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ------------------------------------------------------------------ sign-out and /me

    @Test
    void signOutNeedsAnAdminTokenRevokesAndClearsTheCookie() throws Exception {
        sessionIsLive(true);

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/sign-out")).header("Authorization", adminBearer()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("sahaj_admin_rt=;")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/admin/auth")));
        verify(service).signOut();
    }

    @Test
    void signOutWithoutATokenIs401AndDoesNothing() throws Exception {
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/sign-out")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/unauthorized"));
        verify(service, never()).signOut();
    }

    @Test
    void meReturnsTheSignedInAdmin() throws Exception {
        sessionIsLive(true);
        when(service.me()).thenReturn(new AdminIdentity(ADMIN_ID, "Dev admin"));

        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.name").value("Dev admin"))
                .andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.roleLabel").value("Platform admin"));
    }

    @Test
    void everyRequestChecksTheSessionRow() throws Exception {
        sessionIsLive(true);
        when(service.me()).thenReturn(new AdminIdentity(ADMIN_ID, "Dev admin"));
        String bearer = adminBearer();

        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", bearer)).andExpect(status().isOk());

        verify(repository, org.mockito.Mockito.times(2)).sessionTouch(SESSION_ID, ADMIN_ID);
    }

    @Test
    void anEndedSessionAnswersAdminSessionEndedEvenWithAValidToken() throws Exception {
        sessionIsLive(false);

        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", adminBearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-session-ended"))
                .andExpect(jsonPath("$.title").value("Your session ended. Sign in again."));
        verify(service, never()).me();
    }

    // ------------------------------------------------------------------ realm separation

    @Test
    void noTokenIs401Unauthorized() throws Exception {
        mockMvc.perform(fromNewAddress(get(BASE + "/me")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/unauthorized"));
    }

    @Test
    void aClinicTokenIsRefusedOnAdminRoutes() throws Exception {
        String clinic = clinicTokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "OWNER").token();

        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", "Bearer " + clinic))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewAddress(post(BASE + "/auth/sign-out")).header("Authorization", "Bearer " + clinic))
                .andExpect(status().isUnauthorized());
        verify(repository, never()).sessionTouch(any(), any());
    }

    @Test
    void aClinicTokenIsIgnoredOnThePublicSignInRoute() throws Exception {
        String clinic = clinicTokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "OWNER").token();
        when(service.login(any(), any(), any()))
                .thenReturn(new AdminAuthService.LoginResult(AdminAuthService.Next.TOTP, TOKEN, 300));

        mockMvc.perform(fromNewAddress(post(BASE + "/auth/login")).contentType(JSON)
                        .header("Authorization", "Bearer " + clinic)
                        .content("{\"email\":\"dev.admin@example.test\",\"password\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aPreAuthTokenIsNotABearerToken() throws Exception {
        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        verify(repository, never()).sessionTouch(any(), any());
    }

    @Test
    void anAdminTokenIsRefusedOnTheClinicApi() throws Exception {
        sessionIsLive(true);

        mockMvc.perform(get("/api/v1/platform/team").header("Authorization", adminBearer()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/opd/appointments").header("Authorization", adminBearer()))
                .andExpect(status().isUnauthorized());
        verify(repository, never()).sessionTouch(any(), any());
    }

    @Test
    void aClinicRoleNeverSatisfiesAnAdminRoute() throws Exception {
        mockMvc.perform(fromNewAddress(get(BASE + "/me"))
                        .with(TestTenantSecurity.asUser(UUID.randomUUID(), UUID.randomUUID(), "OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownAdminRouteNeedsTheAdminRole() throws Exception {
        mockMvc.perform(fromNewAddress(get(BASE + "/clinics"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aDatabaseFailureInTheSessionCheckFailsClosedWith503() throws Exception {
        when(repository.sessionTouch(SESSION_ID, ADMIN_ID)).thenThrow(new IllegalStateException("db down"));

        mockMvc.perform(fromNewAddress(get(BASE + "/me")).header("Authorization", adminBearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/admin-unavailable"));
    }
}
