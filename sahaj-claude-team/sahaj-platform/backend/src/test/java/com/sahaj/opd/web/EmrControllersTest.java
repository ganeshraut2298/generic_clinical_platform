package com.sahaj.opd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.emr.EmrDoctorContextService;
import com.sahaj.opd.application.emr.EmrLibraryService;
import com.sahaj.opd.application.emr.EmrMasterService;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrViews;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ForbiddenException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-43 and the read-only EMR paths (docs/api/opd.yaml 0.3.0): contract shape, 401 and 403, the 403 on writes to the
 * read-only global paths, the retired SAH-44 paths answering 404, and the problem types.
 */
@WebMvcTest(controllers = {EmrMasterController.class, EmrDoctorController.class, EmrLibraryController.class,
        EmrReadOnlyPathsController.class})
@Import(SecurityConfig.class)
class EmrControllersTest {

    private static final String EMR = "/api/v1/opd/emr";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String[] CLINIC_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK",
            "BILLING", "PHARMACIST", "COUNSELLOR"};
    private static final String[] NOT_DOCTOR = {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmrMasterService masters;

    @MockBean
    private EmrDoctorContextService doctorContext;

    @MockBean
    private EmrLibraryService library;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT, USER, role);
    }

    // ---- 401 ----

    @Test
    void aSignedOutVisitorGets401OnEveryEmrOperation() throws Exception {
        mockMvc.perform(get(EMR + "/masters/complaints")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/quick-options/diagnoses")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/library/sections")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/starter-departments")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(EMR + "/masters/complaints").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- masters ----

    @Test
    void everyClinicRoleSearchesAMasterAndTheClinicIsNotTakenFromTheRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(masters.search("advice", "wa", "gm", 1)).thenReturn(new EmrViews.MasterPage(
                List.of(new EmrViews.MasterItem(id, "Warm water gargles", null, "Marathi text", null)), 21, 1, 20));

        for (String role : CLINIC_ROLES) {
            mockMvc.perform(get(EMR + "/masters/advice").param("q", "wa").param("specialty", "gm")
                            .param("page", "1").param("tenantId", UUID.randomUUID().toString()).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.items[0].name").value("Warm water gargles"))
                    .andExpect(jsonPath("$.items[0].specialtyHint").value((Object) null))
                    .andExpect(jsonPath("$.items[0].nameMr").value("Marathi text"))
                    .andExpect(jsonPath("$.items[0].nameHi").value((Object) null))
                    .andExpect(jsonPath("$.total").value(21))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(20));
        }
    }

    @Test
    void aSearchWithNoMatchIs200WithAnEmptyList() throws Exception {
        when(masters.search("advice", null, null, null)).thenReturn(new EmrViews.MasterPage(List.of(), 0, 0, 20));

        mockMvc.perform(get(EMR + "/masters/advice").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anUnknownMasterIsBadRequestAndATooLongTextIsValidation() throws Exception {
        when(masters.search("diagnoses", null, null, null)).thenThrow(EmrProblemException.badRequest("Search x."));
        when(masters.search("complaints", "long", null, null))
                .thenThrow(new InvalidInputException("q", "Search text must be 100 characters or fewer."));

        mockMvc.perform(get(EMR + "/masters/diagnoses").with(as("DOCTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"));
        mockMvc.perform(get(EMR + "/masters/complaints").param("q", "long").with(as("DOCTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("q"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Search text must be 100 characters or fewer."));
    }

    // ---- writes to the read-only global paths ----

    @Test
    void everyWriteMethodOnTheGlobalPathsIs403ForEveryRoleIncludingTheOwner() throws Exception {
        String[] paths = {EMR + "/masters/complaints", EMR + "/masters", EMR + "/quick-options/diagnoses",
                EMR + "/library/sections", EMR + "/library/sections/vitals", EMR + "/starter-departments",
                EMR + "/starter-departments/gm", EMR + "/allergy-classes", EMR + "/allergy-classes/penicillin",
                EMR + "/form-starters", EMR + "/form-starters/consent"};
        for (String role : CLINIC_ROLES) {
            for (String path : paths) {
                for (MockHttpServletRequestBuilder builder : new MockHttpServletRequestBuilder[] {
                        post(path), put(path), patch(path), delete(path)}) {
                    mockMvc.perform(builder.with(as(role)).contentType(JSON).content("{\"name\":\"x\"}"))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
                }
            }
        }
        verifyNoInteractions(masters, library);
    }

    @Test
    void theRetiredSah44PathsAreGone() throws Exception {
        mockMvc.perform(get(EMR + "/templates").with(as("OWNER"))).andExpect(status().isNotFound());
        mockMvc.perform(get(EMR + "/templates/gp").with(as("OWNER"))).andExpect(status().isNotFound());
        mockMvc.perform(get(EMR + "/settings").with(as("OWNER"))).andExpect(status().isNotFound());
    }

    // ---- quick options ----

    @Test
    void aDoctorReadsTheirOwnRankingAndNoDoctorIdIsReadFromTheRequest() throws Exception {
        Instant used = Instant.parse("2026-11-10T06:05:00Z");
        when(masters.quickOptions(any(ClinicUser.class), eq("diagnoses"), eq(5), eq("gm"))).thenReturn(
                new EmrViews.QuickOptionList("diagnoses", List.of(
                        new EmrViews.QuickOption("J06.9", "Acute upper respiratory infection, unspecified", "J06.9",
                                null, null, 5, used, null, null),
                        new EmrViews.QuickOption("A09", "Infectious gastroenteritis", "A09", null, null, 0, null,
                                null, null))));

        mockMvc.perform(get(EMR + "/quick-options/diagnoses").param("limit", "5").param("specialty", "gm")
                        .param("doctorMembershipId", UUID.randomUUID().toString()).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.master").value("diagnoses"))
                .andExpect(jsonPath("$.items[0].itemKey").value("J06.9"))
                .andExpect(jsonPath("$.items[0].icd10Code").value("J06.9"))
                .andExpect(jsonPath("$.items[0].form").value((Object) null))
                .andExpect(jsonPath("$.items[0].favourite").value((Object) null))
                .andExpect(jsonPath("$.items[0].useCount").value(5))
                .andExpect(jsonPath("$.items[0].lastUsedAt").value("2026-11-10T06:05:00Z"))
                .andExpect(jsonPath("$.items[1].lastUsedAt").value((Object) null));

        org.mockito.ArgumentCaptor<ClinicUser> captor = org.mockito.ArgumentCaptor.forClass(ClinicUser.class);
        verify(masters).quickOptions(captor.capture(), eq("diagnoses"), eq(5), eq("gm"));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().userId()).isEqualTo(USER);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    void theOtherRolesGet403OnQuickOptionsAndTheServiceIsNotCalled() throws Exception {
        for (String role : NOT_DOCTOR) {
            mockMvc.perform(get(EMR + "/quick-options/complaints").with(as(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
        }
        verify(masters, never()).quickOptions(any(), any(), any(), any());
    }

    @Test
    void anOwnerWithoutDoctorDetailsGets403WithTheReason() throws Exception {
        when(masters.quickOptions(any(ClinicUser.class), eq("complaints"), any(), any())).thenThrow(
                new ForbiddenException("doctor-details-missing", "Doctor details are missing",
                        "Add your doctor details in Team and access to write consultations."));

        mockMvc.perform(get(EMR + "/quick-options/complaints").with(as("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/doctor-details-missing"))
                .andExpect(jsonPath("$.detail").value(
                        "Add your doctor details in Team and access to write consultations."));
    }

    // ---- /emr/me ----

    @Test
    void theDoctorReadsTheirOwnDetailsWithTheMissingListAndTheDepartmentChoice() throws Exception {
        UUID membership = UUID.randomUUID();
        when(doctorContext.me(any(ClinicUser.class))).thenReturn(new EmrViews.DoctorContext(membership,
                "Dr Test Person", "DOCTOR", "General Physician", "MBBS, MD", null, List.of("REGISTRATION_NO"),
                new EmrViews.DepartmentChoice("gm", "General Medicine", "gm", "STARTER", null, "DOCTOR_SPECIALTY"),
                List.of(new EmrViews.DepartmentRef("gm", "General Medicine"))));

        mockMvc.perform(get(EMR + "/me").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.role").value("DOCTOR"))
                .andExpect(jsonPath("$.specialty").value("General Physician"))
                .andExpect(jsonPath("$.registrationNo").value((Object) null))
                .andExpect(jsonPath("$.missing[0]").value("REGISTRATION_NO"))
                .andExpect(jsonPath("$.department.departmentKey").value("gm"))
                .andExpect(jsonPath("$.department.departmentName").value("General Medicine"))
                .andExpect(jsonPath("$.department.starterKey").value("gm"))
                .andExpect(jsonPath("$.department.layoutSource").value("STARTER"))
                .andExpect(jsonPath("$.department.versionNo").value((Object) null))
                .andExpect(jsonPath("$.department.choiceSource").value("DOCTOR_SPECIALTY"))
                .andExpect(jsonPath("$.departments[0].departmentKey").value("gm"))
                .andExpect(jsonPath("$.template").doesNotExist());
    }

    @Test
    void theOtherRolesGet403OnMe() throws Exception {
        for (String role : NOT_DOCTOR) {
            mockMvc.perform(get(EMR + "/me").with(as(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(doctorContext);
    }

    // ---- library and starters ----

    @Test
    void everyClinicRoleReadsTheStarterListAndAnUnknownStarterIs404() throws Exception {
        when(library.starters()).thenReturn(List.of(new EmrViews.StarterSummary("gm", "General Medicine", "GM",
                "#1D5FA8", null, 19, 5, List.of("Dental chart"))));
        when(library.starter("gp")).thenThrow(EmrProblemException.starterNotFound());

        for (String role : CLINIC_ROLES) {
            mockMvc.perform(get(EMR + "/starter-departments").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].starterKey").value("gm"))
                    .andExpect(jsonPath("$[0].description").value((Object) null))
                    .andExpect(jsonPath("$[0].specialtySectionCount").value(5))
                    .andExpect(jsonPath("$[0].offAtStart[0]").value("Dental chart"));
        }
        mockMvc.perform(get(EMR + "/starter-departments/gp").with(as("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/starter-not-found"));
    }
}
