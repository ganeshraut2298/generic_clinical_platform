package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SAH-3: the one-time code itself - AC3 (lockout), AC6 (never leaked) and AC9 (expiry and resend).
 * Runs end to end against {@code sahaj_test}.
 */
@OnTestDatabase
class OtpSecurityIT extends AuthFlowTestSupport {

    // ---- AC3: five wrong codes locks the mobile for 15 minutes with a clear message ----

    @Test
    void fiveWrongCodesLocksTheMobileForFifteenMinutesWith423() throws Exception {
        String mobile = uniqueMobile();
        String realCode = requestOtpAndCaptureCode(mobile, "LOGIN");
        String wrongCode = realCode.equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(post(AUTH + "/otp/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(otpVerifyBody(mobile, wrongCode, "LOGIN", null, null)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, wrongCode, "LOGIN", null, null)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.retryAfterSeconds").value(900))
                .andExpect(jsonPath("$.title").exists());

        // Even the right code is refused once locked.
        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, realCode, "LOGIN", null, null)))
                .andExpect(status().isLocked());
    }

    @Test
    void whileLockedRequestingANewCodeIsAlsoRefused() throws Exception {
        String mobile = uniqueMobile();
        String realCode = requestOtpAndCaptureCode(mobile, "LOGIN");
        String wrongCode = realCode.equals("000000") ? "111111" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post(AUTH + "/otp/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(otpVerifyBody(mobile, wrongCode, "LOGIN", null, null)));
        }

        mvc.perform(post(AUTH + "/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpRequestBody(mobile, "LOGIN")))
                .andExpect(status().isLocked());
    }

    // ---- AC6: the code never appears in any response body, Set-Cookie header, or URL ----

    @Test
    void theCodeNeverAppearsInAnyResponseBodyHeaderOrUrlAcrossRequestAndVerify() throws Exception {
        String mobile = uniqueMobile();

        RequestOutcome requested = requestOtpCapturingResultAndCode(mobile, "SIGNUP");
        assertNoneOfTheseContain(requested.result(), requested.code());

        MvcResult verifyResult = verify(mobile, requested.code(), "SIGNUP", "v0.1", "v0.1");
        assertThat(verifyResult.getResponse().getStatus()).isEqualTo(200);
        assertNoneOfTheseContain(verifyResult, requested.code());
    }

    private void assertNoneOfTheseContain(MvcResult result, String code) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("response body").doesNotContain(code);

        for (String headerName : Collections.list(Collections.enumeration(result.getResponse().getHeaderNames()))) {
            for (String value : result.getResponse().getHeaders(headerName)) {
                assertThat(value).as("header " + headerName).doesNotContain(code);
            }
        }

        String uri = result.getRequest().getRequestURI()
                + (result.getRequest().getQueryString() == null ? "" : "?" + result.getRequest().getQueryString());
        assertThat(uri).as("request URL").doesNotContain(code);
    }

    // ---- AC9: a code older than 5 minutes is refused, and a new one can be requested ----

    @Test
    void aCodeOlderThanFiveMinutesIsRefusedAndANewOneCanBeRequestedAndUsed() throws Exception {
        String mobile = uniqueMobile();
        String staleCode = requestOtpAndCaptureCode(mobile, "SIGNUP");

        ageOutLatestChallenge(mobile);

        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, staleCode, "SIGNUP", "v0.1", "v0.1")))
                .andExpect(status().isUnauthorized());

        String freshCode = requestOtpAndCaptureCode(mobile, "SIGNUP");
        assertThat(freshCode).isNotEqualTo(staleCode);

        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, freshCode, "SIGNUP", "v0.1", "v0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("SETUP"));
    }
}
