package com.sahaj.ipd.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.ipd.application.MedicineOrderService;
import com.sahaj.ipd.application.MedicineViews.ActionOutcome;
import com.sahaj.ipd.application.MedicineViews.DrugOption;
import com.sahaj.ipd.application.MedicineViews.EmrPatient;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import com.sahaj.ipd.application.MedicineViews.OrderCheckView;
import com.sahaj.ipd.application.MedicineViews.OrderFinding;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.PharmacistCheckRow;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecksSummary;
import com.sahaj.ipd.application.MedicineViews.RejectOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.application.PharmacistCheckService;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
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
 * SAH-31 the seven endpoints: contract field names, status codes (201 for a placed order, 200 for the outcome
 * answers), the problem types and texts of refusals, and that the user and the role come from the token.
 */
@WebMvcTest(controllers = MedicineOrderController.class)
@Import(SecurityConfig.class)
class MedicineOrderControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");
    private static final String TOKEN = "Qm9yZGVyLWNoZWNrLXRva2";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MedicineOrderService orders;

    @MockBean
    private PharmacistCheckService pharmacist;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static EmrPatient patient() {
        return new EmrPatient(PATIENT_ID, "Test Patient", "UH-000123", 54, "F", "202", "Penicillin (rash)",
                "RECORDED");
    }

    private static MedicineOrderRow orderRow() {
        return new MedicineOrderRow(ORDER_ID, "asp", "Tab. Aspirin 75 mg", "Oral", null, "od", "Once daily",
                "PENDING", "Dr Meera Joshi", NOW, new KeyLabel("CARDIOLOGY_STENT", "Recent coronary stent, cardiology advised"),
                null, null, null, null, null, null, null, true, null);
    }

    private static EmrSheet sheet() {
        return new EmrSheet(ADMISSION_ID, "ADMITTED", "on", NOW, patient(), "Dr Meera Joshi", "Chest infection", 3,
                List.of(orderRow()), true, null, List.of(new DrugOption("hep", "Inj. Heparin 5,000 IU", "SC",
                        "Anticoagulant")), List.of(new KeyLabel("od", "Once daily")),
                List.of(new KeyLabel("BENEFIT_MONITORED", "Benefit outweighs the risk, monitoring in place")));
    }

    private static OrderCheckView check() {
        return new OrderCheckView("RECORDED", List.of(),
                List.of(new OrderFinding("INTERACTION", "MAJOR", "hep", "Bleeding risk")), TOKEN);
    }

    private static PharmacistChecks checks() {
        PharmacistCheckRow row = new PharmacistCheckRow(ORDER_ID, ADMISSION_ID, "asp", "Tab. Aspirin 75 mg", null,
                "od", "Once daily", patient(), "Dr Meera Joshi", NOW, null, check(), true, null, true);
        return new PharmacistChecks("on", NOW, 1, "Ph. Ravi Kulkarni",
                List.of(new KeyLabel("ALLERGY_CONFLICT", "Allergy conflict")), List.of(row), "cursor");
    }

    private static String orderBody() {
        return "{\"clientKey\":\"3f8e2a8c-5b0e-4f7e-9d43-0c6a0b6f2a11\",\"drugKey\":\"asp\",\"frequencyKey\":\"od\"}";
    }

    // ---- EMR ----

    @Test
    void theEmrSheetHasTheContractShape() throws Exception {
        when(orders.emr(TENANT_ID, USER_ID, "DOCTOR", ADMISSION_ID)).thenReturn(sheet());

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/emr").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.state").value("on"))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.patient.patientId").value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$.patient.name").value("Test Patient"))
                .andExpect(jsonPath("$.patient.uhid").value("UH-000123"))
                .andExpect(jsonPath("$.patient.age").value(54))
                .andExpect(jsonPath("$.patient.sex").value("F"))
                .andExpect(jsonPath("$.patient.bedCode").value("202"))
                .andExpect(jsonPath("$.patient.allergyText").value("Penicillin (rash)"))
                .andExpect(jsonPath("$.patient.allergyStatus").value("RECORDED"))
                .andExpect(jsonPath("$.admittingDoctorName").value("Dr Meera Joshi"))
                .andExpect(jsonPath("$.diagnosis").value("Chest infection"))
                .andExpect(jsonPath("$.dayNumber").value(3))
                .andExpect(jsonPath("$.orders[0].id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.orders[0].drugKey").value("asp"))
                .andExpect(jsonPath("$.orders[0].drugName").value("Tab. Aspirin 75 mg"))
                .andExpect(jsonPath("$.orders[0].route").value("Oral"))
                .andExpect(jsonPath("$.orders[0].highAlert").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].frequencyKey").value("od"))
                .andExpect(jsonPath("$.orders[0].frequencyLabel").value("Once daily"))
                .andExpect(jsonPath("$.orders[0].status").value("PENDING"))
                .andExpect(jsonPath("$.orders[0].orderedByName").value("Dr Meera Joshi"))
                .andExpect(jsonPath("$.orders[0].orderedAt").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.orders[0].overrideReason.key").value("CARDIOLOGY_STENT"))
                .andExpect(jsonPath("$.orders[0].overrideReason.label").value("Recent coronary stent, cardiology advised"))
                .andExpect(jsonPath("$.orders[0].verifiedByName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].rejectReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].stoppedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].canStop").value(true))
                .andExpect(jsonPath("$.canOrder").value(true))
                .andExpect(jsonPath("$.orderLockReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.drugs[0].key").value("hep"))
                .andExpect(jsonPath("$.drugs[0].highAlert").value("Anticoagulant"))
                .andExpect(jsonPath("$.frequencies[0].label").value("Once daily"))
                .andExpect(jsonPath("$.overrideReasons[0].key").value("BENEFIT_MONITORED"));
    }

    @Test
    void aRoleWithoutAccessGetsTheServicesForbiddenDetail() throws Exception {
        when(orders.emr(any(), any(), eq("FRONT_DESK"), any()))
                .thenThrow(new ForbiddenException("forbidden", "Not allowed", "Clinical details are hidden for your role."));

        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/emr").with(as("FRONT_DESK")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/forbidden")))
                .andExpect(jsonPath("$.detail").value("Clinical details are hidden for your role."));
    }

    @Test
    void theEmrOfAMissingAdmissionIs404AndModuleOffIs403() throws Exception {
        when(orders.emr(any(), any(), eq("DOCTOR"), any())).thenThrow(new NotFoundException("Admission not found"));
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/emr").with(as("DOCTOR")))
                .andExpect(status().isNotFound());

        when(orders.emr(any(), any(), eq("NURSE"), any())).thenThrow(new ModuleOffException("In-patients is off"));
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/emr").with(as("NURSE")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(Matchers.endsWith("/module-off")));
    }

    @Test
    void signedOutVisitorsGet401OnEveryEndpoint() throws Exception {
        mockMvc.perform(get(URL + "/admissions/" + ADMISSION_ID + "/emr")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").contentType(JSON)
                .content(orderBody())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/stop")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/reject").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/pharmacist-checks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/pharmacist-checks/summary")).andExpect(status().isUnauthorized());
    }

    // ---- place an order ----

    @Test
    void aPlacedOrderIs201WithTheOutcomeTheCheckTheOrderAndTheSheet() throws Exception {
        when(orders.order(eq(TENANT_ID), eq(USER_ID), eq("DOCTOR"), eq(ADMISSION_ID), any(MedicineOrderInput.class)))
                .thenReturn(new MedicineOrderAnswer("PLACED", false, check(), orderRow(), sheet()));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("PLACED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.check.allergyStatus").value("RECORDED"))
                .andExpect(jsonPath("$.check.hardStops.length()").value(0))
                .andExpect(jsonPath("$.check.warnings[0].code").value("INTERACTION"))
                .andExpect(jsonPath("$.check.warnings[0].severity").value("MAJOR"))
                .andExpect(jsonPath("$.check.warnings[0].otherDrugKey").value("hep"))
                .andExpect(jsonPath("$.check.warnings[0].text").value("Bleeding risk"))
                .andExpect(jsonPath("$.check.checkToken").value(TOKEN))
                .andExpect(jsonPath("$.order.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.sheet.admissionId").value(ADMISSION_ID.toString()));
    }

    @Test
    void theBodyIsPassedAsSentAndUnknownPropertiesAreIgnored() throws Exception {
        when(orders.order(any(), any(), any(), any(), any())).thenReturn(
                new MedicineOrderAnswer("NEEDS_REASON", false, check(), null, sheet()));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("OWNER"))
                .contentType(JSON).content("{\"clientKey\":\"k\",\"drugKey\":\"asp\",\"frequencyKey\":\"od\","
                        + "\"overrideReasonKey\":\"CARDIOLOGY_STENT\",\"checkToken\":\"" + TOKEN + "\",\"extra\":1}"))
                .andExpect(status().isOk());

        ArgumentCaptor<MedicineOrderInput> body = ArgumentCaptor.forClass(MedicineOrderInput.class);
        verify(orders).order(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(ADMISSION_ID), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue().clientKey()).isEqualTo("k");
        org.assertj.core.api.Assertions.assertThat(body.getValue().overrideReasonKey()).isEqualTo("CARDIOLOGY_STENT");
        org.assertj.core.api.Assertions.assertThat(body.getValue().checkToken()).isEqualTo(TOKEN);
    }

    @Test
    void blockedAndNeedsReasonAreOkAnswersWithAnOutcomeAndNoOrder() throws Exception {
        when(orders.order(any(), any(), any(), any(), any())).thenReturn(
                new MedicineOrderAnswer("BLOCKED", false, check(), null, sheet()));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BLOCKED"))
                .andExpect(jsonPath("$.order").value(Matchers.nullValue()));
    }

    @Test
    void aReplayOfAPlacedOrderIs200WithReplayedTrue() throws Exception {
        when(orders.order(any(), any(), any(), any(), any())).thenReturn(
                new MedicineOrderAnswer("PLACED", true, check(), orderRow(), sheet()));

        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PLACED"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void theRefusalsOfAnOrderAreProblemDetailsWithTheContractTypes() throws Exception {
        when(orders.order(any(), any(), eq("NURSE"), any(), any()))
                .thenThrow(new ForbiddenException("forbidden", "Not allowed", "Only a doctor can do this."));
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("NURSE"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Only a doctor can do this."));

        when(orders.order(any(), any(), eq("DOCTOR"), any(), any())).thenThrow(
                new ConflictException("client-key-reused", "Client key already used", "This order was already placed"
                        + " with different values. Reload the page and try again."))
                .thenThrow(new ConflictException("not-admitted", "Patient is not admitted", "not admitted"))
                .thenThrow(new InvalidFieldsException(List.of(new FieldMessage("drugKey", "Choose a medicine from the list"),
                        new FieldMessage("frequencyKey", "Choose how often")), true));
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/client-key-reused")));
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/not-admitted")));
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                        .contentType(JSON).content(orderBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/validation")))
                .andExpect(jsonPath("$.detail").value("Choose a medicine from the list"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    void aMalformedBodyIs400() throws Exception {
        mockMvc.perform(post(URL + "/admissions/" + ADMISSION_ID + "/medicine-orders").with(as("DOCTOR"))
                .contentType(JSON).content("{not json")).andExpect(status().isBadRequest());
    }

    // ---- stop ----

    @Test
    void stopAnswersTheSheetWithoutABody() throws Exception {
        when(orders.stop(TENANT_ID, USER_ID, "DOCTOR", ORDER_ID)).thenReturn(sheet());

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/stop").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.orders[0].canStop").value(true));
    }

    @Test
    void stopOfAnOrderInAnotherStateIs409OrderState() throws Exception {
        when(orders.stop(any(), any(), any(), any())).thenThrow(
                new ConflictException("order-state", "Order already stopped", "This order is already stopped."));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/stop").with(as("DOCTOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/order-state")))
                .andExpect(jsonPath("$.detail").value("This order is already stopped."));
    }

    // ---- the pharmacist check ----

    @Test
    void theQueueHasTheContractShape() throws Exception {
        when(pharmacist.list(TENANT_ID, USER_ID, "PHARMACIST", "20", "abc")).thenReturn(checks());

        mockMvc.perform(get(URL + "/pharmacist-checks").param("limit", "20").param("cursor", "abc")
                        .with(as("PHARMACIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("on"))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.waiting").value(1))
                .andExpect(jsonPath("$.verifierName").value("Ph. Ravi Kulkarni"))
                .andExpect(jsonPath("$.rejectReasons[0].key").value("ALLERGY_CONFLICT"))
                .andExpect(jsonPath("$.nextCursor").value("cursor"))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.items[0].admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.items[0].drugName").value("Tab. Aspirin 75 mg"))
                .andExpect(jsonPath("$.items[0].frequencyLabel").value("Once daily"))
                .andExpect(jsonPath("$.items[0].patient.name").value("Test Patient"))
                .andExpect(jsonPath("$.items[0].orderedByName").value("Dr Meera Joshi"))
                .andExpect(jsonPath("$.items[0].overrideReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].check.checkToken").value(TOKEN))
                .andExpect(jsonPath("$.items[0].canVerify").value(true))
                .andExpect(jsonPath("$.items[0].verifyLockReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].canReject").value(true));
    }

    @Test
    void theLimitAndTheCursorAreOptionalAndPassedAsText() throws Exception {
        when(pharmacist.list(TENANT_ID, USER_ID, "OWNER", null, null)).thenReturn(checks());

        mockMvc.perform(get(URL + "/pharmacist-checks").with(as("OWNER"))).andExpect(status().isOk());
        verify(pharmacist).list(TENANT_ID, USER_ID, "OWNER", null, null);
    }

    @Test
    void theSummaryHoldsCountsOnly() throws Exception {
        when(pharmacist.summary(TENANT_ID, USER_ID, "OWNER")).thenReturn(new PharmacistChecksSummary(4, TOKEN, NOW));

        mockMvc.perform(get(URL + "/pharmacist-checks/summary").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting").value(4))
                .andExpect(jsonPath("$.changeToken").value(TOKEN))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.patient").doesNotExist());
    }

    @Test
    void aRoleWithoutAccessToTheQueueGets403WithTheContractDetail() throws Exception {
        when(pharmacist.list(any(), any(), eq("DOCTOR"), any(), any())).thenThrow(new ForbiddenException("forbidden",
                "Not allowed", "Only the pharmacist or the clinic owner can check orders."));

        mockMvc.perform(get(URL + "/pharmacist-checks").with(as("DOCTOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only the pharmacist or the clinic owner can check orders."));
    }

    // ---- verify ----

    private static PharmacistActionResult verified() {
        return new PharmacistActionResult(new ActionOutcome(ORDER_ID, "Tab. Aspirin 75 mg", "VERIFIED", false,
                "Ph. Ravi Kulkarni", null), checks());
    }

    @Test
    void verifyAnswersTheResultAndTheFirstPageOfTheQueue() throws Exception {
        when(pharmacist.verify(eq(TENANT_ID), eq(USER_ID), eq("PHARMACIST"), eq(ORDER_ID),
                any(VerifyOrderInput.class))).thenReturn(verified());

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content("{\"pin\":\"0482\",\"checkToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.result.drugName").value("Tab. Aspirin 75 mg"))
                .andExpect(jsonPath("$.result.outcome").value("VERIFIED"))
                .andExpect(jsonPath("$.result.replayed").value(false))
                .andExpect(jsonPath("$.result.actorName").value("Ph. Ravi Kulkarni"))
                .andExpect(jsonPath("$.result.rejectReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.checks.waiting").value(1));

        ArgumentCaptor<VerifyOrderInput> body = ArgumentCaptor.forClass(VerifyOrderInput.class);
        verify(pharmacist).verify(any(), any(), any(), any(), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue().pin()).isEqualTo("0482");
    }

    @Test
    void thePinProblemsKeepTheirTypesStatusesAndTexts() throws Exception {
        when(pharmacist.verify(any(), any(), any(), any(), any()))
                .thenThrow(StaffPinException.of(new PinCheck(PinCheck.Outcome.WRONG, 2, null, 0)))
                .thenThrow(StaffPinException.of(new PinCheck(PinCheck.Outcome.NOT_SET, 0, null, 0)))
                .thenThrow(StaffPinException.rateLimited(120))
                .thenThrow(StaffPinException.unavailable())
                .thenThrow(new ForbiddenException("self-verification", "You cannot verify your own order",
                        "The doctor who ordered this cannot verify it. Ask the pharmacist."));
        String body = "{\"pin\":\"0482\",\"checkToken\":\"" + TOKEN + "\"}";

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/wrong-pin")))
                .andExpect(jsonPath("$.detail").value("That PIN is not right. 2 tries left."));
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/pin-not-set")));
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/rate-limit")))
                .andExpect(jsonPath("$.retryAfterSeconds").value(120));
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/pin-unavailable")));
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("OWNER"))
                        .contentType(JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/self-verification")));
    }

    @Test
    void theRefusalsOfVerifyAre409WithTheContractTypes() throws Exception {
        when(pharmacist.verify(any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("order-blocked", "Order is blocked", "Hard stop on this order. Reject it."))
                .thenThrow(new ConflictException("order-checks-changed", "The checks changed",
                        "The checks on this order changed. Review them and try again."));
        String body = "{\"pin\":\"0482\",\"checkToken\":\"" + TOKEN + "\"}";

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/order-blocked")));
        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/verify").with(as("PHARMACIST"))
                        .contentType(JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/order-checks-changed")));
    }

    // ---- reject ----

    @Test
    void rejectAnswersTheResultWithTheReasonLabel() throws Exception {
        when(orders.reject(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(ORDER_ID), any(RejectOrderInput.class)))
                .thenReturn(new PharmacistActionResult(new ActionOutcome(ORDER_ID, "Tab. Aspirin 75 mg", "REJECTED",
                        false, "Ph. Ravi Kulkarni", new KeyLabel("INTERACTION_RISK", "Interaction risk too high")),
                        checks()));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/reject").with(as("OWNER"))
                        .contentType(JSON).content("{\"reasonKey\":\"INTERACTION_RISK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.result.rejectReason.key").value("INTERACTION_RISK"))
                .andExpect(jsonPath("$.result.rejectReason.label").value("Interaction risk too high"))
                .andExpect(jsonPath("$.checks.items[0].orderId").value(ORDER_ID.toString()));
    }

    @Test
    void rejectWithoutAReasonIs400OnTheReasonKeyField() throws Exception {
        when(orders.reject(any(), any(), any(), any(), any())).thenThrow(
                new InvalidFieldsException(List.of(new FieldMessage("reasonKey", "Choose a reason")), true));

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/reject").with(as("PHARMACIST"))
                        .contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reasonKey"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Choose a reason"));
    }

    @Test
    void theClinicAndTheUserComeFromTheTokenNeverFromTheBody() throws Exception {
        when(orders.stop(any(), any(), any(), any())).thenReturn(sheet());

        mockMvc.perform(post(URL + "/medicine-orders/" + ORDER_ID + "/stop").with(as("DOCTOR"))
                .contentType(JSON).content("{\"tenantId\":\"" + UUID.randomUUID() + "\",\"userId\":\""
                        + UUID.randomUUID() + "\"}")).andExpect(status().isOk());

        verify(orders).stop(TENANT_ID, USER_ID, "DOCTOR", ORDER_ID);
    }
}
