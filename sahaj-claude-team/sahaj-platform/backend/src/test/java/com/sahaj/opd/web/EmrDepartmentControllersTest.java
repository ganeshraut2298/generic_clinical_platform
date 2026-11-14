package com.sahaj.opd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.opd.application.VisitView;
import com.sahaj.opd.application.emr.EmrDepartmentService;
import com.sahaj.opd.application.emr.EmrInputs.AdmissionAdviceInput;
import com.sahaj.opd.application.emr.EmrInputs.DepartmentCreateInput;
import com.sahaj.opd.application.emr.EmrInputs.LayoutInput;
import com.sahaj.opd.application.emr.EmrInputs.OpenInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.VisitTypeInput;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrViews;
import com.sahaj.opd.application.emr.EmrVisitTypeService;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The department configuration, record and visit-type endpoints (docs/api/opd.yaml 0.3.0): status codes, 401 and 403
 * before the service, the closed request bodies with the clinic id ignored, and the problem types.
 */
@WebMvcTest(controllers = {EmrDepartmentController.class, EmrRecordController.class, EmrVisitTypeController.class})
@Import(SecurityConfig.class)
class EmrDepartmentControllersTest {

    private static final String EMR = "/api/v1/opd/emr";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String[] NOT_OWNER = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] NOT_DOCTOR = {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"};
    private static final String LAYOUT_BODY = "{\"revision\":2,\"origin\":\"EDIT\",\"sections\":[{\"key\":\"vitals\","
            + "\"part\":\"ASSESSMENT\",\"shown\":true,\"required\":false,\"print\":true,\"copy\":false,"
            + "\"collapsed\":false,\"whoCanFill\":[\"DOCTOR\",\"NURSE\"]}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmrDepartmentService departments;

    @MockBean
    private EmrRecordService records;

    @MockBean
    private EmrVisitTypeService visitTypes;

    @MockBean
    private com.sahaj.opd.application.emr.EmrCasePaperService casePaper;

    @MockBean
    private com.sahaj.opd.application.emr.EmrHistoryService history;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT, USER, role);
    }

    private static EmrViews.DepartmentView view() {
        return new EmrViews.DepartmentView("gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null, "TEMPLATE",
                0, false, 19, 17, 4, 0, List.of(), true, 0, "STARTER", List.of());
    }

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(EMR + "/departments")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(EMR + "/departments/gm/draft").contentType(JSON).content(LAYOUT_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(EMR + "/records/open").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/opd/visits/" + UUID.randomUUID() + "/visit-type").contentType(JSON)
                .content("{\"visitType\":\"NEW\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(departments, records, visitTypes);
    }

    @Test
    void everyClinicRoleReadsTheDepartmentsAndTheOwnerOnlyWrites() throws Exception {
        when(departments.list(any(ClinicUser.class))).thenReturn(new EmrViews.DepartmentList(true, List.of(
                new EmrViews.DepartmentSummary("gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                        "TEMPLATE", 0, false, 19, 17, 4, 0, List.of()))));

        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST",
                "COUNSELLOR", "VISITING_DOCTOR"}) {
            mockMvc.perform(get(EMR + "/departments").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canEdit").value(true))
                    .andExpect(jsonPath("$.departments[0].departmentKey").value("gm"))
                    .andExpect(jsonPath("$.departments[0].status").value("TEMPLATE"))
                    .andExpect(jsonPath("$.departments[0].copiedFromKey").value((Object) null))
                    .andExpect(jsonPath("$.departments[0].sectionsOn").value(17));
        }
        for (String role : NOT_OWNER) {
            mockMvc.perform(put(EMR + "/departments/gm/draft").with(as(role)).contentType(JSON).content(LAYOUT_BODY))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(EMR + "/departments/gm/publish").with(as(role)).contentType(JSON)
                    .content(LAYOUT_BODY)).andExpect(status().isForbidden());
            mockMvc.perform(post(EMR + "/departments").with(as(role)).contentType(JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(EMR + "/department-doctor-options").with(as(role))).andExpect(status().isForbidden());
        }
        verify(departments, never()).saveDraft(any(), any(), any());
        verify(departments, never()).publish(any(), any(), any());
        verify(departments, never()).create(any(), any());
    }

    @Test
    void theDraftBodyIsHandedToTheServiceAndATenantOrClinicIdInTheBodyIsIgnored() throws Exception {
        when(departments.saveDraft(any(ClinicUser.class), eq("gm"), any(LayoutInput.class)))
                .thenReturn(new EmrViews.DraftSaveResult("SAVED", view()));
        String body = LAYOUT_BODY.replace("{\"revision\"", "{\"tenantId\":\"" + UUID.randomUUID()
                + "\",\"clinicId\":\"" + UUID.randomUUID() + "\",\"revision\"");

        mockMvc.perform(put(EMR + "/departments/gm/draft").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SAVED"))
                .andExpect(jsonPath("$.department.revision").value(0));

        ArgumentCaptor<LayoutInput> captor = ArgumentCaptor.forClass(LayoutInput.class);
        ArgumentCaptor<ClinicUser> user = ArgumentCaptor.forClass(ClinicUser.class);
        verify(departments).saveDraft(user.capture(), eq("gm"), captor.capture());
        assertThat(user.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().revision()).isEqualTo(2L);
        assertThat(captor.getValue().origin()).isEqualTo("EDIT");
        assertThat(captor.getValue().sections()).hasSize(1);
        assertThat(captor.getValue().sections().get(0).whoCanFill()).containsExactly("DOCTOR", "NURSE");
    }

    @Test
    void theBodiesAreClosedAnUnknownFieldIsValidationAndNoNameIsEchoed() throws Exception {
        String label = LAYOUT_BODY.replace("\"part\"", "\"label\":\"Renamed\",\"part\"");
        mockMvc.perform(put(EMR + "/departments/gm/draft").with(as("OWNER")).contentType(JSON).content(label))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("body"));
        mockMvc.perform(post(EMR + "/records/open").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + UUID.randomUUID()
                                + "\",\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
        mockMvc.perform(patch("/api/v1/opd/visits/" + UUID.randomUUID() + "/visit-type").with(as("DOCTOR"))
                        .contentType(JSON).content("{\"visitType\":\"NEW\",\"fee\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
        verifyNoInteractions(departments, records, visitTypes);
    }

    @Test
    void thePublishAndAddDepartmentProblemsKeepTheirSlugsAndStatuses() throws Exception {
        when(departments.publish(any(ClinicUser.class), eq("gm"), any(LayoutInput.class)))
                .thenThrow(EmrProblemException.emrSetupChanged());
        when(departments.create(any(ClinicUser.class), any(DepartmentCreateInput.class)))
                .thenThrow(EmrProblemException.departmentNameTaken());

        mockMvc.perform(post(EMR + "/departments/gm/publish").with(as("OWNER")).contentType(JSON).content(LAYOUT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/emr-setup-changed"))
                .andExpect(jsonPath("$.detail").value("Someone else changed this department. Reload and try again."));
        mockMvc.perform(post(EMR + "/departments").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"General Medicine\",\"source\":{\"kind\":\"BLANK\"},"
                                + "\"copyPrintFlags\":true,\"doctorMembershipIds\":[],\"rooms\":[],"
                                + "\"clinicId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/department-name-taken"));
    }

    @Test
    void addingADepartmentAnswers201() throws Exception {
        when(departments.create(any(ClinicUser.class), any(DepartmentCreateInput.class))).thenReturn(view());

        mockMvc.perform(post(EMR + "/departments").with(as("OWNER")).contentType(JSON)
                        .content("{\"name\":\"Dental chair 2\",\"source\":{\"kind\":\"COPY\","
                                + "\"copyFromDepartmentKey\":\"dental\"},\"copyPrintFlags\":true,"
                                + "\"doctorMembershipIds\":[\"" + UUID.randomUUID() + "\"],\"rooms\":[\"Chair 2\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentKey").value("gm"));
    }

    // ---- records ----

    private EmrViews.RecordView recordView() {
        return new EmrViews.RecordView(RECORD, "OPD_VISIT", UUID.randomUUID(), null, "DRAFT", false, null, 3,
                new EmrViews.RecordHeader(null, "Asha Rao", "AR", 30, "F", null, null, null, null, null, "NEW",
                        new EmrViews.DoctorOnRecord(UUID.randomUUID(), "Dr Test", null), null, null, false),
                new EmrViews.Snapshot("gm", "General Medicine", "gm", "STARTER", 1, "CLINIC_DEFAULT",
                        Instant.parse("2026-11-12T05:00:00Z"), List.of()),
                List.of(), List.of(), List.of(), true, new EmrViews.UiState("ASSESSMENT", null), false, null,
                List.of(), new EmrViews.IpdState("OFF", true), List.of(), List.of(), null,
                new EmrViews.PrintPreferences(null, null), false);
    }

    @Test
    void openAnswers201WhenCreatedAnd200WhenFound() throws Exception {
        UUID visit = UUID.randomUUID();
        when(records.open(any(ClinicUser.class), any(OpenInput.class))).thenReturn(
                new EmrViews.OpenResult(true, "MOVED", recordView()), new EmrViews.OpenResult(false,
                        "ALREADY_WITH_DOCTOR", recordView()));
        String body = "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + visit + "\"}";

        mockMvc.perform(post(EMR + "/records/open").with(as("DOCTOR")).contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.stageOutcome").value("MOVED"))
                .andExpect(jsonPath("$.record.id").value(RECORD.toString()))
                .andExpect(jsonPath("$.record.header.patientName").value("Asha Rao"))
                .andExpect(jsonPath("$.record.header.patientId").value((Object) null))
                .andExpect(jsonPath("$.record.layout.layoutSource").value("STARTER"))
                .andExpect(jsonPath("$.record.ipd.state").value("OFF"))
                .andExpect(jsonPath("$.record.readOnlyReason").value((Object) null));
        mockMvc.perform(post(EMR + "/records/open").with(as("OWNER")).contentType(JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void theOtherRolesAreRefusedOnEveryRecordOperationBeforeTheService() throws Exception {
        for (String role : NOT_DOCTOR) {
            mockMvc.perform(post(EMR + "/records/open").with(as(role)).contentType(JSON)
                    .content("{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(EMR + "/records/" + RECORD).with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(put(EMR + "/records/" + RECORD + "/draft").with(as(role)).contentType(JSON)
                    .content("{\"revision\":0,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},"
                            + "\"sections\":[]}")).andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/v1/opd/visits/" + UUID.randomUUID() + "/visit-type").with(as(role))
                    .contentType(JSON).content("{\"visitType\":\"NEW\"}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(records, visitTypes);
    }

    @Test
    void theRecordProblemsKeepTheirSlugsAndStatuses() throws Exception {
        when(records.get(any(ClinicUser.class), eq(RECORD))).thenThrow(EmrProblemException.notOwnPatient());
        UUID other = UUID.randomUUID();
        when(records.get(any(ClinicUser.class), eq(other))).thenThrow(EmrProblemException.recordNotFound());
        when(records.saveDraft(any(ClinicUser.class), eq(RECORD), any(RecordDraftInput.class)))
                .thenThrow(EmrProblemException.emrRecordChanged());
        when(records.adviseAdmission(any(ClinicUser.class), eq(RECORD), any(AdmissionAdviceInput.class)))
                .thenThrow(EmrProblemException.emrRecordSigned());

        mockMvc.perform(get(EMR + "/records/" + RECORD).with(as("DOCTOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-own-patient"));
        mockMvc.perform(get(EMR + "/records/" + other).with(as("DOCTOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/record-not-found"));
        mockMvc.perform(put(EMR + "/records/" + RECORD + "/draft").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"revision\":1,\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":\"vitals\"},"
                                + "\"sections\":[{\"key\":\"vitals\",\"values\":{\"pulse\":72},\"confirmCopy\":true}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/emr-record-changed"));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/admission-advice").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"revision\":1,\"referralLine\":\"Refer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/emr-record-signed"));
    }

    @Test
    void theSectionValuesReachTheServiceAsJsonNotAsText() throws Exception {
        when(records.saveDraft(any(ClinicUser.class), eq(RECORD), any(RecordDraftInput.class)))
                .thenReturn(recordView());

        mockMvc.perform(put(EMR + "/records/" + RECORD + "/draft").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"revision\":1,\"uiState\":{\"part\":\"TREATMENT\",\"sectionKey\":null},"
                                + "\"sections\":[{\"key\":\"vitals\",\"values\":{\"pulse\":72.5,\"unit\":"
                                + "{\"value\":3,\"unit\":\"days\"}}}]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RecordDraftInput> captor = ArgumentCaptor.forClass(RecordDraftInput.class);
        verify(records).saveDraft(any(ClinicUser.class), eq(RECORD), captor.capture());
        assertThat(captor.getValue().revision()).isEqualTo(1L);
        assertThat(captor.getValue().uiState().part()).isEqualTo("TREATMENT");
        assertThat(captor.getValue().sections().get(0).values().path("pulse").decimalValue().toPlainString())
                .isEqualTo("72.5");
        assertThat(captor.getValue().sections().get(0).values().path("unit").path("unit").asText()).isEqualTo("days");
        assertThat(captor.getValue().sections().get(0).confirmCopy()).isNull();
    }

    // ---- visit type ----

    @Test
    void theVisitTypeChangeReturnsTheVisitWithItsEmrFields() throws Exception {
        UUID visit = UUID.randomUUID();
        when(visitTypes.change(any(ClinicUser.class), eq(visit), any(VisitTypeInput.class))).thenReturn(new VisitView(
                visit, 7, UUID.randomUUID(), "Asha Rao", 30, "F", UUID.randomUUID(), "Dr Test", "REVISIT",
                "WITH_DOCTOR", 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, RECORD, "DRAFT"));

        mockMvc.perform(patch("/api/v1/opd/visits/" + visit + "/visit-type").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"visitType\":\"REVISIT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitType").value("REVISIT"))
                .andExpect(jsonPath("$.emrRecordId").value(RECORD.toString()))
                .andExpect(jsonPath("$.emrStatus").value("DRAFT"));
    }

    @Test
    void emergencyAndAnotherDoctorsVisitKeepTheirProblemTypes() throws Exception {
        UUID visit = UUID.randomUUID();
        when(visitTypes.change(any(ClinicUser.class), eq(visit), any(VisitTypeInput.class)))
                .thenThrow(EmrProblemException.visitTypeNotAvailable())
                .thenThrow(EmrProblemException.notOwnPatient())
                .thenThrow(new AccessDeniedException("no"));

        mockMvc.perform(patch("/api/v1/opd/visits/" + visit + "/visit-type").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"visitType\":\"EMERGENCY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/visit-type-not-available"))
                .andExpect(jsonPath("$.detail").value("Emergency is available in a later step."));
        mockMvc.perform(patch("/api/v1/opd/visits/" + visit + "/visit-type").with(as("DOCTOR")).contentType(JSON)
                        .content("{\"visitType\":\"NEW\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-own-patient"));
        mockMvc.perform(patch("/api/v1/opd/visits/" + visit + "/visit-type").with(as("OWNER")).contentType(JSON)
                        .content("{\"visitType\":\"NEW\"}"))
                .andExpect(status().isForbidden());
    }
}
