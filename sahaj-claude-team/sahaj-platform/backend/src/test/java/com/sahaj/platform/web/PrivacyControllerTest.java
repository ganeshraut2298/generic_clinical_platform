package com.sahaj.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.application.PrivacyService;
import com.sahaj.platform.application.PrivacySettingsRepository.PrivacyRow;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-17 privacy endpoints: contract shape, any clinic role reads, owner-only save, 400 problem. */
@WebMvcTest(controllers = PrivacyController.class)
@Import(SecurityConfig.class)
class PrivacyControllerTest {

    private static final String URL = "/api/v1/platform/privacy";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BODY =
            "{\"consentRequired\":false,\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":\"HFR-1\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PrivacyService privacyService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClinicRoleReadsTheSettingsWithTheContractFieldNames() throws Exception {
        when(privacyService.get(TENANT_ID)).thenReturn(new PrivacyRow(true, true, true, null));

        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
                "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(URL).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.consentRequired").value(true))
                    .andExpect(jsonPath("$.promoConsent").value(true))
                    .andExpect(jsonPath("$.recordCopy").value(true))
                    .andExpect(jsonPath("$.hfrId").doesNotExist());
        }
    }

    @Test
    void theOwnerSavesAndGetsTheSavedSettingsBack() throws Exception {
        when(privacyService.save(TENANT_ID, USER_ID, false, true, true, "HFR-1"))
                .thenReturn(new PrivacyRow(false, true, true, "HFR-1"));

        mockMvc.perform(put(URL).with(as("OWNER")).contentType(JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentRequired").value(false))
                .andExpect(jsonPath("$.hfrId").value("HFR-1"));
    }

    @Test
    void everyOtherRoleGets403OnSave() throws Exception {
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
                "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(put(URL).with(as(role)).contentType(JSON).content(BODY))
                    .andExpect(status().isForbidden());
        }
        verify(privacyService, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTooLongHfrIdIs400ProblemJsonWithAFieldError() throws Exception {
        when(privacyService.save(eq(TENANT_ID), eq(USER_ID), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("hfrId", "The HFR ID can be up to 32 characters"));

        mockMvc.perform(put(URL).with(as("OWNER")).contentType(JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("hfrId"));
    }

    @Test
    void anHfrIdSentAsNullIsPassedOnAsNull() throws Exception {
        when(privacyService.save(eq(TENANT_ID), eq(USER_ID), any(), any(), any(), isNull()))
                .thenReturn(new PrivacyRow(true, true, true, null));

        mockMvc.perform(put(URL).with(as("OWNER")).contentType(JSON)
                        .content("{\"consentRequired\":true,\"promoConsent\":true,\"recordCopy\":true,\"hfrId\":null}"))
                .andExpect(status().isOk());
    }
}
