package com.sahaj.patient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.application.PatientService;
import com.sahaj.patient.application.PatientSummaryPage;
import com.sahaj.patient.application.PatientSummaryView;
import com.sahaj.patient.application.PatientView;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * DEC-049 answer 2 (patient.yaml 0.2.1): the three patient reads (list, one patient, family) are open to OWNER,
 * FRONT_DESK, NURSE, DOCTOR, VISITING_DOCTOR, BILLING and PHARMACIST and closed to COUNSELLOR (403). BILLING and
 * PHARMACIST can read but never receive {@code dateOfBirth}; they get {@code age} and {@code ageDetail}.
 */
@WebMvcTest(controllers = PatientController.class)
@Import(SecurityConfig.class)
class PatientReadRolesControllerTest {

    private static final String BASE = "/api/v1/patient/patients";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final LocalDate BORN = LocalDate.of(2025, 6, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String[] READERS = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING",
            "PHARMACIST"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PatientService patientService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PatientView datedView() {
        return new PatientView(PATIENT_ID, "PT-00001", "Asha Rao", 1, "F", "9123450001", null, "mr", null, null, false,
                Instant.now(), null, BORN, PatientAge.fromDate(BORN, TODAY));
    }

    private static PatientSummaryView child() {
        return new PatientSummaryView(UUID.randomUUID(), "PT-00002", "Ramesh Rao", 1, "M", "9123450001", null, null,
                PatientAge.fromDate(BORN, TODAY));
    }

    @Test
    void sevenRolesReadTheListTheDetailAndTheFamily() throws Exception {
        when(patientService.search(eq(TENANT_ID), any(), any(), eq(0))).thenReturn(new PatientSummaryPage(List.of(), 0));
        when(patientService.open(TENANT_ID, USER_ID, PATIENT_ID)).thenReturn(datedView());
        when(patientService.family(TENANT_ID, PATIENT_ID)).thenReturn(List.of(child()));

        for (String role : READERS) {
            mockMvc.perform(get(BASE).with(as(role))).andExpect(status().isOk());
            mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as(role))).andExpect(status().isOk());
            mockMvc.perform(get(BASE + "/" + PATIENT_ID + "/family").with(as(role))).andExpect(status().isOk());
        }
    }

    @Test
    void counsellorGets403OnTheListTheDetailAndTheFamily() throws Exception {
        mockMvc.perform(get(BASE).with(as("COUNSELLOR"))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as("COUNSELLOR"))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + PATIENT_ID + "/family").with(as("COUNSELLOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void billingAndPharmacistGetAgeAndAgeDetailButNoDateOfBirthProperty() throws Exception {
        when(patientService.open(TENANT_ID, USER_ID, PATIENT_ID)).thenReturn(datedView());

        for (String role : new String[] {"BILLING", "PHARMACIST"}) {
            String body = mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Asha Rao"))
                    .andExpect(jsonPath("$.age").value(1))
                    .andExpect(jsonPath("$.ageDetail.unit").value("MONTHS"))
                    .andExpect(jsonPath("$.ageDetail.value").value(17))
                    .andExpect(jsonPath("$.dateOfBirth").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).as(role).doesNotContain("dateOfBirth").doesNotContain("2025-06-10");
        }
    }

    @Test
    void theRolesThatMaySeeTheDateStillGetIt() throws Exception {
        when(patientService.open(TENANT_ID, USER_ID, PATIENT_ID)).thenReturn(datedView());

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR"}) {
            mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as(role)))
                    .andExpect(jsonPath("$.dateOfBirth").value("2025-06-10"));
        }
    }

    @Test
    void listAndFamilyRowsForBillingAndPharmacistCarryNoDateOfBirth() throws Exception {
        when(patientService.search(eq(TENANT_ID), any(), any(), eq(0)))
                .thenReturn(new PatientSummaryPage(List.of(child()), 1));
        when(patientService.family(TENANT_ID, PATIENT_ID)).thenReturn(List.of(child()));

        for (String role : new String[] {"BILLING", "PHARMACIST"}) {
            String list = mockMvc.perform(get(BASE).with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].ageDetail.unit").value("MONTHS"))
                    .andReturn().getResponse().getContentAsString();
            String family = mockMvc.perform(get(BASE + "/" + PATIENT_ID + "/family").with(as(role)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(list + family).as(role).doesNotContain("dateOfBirth").doesNotContain("2025-06-10");
        }
    }
}
