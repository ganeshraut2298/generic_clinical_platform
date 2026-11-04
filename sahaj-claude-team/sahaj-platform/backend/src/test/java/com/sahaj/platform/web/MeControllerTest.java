package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.MeService;
import com.sahaj.platform.application.MeView;
import com.sahaj.platform.application.PreferencesInput;
import com.sahaj.platform.domain.UserPreference;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** SAH-5: any signed-in role may read {@code /me}; the server, not the client, decides the menu. */
@WebMvcTest(controllers = MeController.class)
@Import(SecurityConfig.class)
class MeControllerTest {

    private static final String BASE = "/api/v1/platform/me";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeService meService;

    private static MeView aView() {
        return new MeView(USER_ID, UUID.randomUUID(), "Asha", "NURSE",
                new MeView.Clinic("Sunrise Clinic", "Pune", "teal", null, "FREE", 120, true, 100),
                List.of("today", "queue", "patients"), UserPreference.defaults());
    }

    @Test
    void anySignedInRoleCanReadMe() throws Exception {
        when(meService.getMe(TENANT_ID, USER_ID, "NURSE")).thenReturn(aView());

        mockMvc.perform(get(BASE).with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha"))
                .andExpect(jsonPath("$.role").value("NURSE"))
                .andExpect(jsonPath("$.menu[0]").value("today"))
                .andExpect(jsonPath("$.clinic.name").value("Sunrise Clinic"))
                .andExpect(jsonPath("$.preferences.language").value("en"));
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void patchingPreferencesSavesOnlyTheGivenFields() throws Exception {
        when(meService.updatePreferences(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any(PreferencesInput.class)))
                .thenReturn(aView());

        mockMvc.perform(patch(BASE + "/preferences")
                        .with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha"));
    }

    @Test
    void anInvalidLanguageIsRejectedWith400() throws Exception {
        mockMvc.perform(patch(BASE + "/preferences")
                        .with(TestTenantSecurity.asUser(TENANT_ID, USER_ID, "OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }
}
