package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.EmergencyAccessService;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessGrant;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessPatient;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessPicker;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReasonView;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewPage;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewResult;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewRow;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-34 break-glass endpoints (34E, 34F): contract shape, roles and problem types. */
@WebMvcTest(controllers = EmergencyAccessController.class)
@Import(SecurityConfig.class)
class EmergencyAccessControllerTest {

    private static final String URL = "/api/v1/ipd/emergency-access";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID GRANT = UUID.randomUUID();
    private static final Instant OPENED = Instant.parse("2026-11-10T05:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-11-10T05:30:00Z");
    private static final String BODY = "{\"admissionId\":\"" + ADMISSION + "\",\"reasonKey\":\"DETERIORATING\","
            + "\"confirmed\":true}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmergencyAccessService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/patients")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void pickerAnswersTheContractShape() throws Exception {
        when(service.picker(TENANT_ID, USER_ID, "NURSE")).thenReturn(new EmergencyAccessPicker(
                List.of(new EmergencyAccessPatient(ADMISSION, "Test Patient", "B1", "UH-1")),
                List.of(new EmergencyAccessReasonView("DETERIORATING", "Patient deteriorating, rapid response"),
                        new EmergencyAccessReasonView("COVERING_BREAK", "Covering for a colleague on break"),
                        new EmergencyAccessReasonView("DUTY_DOCTOR_ASKED", "Asked to help by the duty doctor"))));

        mockMvc.perform(get(URL + "/patients").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].admissionId").value(ADMISSION.toString()))
                .andExpect(jsonPath("$.items[0].patientName").value("Test Patient"))
                .andExpect(jsonPath("$.items[0].bedCode").value("B1"))
                .andExpect(jsonPath("$.items[0].uhid").value("UH-1"))
                .andExpect(jsonPath("$.reasons[2].key").value("DUTY_DOCTOR_ASKED"));
    }

    @Test
    void aCallerWhoMayNotUseItGetsTheContractText() throws Exception {
        when(service.picker(any(), any(), eq("OWNER"))).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "Emergency access is only for staff who see some wards."));

        mockMvc.perform(get(URL + "/patients").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("forbidden")))
                .andExpect(jsonPath("$.detail").value("Emergency access is only for staff who see some wards."));
    }

    @Test
    void openAnswers201WithTheGrant() throws Exception {
        when(service.open(any(), any(), any(), any())).thenReturn(new EmergencyAccessGrant(GRANT, ADMISSION,
                "Test Patient", "B1", "DETERIORATING", "Patient deteriorating, rapid response", OPENED, ENDS));

        mockMvc.perform(post(URL).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(GRANT.toString()))
                .andExpect(jsonPath("$.patientName").value("Test Patient"))
                .andExpect(jsonPath("$.reasonLabel").value("Patient deteriorating, rapid response"))
                .andExpect(jsonPath("$.openedAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
        verify(service).open(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), any());
    }

    @Test
    void openProblemsMapToTheirStatusAndType() throws Exception {
        when(service.open(any(), any(), any(), any()))
                .thenThrow(new InvalidFieldsException(List.of(new FieldMessage("reasonKey",
                        "Choose a reason for emergency access."))))
                .thenThrow(new NotFoundException("This patient is not admitted any more."))
                .thenThrow(new ConflictException("already-in-scope", "Already in your list",
                        "This patient is already in your list."))
                .thenThrow(new ModuleOffException("off"));

        mockMvc.perform(post(URL).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reasonKey"));
        mockMvc.perform(post(URL).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(URL).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("already-in-scope")))
                .andExpect(jsonPath("$.detail").value("This patient is already in your list."));
        mockMvc.perform(post(URL).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void aBodyThatIsNotJsonIs400() throws Exception {
        mockMvc.perform(post(URL).with(as("NURSE")).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
        verify(service, never()).open(any(), any(), any(), any());
    }

    @Test
    void theOwnerListAndTheReviewAreForTheOwnerOnly() throws Exception {
        for (String role : new String[] {"NURSE", "DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST"}) {
            mockMvc.perform(get(URL).with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/" + GRANT + "/review").with(as(role))).andExpect(status().isForbidden());
        }
        verify(service, never()).list(any(), any(), any(), any(), any());
        verify(service, never()).review(any(), any(), any(), any());
    }

    @Test
    void theOwnerListAnswersThePageAndPassesLimitAndCursorAsText() throws Exception {
        EmergencyAccessReviewRow row = new EmergencyAccessReviewRow(GRANT, ADMISSION, "Asha", "Test Patient", "B1",
                "COVERING_BREAK", "Covering for a colleague on break", OPENED, ENDS, "ADMITTED");
        when(service.list(TENANT_ID, USER_ID, "OWNER", "10", "abc"))
                .thenReturn(new EmergencyAccessReviewPage(OPENED, 7, List.of(row), "next"));

        mockMvc.perform(get(URL).param("limit", "10").param("cursor", "abc").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting").value(7))
                .andExpect(jsonPath("$.items[0].grantId").value(GRANT.toString()))
                .andExpect(jsonPath("$.items[0].staffName").value("Asha"))
                .andExpect(jsonPath("$.items[0].admissionStatus").value("ADMITTED"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    void reviewAnswers200AndItsProblems() throws Exception {
        when(service.review(TENANT_ID, USER_ID, "OWNER", GRANT))
                .thenReturn(new EmergencyAccessReviewResult(GRANT, OPENED, 2))
                .thenThrow(new ConflictException("already-reviewed", "Already reviewed", "Already reviewed."))
                .thenThrow(new ForbiddenException("forbidden", "Not allowed",
                        "You cannot review your own emergency access."))
                .thenThrow(new NotFoundException("Not found"));

        mockMvc.perform(post(URL + "/" + GRANT + "/review").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantId").value(GRANT.toString()))
                .andExpect(jsonPath("$.waiting").value(2));
        mockMvc.perform(post(URL + "/" + GRANT + "/review").with(as("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("already-reviewed")))
                .andExpect(jsonPath("$.detail").value("Already reviewed."));
        mockMvc.perform(post(URL + "/" + GRANT + "/review").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You cannot review your own emergency access."));
        mockMvc.perform(post(URL + "/" + GRANT + "/review").with(as("OWNER"))).andExpect(status().isNotFound());
    }

    @Test
    void aReviewIdThatIsNotAUuidIs400() throws Exception {
        mockMvc.perform(post(URL + "/not-a-uuid/review").with(as("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request could not be understood. Check it and try again."));
    }
}
