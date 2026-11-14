package com.sahaj.platform.web;

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

import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.application.ModulesService;
import com.sahaj.platform.application.ModulesView;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-19 modules endpoints: contract shape, any clinic role reads, owner-only writes, 400/404/409 problems. */
@WebMvcTest(controllers = ModulesController.class)
@Import(SecurityConfig.class)
class ModulesControllerTest {

    private static final String URL = "/api/v1/platform/modules";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String[] OTHER_ROLES = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModulesService modulesService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static ModulesView view(String state, Instant trialEnd, boolean requested, boolean hra, boolean trialAvail) {
        return new ModulesView("FREE",
                new ModulesView.Ipd(state, trialEnd, requested, requested ? Instant.parse("2026-11-05T10:00:00Z") : null),
                trialAvail, hra, new ModulesView.MenuCounts(6, 9));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClinicRoleReadsTheViewWithTheContractFieldNames() throws Exception {
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
                "PHARMACIST", "COUNSELLOR"}) {
            when(modulesService.get(TENANT_ID, USER_ID, role)).thenReturn(view("off", null, false, false, true));
            mockMvc.perform(get(URL).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.plan").value("FREE"))
                    .andExpect(jsonPath("$.ipd.state").value("off"))
                    .andExpect(jsonPath("$.ipd.requested").value(false))
                    .andExpect(jsonPath("$.ipd.trialEndsAt").doesNotExist())
                    .andExpect(jsonPath("$.ipdTrialAvailable").value(true))
                    .andExpect(jsonPath("$.hra.on").value(false))
                    .andExpect(jsonPath("$.menuCounts.simple").value(6))
                    .andExpect(jsonPath("$.menuCounts.advanced").value(9));
        }
    }

    @Test
    void theOwnerRequestsIpd() throws Exception {
        when(modulesService.requestIpd(TENANT_ID, USER_ID, "OWNER")).thenReturn(view("off", null, true, false, true));

        mockMvc.perform(post(URL + "/ipd-request").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ipd.requested").value(true))
                .andExpect(jsonPath("$.ipd.requestedAt").exists());
    }

    @Test
    void theOwnerStartsTheTrial() throws Exception {
        when(modulesService.startIpdTrial(TENANT_ID, USER_ID, "OWNER"))
                .thenReturn(view("trial", Instant.parse("2026-12-05T10:00:00Z"), false, false, false));

        mockMvc.perform(post(URL + "/ipd-trial").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ipd.state").value("trial"))
                .andExpect(jsonPath("$.ipd.trialEndsAt").exists())
                .andExpect(jsonPath("$.ipdTrialAvailable").value(false));
    }

    @Test
    void theOwnerSwitchesHra() throws Exception {
        when(modulesService.setHra(TENANT_ID, USER_ID, "OWNER", true)).thenReturn(view("off", null, false, true, true));

        mockMvc.perform(put(URL + "/hra").with(as("OWNER")).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hra.on").value(true));
    }

    @Test
    void everyOtherRoleGets403OnEveryWrite() throws Exception {
        for (String role : OTHER_ROLES) {
            mockMvc.perform(post(URL + "/ipd-request").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/ipd-trial").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/hra").with(as(role)).contentType(JSON).content("{\"on\":true}"))
                    .andExpect(status().isForbidden());
        }
        verify(modulesService, never()).requestIpd(any(), any(), any());
        verify(modulesService, never()).startIpdTrial(any(), any(), any());
        verify(modulesService, never()).setHra(any(), any(), any(), any());
    }

    @Test
    void aMissingOnIs400WithAFieldError() throws Exception {
        when(modulesService.setHra(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(null)))
                .thenThrow(new InvalidInputException("on", "Choose on or off"));

        mockMvc.perform(put(URL + "/hra").with(as("OWNER")).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("on"));
    }

    @Test
    void aNonBooleanOnIs400() throws Exception {
        mockMvc.perform(put(URL + "/hra").with(as("OWNER")).contentType(JSON).content("{\"on\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
        verify(modulesService, never()).setHra(any(), any(), any(), any());
    }

    @Test
    void conflictAndNotFoundAreProblems() throws Exception {
        when(modulesService.requestIpd(TENANT_ID, USER_ID, "OWNER"))
                .thenThrow(new ConflictException("ipd-not-off", "IPD is already set up", "IPD is already on."));
        when(modulesService.startIpdTrial(TENANT_ID, USER_ID, "OWNER"))
                .thenThrow(new NotFoundException("The IPD trial is not available"));

        mockMvc.perform(post(URL + "/ipd-request").with(as("OWNER"))).andExpect(status().isConflict());
        mockMvc.perform(post(URL + "/ipd-trial").with(as("OWNER"))).andExpect(status().isNotFound());
    }
}
