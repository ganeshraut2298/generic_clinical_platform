package com.sahaj.ipd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.VitalsService;
import com.sahaj.ipd.application.VitalsViews.EscalationResult;
import com.sahaj.ipd.application.VitalsViews.LatestVitals;
import com.sahaj.ipd.application.VitalsViews.NursingNote;
import com.sahaj.ipd.application.VitalsViews.OpenEscalation;
import com.sahaj.ipd.application.VitalsViews.Outcome;
import com.sahaj.ipd.application.VitalsViews.Saved;
import com.sahaj.ipd.application.VitalsViews.Sheet;
import com.sahaj.ipd.application.VitalsViews.VitalsInput;
import com.sahaj.ipd.application.VitalsViews.VitalsSaved;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
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

/** SAH-30 Nursing sheet and vitals endpoints: contract shape, status codes, roles and problem types. */
@WebMvcTest(controllers = VitalsController.class)
@Import(SecurityConfig.class)
class VitalsControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID VITALS_ID = UUID.randomUUID();
    private static final UUID ESCALATION_ID = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00Z");
    private static final String[] VIEW_ROLES = {"OWNER", "NURSE", "DOCTOR"};
    private static final String[] RECORD_ROLES = {"OWNER", "NURSE"};
    private static final String[] NO_RECORD_ROLES = {"DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR",
        "PHARMACIST", "COUNSELLOR"};
    private static final String[] NO_VIEW_ROLES = {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST",
        "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VitalsService service;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static LatestVitals latest() {
        return new LatestVitals(VITALS_ID, "120/80", 120, 80, 80, 98, new BigDecimal("37.0"), 16, 0, Risk.LOW,
                NOW.minusSeconds(2100), "Sr. Anjali Pawar");
    }

    private static Sheet sheet(boolean canRecord, String reason, LatestVitals latest) {
        return new Sheet(ADMISSION_ID, "Test Patient", "202", "ADMITTED", "on", canRecord, reason, NOW, latest, false,
                List.of(new NursingNote(UUID.randomUUID(), "Slept well", NOW.minusSeconds(2100),
                        "Sr. Anjali Pawar")), 0);
    }

    private static VitalsSaved saved(boolean replayed, Outcome outcome, OpenEscalation open) {
        Sheet sheet = sheet(true, null, latest());
        return new VitalsSaved(sheet.admissionId(), sheet.patientName(), sheet.bedCode(), sheet.status(), sheet.state(),
                sheet.canRecord(), sheet.readOnlyReason(), sheet.asOf(), sheet.latest(), sheet.vitalsDue(),
                sheet.recentNotes(), sheet.doseCount(), new Saved(VITALS_ID, 5, Risk.MEDIUM, NOW, true, replayed),
                new EscalationResult(outcome, open));
    }

    private static OpenEscalation open() {
        return new OpenEscalation(ESCALATION_ID, 5, Risk.MEDIUM, 60, "OPEN", NOW);
    }

    private static String body() {
        return "{\"clientKey\":\"" + KEY + "\",\"bp\":\"120/80\",\"pulse\":\"80\",\"spo2\":\"98\",\"temp\":\"37.0\","
                + "\"rr\":\"16\",\"note\":\"Slept well\"}";
    }

    // ---- GET ----

    @Test
    void signedOutVisitorGets401() throws Exception {
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/nursing")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").contentType(JSON).content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSheetIsInContractShapeForOwnerNurseAndDoctorAndPassesTheRoleAndTheUser() throws Exception {
        for (String role : VIEW_ROLES) {
            when(service.sheet(TENANT_ID, USER_ID, role, ADMISSION_ID))
                    .thenReturn(sheet(!"DOCTOR".equals(role), "DOCTOR".equals(role) ? "ROLE" : null, latest()));
            mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/nursing").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                    .andExpect(jsonPath("$.patientName").value("Test Patient"))
                    .andExpect(jsonPath("$.bedCode").value("202"))
                    .andExpect(jsonPath("$.status").value("ADMITTED"))
                    .andExpect(jsonPath("$.state").value("on"))
                    .andExpect(jsonPath("$.canRecord").value(!"DOCTOR".equals(role)))
                    .andExpect(jsonPath("$.asOf").value("2026-11-11T05:30:00Z"))
                    .andExpect(jsonPath("$.vitalsDue").value(false))
                    .andExpect(jsonPath("$.latest.id").value(VITALS_ID.toString()))
                    .andExpect(jsonPath("$.latest.bp").value("120/80"))
                    .andExpect(jsonPath("$.latest.bpSystolic").value(120))
                    .andExpect(jsonPath("$.latest.bpDiastolic").value(80))
                    .andExpect(jsonPath("$.latest.pulse").value(80))
                    .andExpect(jsonPath("$.latest.spo2").value(98))
                    .andExpect(jsonPath("$.latest.tempC").value(37.0))
                    .andExpect(jsonPath("$.latest.rr").value(16))
                    .andExpect(jsonPath("$.latest.ewsScore").value(0))
                    .andExpect(jsonPath("$.latest.ewsRisk").value("LOW"))
                    .andExpect(jsonPath("$.latest.recordedAt").value("2026-11-11T04:55:00Z"))
                    .andExpect(jsonPath("$.latest.recordedByName").value("Sr. Anjali Pawar"))
                    .andExpect(jsonPath("$.recentNotes[0].text").value("Slept well"))
                    .andExpect(jsonPath("$.recentNotes[0].recordedByName").value("Sr. Anjali Pawar"));
            verify(service).sheet(TENANT_ID, USER_ID, role, ADMISSION_ID);
        }
    }

    @Test
    void theSheetShowsTheReadOnlyReasonOnlyWhenThereIsOne() throws Exception {
        when(service.sheet(any(), any(), eq("DOCTOR"), any())).thenReturn(sheet(false, "ROLE", null));

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/nursing").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canRecord").value(false))
                .andExpect(jsonPath("$.readOnlyReason").value("ROLE"))
                .andExpect(jsonPath("$.latest").value(Matchers.nullValue()));
    }

    @Test
    void everyOtherRoleGets403OnTheSheetWithoutReachingTheService() throws Exception {
        for (String role : NO_VIEW_ROLES) {
            mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/nursing").with(as(role)))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).sheet(any(), any(), any(), any());
    }

    @Test
    void theSheetAnswers404ForAnUnknownAdmissionAnd403ModuleOffWhenIpdIsOff() throws Exception {
        UUID missing = UUID.randomUUID();
        when(service.sheet(any(), any(), any(), eq(missing))).thenThrow(new NotFoundException("Admission not found"));
        when(service.sheet(any(), any(), any(), eq(ADMISSION_ID)))
                .thenThrow(new ModuleOffException("In-patients (IPD) is not switched on for your clinic."));

        mockMvc.perform(get(URL + "/admissions/" + missing + "/nursing").with(as("NURSE")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/nursing").with(as("NURSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void aMalformedAdmissionIdAnswers400NotAServerError() throws Exception {
        mockMvc.perform(get(URL + "/admissions/not-a-uuid/nursing").with(as("NURSE")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL + "/admissions/not-a-uuid/vitals").with(as("NURSE")).contentType(JSON)
                .content(body())).andExpect(status().isBadRequest());
        verify(service, never()).sheet(any(), any(), any(), any());
        verify(service, never()).record(any(), any(), any(), any(), any());
    }

    // ---- POST ----

    @Test
    void aSaveAnswers201WithTheSheetFieldsFlatPlusSavedAndEscalation() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenReturn(saved(false, Outcome.RAISED, open()));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.patientName").value("Test Patient"))
                .andExpect(jsonPath("$.canRecord").value(true))
                .andExpect(jsonPath("$.latest.tempC").value(37.0))
                .andExpect(jsonPath("$.recentNotes.length()").value(1))
                .andExpect(jsonPath("$.saved.vitalsId").value(VITALS_ID.toString()))
                .andExpect(jsonPath("$.saved.ewsScore").value(5))
                .andExpect(jsonPath("$.saved.ewsRisk").value("MEDIUM"))
                .andExpect(jsonPath("$.saved.recordedAt").value("2026-11-11T05:30:00Z"))
                .andExpect(jsonPath("$.saved.noteAdded").value(true))
                .andExpect(jsonPath("$.saved.replayed").value(false))
                .andExpect(jsonPath("$.escalation.outcome").value("RAISED"))
                .andExpect(jsonPath("$.escalation.open.id").value(ESCALATION_ID.toString()))
                .andExpect(jsonPath("$.escalation.open.ewsScore").value(5))
                .andExpect(jsonPath("$.escalation.open.ewsRisk").value("MEDIUM"))
                .andExpect(jsonPath("$.escalation.open.targetMinutes").value(60))
                .andExpect(jsonPath("$.escalation.open.status").value("OPEN"))
                .andExpect(jsonPath("$.escalation.open.raisedAt").value("2026-11-11T05:30:00Z"));
    }

    @Test
    void aRepeatOfTheClientKeyAnswers200() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenReturn(saved(true, Outcome.NONE, open()));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("OWNER")).contentType(JSON)
                .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved.replayed").value(true))
                .andExpect(jsonPath("$.escalation.outcome").value("NONE"));
    }

    @Test
    void withNoOpenEscalationTheOpenFieldIsPresentAndNull() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenReturn(saved(false, Outcome.NONE, null));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.escalation.open").value(Matchers.nullValue()));
    }

    @Test
    void theBodyIsPassedAsTypedWithTheCallersTenantUserAndRole() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenReturn(saved(false, Outcome.NONE, null));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body())).andExpect(status().isCreated());

        ArgumentCaptor<VitalsInput> sent = ArgumentCaptor.forClass(VitalsInput.class);
        verify(service).record(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), eq(ADMISSION_ID), sent.capture());
        VitalsInput input = sent.getValue();
        assertThat(input.clientKey()).isEqualTo(KEY.toString());
        assertThat(input.bp()).isEqualTo("120/80");
        assertThat(input.pulse()).isEqualTo("80");
        assertThat(input.spo2()).isEqualTo("98");
        assertThat(input.temp()).isEqualTo("37.0");
        assertThat(input.rr()).isEqualTo("16");
        assertThat(input.note()).isEqualTo("Slept well");
    }

    @Test
    void aClientCannotChooseTheTenantOrAScoreInTheBody() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenReturn(saved(false, Outcome.NONE, null));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content("{\"clientKey\":\"" + KEY + "\",\"bp\":\"120/80\",\"pulse\":\"80\",\"spo2\":\"98\","
                        + "\"temp\":\"37.0\",\"rr\":\"16\",\"tenantId\":\"" + UUID.randomUUID()
                        + "\",\"ewsScore\":0,\"ewsRisk\":\"LOW\"}"))
                .andExpect(status().isCreated());

        verify(service).record(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), eq(ADMISSION_ID), any());
    }

    @Test
    void onlyOwnerAndNurseMaySaveEveryOtherRoleGets403() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenReturn(saved(false, Outcome.NONE, null));

        for (String role : RECORD_ROLES) {
            mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as(role)).contentType(JSON)
                    .content(body())).andExpect(status().isCreated());
        }
        for (String role : NO_RECORD_ROLES) {
            mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as(role)).contentType(JSON)
                    .content(body())).andExpect(status().isForbidden());
        }
        verify(service).record(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(ADMISSION_ID), any());
        verify(service).record(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), eq(ADMISSION_ID), any());
    }

    @Test
    void validationAnswers400WithEveryFieldErrorInOrder() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("bp", "Enter all five vitals. BP as 120/80."),
                new FieldMessage("temp", "Check the temperature. Enter 25.0 to 45.0 \u00b0C."),
                new FieldMessage("note", "Keep the note to 1,000 characters."))));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("validation")))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("bp"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter all five vitals. BP as 120/80."))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("temp"))
                .andExpect(jsonPath("$.fieldErrors[2].field").value("note"));
    }

    @Test
    void theDetailOfSeveralFieldErrorsIsTheFirstMessageWhenTheRulesAskForIt() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("bp", "Enter all five vitals. BP as 120/80."),
                new FieldMessage("temp", "Check the temperature. Enter 25.0 to 45.0 °C.")), true));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter all five vitals. BP as 120/80."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    void otherFieldErrorsKeepTheGenericDetail() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("bp", "Enter all five vitals. BP as 120/80."),
                new FieldMessage("temp", "Check the temperature. Enter 25.0 to 45.0 °C."))));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Some fields are missing or not valid."));
    }

    @Test
    void aMissingClientKeyAnswers400OnClientKey() throws Exception {
        when(service.record(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidInputException("clientKey", "Reload the page and try again."));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("clientKey"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Reload the page and try again."));
    }

    @Test
    void notAdmittedAndReadOnlyAnswer409WithTheirOwnProblemTypes() throws Exception {
        when(service.record(any(), any(), any(), eq(ADMISSION_ID), any())).thenThrow(new ConflictException(
                "not-admitted", "Patient is not admitted",
                "This patient is not admitted any more. Vitals cannot be saved."));
        UUID other = UUID.randomUUID();
        when(service.record(any(), any(), any(), eq(other), any())).thenThrow(
                new ConflictException("ipd-readonly", "IPD is read-only", "IPD is read-only. Nothing can be changed."));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("not-admitted")))
                .andExpect(jsonPath("$.detail").value("This patient is not admitted any more. Vitals cannot be saved."));
        mockMvc.perform(post(URL + "/admissions/" + other + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("ipd-readonly")));
    }

    @Test
    void anUnknownAdmissionAnswers404AndModuleOffAnswers403() throws Exception {
        UUID missing = UUID.randomUUID();
        when(service.record(any(), any(), any(), eq(missing), any()))
                .thenThrow(new NotFoundException("Admission not found"));
        when(service.record(any(), any(), any(), eq(ADMISSION_ID), any()))
                .thenThrow(new ModuleOffException("In-patients (IPD) is not switched on for your clinic."));

        mockMvc.perform(post(URL + "/admissions/" + missing + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body())).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    @Test
    void anUnexpectedFailureIsAGenericProblemThatNeverEchoesTheVitals() throws Exception {
        when(service.record(any(), any(), any(), any(), any())).thenThrow(
                new IllegalStateException("The escalation could not be stored"));

        String response = mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE"))
                .contentType(JSON).content(body())).andExpect(status().isInternalServerError()).andReturn()
                .getResponse().getContentAsString();

        assertThat(response).doesNotContain("Slept well").doesNotContain("120/80").doesNotContain("escalation");
    }

    @Test
    void aBodyThatIsNotJsonAnswers400() throws Exception {
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/vitals").with(as("NURSE")).contentType(JSON)
                .content("{not json")).andExpect(status().isBadRequest());
        verify(service, never()).record(any(), any(), any(), any(), any());
    }
}
