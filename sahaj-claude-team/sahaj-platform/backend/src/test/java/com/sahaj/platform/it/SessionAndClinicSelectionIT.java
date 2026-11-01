package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.infrastructure.security.RefreshTokenCookie;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SAH-3: clinic selection (AC10), signing out everywhere (AC15), refresh token rotation, reuse
 * detection and the rule that a refresh token is a cookie and nothing else. Runs end to end
 * against {@code sahaj_test}.
 */
@OnTestDatabase
class SessionAndClinicSelectionIT extends AuthFlowTestSupport {

    /** Signs a fresh mobile up (owner of tenant A), then gives it a second, already-ACTIVE membership in a brand new tenant B. */
    private TwoClinicUser createUserWithTwoClinics() throws Exception {
        String mobile = uniqueMobile();
        Session signUp = signUp(mobile);
        UUID tenantB = UUID.randomUUID();
        createTenant(tenantB, UUID.randomUUID(), uniqueName("Second clinic"));
        addMembership(tenantB, signUp.userId(), "DOCTOR");
        return new TwoClinicUser(mobile, signUp.userId(), signUp.tenantId(), tenantB);
    }

    private record TwoClinicUser(String mobile, UUID userId, UUID tenantA, UUID tenantB) {
    }

    // ---- AC10: a mobile in more than one clinic chooses, then the app opens ----

    @Test
    void loginWithTwoClinicsReturnsChooseClinicAndSelectingTheRightOneOpensTheApp() throws Exception {
        TwoClinicUser user = createUserWithTwoClinics();

        String code = requestOtpAndCaptureCode(user.mobile(), "LOGIN");
        MvcResult result = mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(user.mobile(), code, "LOGIN", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("CHOOSE_CLINIC"))
                .andExpect(jsonPath("$.selectionToken").exists())
                .andExpect(jsonPath("$.clinics.length()").value(2))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        String selectionToken = readJsonString(result, "selectionToken");

        mvc.perform(post(AUTH + "/select-clinic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionToken\":\"" + selectionToken + "\",\"tenantId\":\"" + user.tenantB() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("APP"))
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void selectClinicWithATenantTheUserDoesNotBelongToIsRefused() throws Exception {
        TwoClinicUser user = createUserWithTwoClinics();
        String code = requestOtpAndCaptureCode(user.mobile(), "LOGIN");
        MvcResult result = verify(user.mobile(), code, "LOGIN", null, null);
        String selectionToken = readJsonString(result, "selectionToken");

        UUID someoneElsesTenant = UUID.randomUUID();
        mvc.perform(post(AUTH + "/select-clinic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionToken\":\"" + selectionToken + "\",\"tenantId\":\"" + someoneElsesTenant + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void selectClinicWithNoSelectionTokenIs400() throws Exception {
        mvc.perform(post(AUTH + "/select-clinic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionToken\":\"\",\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("selectionToken"));
    }

    @Test
    void selectClinicWithAGarbledSelectionTokenIs401() throws Exception {
        mvc.perform(post(AUTH + "/select-clinic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionToken\":\"not-a-real-token\",\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- AC15: log out on all devices stops every refresh token, in every tenant ----

    @Test
    void logoutAllRevokesRefreshTokensInEveryTenantTheUserBelongsToNotJustTheOneActiveAtLogout() throws Exception {
        TwoClinicUser user = createUserWithTwoClinics();
        String code = requestOtpAndCaptureCode(user.mobile(), "LOGIN");
        MvcResult chooseResult = verify(user.mobile(), code, "LOGIN", null, null);
        String selectionToken = readJsonString(chooseResult, "selectionToken");

        MvcResult sessionAResult = mvc.perform(post(AUTH + "/select-clinic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionToken\":\"" + selectionToken + "\",\"tenantId\":\"" + user.tenantA() + "\"}"))
                .andReturn();
        String refreshA = refreshCookieOf(sessionAResult);

        MvcResult sessionBResult = mvc.perform(post(AUTH + "/select-clinic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionToken\":\"" + selectionToken + "\",\"tenantId\":\"" + user.tenantB() + "\"}"))
                .andReturn();
        String refreshB = refreshCookieOf(sessionBResult);

        // Log out on all devices while "in" tenant A.
        mvc.perform(post(AUTH + "/logout-all").with(asUser(user.tenantA(), user.userId())))
                .andExpect(status().isNoContent());

        // Tenant A's own refresh token is dead.
        mvc.perform(post(AUTH + "/refresh").cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, refreshA)))
                .andExpect(status().isUnauthorized());
        // Tenant B's refresh token - never touched directly at logout - is dead too.
        mvc.perform(post(AUTH + "/refresh").cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, refreshB)))
                .andExpect(status().isUnauthorized());
    }

    // ---- Refresh rotation and reuse detection ----

    @Test
    void refreshRotatesTheCookieAndTheOldValueStopsWorking() throws Exception {
        String mobile = uniqueMobile();
        Session session = signUp(mobile);

        MvcResult refreshed = mvc.perform(post(AUTH + "/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, session.refreshCookieValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("APP"))
                .andReturn();
        String rotated = refreshCookieOf(refreshed);
        assertThat(rotated).isNotEqualTo(session.refreshCookieValue());

        // The original cookie value cannot be used a second time.
        mvc.perform(post(AUTH + "/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, session.refreshCookieValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reusingAnAlreadyRotatedRefreshTokenRevokesEveryLiveTokenForThatUserInThatTenant() throws Exception {
        String mobile = uniqueMobile();
        Session session = signUp(mobile);

        MvcResult refreshed = mvc.perform(post(AUTH + "/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, session.refreshCookieValue())))
                .andExpect(status().isOk())
                .andReturn();
        String rotated = refreshCookieOf(refreshed);

        // Reuse of the rotated-out original: refused, and treated as compromised.
        mvc.perform(post(AUTH + "/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, session.refreshCookieValue())))
                .andExpect(status().isUnauthorized());

        // The token that replaced it is now revoked too, even though it was never reused.
        mvc.perform(post(AUTH + "/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, rotated)))
                .andExpect(status().isUnauthorized());
    }

    // ---- Refresh token hygiene: a cookie only, never JSON ----

    @Test
    void theRefreshTokenIsNeverPresentInAnyJsonResponseBody() throws Exception {
        String mobile = uniqueMobile();
        Session session = signUp(mobile);

        mvc.perform(post(AUTH + "/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.COOKIE_NAME, session.refreshCookieValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).as("no field named like a refresh token")
                            .doesNotContainIgnoringCase("refresh")
                            .doesNotContain(session.refreshCookieValue());
                });
    }

    private static String readJsonString(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("Field " + field + " not found in: " + body);
        }
        return m.group(1);
    }

    private static String refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(RefreshTokenCookie.COOKIE_NAME);
        return cookie == null ? null : cookie.getValue();
    }
}
