package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.VisitCreateInput;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.VisitView;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-9 opd visit endpoints: contract shape, role gate, tenant and role from the signed-in user, problem responses. */
@WebMvcTest(controllers = VisitController.class)
@Import(SecurityConfig.class)
class VisitControllerTest {

    private static final String OPD = "/api/v1/opd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID VISIT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VisitService visitService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static VisitView view(String stage, String visitType) {
        return new VisitView(VISIT_ID, 7, PATIENT_ID, "Asha Rao", 30, "F", DOCTOR_ID, "Dr Rao", visitType, stage,
                50000, Instant.now());
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(OPD + "/visits/today")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyClinicRoleReadsTodaysBoard() throws Exception {
        when(visitService.today(TENANT_ID, "NURSE", true)).thenReturn(List.of(view("VITALS", "NEW")));

        mockMvc.perform(get(OPD + "/visits/today").param("mine", "true").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenNo").value(7))
                .andExpect(jsonPath("$[0].patientName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].stage").value("VITALS"));
    }

    @Test
    void ownerFrontDeskAndNurseMayIssueAToken() throws Exception {
        when(visitService.issueToken(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), any(VisitCreateInput.class)))
                .thenReturn(view("VITALS", "NEW"));

        mockMvc.perform(post(OPD + "/visits").with(as("NURSE")).contentType(JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visitType").value("NEW"))
                .andExpect(jsonPath("$.feePaise").value(50000));
    }

    @Test
    void aDoctorCannotIssueATokenAndGets403() throws Exception {
        mockMvc.perform(post(OPD + "/visits").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID + "\"}"))
                .andExpect(status().isForbidden());
        verify(visitService, never()).issueToken(any(), any(), any(), any());
    }

    @Test
    void anUnknownPatientOnCreateIs404() throws Exception {
        when(visitService.issueToken(any(), any(), any(), any())).thenThrow(new NotFoundException("Patient not found"));

        mockMvc.perform(post(OPD + "/visits").with(as("OWNER")).contentType(JSON)
                        .content("{\"patientId\":\"" + PATIENT_ID + "\",\"doctorMembershipId\":\"" + DOCTOR_ID + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void movingAStageReturnsTheUpdatedVisit() throws Exception {
        when(visitService.moveStage(TENANT_ID, USER_ID, "OWNER", VISIT_ID, "WITH_DOCTOR"))
                .thenReturn(view("WITH_DOCTOR", "NEW"));

        mockMvc.perform(patch(OPD + "/visits/" + VISIT_ID + "/stage").with(as("OWNER")).contentType(JSON)
                        .content("{\"stage\":\"WITH_DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("WITH_DOCTOR"));
    }

    @Test
    void aForbiddenStageMoveIs403() throws Exception {
        when(visitService.moveStage(any(), any(), any(), any(), any()))
                .thenThrow(new AccessDeniedException("no"));
        mockMvc.perform(patch(OPD + "/visits/" + VISIT_ID + "/stage").with(as("NURSE")).contentType(JSON)
                        .content("{\"stage\":\"BILLING\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anInvalidStageValueIs400() throws Exception {
        when(visitService.moveStage(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("stage", "Choose a valid stage"));
        mockMvc.perform(patch(OPD + "/visits/" + VISIT_ID + "/stage").with(as("OWNER")).contentType(JSON)
                        .content("{\"stage\":\"NOT_A_STAGE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownVisitIs404() throws Exception {
        when(visitService.moveStage(any(), any(), any(), any(), any())).thenThrow(new NotFoundException("x"));

        mockMvc.perform(patch(OPD + "/visits/" + VISIT_ID + "/stage").with(as("OWNER")).contentType(JSON)
                        .content("{\"stage\":\"WAITING\"}"))
                .andExpect(status().isNotFound());
    }
}
