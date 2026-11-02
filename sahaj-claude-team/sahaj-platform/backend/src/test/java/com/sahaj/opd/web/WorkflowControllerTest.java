package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.WorkflowService;
import com.sahaj.opd.application.WorkflowStep;
import com.sahaj.opd.application.WorkflowVersionView;
import com.sahaj.platform.api.InvalidInputException;
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

/** SAH-12 workflow endpoints: contract shape, owner-only writes (403), tenant from the signed-in user, 400 problem. */
@WebMvcTest(controllers = WorkflowController.class)
@Import(SecurityConfig.class)
class WorkflowControllerTest {

    private static final String WORKFLOW = "/api/v1/opd/workflow";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BODY = "{\"steps\":[{\"key\":\"token\",\"on\":true},{\"key\":\"queue\",\"on\":true},"
            + "{\"key\":\"vitals\",\"on\":true,\"owner\":\"NURSE\"},{\"key\":\"consult\",\"on\":true},"
            + "{\"key\":\"counsel\",\"on\":true,\"owner\":\"DOCTOR\"},{\"key\":\"casepaper\",\"on\":true},"
            + "{\"key\":\"billing\",\"on\":true},{\"key\":\"followup\",\"on\":true}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static List<WorkflowStep> steps() {
        return List.of(new WorkflowStep("token", true, null), new WorkflowStep("vitals", true, "NURSE"));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(WORKFLOW + "/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyClinicRoleReadsTheCurrentVersionWithContractFieldNames() throws Exception {
        when(service.current(TENANT_ID, USER_ID))
                .thenReturn(new WorkflowVersionView(2, steps(), Instant.parse("2026-11-02T10:00:00Z"), "Dr Owner"));

        mockMvc.perform(get(WORKFLOW + "/current").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.publishedByName").value("Dr Owner"))
                .andExpect(jsonPath("$.publishedAt").exists())
                .andExpect(jsonPath("$.steps[0].key").value("token"))
                .andExpect(jsonPath("$.steps[0].owner").doesNotExist())
                .andExpect(jsonPath("$.steps[1].owner").value("NURSE"));
    }

    @Test
    void anyClinicRoleReadsTheDraftAndTheVersions() throws Exception {
        when(service.draft(TENANT_ID, USER_ID)).thenReturn(steps());
        when(service.versions(TENANT_ID, USER_ID)).thenReturn(List.of(
                new WorkflowVersionView(2, steps(), Instant.now(), null),
                new WorkflowVersionView(1, steps(), Instant.now(), null)));

        mockMvc.perform(get(WORKFLOW + "/draft").with(as("FRONT_DESK")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps[1].on").value(true));
        mockMvc.perform(get(WORKFLOW + "/versions").with(as("DOCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].versionNo").value(2));
    }

    @Test
    void ownerSavesTheDraft() throws Exception {
        when(service.saveDraft(eq(TENANT_ID), eq(USER_ID), any())).thenReturn(steps());

        mockMvc.perform(put(WORKFLOW + "/draft").with(as("OWNER")).contentType(JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].key").value("token"));
    }

    @Test
    void aNonOwnerGets403OnSaveAndPublish() throws Exception {
        mockMvc.perform(put(WORKFLOW + "/draft").with(as("NURSE")).contentType(JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(WORKFLOW + "/publish").with(as("FRONT_DESK"))).andExpect(status().isForbidden());
        verify(service, never()).publish(any(), any());
        verify(service, never()).saveDraft(any(), any(), any());
    }

    @Test
    void ownerPublishes() throws Exception {
        when(service.publish(TENANT_ID, USER_ID))
                .thenReturn(new WorkflowVersionView(3, steps(), Instant.now(), "Dr Owner"));

        mockMvc.perform(post(WORKFLOW + "/publish").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versionNo").value(3));
    }

    @Test
    void badInputIs400() throws Exception {
        when(service.saveDraft(eq(TENANT_ID), eq(USER_ID), any()))
                .thenThrow(new InvalidInputException("steps", "Token must be the first step"));

        mockMvc.perform(put(WORKFLOW + "/draft").with(as("OWNER")).contentType(JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }
}
