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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.ChemoCheckService;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ActionInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.CheckerInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ConfirmInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.HoldInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.OrderInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.PinInput;
import com.sahaj.opd.application.emr.specialty.CancerJourneyService;
import com.sahaj.opd.application.emr.specialty.ChemoOrderService;
import com.sahaj.opd.application.emr.specialty.ChemoSecondCheckService;
import com.sahaj.opd.application.emr.specialty.SpecialtyHistoryService;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceService;
import com.sahaj.opd.application.emr.specialty.SurgeryBookingService;
import com.sahaj.opd.application.emr.specialty.VisitTemplateService;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.support.TestTenantSecurity;
import com.sahaj.platform.web.security.SecurityConfig;
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

/**
 * The specialty and chemotherapy endpoints of docs/api/opd.yaml 0.5.0: who may call them, the closed bodies, the clinic
 * taken from the signed-in user, the problem types, and the PIN that is never echoed.
 */
@WebMvcTest(controllers = {EmrChemoController.class, EmrSpecialtyController.class, EmrReadOnlyPathsController.class})
@Import(SecurityConfig.class)
class EmrSpecialtyControllersTest {

    private static final String EMR = "/api/v1/opd/emr";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID CHECKER = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String KEY = "key-0000001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private ChemoOrderService orders;
    @MockBean
    private ChemoCheckService checks;
    @MockBean
    private ChemoSecondCheckService secondCheck;
    @MockBean
    private SpecialtyReferenceService reference;
    @MockBean
    private SpecialtyHistoryService history;
    @MockBean
    private SurgeryBookingService booking;
    @MockBean
    private VisitTemplateService templates;
    @MockBean
    private CancerJourneyService journey;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT, USER, role);
    }

    private ObjectNode node(String text) {
        try {
            return (ObjectNode) mapper.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String ORDER_BODY = "{\"revision\":0,\"protocolKey\":\"AC\",\"levelPct\":100,"
            + "\"finalDoses\":[{\"drugKey\":\"doxorubicin\",\"finalMg\":95}],\"allergyConfirmed\":true,"
            + "\"consentNewSigned\":true,\"scheme\":\"APPROVED\"}";

    @Test
    void aSignedOutVisitorGets401OnEveryOperation() throws Exception {
        mockMvc.perform(get(EMR + "/records/" + RECORD + "/chemo-order")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(EMR + "/records/" + RECORD + "/chemo-order").contentType(JSON).content(ORDER_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/chemo-checks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/specialty-reference/onco")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMR + "/visit-templates")).andExpect(status().isUnauthorized());
        verifyNoInteractions(orders, checks, secondCheck, reference, history, booking, templates);
    }

    @Test
    void theOrderSheetIsForDoctorsAndTheOwnerOnly() throws Exception {
        when(orders.get(any(ClinicUser.class), eq(RECORD))).thenReturn(node("{'status':'NONE'}"));
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR"}) {
            mockMvc.perform(get(EMR + "/records/" + RECORD + "/chemo-order").with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NONE"));
        }
        for (String role : new String[] {"NURSE", "PHARMACIST", "FRONT_DESK", "BILLING", "COUNSELLOR"}) {
            mockMvc.perform(get(EMR + "/records/" + RECORD + "/chemo-order").with(as(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(EMR + "/records/" + RECORD + "/chemo-order").with(as(role)).contentType(JSON)
                    .content(ORDER_BODY)).andExpect(status().isForbidden());
            mockMvc.perform(post(EMR + "/records/" + RECORD + "/chemo-order/send").with(as(role)).contentType(JSON)
                    .content("{\"revision\":0,\"fingerprint\":\"" + FINGERPRINT + "\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(EMR + "/records/" + RECORD + "/specialty-history").with(as(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theCheckerListAndConfirmationAreForTheFiveCheckerRolesOnly() throws Exception {
        when(checks.list(any(ClinicUser.class), eq(null))).thenReturn(node("{'items':[],'total':0,'page':0,'pageSize':20}"));
        when(checks.summary(any(ClinicUser.class), eq(ORDER))).thenReturn(node("{'orderId':'" + ORDER + "'}"));
        when(checks.confirm(any(ClinicUser.class), eq(ORDER), any(ConfirmInput.class)))
                .thenReturn(node("{'orderId':'" + ORDER + "','canConfirm':false}"));
        for (String role : new String[] {"NURSE", "PHARMACIST", "DOCTOR", "VISITING_DOCTOR", "OWNER"}) {
            mockMvc.perform(get(EMR + "/chemo-checks").with(as(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageSize").value(20));
            mockMvc.perform(get(EMR + "/chemo-checks/" + ORDER).with(as(role))).andExpect(status().isOk());
            mockMvc.perform(post(EMR + "/chemo-checks/" + ORDER + "/confirm").with(as(role)).contentType(JSON)
                    .content("{\"fingerprint\":\"" + FINGERPRINT + "\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.canConfirm").value(false));
        }
        for (String role : new String[] {"FRONT_DESK", "BILLING", "COUNSELLOR"}) {
            mockMvc.perform(get(EMR + "/chemo-checks").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(get(EMR + "/chemo-checks/" + ORDER).with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(post(EMR + "/chemo-checks/" + ORDER + "/confirm").with(as(role)).contentType(JSON)
                    .content("{\"fingerprint\":\"" + FINGERPRINT + "\"}")).andExpect(status().isForbidden());
        }
    }

    @Test
    void theSavePassesTheBodyAndTheClinicIsAlwaysTheSignedInUsers() throws Exception {
        when(orders.save(any(ClinicUser.class), eq(RECORD), any(OrderInput.class)))
                .thenReturn(node("{'status':'DRAFT','revision':0}"));

        mockMvc.perform(put(EMR + "/records/" + RECORD + "/chemo-order").with(as("DOCTOR")).contentType(JSON)
                .content(ORDER_BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        ArgumentCaptor<ClinicUser> user = ArgumentCaptor.forClass(ClinicUser.class);
        ArgumentCaptor<OrderInput> input = ArgumentCaptor.forClass(OrderInput.class);
        verify(orders).save(user.capture(), eq(RECORD), input.capture());
        assertThat(user.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(input.getValue().protocolKey()).isEqualTo("AC");
        assertThat(input.getValue().finalDoses().get(0).finalMg()).isEqualTo(95.0);
    }

    @Test
    void aBodyWithAFieldTheContractDoesNotListIsRefusedAs400() throws Exception {
        mockMvc.perform(put(EMR + "/records/" + RECORD + "/chemo-order").with(as("DOCTOR")).contentType(JSON)
                .content(ORDER_BODY.replace("\"revision\":0", "\"revision\":0,\"tenantId\":\"" + UUID.randomUUID()
                        + "\""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/chemo-order/hold").with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":0,\"colour\":\"red\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
    }

    @Test
    void theProblemTypesOfTheContractAreAnswered() throws Exception {
        when(orders.send(any(ClinicUser.class), eq(RECORD), any(ActionInput.class))).thenThrow(
                EmrProblemException.of(409, "second-check-not-confirmed", "Second check not confirmed",
                        "The second check is not confirmed yet."));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/chemo-order/send").with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":1,\"fingerprint\":\"" + FINGERPRINT + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/second-check-not-confirmed"))
                .andExpect(jsonPath("$.detail").value("The second check is not confirmed yet."));
        when(checks.summary(any(ClinicUser.class), eq(ORDER))).thenThrow(EmrProblemException.of(403,
                "not-named-checker", "You are not the named checker", "This order names another person."));
        mockMvc.perform(get(EMR + "/chemo-checks/" + ORDER).with(as("NURSE"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-named-checker"));
        when(orders.hold(any(ClinicUser.class), eq(RECORD), any(HoldInput.class))).thenThrow(EmrProblemException.of(
                409, "checks-failing", "Checks are failing", "Record the hold needs a Delay or Stop decision."));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/chemo-order/hold").with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/checks-failing"));
    }

    @Test
    void theCheckerIsNamedAndOptionsAreListed() throws Exception {
        when(orders.checkerOptions(any(ClinicUser.class), eq(RECORD))).thenReturn(List.of(java.util.Map.of(
                "membershipId", CHECKER, "name", "Sr Mary", "role", "NURSE", "hasPin", true)));
        mockMvc.perform(get(EMR + "/records/" + RECORD + "/chemo-order/checker-options").with(as("DOCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Sr Mary"))
                .andExpect(jsonPath("$[0].hasPin").value(true));
        when(orders.nameChecker(any(ClinicUser.class), eq(RECORD), any(CheckerInput.class))).thenReturn(
                node("{'secondCheck':{'state':'WAITING'}}"));
        mockMvc.perform(put(EMR + "/records/" + RECORD + "/chemo-order/checker").with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":1,\"checkerMembershipId\":\"" + CHECKER + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.secondCheck.state").value("WAITING"));
        mockMvc.perform(put(EMR + "/records/" + RECORD + "/chemo-order/checker").with(as("DOCTOR")).contentType(JSON)
                .content("{\"revision\":1,\"checkerMembershipId\":null}")).andExpect(status().isOk());
    }

    @Test
    void thePinGoesToTheSecondCheckServiceAndIsNeverEchoedWhenItFails() throws Exception {
        when(secondCheck.confirmByPin(any(ClinicUser.class), eq(RECORD), any(PinInput.class))).thenThrow(
                StaffPinException.of(new PinCheck(PinCheck.Outcome.WRONG, 3, null, 0),
                        StaffPinException.Subject.CHECKER));
        String body = "{\"orderRevision\":2,\"fingerprint\":\"" + FINGERPRINT + "\",\"checkerMembershipId\":\""
                + CHECKER + "\",\"pin\":\"4821\"}";

        String response = mockMvc.perform(post(EMR + "/records/" + RECORD + "/chemo-order/second-check")
                .with(as("DOCTOR")).contentType(JSON).content(body)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/wrong-pin"))
                .andExpect(jsonPath("$.detail").value("That PIN is not right.")).andReturn().getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("4821");
        ArgumentCaptor<PinInput> input = ArgumentCaptor.forClass(PinInput.class);
        verify(secondCheck).confirmByPin(any(ClinicUser.class), eq(RECORD), input.capture());
        assertThat(input.getValue().checkerMembershipId()).isEqualTo(CHECKER);
        assertThat(input.getValue().toString()).doesNotContain("4821");
    }

    @Test
    void theColleagueLimitIs429WithNeutralTextAndNoRetryAfter() throws Exception {
        when(secondCheck.confirmByPin(any(ClinicUser.class), eq(RECORD), any(PinInput.class))).thenThrow(
                StaffPinException.of(new PinCheck(PinCheck.Outcome.PAIR_LIMIT, 0, null, 0),
                        StaffPinException.Subject.CHECKER));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/chemo-order/second-check").with(as("DOCTOR"))
                .contentType(JSON).content("{\"orderRevision\":2,\"fingerprint\":\"" + FINGERPRINT
                        + "\",\"checkerMembershipId\":\"" + CHECKER + "\",\"pin\":\"1111\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/colleague-pin-limit"))
                .andExpect(jsonPath("$.detail").value(
                        "Too many wrong PIN tries for this person. Choose another checker."))
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());
    }

    // ---- reference, history, booking, templates ----

    @Test
    void theReferenceAndTheHistoryAreForDoctors() throws Exception {
        when(reference.get(any(ClinicUser.class), eq("onco"))).thenReturn(node("{'profile':'onco','tagNote':'t'}"));
        when(history.get(any(ClinicUser.class), eq(RECORD))).thenReturn(node("{'profile':'onco','someHidden':false}"));
        mockMvc.perform(get(EMR + "/specialty-reference/onco").with(as("DOCTOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("onco"));
        mockMvc.perform(get(EMR + "/records/" + RECORD + "/specialty-history").with(as("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.someHidden").value(false));
        mockMvc.perform(get(EMR + "/specialty-reference/onco").with(as("NURSE"))).andExpect(status().isForbidden());
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            mockMvc.perform(post(EMR + "/specialty-reference/onco").with(as(role)).contentType(JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(EMR + "/specialty-reference/onco").with(as(role)).contentType(JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theSurgeryBookingTakesTheIdempotencyKeyAndTheRevision() throws Exception {
        when(booking.book(any(ClinicUser.class), eq(RECORD), eq(KEY), eq(4L))).thenReturn(
                new SurgeryBookingService.Result(null, UUID.randomUUID(), null, false,
                        "Booked. This clinic has no IPD, so no ward bed was requested."));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/surgery-booking").with(as("DOCTOR")).header(
                "Idempotency-Key", KEY).contentType(JSON).content("{\"revision\":4}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionRequested").value(false))
                .andExpect(jsonPath("$.message").value("Booked. This clinic has no IPD, so no ward bed was requested."));
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/surgery-booking").with(as("DOCTOR")).header(
                "Idempotency-Key", KEY).contentType(JSON).content("{\"revision\":4,\"extra\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(EMR + "/records/" + RECORD + "/surgery-booking").with(as("FRONT_DESK")).header(
                "Idempotency-Key", KEY).contentType(JSON).content("{\"revision\":4}")).andExpect(status().isForbidden());
    }

    @Test
    void visitTemplatesAreListedAndCreatedWith201() throws Exception {
        when(templates.list(any(ClinicUser.class))).thenReturn(List.of(node("{'id':'" + UUID.randomUUID()
                + "','name':'Fever, adult','subLine':null,'items':{}}")));
        when(templates.create(any(ClinicUser.class), any(VisitTemplateService.Input.class))).thenReturn(
                node("{'name':'Fever, adult'}"));
        mockMvc.perform(get(EMR + "/visit-templates").with(as("DOCTOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Fever, adult"));
        mockMvc.perform(post(EMR + "/visit-templates").with(as("DOCTOR")).contentType(JSON)
                .content("{\"name\":\"Fever, adult\",\"subLine\":null,\"items\":{\"repeatLastRx\":true}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Fever, adult"));
        mockMvc.perform(post(EMR + "/visit-templates").with(as("DOCTOR")).contentType(JSON)
                .content("{\"name\":\"x\",\"items\":{},\"colour\":\"red\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(EMR + "/visit-templates").with(as("NURSE")).contentType(JSON)
                .content("{\"name\":\"x\",\"items\":{}}")).andExpect(status().isForbidden());
    }

    // ---- SAH-75-7 (ADR-026 D7): PUT /emr/records/{recordId}/cancer-journey ----

    @Test
    void theCancerJourneyIsSavedByDoctorsAndTheOwnerWithTheBodyAsSentAndNobodyElse() throws Exception {
        when(journey.save(any(ClinicUser.class), eq(RECORD), any())).thenReturn(
                new CancerJourneyService.JourneyView(2, "IIB", "cT2 cN2 M0", null, 28, null, "CURATIVE", null, 1, null,
                        java.time.Instant.parse("2026-11-14T06:30:00Z"), "Dr Rao"));
        String body = "{\"expectedVersion\":1,\"stageGroup\":\"IIB\",\"geneticsText\":null,\"ki67Pct\":28}";

        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "OWNER"}) {
            mockMvc.perform(put(EMR + "/records/" + RECORD + "/cancer-journey").with(as(role)).contentType(JSON)
                    .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.recordedByName").value("Dr Rao"))
                    .andExpect(jsonPath("$.stageGroup").value("IIB"));
        }
        for (String role : new String[] {"FRONT_DESK", "BILLING", "NURSE", "PHARMACIST", "COUNSELLOR"}) {
            mockMvc.perform(put(EMR + "/records/" + RECORD + "/cancer-journey").with(as(role)).contentType(JSON)
                    .content(body)).andExpect(status().isForbidden());
        }
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> sent = org.mockito.ArgumentCaptor.forClass(
                com.fasterxml.jackson.databind.JsonNode.class);
        verify(journey, org.mockito.Mockito.times(3)).save(any(ClinicUser.class), eq(RECORD), sent.capture());
        // a field sent as null reaches the service as null (it clears), a field left out is absent (it keeps)
        assertThat(sent.getValue().has("geneticsText")).isTrue();
        assertThat(sent.getValue().get("geneticsText").isNull()).isTrue();
        assertThat(sent.getValue().has("tnmText")).isFalse();
    }

    @Test
    void aStaleJourneyIs409JourneyChangedAndASignedRecord409() throws Exception {
        when(journey.save(any(ClinicUser.class), eq(RECORD), any())).thenThrow(EmrProblemException.of(409,
                "journey-changed", "Journey changed", "The journey was changed. Reload."));

        mockMvc.perform(put(EMR + "/records/" + RECORD + "/cancer-journey").with(as("DOCTOR")).contentType(JSON)
                .content("{\"expectedVersion\":0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/journey-changed"))
                .andExpect(jsonPath("$.detail").value("The journey was changed. Reload."));
    }

    @Test
    void aSignedOutVisitorGets401OnTheJourney() throws Exception {
        mockMvc.perform(put(EMR + "/records/" + RECORD + "/cancer-journey").contentType(JSON)
                .content("{\"expectedVersion\":0}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(journey);
    }
}
