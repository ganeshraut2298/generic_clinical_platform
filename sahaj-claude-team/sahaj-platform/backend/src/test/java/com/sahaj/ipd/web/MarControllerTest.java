package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.DoseService;
import com.sahaj.ipd.application.DoseService.DoseResult;
import com.sahaj.ipd.application.MarService;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MarViews.MarOrderView;
import com.sahaj.ipd.application.MarViews.SecondNurseOption;
import com.sahaj.ipd.application.MarViews.SlotView;
import com.sahaj.ipd.application.MedicineViews.EmrPatient;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/**
 * SAH-32 the two endpoints: contract field names, 201 for a new record and 200 for a replay, the problem types and
 * texts of refusals (including the second nurse's PIN answers), and that the clinic, the person and the role come from
 * the token and the body is handed to the service as sent.
 */
@WebMvcTest(controllers = MarController.class)
@Import(SecurityConfig.class)
class MarControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOSE_ID = UUID.randomUUID();
    private static final UUID NURSE_MEMBERSHIP = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final Instant NOW = Instant.parse("2026-11-11T05:35:00Z");
    private static final Instant SLOT = Instant.parse("2026-11-11T02:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MarService mar;

    @MockBean
    private DoseService doses;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static DoseView dose() {
        return new DoseView(DOSE_ID, SLOT, "GIVEN", null, SLOT.plusSeconds(240), "Sr. Anjali Pawar", "Sr. Rupali More");
    }

    private static Mar marView() {
        SlotView given = new SlotView(SLOT, "GIVEN", null, false, dose());
        SlotView later = new SlotView(Instant.parse("2026-11-11T14:30:00Z"), "LATER", null, false, null);
        SlotView late = new SlotView(Instant.parse("2026-11-11T00:30:00Z"), "LATE", 5, true, null);
        MarOrderView hep = new MarOrderView(ORDER_ID, "hep", "Inj. Heparin 5,000 IU", "SC", "Anticoagulant", "bd",
                "Twice daily", "Dr Meera Joshi", "ACTIVE", List.of(given, later));
        MarOrderView pcm = new MarOrderView(UUID.randomUUID(), "pcm", "Tab. Paracetamol 650 mg", "Oral", null, "od",
                "Once daily", "Dr Meera Joshi", "ACTIVE", List.of(late));
        return new Mar(ADMISSION_ID, "ADMITTED", "on", NOW, new EmrPatient(PATIENT_ID, "Test Patient", "UH-000123", 54,
                "F", "202", "None known", "NONE_KNOWN"), 1, true, null, List.of(hep, pcm),
                List.of(new SecondNurseOption(NURSE_MEMBERSHIP, "Sr. Rupali More")),
                List.of(new KeyLabel("PATIENT_REFUSED", "Patient refused")));
    }

    private static DoseResult result(boolean created) {
        return new DoseResult(new DoseRecorded(!created, dose(), marView()), created);
    }

    private static final String GIVEN_BODY = "{\"clientKey\":\"2b4f6a8c-1d3e-4f50-9a7b-3c5d7e9f1a2b\","
            + "\"slotAt\":\"2026-11-11T02:30:00Z\",\"outcome\":\"GIVEN\",\"wristband\":\"UH-000123\","
            + "\"identityConfirmed\":true,\"secondNurseId\":\"5e2f8a1b-9c3d-4e7f-8a6b-1c2d3e4f5a6b\","
            + "\"secondNursePin\":\"0482\",\"unknown\":\"ignored\"}";

    // ---- GET /mar ----

    @Test
    void theMarHasTheContractShape() throws Exception {
        when(mar.mar(TENANT_ID, USER_ID, "NURSE", ADMISSION_ID)).thenReturn(marView());

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/mar").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.state").value("on"))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T05:35:00Z"))
                .andExpect(jsonPath("$.patient.patientId").value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$.patient.name").value("Test Patient"))
                .andExpect(jsonPath("$.patient.uhid").value("UH-000123"))
                .andExpect(jsonPath("$.patient.bedCode").value("202"))
                .andExpect(jsonPath("$.patient.allergyStatus").value("NONE_KNOWN"))
                .andExpect(jsonPath("$.doseCount").value(1))
                .andExpect(jsonPath("$.canGive").value(true))
                .andExpect(jsonPath("$.giveLockReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.orders[0].drugKey").value("hep"))
                .andExpect(jsonPath("$.orders[0].drugName").value("Inj. Heparin 5,000 IU"))
                .andExpect(jsonPath("$.orders[0].route").value("SC"))
                .andExpect(jsonPath("$.orders[0].highAlert").value("Anticoagulant"))
                .andExpect(jsonPath("$.orders[0].frequencyKey").value("bd"))
                .andExpect(jsonPath("$.orders[0].frequencyLabel").value("Twice daily"))
                .andExpect(jsonPath("$.orders[0].orderedByName").value("Dr Meera Joshi"))
                .andExpect(jsonPath("$.orders[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.orders[0].slots[0].slotAt").value("2026-11-11T02:30:00Z"))
                .andExpect(jsonPath("$.orders[0].slots[0].state").value("GIVEN"))
                .andExpect(jsonPath("$.orders[0].slots[0].lateHours").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].slots[0].canGive").value(false))
                .andExpect(jsonPath("$.orders[0].slots[0].dose.id").value(DOSE_ID.toString()))
                .andExpect(jsonPath("$.orders[0].slots[0].dose.outcome").value("GIVEN"))
                .andExpect(jsonPath("$.orders[0].slots[0].dose.reason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].slots[0].dose.recordedAt").value("2026-11-11T02:34:00Z"))
                .andExpect(jsonPath("$.orders[0].slots[0].dose.recordedByName").value("Sr. Anjali Pawar"))
                .andExpect(jsonPath("$.orders[0].slots[0].dose.secondCheckByName").value("Sr. Rupali More"))
                .andExpect(jsonPath("$.orders[0].slots[1].state").value("LATER"))
                .andExpect(jsonPath("$.orders[0].slots[1].dose").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[1].slots[0].state").value("LATE"))
                .andExpect(jsonPath("$.orders[1].slots[0].lateHours").value(5))
                .andExpect(jsonPath("$.orders[1].slots[0].canGive").value(true))
                .andExpect(jsonPath("$.orders[1].highAlert").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.secondNurses[0].membershipId").value(NURSE_MEMBERSHIP.toString()))
                .andExpect(jsonPath("$.secondNurses[0].name").value("Sr. Rupali More"))
                .andExpect(jsonPath("$.notGivenReasons[0].key").value("PATIENT_REFUSED"))
                .andExpect(jsonPath("$.notGivenReasons[0].label").value("Patient refused"));
    }

    @Test
    void theUserTheClinicAndTheRoleComeFromTheToken() throws Exception {
        when(mar.mar(TENANT_ID, USER_ID, "DOCTOR", ADMISSION_ID)).thenReturn(marView());

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/mar").with(as("DOCTOR")))
                .andExpect(status().isOk());

        verify(mar).mar(TENANT_ID, USER_ID, "DOCTOR", ADMISSION_ID);
    }

    @Test
    void aRefusedRoleGetsTheServicesForbiddenDetail() throws Exception {
        when(mar.mar(any(), any(), eq("FRONT_DESK"), any())).thenThrow(new ForbiddenException("forbidden", "Not allowed",
                "You cannot open the medicine round."));

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/mar").with(as("FRONT_DESK")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/forbidden")))
                .andExpect(jsonPath("$.detail").value("You cannot open the medicine round."));
    }

    @Test
    void aMissingAdmissionIs404AndModuleOffIs403() throws Exception {
        when(mar.mar(any(), any(), eq("NURSE"), any())).thenThrow(new NotFoundException("Admission not found"));
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/mar").with(as("NURSE")))
                .andExpect(status().isNotFound());

        when(mar.mar(any(), any(), eq("OWNER"), any())).thenThrow(new ModuleOffException("In-patients is off"));
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/mar").with(as("OWNER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(Matchers.endsWith("/module-off")));
    }

    @Test
    void signedOutVisitorsGet401() throws Exception {
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/mar")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").contentType(JSON).content(GIVEN_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ---- POST /doses ----

    @Test
    void aNewRecordAnswers201WithTheDoseAndTheMar() throws Exception {
        when(doses.record(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), eq(ORDER_ID), any())).thenReturn(result(true));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.dose.id").value(DOSE_ID.toString()))
                .andExpect(jsonPath("$.dose.slotAt").value("2026-11-11T02:30:00Z"))
                .andExpect(jsonPath("$.dose.outcome").value("GIVEN"))
                .andExpect(jsonPath("$.mar.admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.mar.orders[0].slots[0].state").value("GIVEN"));
    }

    @Test
    void aReplayAnswers200WithReplayedTrue() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenReturn(result(false));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void theBodyIsHandedToTheServiceAsSentAndUnknownPropertiesAreIgnored() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenReturn(result(true));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("OWNER")).contentType(JSON)
                .content(GIVEN_BODY)).andExpect(status().isCreated());

        ArgumentCaptor<DoseInput> body = ArgumentCaptor.forClass(DoseInput.class);
        verify(doses).record(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(ORDER_ID), body.capture());
        DoseInput sent = body.getValue();
        org.assertj.core.api.Assertions.assertThat(sent.clientKey()).isEqualTo("2b4f6a8c-1d3e-4f50-9a7b-3c5d7e9f1a2b");
        org.assertj.core.api.Assertions.assertThat(sent.slotAt()).isEqualTo("2026-11-11T02:30:00Z");
        org.assertj.core.api.Assertions.assertThat(sent.outcome()).isEqualTo("GIVEN");
        org.assertj.core.api.Assertions.assertThat(sent.wristband()).isEqualTo("UH-000123");
        org.assertj.core.api.Assertions.assertThat(sent.identityConfirmed()).isEqualTo(Boolean.TRUE);
        org.assertj.core.api.Assertions.assertThat(sent.secondNurseId()).isEqualTo("5e2f8a1b-9c3d-4e7f-8a6b-1c2d3e4f5a6b");
        org.assertj.core.api.Assertions.assertThat(sent.secondNursePin()).isEqualTo("0482");
    }

    @Test
    void aTickSentAsTextIsNotTrueAndAPinSentAsANumberKeepsItsDigits() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenReturn(result(true));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content("{\"clientKey\":\"k\",\"slotAt\":\"s\",\"outcome\":\"GIVEN\",\"identityConfirmed\":\"yes\","
                        + "\"secondNursePin\":4821}")).andExpect(status().isCreated());

        ArgumentCaptor<DoseInput> body = ArgumentCaptor.forClass(DoseInput.class);
        verify(doses).record(any(), any(), any(), any(), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue().identityConfirmed()).isEqualTo("yes");
        org.assertj.core.api.Assertions.assertThat(body.getValue().secondNursePin()).isEqualTo("4821");
    }

    @Test
    void aFormatErrorAnswers400WithTheFieldErrorsAndTheFirstMessageAsDetail() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("clientKey", "Reload the page and try again."),
                new FieldMessage("slotAt", "Reload the page and try again.")), true));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/validation")))
                .andExpect(jsonPath("$.detail").value("Reload the page and try again."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("clientKey"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("slotAt"));
    }

    @Test
    void aBedsideMessageAnswers400OnItsField() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenThrow(new InvalidInputException("wristband",
                "Scan the wristband first."));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Scan the wristband first."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("wristband"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Scan the wristband first."));
    }

    @Test
    void aWristbandMismatchAnswers409WithNoName() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenThrow(new ConflictException("wristband-mismatch",
                "Wristband does not match",
                "Wristband does not match this patient. Stop. Check the patient before giving anything."));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/wristband-mismatch"))
                .andExpect(jsonPath("$.detail").value(
                        "Wristband does not match this patient. Stop. Check the patient before giving anything."));
    }

    @Test
    void theStateConflictsKeepTheirProblemTypes() throws Exception {
        for (String type : new String[] {"dose-recorded", "order-not-verified", "order-state", "dose-not-due",
                "dose-before-start", "not-admitted", "ipd-readonly", "client-key-reused"}) {
            doThrow(new ConflictException(type, "t", "d")).when(doses).record(any(), any(), any(), any(), any());

            mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                    .content(GIVEN_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/" + type));
        }
    }

    @Test
    void aRefusedRoleOnTheDoseEndpointGetsTheGiveText() throws Exception {
        when(doses.record(any(), any(), eq("DOCTOR"), any(), any())).thenThrow(new ForbiddenException("forbidden",
                "Not allowed", "Only nurses and the clinic owner can give medicines."));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("DOCTOR")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/forbidden")))
                .andExpect(jsonPath("$.detail").value("Only nurses and the clinic owner can give medicines."));
    }

    @Test
    void theSecondNurseSPinAnswersAreTheColleagueTexts() throws Exception {
        doThrow(StaffPinException.of(new PinCheck(PinCheck.Outcome.WRONG, 2, null, 0),
                        StaffPinException.Subject.COLLEAGUE)).when(doses).record(any(), any(), any(), any(), any());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/wrong-pin"))
                .andExpect(jsonPath("$.detail").value("That PIN is not right."));

        doThrow(StaffPinException.of(new PinCheck(PinCheck.Outcome.RATE_LIMITED, 0, null, 600),
                        StaffPinException.Subject.COLLEAGUE)).when(doses).record(any(), any(), any(), any(), any());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(600));

        doThrow(StaffPinException.of(new PinCheck(PinCheck.Outcome.NOT_SET, 0, null, 0),
                        StaffPinException.Subject.COLLEAGUE)).when(doses).record(any(), any(), any(), any(), any());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/pin-not-set"))
                .andExpect(jsonPath("$.detail").value("The second nurse has not set a PIN yet. Choose another nurse."));
    }

    @Test
    void aBodyThatIsNotJsonIs400() throws Exception {
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content("not json")).andExpect(status().isBadRequest());
    }

    @Test
    void anOrderIdThatIsNotAUuidIs400() throws Exception {
        mockMvc.perform(post(URL + "/medicine-orders/nope/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY)).andExpect(status().isBadRequest());
    }

    @Test
    void anUnexpectedFaultIsAGenericProblemWithNoDetailOfTheDatabase() throws Exception {
        when(doses.record(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException(
                "The dose could not be stored", new RuntimeException("ck_dose_administration_x UH-000123")));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/doses").with(as("NURSE")).contentType(JSON)
                .content(GIVEN_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("UH-000123"))))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("ck_dose"))));
    }
}
