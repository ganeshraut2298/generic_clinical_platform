package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.AccessRefusedException;
import com.sahaj.platform.application.AuthService;
import com.sahaj.platform.application.OtpLockedException;
import com.sahaj.platform.application.OtpResendTooSoonException;
import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.application.VerifyOutcome;
import com.sahaj.platform.domain.AuthMode;
import com.sahaj.platform.domain.TenantSummary;
import com.sahaj.platform.infrastructure.security.RefreshTokenCookie;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** SAH-3: contract shape, error mapping and the refresh cookie behaviour of the auth endpoints. */
@WebMvcTest(controllers = AuthController.class, properties = "sahaj.auth.refresh-cookie-secure=true")
@Import(SecurityConfig.class)
class AuthControllerTest {

    private static final String BASE = "/api/v1/platform/auth";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void requestOtpReturns202AndNeverTheCode() throws Exception {
        when(authService.requestOtp("9123457410")).thenReturn(30);

        mockMvc.perform(post(BASE + "/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendAfterSeconds").value(30))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("code");
                });
    }

    @Test
    void requestOtpRejectsAnInvalidMobile() throws Exception {
        mockMvc.perform(post(BASE + "/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"12345\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("mobile"));
    }

    @Test
    void requestOtpLockedMapsTo423WithRetryAfter() throws Exception {
        when(authService.requestOtp(any())).thenThrow(new OtpLockedException(Duration.ofMinutes(15)));

        mockMvc.perform(post(BASE + "/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.retryAfterSeconds").value(900));
    }

    @Test
    void requestOtpTooSoonMapsTo429() throws Exception {
        when(authService.requestOtp(any())).thenThrow(new OtpResendTooSoonException(Duration.ofSeconds(20)));

        mockMvc.perform(post(BASE + "/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.retryAfterSeconds").value(20));
    }

    @Test
    void verifyRejectsAShortCode() throws Exception {
        mockMvc.perform(post(BASE + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"code\":\"123\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
    }

    @Test
    void verifyNotRegisteredIs200WithNoCookie() throws Exception {
        when(authService.verify(eq("9123457410"), eq("123456"), eq(AuthMode.LOGIN), eq(null)))
                .thenReturn(VerifyOutcome.notRegistered());

        mockMvc.perform(post(BASE + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"code\":\"123456\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("NOT_REGISTERED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void verifyAccessRefusedMapsTo403() throws Exception {
        when(authService.verify(any(), any(), any(), any())).thenThrow(new AccessRefusedException("no"));

        mockMvc.perform(post(BASE + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"code\":\"123456\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifySignUpSetsTheRefreshCookieAndReturnsSetup() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(authService.verify(any(), any(), eq(AuthMode.SIGNUP), any()))
                .thenReturn(VerifyOutcome.setup(new TokenService.AccessToken("jwt", 900), "tenant.secret", expiresAt));

        mockMvc.perform(post(BASE + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"code\":\"123456\",\"mode\":\"SIGNUP\","
                                + "\"consent\":{\"termsVersion\":\"v0.1\",\"privacyVersion\":\"v0.1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("SETUP"))
                .andExpect(jsonPath("$.accessToken").value("jwt"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(cookie().exists(RefreshTokenCookie.COOKIE_NAME))
                .andExpect(cookie().httpOnly(RefreshTokenCookie.COOKIE_NAME, true))
                .andExpect(cookie().secure(RefreshTokenCookie.COOKIE_NAME, true));
    }

    @Test
    void verifyChooseClinicListsEveryActiveClinic() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(authService.verify(any(), any(), any(), any()))
                .thenReturn(VerifyOutcome.chooseClinic("sel-token", List.of(new TenantSummary(tenantId, "A clinic"))));

        mockMvc.perform(post(BASE + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9123457410\",\"code\":\"123456\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("CHOOSE_CLINIC"))
                .andExpect(jsonPath("$.selectionToken").value("sel-token"))
                .andExpect(jsonPath("$.clinics[0].name").value("A clinic"));
    }

    @Test
    void refreshWithNoCookieIs401() throws Exception {
        mockMvc.perform(post(BASE + "/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutALoginIs401() throws Exception {
        mockMvc.perform(post(BASE + "/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithALoginIs204AndClearsTheCookie() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post(BASE + "/logout").with(TestTenantSecurity.asUser(tenantId, userId)))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(RefreshTokenCookie.COOKIE_NAME, 0));
    }

    @Test
    void logoutAllWithALoginIs204() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post(BASE + "/logout-all").with(TestTenantSecurity.asUser(tenantId, userId)))
                .andExpect(status().isNoContent());
    }
}
