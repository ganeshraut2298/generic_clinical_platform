package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.application.OtpLockedException;
import com.sahaj.platform.application.OtpResendTooSoonException;
import com.sahaj.platform.application.PinCodeSentView;
import com.sahaj.platform.application.PinSetInput;
import com.sahaj.platform.application.PinStatusView;
import com.sahaj.platform.application.StaffPinService;
import com.sahaj.platform.domain.PinState;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-31: the three own-PIN endpoints, their shapes and problem types as in docs/api/platform.yaml. */
@WebMvcTest(controllers = MePinController.class)
@Import(SecurityConfig.class)
class MePinControllerTest {

    private static final String PIN = "/api/v1/platform/me/pin";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String BODY = "{\"code\":\"493017\",\"newPin\":\"4831\",\"confirmPin\":\"4831\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StaffPinService staffPinService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PinStatusView set() {
        return new PinStatusView(true, PinState.SET, Instant.parse("2026-11-06T05:35:00Z"), null, 5, null,
                "••••••7410");
    }

    @Test
    void theStatusHasEverySevenFieldsAndNoSecret() throws Exception {
        when(staffPinService.status(TENANT_ID, USER_ID)).thenReturn(set());

        mockMvc.perform(get(PIN).with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.state").value("SET"))
                .andExpect(jsonPath("$.setAt").value("2026-11-06T05:35:00Z"))
                .andExpect(jsonPath("$.lockedUntil").value((Object) null))
                .andExpect(jsonPath("$.triesLeft").value(5))
                .andExpect(jsonPath("$.notSetReason").value((Object) null))
                .andExpect(jsonPath("$.mobileMasked").value("••••••7410"))
                .andExpect(jsonPath("$.pinHash").doesNotExist())
                .andExpect(jsonPath("$.pin").doesNotExist());
    }

    @Test
    void aNotSetStatusKeepsEveryKeyWithNulls() throws Exception {
        when(staffPinService.status(TENANT_ID, USER_ID)).thenReturn(new PinStatusView(true, PinState.NOT_SET, null,
                null, null, "RESET_BY_OWNER", "••••••7410"));

        mockMvc.perform(get(PIN).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_SET"))
                .andExpect(jsonPath("$.setAt").value((Object) null))
                .andExpect(jsonPath("$.triesLeft").value((Object) null))
                .andExpect(jsonPath("$.notSetReason").value("RESET_BY_OWNER"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"triesLeft\":null")));
    }

    @Test
    void everyClinicRoleMaySeeAndSetItsOwnPinButASignedOutVisitorGets401() throws Exception {
        when(staffPinService.status(TENANT_ID, USER_ID)).thenReturn(set());
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
                "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(PIN).with(as(role))).andExpect(status().isOk());
        }
        mockMvc.perform(get(PIN)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PIN + "/code")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sendingACodeAnswers202WithTheWaitAndTheMaskedMobile() throws Exception {
        when(staffPinService.sendCode(TENANT_ID, USER_ID)).thenReturn(new PinCodeSentView(30, "••••••7410"));

        mockMvc.perform(post(PIN + "/code").with(as("NURSE")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendAfterSeconds").value(30))
                .andExpect(jsonPath("$.mobileMasked").value("••••••7410"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void theCodeRequestPassesOnTheOtpProblems() throws Exception {
        when(staffPinService.sendCode(TENANT_ID, USER_ID))
                .thenThrow(new OtpResendTooSoonException(Duration.ofSeconds(12)));
        mockMvc.perform(post(PIN + "/code").with(as("NURSE")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(12));

        org.mockito.Mockito.doThrow(new OtpLockedException(Duration.ofMinutes(15))).when(staffPinService)
                .sendCode(TENANT_ID, USER_ID);
        mockMvc.perform(post(PIN + "/code").with(as("NURSE")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/otp-locked"));
    }

    @Test
    void savingCallsTheServiceWithTheSessionPersonAndAnswers200WithTheStatus() throws Exception {
        when(staffPinService.set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class))).thenReturn(set());

        mockMvc.perform(put(PIN).with(as("PHARMACIST")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SET"));

        org.mockito.ArgumentCaptor<PinSetInput> input = org.mockito.ArgumentCaptor.forClass(PinSetInput.class);
        verify(staffPinService).set(eq(TENANT_ID), eq(USER_ID), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue().code()).isEqualTo("493017");
        org.assertj.core.api.Assertions.assertThat(input.getValue().newPin()).isEqualTo("4831");
        org.assertj.core.api.Assertions.assertThat(input.getValue().confirmPin()).isEqualTo("4831");
    }

    @Test
    void aClientCannotNameAnotherPersonOrClinic() throws Exception {
        when(staffPinService.set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class))).thenReturn(set());
        String forged = "{\"code\":\"493017\",\"newPin\":\"4831\",\"confirmPin\":\"4831\","
                + "\"userId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(put(PIN).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isOk());

        verify(staffPinService).set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class));
    }

    @Test
    void fieldErrorsComeBackTogetherAsAValidationProblem() throws Exception {
        when(staffPinService.set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class))).thenThrow(
                new InvalidFieldsException(List.of(new FieldMessage("newPin", "This PIN is too easy to guess. Choose another one."),
                        new FieldMessage("confirmPin", "The two PINs do not match."))));

        mockMvc.perform(put(PIN).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPin"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("confirmPin"));
    }

    @Test
    void aMalformedBodyIs400WithoutEchoingIt() throws Exception {
        mockMvc.perform(put(PIN).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content("{\"newPin\":4831"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("4831"))));
        verify(staffPinService, never()).set(any(), any(), any());
    }

    @Test
    void thePinProblemsHaveTheirContractStatusesTypesAndTexts() throws Exception {
        when(staffPinService.set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class)))
                .thenThrow(StaffPinException.unavailable());
        mockMvc.perform(put(PIN).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/pin-unavailable"))
                .andExpect(jsonPath("$.title").value("PIN checks are off"))
                .andExpect(jsonPath("$.detail").value("PIN checks are not available right now. Try again later."))
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());

        when(staffPinService.set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class)))
                .thenThrow(StaffPinException.rateLimited(240));
        mockMvc.perform(put(PIN).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.detail").value("Too many PIN tries in a short time. Try again in a few minutes."))
                .andExpect(jsonPath("$.retryAfterSeconds").value(240));
    }

    @Test
    void twoFirstSavesAtOnceGiveA409ConcurrentChange() throws Exception {
        when(staffPinService.set(eq(TENANT_ID), eq(USER_ID), any(PinSetInput.class))).thenThrow(
                new ConflictException("concurrent-change", "Your PIN just changed", "Try again."));

        mockMvc.perform(put(PIN).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/concurrent-change"));
    }

    @Test
    void noActiveMembershipIs403Forbidden() throws Exception {
        when(staffPinService.status(TENANT_ID, USER_ID)).thenThrow(
                new ForbiddenException("forbidden", "Not allowed", "You do not have access to this."));

        mockMvc.perform(get(PIN).with(as("NURSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
    }

    @Test
    void theRequestObjectNeverPrintsItsSecrets() {
        org.assertj.core.api.Assertions.assertThat(new PinSetRequest("493017", "4831", "4831").toString())
                .doesNotContain("493017").doesNotContain("4831");
    }
}
