package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * SAH-3: sign-up and log-in end to end, through the real {@code /auth/**} endpoints against
 * {@code sahaj_test} - AC1, AC2, AC4, AC5, AC11, AC12, AC13, AC14, plus the negative, role and
 * boundary cases the QA process requires.
 */
@OnTestDatabase
class SignUpAndLoginIT extends AuthFlowTestSupport {

    // ---- AC1: sign-up creates tenant, owner, membership, consent and audit in one go ----

    @Test
    void signUpCreatesTenantUserOwnerMembershipConsentAndAuditTogetherAndOpensSetup() throws Exception {
        String mobile = uniqueMobile();

        Session session = signUp(mobile);

        assertThat(session.next()).isEqualTo("SETUP");
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.refreshCookieValue()).isNotBlank();
        assertThat(session.role()).isEqualTo("OWNER");

        UUID tenantId = session.tenantId();
        UUID userId = session.userId();

        db().inTenant(tenantId, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.tenant where id = ?::uuid", tenantId.toString()))
                    .as("tenant row").isEqualTo(1);
            assertThat(DbAccess.count(c, "select count(*) from platform.membership where tenant_id = ?::uuid and user_id = ?::uuid "
                            + "and role = 'OWNER' and status = 'ACTIVE'", tenantId.toString(), userId.toString()))
                    .as("owner membership").isEqualTo(1);
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where tenant_id = ?::uuid and action = 'SIGN_UP'",
                    tenantId.toString())).as("sign-up audit row").isEqualTo(1);
            return null;
        });
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.app_user where id = ?::uuid and mobile = ?",
                    userId.toString(), mobile)).as("app_user row").isEqualTo(1);
            assertThat(DbAccess.count(c, "select count(*) from platform.consent_record where user_id = ?::uuid "
                            + "and terms_version = 'v0.1' and privacy_version = 'v0.1'", userId.toString()))
                    .as("consent row").isEqualTo(1);
            return null;
        });
    }

    // ---- AC2: a registered mobile with one membership lands on APP ----

    @Test
    void loginResponseShapeIsAppWithAFreshSessionAndNoClinicChoice() throws Exception {
        String mobile = uniqueMobile();
        Session signUpSession = signUp(mobile);
        // Sign-up alone does not finish the setup wizard; without this, an OWNER's next login
        // still comes back SETUP (docs/database/SAH-4.md), which is not what this test is about.
        completeSetup(signUpSession.tenantId());

        // Sign-up just requested a code for this mobile; the 30 second resend throttle
        // (OtpService.RESEND_THROTTLE) is still running, so age the challenge out first.
        ageOutLatestChallenge(mobile);
        String code = requestOtpAndCaptureCode(mobile, "LOGIN");
        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, "LOGIN", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("APP"))
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.selectionToken").doesNotExist())
                .andExpect(jsonPath("$.clinics").doesNotExist())
                .andExpect(header().exists("Set-Cookie"));
    }

    // ---- AC4: an unregistered mobile on Log in ----

    @Test
    void unregisteredMobileOnLoginReturnsNotRegisteredWithoutOpeningASession() throws Exception {
        String mobile = uniqueMobile();
        String code = requestOtpAndCaptureCode(mobile, "LOGIN");

        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, "LOGIN", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("NOT_REGISTERED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ---- AC5: a membership that was removed cannot log in (role/permission refusal: 403) ----

    @Test
    void aRemovedMembershipCannotLogInAndGets403() throws Exception {
        String mobile = uniqueMobile();
        Session signUpSession = signUp(mobile);
        // platform.guard_last_owner() refuses to deactivate the clinic's only active owner, so add
        // a second active owner first (SAH-6 AC10 backstop in V2026_11_01_1023). The membership's
        // user_id is a foreign key to platform.app_user, so the second owner must be a real,
        // signed-up person, not just any UUID.
        Session secondOwner = signUp(uniqueMobile());
        addMembership(signUpSession.tenantId(), secondOwner.userId(), "OWNER");
        setMembershipStatus(signUpSession.tenantId(), signUpSession.userId(), "REMOVED");

        ageOutLatestChallenge(mobile);
        String code = requestOtpAndCaptureCode(mobile, "LOGIN");
        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, "LOGIN", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void anInactiveMembershipCannotLogInEither() throws Exception {
        String mobile = uniqueMobile();
        Session signUpSession = signUp(mobile);
        // Same last-owner backstop as above: give the clinic a second active owner first.
        Session secondOwner = signUp(uniqueMobile());
        addMembership(signUpSession.tenantId(), secondOwner.userId(), "OWNER");
        setMembershipStatus(signUpSession.tenantId(), signUpSession.userId(), "INACTIVE");

        ageOutLatestChallenge(mobile);
        String code = requestOtpAndCaptureCode(mobile, "LOGIN");
        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, "LOGIN", null, null)))
                .andExpect(status().isForbidden());
    }

    // ---- AC11: signing up again on a mobile that already has a clinic logs them in instead ----

    @Test
    void signingUpAgainOnAMobileThatAlreadyHasAClinicLogsInInsteadOfCreatingAnother() throws Exception {
        String mobile = uniqueMobile();
        Session firstSignUp = signUp(mobile);
        // Sign-up alone does not finish the setup wizard; without this, the owner's next login
        // still comes back SETUP (docs/database/SAH-4.md) rather than APP.
        completeSetup(firstSignUp.tenantId());

        // signUp() already requested a code for this mobile; past the 30 second resend throttle
        // before asking for another one.
        ageOutLatestChallenge(mobile);
        String code = requestOtpAndCaptureCode(mobile, "SIGNUP");
        var result = mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, "SIGNUP", "v0.1", "v0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("APP"))
                .andReturn();
        Session secondAttempt = Session.from(result, tokenService);

        assertThat(secondAttempt.tenantId()).as("still the one clinic, no second tenant created")
                .isEqualTo(firstSignUp.tenantId());
        db().inTenant(firstSignUp.tenantId(), c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where action = 'SIGN_UP' and tenant_id = ?::uuid",
                    firstSignUp.tenantId().toString())).as("only the original SIGN_UP").isEqualTo(1);
            return null;
        });
    }

    // ---- AC12: Terms/Privacy consent recorded with time and version ----

    @Test
    void consentIsRecordedWithTermsVersionPrivacyVersionAndATimestamp() throws Exception {
        OffsetDateTime before = OffsetDateTime.now();
        String mobile = uniqueMobile();
        Session session = signUp(mobile);

        db().asApp(c -> {
            try (var ps = c.prepareStatement(
                    "select terms_version, privacy_version, at from platform.consent_record where user_id = ?::uuid")) {
                ps.setString(1, session.userId().toString());
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("terms_version")).isEqualTo("v0.1");
                    assertThat(rs.getString("privacy_version")).isEqualTo("v0.1");
                    OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
                    assertThat(Duration.between(before, at).abs()).isLessThan(Duration.ofMinutes(2));
                }
            }
            return null;
        });
    }

    // ---- AC13: a new clinic has the Free plan ending 12 months from creation ----

    @Test
    void aNewClinicHasTheFreePlanEndingTwelveMonthsFromCreation() throws Exception {
        String mobile = uniqueMobile();
        Session session = signUp(mobile);

        db().inTenant(session.tenantId(), c -> {
            try (var ps = c.prepareStatement(
                    "select plan, created_at, plan_ends_at from platform.tenant where id = ?::uuid")) {
                ps.setString(1, session.tenantId().toString());
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("plan")).isEqualTo("FREE");
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    OffsetDateTime planEndsAt = rs.getObject("plan_ends_at", OffsetDateTime.class);
                    assertThat(planEndsAt).isNotNull();
                    long months = ChronoUnit.MONTHS.between(createdAt.atZoneSameInstant(java.time.ZoneOffset.UTC),
                            planEndsAt.atZoneSameInstant(java.time.ZoneOffset.UTC));
                    assertThat(months).as("plan runs twelve months from creation").isEqualTo(12);
                }
            }
            return null;
        });
    }

    // ---- AC14: sign-in, sign-up and logout each write an audit event with the mobile masked ----

    @Test
    void signUpSignInAndLogoutEachWriteAnAuditEventWithTheMobileMaskedButTheClinicNameVisible() throws Exception {
        String mobile = uniqueMobile();
        Session signUpSession = signUp(mobile);
        UUID tenantId = signUpSession.tenantId();
        String maskedTail = "••••••" + mobile.substring(mobile.length() - 4);
        ageOutLatestChallenge(mobile);

        db().inTenant(tenantId, c -> {
            assertThat(DbAccess.text(c, "select after_json ->> 'mobile' from platform.audit_event where action = 'SIGN_UP' and tenant_id = ?::uuid",
                    tenantId.toString())).as("mobile masked on sign-up").isEqualTo(maskedTail);
            assertThat(DbAccess.text(c, "select after_json ->> 'name' from platform.audit_event where action = 'SIGN_UP' and tenant_id = ?::uuid",
                    tenantId.toString())).as("clinic name stays visible, it is not patient data").isEqualTo("New clinic");
            String raw = DbAccess.text(c, "select after_json::text from platform.audit_event where action = 'SIGN_UP' and tenant_id = ?::uuid",
                    tenantId.toString());
            assertThat(raw).doesNotContain(mobile);
            return null;
        });

        String loginCode = requestOtpAndCaptureCode(mobile, "LOGIN");
        verify(mobile, loginCode, "LOGIN", null, null);
        db().inTenant(tenantId, c -> {
            assertThat(DbAccess.text(c, "select after_json ->> 'mobile' from platform.audit_event where action = 'SIGN_IN' and tenant_id = ?::uuid",
                    tenantId.toString())).as("mobile masked on sign-in").isEqualTo(maskedTail);
            return null;
        });

        mvc.perform(post(AUTH + "/logout").with(com.sahaj.platform.support.TestTenantSecurity.asUser(tenantId, signUpSession.userId())))
                .andExpect(status().isNoContent());
        db().inTenant(tenantId, c -> {
            assertThat(DbAccess.text(c, "select after_json ->> 'mobile' from platform.audit_event where action = 'SIGN_OUT' and tenant_id = ?::uuid",
                    tenantId.toString())).as("mobile masked on sign-out").isEqualTo(maskedTail);
            return null;
        });
    }

    // ---- negative / boundary: invalid input gets 400 with a problem-details body ----

    @Test
    void anInvalidMobileOnOtpRequestIs400WithAProblemDetailsBody() throws Exception {
        mvc.perform(post(AUTH + "/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"12345\",\"mode\":\"LOGIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(PROBLEM_JSON)))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.startsWith(PROBLEM_BASE)))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("mobile"));
    }

    @Test
    void signUpWithoutConsentIs400WithAFieldError() throws Exception {
        String mobile = uniqueMobile();
        String code = requestOtpAndCaptureCode(mobile, "SIGNUP");

        mvc.perform(post(AUTH + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpVerifyBody(mobile, code, "SIGNUP", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("consent"));
    }
}
