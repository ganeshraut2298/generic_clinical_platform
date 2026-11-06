package com.sahaj.patient.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.patient.application.ConsentInput;
import com.sahaj.patient.application.ConsentService;
import com.sahaj.patient.application.ConsentService.ConsentEntryView;
import com.sahaj.patient.application.ConsentService.ConsentStateView;
import com.sahaj.patient.application.RecordAccessService;
import com.sahaj.patient.application.RecordAccessService.RecordAccessView;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/** SAH-17 consent and record-access endpoints: role gates, contract shapes, problem responses. */
@WebMvcTest(controllers = PatientPrivacyController.class)
@Import(SecurityConfig.class)
class PatientPrivacyControllerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String CONSENTS = "/api/v1/patient/patients/" + PATIENT_ID + "/consents";
    private static final String ACCESS = "/api/v1/patient/record-access";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final Instant AT = Instant.parse("2026-11-04T05:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConsentService consentService;

    @MockBean
    private RecordAccessService recordAccessService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void signedOutVisitorsGet401() throws Exception {
        mockMvc.perform(get(CONSENTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ACCESS)).andExpect(status().isUnauthorized());
    }

    @Test
    void anyClinicRoleReadsTheConsentStateWithNullForAKindNeverRecorded() throws Exception {
        when(consentService.state(TENANT_ID, PATIENT_ID)).thenReturn(new ConsentStateView(
                new ConsentEntryView("RECORDS", true, AT, "Sunita Pawar"), null));

        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
                "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(CONSENTS).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records.kind").value("RECORDS"))
                    .andExpect(jsonPath("$.records.given").value(true))
                    .andExpect(jsonPath("$.records.recordedAt").exists())
                    .andExpect(jsonPath("$.records.recordedByName").value("Sunita Pawar"))
                    .andExpect(jsonPath("$.promotional").value((Object) null));
        }
    }

    @Test
    void anUnknownPatientIs404() throws Exception {
        when(consentService.state(TENANT_ID, PATIENT_ID)).thenThrow(new NotFoundException("Patient not found"));

        mockMvc.perform(get(CONSENTS).with(as("OWNER"))).andExpect(status().isNotFound());
    }

    @Test
    void ownerFrontDeskAndNurseRecordAConsent() throws Exception {
        when(consentService.record(eq(TENANT_ID), eq(USER_ID), eq(PATIENT_ID), any(ConsentInput.class)))
                .thenReturn(new ConsentStateView(null, new ConsentEntryView("PROMOTIONAL", false, AT, "Sunita Pawar")));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            mockMvc.perform(post(CONSENTS).with(as(role)).contentType(JSON)
                            .content("{\"kind\":\"PROMOTIONAL\",\"given\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.promotional.given").value(false))
                    .andExpect(jsonPath("$.records").value((Object) null));
        }
        verify(consentService, org.mockito.Mockito.times(3)).record(eq(TENANT_ID), eq(USER_ID), eq(PATIENT_ID),
                eq(new ConsentInput("PROMOTIONAL", false)));
    }

    @Test
    void otherRolesGet403WhenRecordingAConsent() throws Exception {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(post(CONSENTS).with(as(role)).contentType(JSON)
                            .content("{\"kind\":\"RECORDS\",\"given\":true}"))
                    .andExpect(status().isForbidden());
        }
        verify(consentService, never()).record(any(), any(), any(), any());
    }

    @Test
    void aBadKindIs400WithAFieldError() throws Exception {
        when(consentService.record(any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("kind", "Choose records or promotional messages"));

        mockMvc.perform(post(CONSENTS).with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"kind\":\"X\",\"given\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("kind"));
    }

    @Test
    void theOwnerReadsRecordAccessWithTheContractShapeAndTheLimitIsPassedOn() throws Exception {
        when(recordAccessService.recent(TENANT_ID, USER_ID, 3)).thenReturn(
                List.of(new RecordAccessView(AT, "Sunita Pawar", PATIENT_ID, "Asha Rao")));

        mockMvc.perform(get(ACCESS + "?limit=3").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userName").value("Sunita Pawar"))
                .andExpect(jsonPath("$[0].patientId").value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$[0].patientName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].at").exists());
    }

    @Test
    void withoutALimitTheServiceDefaultApplies() throws Exception {
        when(recordAccessService.recent(TENANT_ID, USER_ID, null)).thenReturn(List.of());

        mockMvc.perform(get(ACCESS).with(as("OWNER"))).andExpect(status().isOk());
    }

    @Test
    void everyNonOwnerGets403OnRecordAccess() throws Exception {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
                "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(ACCESS).with(as(role))).andExpect(status().isForbidden());
        }
        verify(recordAccessService, never()).recent(any(), any(), any());
    }

    @Test
    void aBadLimitIs400() throws Exception {
        when(recordAccessService.recent(TENANT_ID, USER_ID, 99))
                .thenThrow(new InvalidInputException("limit", "Choose a number from 1 to 50"));

        mockMvc.perform(get(ACCESS + "?limit=99").with(as("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
        mockMvc.perform(get(ACCESS + "?limit=abc").with(as("OWNER"))).andExpect(status().isBadRequest());
    }
}
