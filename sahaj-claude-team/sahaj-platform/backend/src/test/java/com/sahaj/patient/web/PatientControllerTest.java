package com.sahaj.patient.web;

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

import com.sahaj.patient.application.PatientInput;
import com.sahaj.patient.application.PatientRepository.PatientPage;
import com.sahaj.patient.application.PatientService;
import com.sahaj.patient.application.PatientSummaryView;
import com.sahaj.patient.application.PatientUpdateInput;
import com.sahaj.patient.application.PatientView;
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

/** SAH-8 patient endpoints: contract shape, role gate (AC8), tenant from the signed-in user, problem responses. */
@WebMvcTest(controllers = PatientController.class)
@Import(SecurityConfig.class)
class PatientControllerTest {

    private static final String BASE = "/api/v1/patient/patients";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PatientService patientService;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static PatientView view(UUID id, UUID duplicateOfId) {
        return new PatientView(id, "PT-00001", "Asha Rao", 30, "F", "9123450001", null, "mr", null, null, false,
                Instant.now(), duplicateOfId);
    }

    @Test
    void aSignedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClinicRoleCanReadButOnlyOwnerAndFrontDeskCanWrite() throws Exception {
        when(patientService.search(eq(TENANT_ID), any(), any(), eq(0))).thenReturn(new PatientPage(List.of(), 0));

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR"}) {
            mockMvc.perform(get(BASE).with(as(role))).andExpect(status().isOk());
        }
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST"}) {
            mockMvc.perform(post(BASE).with(as(role)).contentType(JSON)
                            .content("{\"name\":\"Asha\",\"age\":30,\"sex\":\"F\",\"mobile\":\"9123450001\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(patientService, never()).create(any(), any(), any());
    }

    @Test
    void theFrontDeskRegistersAPatientAndGets201WithTheContractShape() throws Exception {
        when(patientService.create(eq(TENANT_ID), eq(USER_ID), any(PatientInput.class)))
                .thenReturn(view(PATIENT_ID, null));

        mockMvc.perform(post(BASE).with(as("FRONT_DESK")).contentType(JSON)
                        .content("{\"name\":\"Asha Rao\",\"age\":30,\"sex\":\"F\",\"mobile\":\"9123450001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$.uhid").value("PT-00001"))
                .andExpect(jsonPath("$.name").value("Asha Rao"))
                .andExpect(jsonPath("$.possibleDuplicateOfId").value((Object) null));
    }

    @Test
    void aPossibleDuplicateIsFlaggedButStillCreated() throws Exception {
        UUID duplicateOf = UUID.randomUUID();
        when(patientService.create(eq(TENANT_ID), eq(USER_ID), any(PatientInput.class)))
                .thenReturn(view(PATIENT_ID, duplicateOf));

        mockMvc.perform(post(BASE).with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"Asha Rao\",\"age\":30,\"sex\":\"F\",\"mobile\":\"9123450001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicateOfId").value(duplicateOf.toString()));
    }

    @Test
    void invalidInputIs400WithAFieldError() throws Exception {
        when(patientService.create(any(), any(), any()))
                .thenThrow(new InvalidInputException("mobile", "Enter a 10-digit mobile number"));

        mockMvc.perform(post(BASE).with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"Asha\",\"age\":30,\"sex\":\"F\",\"mobile\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("mobile"));
    }

    @Test
    void getByIdReturns404ForAnotherTenantsPatient() throws Exception {
        when(patientService.find(TENANT_ID, PATIENT_ID)).thenThrow(new NotFoundException("Patient not found"));

        mockMvc.perform(get(BASE + "/" + PATIENT_ID).with(as("NURSE"))).andExpect(status().isNotFound());
    }

    @Test
    void patchUpdatesOnlyTheFieldsSent() throws Exception {
        when(patientService.update(eq(TENANT_ID), eq(USER_ID), eq(PATIENT_ID), any(PatientUpdateInput.class)))
                .thenReturn(view(PATIENT_ID, null));

        mockMvc.perform(patch(BASE + "/" + PATIENT_ID).with(as("OWNER")).contentType(JSON)
                        .content("{\"chronic\":\"Diabetes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha Rao"));
    }

    @Test
    void familyListsTheContractShape() throws Exception {
        when(patientService.family(TENANT_ID, PATIENT_ID)).thenReturn(List.of(
                new PatientSummaryView(UUID.randomUUID(), "PT-00002", "Ramesh Rao", 8, "M", "9123450001", null,
                        null)));

        mockMvc.perform(get(BASE + "/" + PATIENT_ID + "/family").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ramesh Rao"))
                .andExpect(jsonPath("$[0].lastVisitAt").value((Object) null));
    }

    @Test
    void mergeIsOwnerOrFrontDeskOnlyAndReturnsTheSurvivor() throws Exception {
        UUID keepId = UUID.randomUUID();
        UUID mergeId = UUID.randomUUID();
        when(patientService.merge(TENANT_ID, USER_ID, keepId, mergeId)).thenReturn(view(keepId, null));

        mockMvc.perform(post(BASE + "/merge").with(as("NURSE")).contentType(JSON)
                        .content("{\"keepId\":\"" + keepId + "\",\"mergeId\":\"" + mergeId + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(BASE + "/merge").with(as("OWNER")).contentType(JSON)
                        .content("{\"keepId\":\"" + keepId + "\",\"mergeId\":\"" + mergeId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(keepId.toString()));
    }
}
