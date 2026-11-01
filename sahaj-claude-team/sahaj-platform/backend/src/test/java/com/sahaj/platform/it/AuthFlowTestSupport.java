package com.sahaj.platform.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.support.LogCapture;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared helpers for the SAH-3 sign-up/log-in flow integration tests: driving the real
 * {@code /auth/**} endpoints through MockMvc end to end, reading the one-time code the way the
 * product is allowed to read it (the dev/test log line {@link com.sahaj.platform.infrastructure.otp.LogOtpSender}
 * writes), never from any API response.
 */
abstract class AuthFlowTestSupport extends BaseIntegrationTest {

    static final String AUTH = "/api/v1/platform/auth";

    private static final Pattern CODE_IN_LOG =
            Pattern.compile("One-time code for [^:]+:\\s*(\\d{6})");

    @Autowired
    protected TokenService tokenService;

    /** A mobile number that matches {@code ^[6-9][0-9]{9}$} and is unique enough not to collide within a run. */
    static String uniqueMobile() {
        long n = Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 1_000_000_000L;
        return "9" + String.format("%09d", n);
    }

    /** Sends a request for a code and reads it back out of the one permitted log line, never a response. */
    protected String requestOtpAndCaptureCode(String mobile, String mode) throws Exception {
        return requestOtpCapturingResultAndCode(mobile, mode).code();
    }

    /** Same as {@link #requestOtpAndCaptureCode}, but also hands back the raw {@code MvcResult} to inspect. */
    protected RequestOutcome requestOtpCapturingResultAndCode(String mobile, String mode) throws Exception {
        try (LogCapture logs = new LogCapture()) {
            MvcResult result = mvc.perform(post(AUTH + "/otp/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(otpRequestBody(mobile, mode)))
                    .andExpect(status().isAccepted())
                    .andReturn();
            String code = lastCode(logs.allText());
            if (code == null) {
                throw new IllegalStateException("No one-time code was captured from the log for this request");
            }
            return new RequestOutcome(result, code);
        }
    }

    protected record RequestOutcome(MvcResult result, String code) {
    }

    private static String lastCode(String logText) {
        Matcher matcher = CODE_IN_LOG.matcher(logText);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    protected static String otpRequestBody(String mobile, String mode) {
        return "{\"mobile\":\"" + mobile + "\",\"mode\":\"" + mode + "\"}";
    }

    protected static String otpVerifyBody(String mobile, String code, String mode, String termsVersion, String privacyVersion) {
        String consent = termsVersion == null ? ""
                : ",\"consent\":{\"termsVersion\":\"" + termsVersion + "\",\"privacyVersion\":\"" + privacyVersion + "\"}";
        return "{\"mobile\":\"" + mobile + "\",\"code\":\"" + code + "\",\"mode\":\"" + mode + "\"" + consent + "}";
    }

    protected MvcResult verify(String mobile, String code, String mode, String termsVersion, String privacyVersion) throws Exception {
        return mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, mode, termsVersion, privacyVersion)))
                .andReturn();
    }

    /** Signs up a brand new mobile end to end and returns the session it opens (next = SETUP). */
    protected Session signUp(String mobile) throws Exception {
        String code = requestOtpAndCaptureCode(mobile, "SIGNUP");
        MvcResult result = verify(mobile, code, "SIGNUP", "v0.1", "v0.1");
        return Session.from(result, tokenService);
    }

    /** A logged-in session read back out of an {@code AuthResult} response: never the raw refresh token's source. */
    protected record Session(String next, String accessToken, String refreshCookieValue, UUID tenantId, UUID userId, String role) {

        static Session from(MvcResult result, TokenService tokenService) throws Exception {
            String body = result.getResponse().getContentAsString();
            String next = readString(body, "next");
            String accessToken = readString(body, "accessToken");
            Cookie cookie = result.getResponse().getCookie(com.sahaj.platform.infrastructure.security.RefreshTokenCookie.COOKIE_NAME);
            String refreshCookieValue = cookie == null ? null : cookie.getValue();
            UUID tenantId = null;
            UUID userId = null;
            String role = null;
            if (accessToken != null) {
                TokenService.AccessTokenClaims claims = tokenService.parseAccessToken(accessToken).orElseThrow();
                tenantId = claims.tenantId();
                userId = claims.userId();
                role = claims.role();
            }
            return new Session(next, accessToken, refreshCookieValue, tenantId, userId, role);
        }

        private static String readString(String json, String field) {
            Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        }
    }

    /** Directly sets a membership's status, committed, bypassing any service - there is no "remove access" API yet (SAH-6). */
    protected void setMembershipStatus(UUID tenantId, UUID userId, String status) throws SQLException {
        try (Connection connection = db().app()) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "update platform.membership set status = ? where tenant_id = ?::uuid and user_id = ?::uuid")) {
                update.setString(1, status);
                update.setString(2, tenantId.toString());
                update.setString(3, userId.toString());
                update.executeUpdate();
            }
            connection.commit();
        }
    }

    /**
     * Directly adds a second, already-ACTIVE membership for an existing user in a second tenant - the way a
     * clinic owner adding a staff member (SAH-6) will eventually produce this row. Committed so later requests
     * on the application's own pool can see it.
     */
    protected void addMembership(UUID tenantId, UUID userId, String role) throws SQLException {
        try (Connection connection = db().app()) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into platform.membership (tenant_id, user_id, role) values (?::uuid, ?::uuid, ?)")) {
                insert.setString(1, tenantId.toString());
                insert.setString(2, userId.toString());
                insert.setString(3, role);
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    /** Pushes the newest OTP challenge for this mobile far enough into the past to be expired and resendable. */
    protected void ageOutLatestChallenge(String mobile) throws SQLException {
        try (Connection connection = db().app()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update platform.otp_challenge set expires_at = now() - interval '1 minute', "
                            + "last_sent_at = now() - interval '10 minutes' "
                            + "where id = (select id from platform.otp_challenge where mobile = ? order by created_at desc limit 1)")) {
                update.setString(1, mobile);
                update.executeUpdate();
            }
        }
    }
}
