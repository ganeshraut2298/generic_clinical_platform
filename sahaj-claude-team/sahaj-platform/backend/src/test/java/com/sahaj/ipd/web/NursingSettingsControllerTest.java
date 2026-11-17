package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.NursingSettingsService;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileView;
import com.sahaj.ipd.application.NursingSettingsViews.Settings;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsInput;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsView;
import com.sahaj.ipd.application.NursingSettingsViews.WardProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.WardView;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-76-6 the five endpoints of nursing settings and ward profiles: contract field names, 201 for a new profile, the
 * token identity handed to the service, and the problem types and texts of refusals. The service rules are covered by
 * {@code NursingSettingsServiceTest}.
 */
@WebMvcTest(controllers = NursingSettingsController.class)
@Import(SecurityConfig.class)
class NursingSettingsControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WARD_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String SETTINGS_BODY = "{\"medVerificationRequired\":true,\"doseWindowBeforeMinutes\":60,"
            + "\"doseWindowAfterMinutes\":60,\"statWindowMinutes\":15,\"unackFlagMinutes\":30,"
            + "\"unackFlagStatMinutes\":5,\"undoSeconds\":10,\"unknown\":\"ignored\"}";
    private static final String PROFILE_BODY = "{\"name\":\"Step-down\",\"medVerificationRequired\":false,"
            + "\"vitalsEveryMinutes\":120,\"doseWindowBeforeMinutes\":null,\"doseWindowAfterMinutes\":45}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NursingSettingsService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Settings answer() {
        return new Settings(true, new SettingsView(true, 60, 60, 15, 30, 5, 10),
                List.of(new ProfileView("icu", "ICU", true, true, 60, null, null, 1)),
                List.of(new WardView(WARD_ID, "Ward A", "icu")),
                List.of(new KeyLabel("hep", "Inj. Heparin 5,000 IU · Anticoagulant")), List.of());
    }

    @Test
    void theSettingsHaveTheContractShape() throws Exception {
        when(service.read(TENANT_ID, USER_ID, "NURSE")).thenReturn(answer());

        mockMvc.perform(get(URL + "/nursing-settings").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.settings.medVerificationRequired").value(true))
                .andExpect(jsonPath("$.settings.doseWindowBeforeMinutes").value(60))
                .andExpect(jsonPath("$.settings.doseWindowAfterMinutes").value(60))
                .andExpect(jsonPath("$.settings.statWindowMinutes").value(15))
                .andExpect(jsonPath("$.settings.unackFlagMinutes").value(30))
                .andExpect(jsonPath("$.settings.unackFlagStatMinutes").value(5))
                .andExpect(jsonPath("$.settings.undoSeconds").value(10))
                .andExpect(jsonPath("$.profiles[0].key").value("icu"))
                .andExpect(jsonPath("$.profiles[0].name").value("ICU"))
                .andExpect(jsonPath("$.profiles[0].starter").value(true))
                .andExpect(jsonPath("$.profiles[0].vitalsEveryMinutes").value(60))
                .andExpect(jsonPath("$.profiles[0].doseWindowBeforeMinutes").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.profiles[0].wardCount").value(1))
                .andExpect(jsonPath("$.wards[0].wardId").value(WARD_ID.toString()))
                .andExpect(jsonPath("$.wards[0].wardName").value("Ward A"))
                .andExpect(jsonPath("$.wards[0].profileKey").value("icu"))
                .andExpect(jsonPath("$.highAlertMedicines[0].key").value("hep"))
                .andExpect(jsonPath("$.timeCriticalMedicines").isEmpty());
    }

    @Test
    void everyClinicRoleReachesTheServiceWhichGivesEachRefusedRoleItsOwnText() throws Exception {
        when(service.read(any(), any(), eq("BILLING"))).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "You cannot open nursing settings."));

        mockMvc.perform(get(URL + "/nursing-settings").with(as("BILLING"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You cannot open nursing settings."));
    }

    @Test
    void savingPassesTheBodyAndTheTokenIdentityAndIgnoresUnknownFields() throws Exception {
        when(service.saveSettings(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any())).thenReturn(answer());

        mockMvc.perform(put(URL + "/nursing-settings").with(as("OWNER")).contentType(JSON).content(SETTINGS_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<SettingsInput> body = ArgumentCaptor.forClass(SettingsInput.class);
        verify(service).saveSettings(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue().undoSeconds()).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThat(body.getValue().medVerificationRequired()).isEqualTo(true);
    }

    @Test
    void aBadRangeIs400WithTheFieldAndTheContractText() throws Exception {
        doThrow(new InvalidFieldsException(List.of(new FieldMessage("undoSeconds", "Enter a number from 1 to 10.")), true))
                .when(service).saveSettings(any(), any(), any(), any());

        mockMvc.perform(put(URL + "/nursing-settings").with(as("OWNER")).contentType(JSON).content(SETTINGS_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("undoSeconds"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a number from 1 to 10."));
    }

    @Test
    void readOnlyIpdAndOtherRefusalsKeepTheirProblemTypes() throws Exception {
        doThrow(new ConflictException("ipd-readonly", "IPD is read-only", "IPD is read-only. Nothing can be changed."))
                .when(service).saveSettings(any(), any(), any(), any());

        mockMvc.perform(put(URL + "/nursing-settings").with(as("OWNER")).contentType(JSON).content(SETTINGS_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/ipd-readonly"));
    }

    @Test
    void addingAProfileAnswers201AndTheNameLimitsAreProblems() throws Exception {
        when(service.addProfile(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), any())).thenReturn(answer());

        mockMvc.perform(post(URL + "/nursing-profiles").with(as("OWNER")).contentType(JSON).content(PROFILE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profiles[0].key").value("icu"));

        ArgumentCaptor<ProfileInput> body = ArgumentCaptor.forClass(ProfileInput.class);
        verify(service).addProfile(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue().name()).isEqualTo("Step-down");
        org.assertj.core.api.Assertions.assertThat(body.getValue().vitalsEveryMinutes()).isEqualTo(120);

        doThrow(new ConflictException("profile-name-taken", "Profile name taken",
                "A profile with this name already exists.")).when(service).addProfile(any(), any(), any(), any());
        mockMvc.perform(post(URL + "/nursing-profiles").with(as("OWNER")).contentType(JSON).content(PROFILE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/profile-name-taken"));

        doThrow(new ConflictException("profile-limit", "Too many profiles", "A clinic can have up to 20 profiles."))
                .when(service).addProfile(any(), any(), any(), any());
        mockMvc.perform(post(URL + "/nursing-profiles").with(as("OWNER")).contentType(JSON).content(PROFILE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A clinic can have up to 20 profiles."));
    }

    @Test
    void changingAProfilePassesTheKeyFromThePathAnUnknownKeyIs404() throws Exception {
        when(service.changeProfile(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq("icu"), any())).thenReturn(answer());

        mockMvc.perform(put(URL + "/nursing-profiles/icu").with(as("OWNER")).contentType(JSON).content(PROFILE_BODY))
                .andExpect(status().isOk());

        doThrow(new NotFoundException("Profile not found")).when(service).changeProfile(any(), any(), any(), eq("nope"),
                any());
        mockMvc.perform(put(URL + "/nursing-profiles/nope").with(as("OWNER")).contentType(JSON).content(PROFILE_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void choosingTheProfileOfAWardAcceptsAKeyOrNull() throws Exception {
        when(service.setWardProfile(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(WARD_ID), any())).thenReturn(answer());

        mockMvc.perform(put(URL + "/wards/" + WARD_ID + "/nursing-profile").with(as("OWNER")).contentType(JSON)
                .content("{\"profileKey\":\"icu\"}")).andExpect(status().isOk());
        mockMvc.perform(put(URL + "/wards/" + WARD_ID + "/nursing-profile").with(as("OWNER")).contentType(JSON)
                .content("{\"profileKey\":null}")).andExpect(status().isOk());

        ArgumentCaptor<WardProfileInput> body = ArgumentCaptor.forClass(WardProfileInput.class);
        verify(service, org.mockito.Mockito.times(2)).setWardProfile(eq(TENANT_ID), eq(USER_ID), eq("OWNER"),
                eq(WARD_ID), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getAllValues()).extracting(WardProfileInput::profileKey)
                .containsExactly("icu", null);

        doThrow(new InvalidInputException("profileKey", "Choose a profile from the list.")).when(service)
                .setWardProfile(any(), any(), any(), any(), any());
        mockMvc.perform(put(URL + "/wards/" + WARD_ID + "/nursing-profile").with(as("OWNER")).contentType(JSON)
                .content("{\"profileKey\":\"nope\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("profileKey"));
    }

    @Test
    void aWardIdThatIsNotAUuidIs400AndSignedOutVisitorsGet401() throws Exception {
        mockMvc.perform(put(URL + "/wards/nope/nursing-profile").with(as("OWNER")).contentType(JSON)
                .content("{\"profileKey\":null}")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "/nursing-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL + "/nursing-settings").contentType(JSON).content(SETTINGS_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/nursing-profiles").contentType(JSON).content(PROFILE_BODY))
                .andExpect(status().isUnauthorized());
    }
}
