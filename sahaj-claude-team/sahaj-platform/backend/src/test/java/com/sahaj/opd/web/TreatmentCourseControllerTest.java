package com.sahaj.opd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.course.CourseInputs.AddSittingInput;
import com.sahaj.opd.application.course.CourseInputs.CreateInput;
import com.sahaj.opd.application.course.CourseViews.AddOptionView;
import com.sahaj.opd.application.course.CourseViews.Changed;
import com.sahaj.opd.application.course.CourseViews.CourseView;
import com.sahaj.opd.application.course.CourseViews.SittingView;
import com.sahaj.opd.application.course.CourseViews.SummaryView;
import com.sahaj.opd.application.course.TreatmentCourseService;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** SAH-58 treatment course endpoints (docs/api/opd.yaml 0.4.0): roles, 201 and 200, closed bodies, problem types. */
@WebMvcTest(controllers = TreatmentCourseController.class)
@Import(SecurityConfig.class)
class TreatmentCourseControllerTest {

    private static final String COURSES = "/api/v1/opd/emr/courses";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID SITTING = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String KEY = "key-0000001";
    private static final String CREATE = "{\"fromRecordId\":\"" + UUID.randomUUID() + "\",\"kind\":\"DENTAL\","
            + "\"sittings\":[{\"addOptionKey\":\"opd_sitting\",\"chargePaise\":250000}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TreatmentCourseService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT, USER, role);
    }

    private static CourseView view() {
        SittingView sitting = new SittingView(SITTING, 1, "OPD", "OPD sitting", null, LocalDate.parse("2026-11-15"),
                "PLANNED", List.of("Root canal"), 250_000, null, false, false, null, null, null);
        return new CourseView(COURSE, "TC-0412", "DENTAL", "SITTING", "Dental treatment course",
                LocalDate.parse("2026-11-12"), "ACTIVE", 1, 0, PATIENT, UUID.randomUUID(), "Dr Rao", null, 3,
                List.of(sitting), List.of(new AddOptionView("opd_sitting", "OPD sitting", "OPD")), true, false,
                "One running account: advances and part payments carry across visits.");
    }

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(COURSES + "?patientId=" + PATIENT)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(COURSES + "/" + COURSE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(COURSES).header("Idempotency-Key", KEY).contentType(JSON).content(CREATE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void ownerDoctorsFrontDeskAndBillingReadWhileNurseAndPharmacistAndCounsellorGet403() throws Exception {
        when(service.get(any(ClinicUser.class), eq(COURSE))).thenReturn(view());
        when(service.list(any(ClinicUser.class), eq(PATIENT))).thenReturn(List.of(new SummaryView(COURSE, "TC-0412",
                "DENTAL", "SITTING", "Dental treatment course", LocalDate.parse("2026-11-12"), "ACTIVE", 1, 0)));

        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING"}) {
            mockMvc.perform(get(COURSES + "/" + COURSE).with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.courseNo").value("TC-0412"))
                    .andExpect(jsonPath("$.sittings[0].sittingNo").value(1))
                    .andExpect(jsonPath("$.sittings[0].status").value("PLANNED"))
                    .andExpect(jsonPath("$.addOptions[0].key").value("opd_sitting"))
                    .andExpect(jsonPath("$.accountNote").exists());
            mockMvc.perform(get(COURSES + "?patientId=" + PATIENT).with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].courseNo").value("TC-0412"));
        }
        for (String role : new String[] {"NURSE", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(get(COURSES + "/" + COURSE).with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(get(COURSES + "?patientId=" + PATIENT).with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void creatingReturns201AndTheSameKeyAgainReturns200WithTheClinicTakenFromTheUser() throws Exception {
        when(service.create(any(ClinicUser.class), eq(KEY), any(CreateInput.class)))
                .thenReturn(new Changed(true, view()), new Changed(false, view()));

        mockMvc.perform(post(COURSES).with(as("DOCTOR")).header("Idempotency-Key", KEY).contentType(JSON)
                .content(CREATE.replace("{\"fromRecordId\"", "{\"tenantId\":\"" + UUID.randomUUID()
                        + "\",\"clinicId\":\"" + UUID.randomUUID() + "\",\"fromRecordId\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(COURSE.toString()));
        mockMvc.perform(post(COURSES).with(as("DOCTOR")).header("Idempotency-Key", KEY).contentType(JSON)
                .content(CREATE)).andExpect(status().isOk());

        ArgumentCaptor<ClinicUser> user = ArgumentCaptor.forClass(ClinicUser.class);
        verify(service, org.mockito.Mockito.times(2)).create(user.capture(), eq(KEY), any(CreateInput.class));
        assertThat(user.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(user.getValue().userId()).isEqualTo(USER);
    }

    @Test
    void onlyDoctorsAndTheOwnerChangeACourse() throws Exception {
        for (String role : new String[] {"FRONT_DESK", "BILLING", "NURSE", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(post(COURSES).with(as(role)).header("Idempotency-Key", KEY).contentType(JSON)
                    .content(CREATE)).andExpect(status().isForbidden());
            mockMvc.perform(post(COURSES + "/" + COURSE + "/sittings").with(as(role)).header("Idempotency-Key", KEY)
                    .contentType(JSON).content("{\"revision\":0,\"addOptionKey\":\"opd_sitting\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(COURSES + "/" + COURSE + "/sittings/" + SITTING).with(as(role)).contentType(JSON)
                    .content("{}")).andExpect(status().isForbidden());
            mockMvc.perform(post(COURSES + "/" + COURSE + "/sittings/" + SITTING + "/link-visit").with(as(role))
                    .contentType(JSON).content("{}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void addingASittingReturns201AndARepeatedKey200() throws Exception {
        when(service.addSitting(any(ClinicUser.class), eq(COURSE), eq(KEY), any(AddSittingInput.class)))
                .thenReturn(new Changed(true, view()), new Changed(false, view()));
        String body = "{\"revision\":3,\"addOptionKey\":\"opd_sitting\",\"plannedDate\":\"2026-12-01\"}";

        mockMvc.perform(post(COURSES + "/" + COURSE + "/sittings").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post(COURSES + "/" + COURSE + "/sittings").with(as("OWNER")).header("Idempotency-Key", KEY)
                .contentType(JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void changingAndLinkingAnswerWithTheCourse() throws Exception {
        when(service.changeSitting(any(ClinicUser.class), eq(COURSE), eq(SITTING), any())).thenReturn(view());
        when(service.linkVisit(any(ClinicUser.class), eq(COURSE), eq(SITTING), any())).thenReturn(view());

        mockMvc.perform(put(COURSES + "/" + COURSE + "/sittings/" + SITTING).with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":3,\"encounterKind\":\"OPD\",\"plannedDate\":null,\"items\":[\"Crown\"],"
                        + "\"chargePaise\":300000,\"note\":null}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));
        mockMvc.perform(post(COURSES + "/" + COURSE + "/sittings/" + SITTING + "/link-visit").with(as("DOCTOR"))
                .contentType(JSON).content("{\"revision\":3,\"visitId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aFieldTheContractDoesNotListIsRefusedWith400Validation() throws Exception {
        mockMvc.perform(post(COURSES).with(as("DOCTOR")).header("Idempotency-Key", KEY).contentType(JSON)
                .content(CREATE.replace("\"kind\":\"DENTAL\"", "\"kind\":\"DENTAL\",\"price\":5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
        mockMvc.perform(put(COURSES + "/" + COURSE + "/sittings/" + SITTING).with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":3,\"encounterKind\":\"OPD\",\"items\":[],\"chargePaise\":0,\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void theProblemTypesOfTheContractAreSpelledOut() throws Exception {
        when(service.get(any(ClinicUser.class), eq(COURSE))).thenThrow(EmrProblemException.of(404, "course-not-found",
                "Course not found", "There is no such treatment course."));
        when(service.changeSitting(any(ClinicUser.class), eq(COURSE), eq(SITTING), any())).thenThrow(
                EmrProblemException.of(409, "sitting-done", "This entry is done", "This entry is done."));
        when(service.linkVisit(any(ClinicUser.class), eq(COURSE), eq(SITTING), any())).thenThrow(
                EmrProblemException.of(409, "visit-already-linked", "Visit already linked", "Linked."));

        mockMvc.perform(get(COURSES + "/" + COURSE).with(as("OWNER"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/course-not-found"));
        mockMvc.perform(put(COURSES + "/" + COURSE + "/sittings/" + SITTING).with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":3,\"encounterKind\":\"OPD\",\"items\":[],\"chargePaise\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/sitting-done"));
        mockMvc.perform(post(COURSES + "/" + COURSE + "/sittings/" + SITTING + "/link-visit").with(as("DOCTOR"))
                .contentType(JSON).content("{\"revision\":3,\"visitId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/visit-already-linked"));
    }
}
