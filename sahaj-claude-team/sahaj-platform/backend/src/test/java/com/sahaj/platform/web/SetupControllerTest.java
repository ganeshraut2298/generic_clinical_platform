package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.SetupCompleteResult;
import com.sahaj.platform.application.SetupService;
import com.sahaj.platform.application.SetupState;
import com.sahaj.platform.application.SlugAvailability;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** SAH-4: role protection and contract shape of the setup wizard's endpoints. */
@WebMvcTest(controllers = SetupController.class)
@Import(SecurityConfig.class)
class SetupControllerTest {

    private static final String BASE = "/api/v1/platform/setup";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SetupService setupService;

    @Test
    void anOwnerCanReadTheirSavedProgress() throws Exception {
        when(setupService.getState(TENANT_ID)).thenReturn(new SetupState(2, Map.of("clinic", Map.of("name", "X")), 2, 5));

        mockMvc.perform(get(BASE).with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(2))
                .andExpect(jsonPath("$.limits.doctors").value(2))
                .andExpect(jsonPath("$.limits.users").value(5));
    }

    @Test
    void aNonOwnerIsRefusedWith403() throws Exception {
        mockMvc.perform(get(BASE).with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "NURSE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void savingAStepReturnsTheUpdatedState() throws Exception {
        when(setupService.saveStep(eq(TENANT_ID), eq(1), any())).thenReturn(new SetupState(2, Map.of(), 2, 5));

        mockMvc.perform(put(BASE + "/steps/1")
                        .with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sunrise Clinic\",\"city\":\"Pune\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(2));
    }

    @Test
    void aStepOutsideOneToFiveIsRejected() throws Exception {
        mockMvc.perform(put(BASE + "/steps/9")
                        .with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void slugAvailabilityReturnsAlternativesWhenTaken() throws Exception {
        when(setupService.checkSlugAvailability(eq(TENANT_ID), eq("clinic")))
                .thenReturn(new SlugAvailability(false, List.of("clinic-pune")));

        mockMvc.perform(get(BASE + "/slug-availability?slug=clinic")
                        .with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.alternatives[0]").value("clinic-pune"));
    }

    @Test
    void completingAsOwnerReturnsNextAppAndTheBrand() throws Exception {
        when(setupService.complete(eq(TENANT_ID), eq(USER_ID))).thenReturn(new SetupCompleteResult("teal"));

        mockMvc.perform(post(BASE + "/complete").with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("APP"))
                .andExpect(jsonPath("$.brand").value("teal"));
    }

    @Test
    void completingAsANonOwnerIsRefused() throws Exception {
        mockMvc.perform(post(BASE + "/complete").with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "DOCTOR")))
                .andExpect(status().isForbidden());
    }
}
