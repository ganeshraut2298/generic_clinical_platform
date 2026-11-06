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

import com.sahaj.ipd.application.EscalationService;
import com.sahaj.ipd.application.SafetyService;
import com.sahaj.ipd.application.SafetyViews.ActionOption;
import com.sahaj.ipd.application.SafetyViews.ActionResult;
import com.sahaj.ipd.application.SafetyViews.Board;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.Event;
import com.sahaj.ipd.application.SafetyViews.EventPage;
import com.sahaj.ipd.application.SafetyViews.Response;
import com.sahaj.ipd.application.SafetyViews.ResponseInput;
import com.sahaj.ipd.application.SafetyViews.Row;
import com.sahaj.ipd.application.SafetyViews.SeniorCallInput;
import com.sahaj.ipd.application.SafetyViews.VersionInput;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
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

/** SAH-33 Safety board endpoints: contract shape, status codes, roles, problem types and typed bodies. */
@WebMvcTest(controllers = SafetyController.class)
@Import(SecurityConfig.class)
class SafetyControllerTest {

    private static final String URL = "/api/v1/ipd";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ESCALATION_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");
    private static final String[] READ_ROLES = {"OWNER", "DOCTOR", "NURSE"};
    private static final String[] NO_READ_ROLES = {"VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST",
        "COUNSELLOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SafetyService reads;

    @MockBean
    private EscalationService actions;

    private static RequestPostProcessor as(String role) {
        return TestTenantSecurity.asUser(TENANT_ID, USER_ID, role);
    }

    private static Row row(Status status) {
        Response response = status == Status.DONE
                ? new Response("BEDSIDE_REVIEW", "Reviewed at the bedside", "Plan", "Dr Meera Joshi", NOW) : null;
        return new Row(ESCALATION_ID, ADMISSION_ID, 3, "Test Patient", "202", 8, Risk.HIGH, 15, status,
                Instant.parse("2026-11-11T05:30:00Z"), "Sr. Anjali Pawar", 30, null, 30, status == Status.OPEN, 1,
                status == Status.OPEN ? null : "Dr Meera Joshi", status == Status.OPEN ? null : NOW, response,
                status == Status.OPEN, status != Status.DONE, false);
    }

    private static ActionResult result(boolean replayed, Status status) {
        return new ActionResult(NOW, replayed, row(status));
    }

    // ---- GET /safety ----

    @Test
    void theBoardHasTheContractShape() throws Exception {
        Board board = new Board("on", "DOCTOR", NOW, new Counts(1, 1, "AAAAAAAAAAAAAAAAAAAAAA", NOW), null,
                List.of(new ActionOption("BEDSIDE_REVIEW", "Reviewed at the bedside")), List.of(row(Status.OPEN)));
        when(reads.board(TENANT_ID, USER_ID, "DOCTOR")).thenReturn(board);

        mockMvc.perform(get(URL + "/safety").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("on"))
                .andExpect(jsonPath("$.view").value("DOCTOR"))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.counts.open").value(1))
                .andExpect(jsonPath("$.counts.pastTarget").value(1))
                .andExpect(jsonPath("$.counts.changeToken").value("AAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(jsonPath("$.counts.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.yourPatients").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.responseActions[0].key").value("BEDSIDE_REVIEW"))
                .andExpect(jsonPath("$.responseActions[0].label").value("Reviewed at the bedside"))
                .andExpect(jsonPath("$.escalations[0].id").value(ESCALATION_ID.toString()))
                .andExpect(jsonPath("$.escalations[0].admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.escalations[0].version").value(3))
                .andExpect(jsonPath("$.escalations[0].patientName").value("Test Patient"))
                .andExpect(jsonPath("$.escalations[0].bedCode").value("202"))
                .andExpect(jsonPath("$.escalations[0].ewsScore").value(8))
                .andExpect(jsonPath("$.escalations[0].ewsRisk").value("HIGH"))
                .andExpect(jsonPath("$.escalations[0].targetMinutes").value(15))
                .andExpect(jsonPath("$.escalations[0].status").value("OPEN"))
                .andExpect(jsonPath("$.escalations[0].raisedAt").value("2026-11-11T05:30:00Z"))
                .andExpect(jsonPath("$.escalations[0].raisedByName").value("Sr. Anjali Pawar"))
                .andExpect(jsonPath("$.escalations[0].minutesSinceRaised").value(30))
                .andExpect(jsonPath("$.escalations[0].reopenedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.escalations[0].waitingMinutes").value(30))
                .andExpect(jsonPath("$.escalations[0].pastTarget").value(true))
                .andExpect(jsonPath("$.escalations[0].seniorCalls").value(1))
                .andExpect(jsonPath("$.escalations[0].acknowledgedByName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.escalations[0].acknowledgedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.escalations[0].response").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.escalations[0].canAcknowledge").value(true))
                .andExpect(jsonPath("$.escalations[0].canRespond").value(true))
                .andExpect(jsonPath("$.escalations[0].canCallSenior").value(false));
    }

    @Test
    void aClosedRowCarriesTheResponseObject() throws Exception {
        when(reads.board(TENANT_ID, USER_ID, "OWNER")).thenReturn(new Board("readonly", "DOCTOR", NOW,
                new Counts(0, 0, "AAAAAAAAAAAAAAAAAAAAAA", NOW), null, List.of(), List.of(row(Status.DONE))));

        mockMvc.perform(get(URL + "/safety").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("readonly"))
                .andExpect(jsonPath("$.escalations[0].status").value("DONE"))
                .andExpect(jsonPath("$.escalations[0].response.action").value("BEDSIDE_REVIEW"))
                .andExpect(jsonPath("$.escalations[0].response.actionLabel").value("Reviewed at the bedside"))
                .andExpect(jsonPath("$.escalations[0].response.note").value("Plan"))
                .andExpect(jsonPath("$.escalations[0].response.closedByName").value("Dr Meera Joshi"))
                .andExpect(jsonPath("$.escalations[0].response.closedAt").value("2026-11-11T06:00:00Z"));
    }

    @Test
    void theNurseBoardCarriesYourPatients() throws Exception {
        when(reads.board(TENANT_ID, USER_ID, "NURSE")).thenReturn(new Board("on", "NURSE", NOW,
                new Counts(0, 0, "AAAAAAAAAAAAAAAAAAAAAA", NOW), 12, List.of(), List.of()));

        mockMvc.perform(get(URL + "/safety").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("NURSE"))
                .andExpect(jsonPath("$.yourPatients").value(12))
                .andExpect(jsonPath("$.escalations.length()").value(0));
    }

    @Test
    void onlyOwnerDoctorAndNurseMayReadTheBoardTheSummaryAndTheAuditTrail() throws Exception {
        when(reads.board(any(), any(), any())).thenReturn(new Board("on", "DOCTOR", NOW, new Counts(0, 0, "AAAAAAAAAAAAAAAAAAAAAA", NOW), null,
                List.of(), List.of()));
        when(reads.summary(any(), any(), any())).thenReturn(new Counts(0, 0, "AAAAAAAAAAAAAAAAAAAAAA", NOW));
        when(reads.events(any(), any(), any(), any(), any())).thenReturn(new EventPage(NOW, List.of(), null));

        for (String role : READ_ROLES) {
            mockMvc.perform(get(URL + "/safety").with(as(role))).andExpect(status().isOk());
            mockMvc.perform(get(URL + "/safety/summary").with(as(role))).andExpect(status().isOk());
            mockMvc.perform(get(URL + "/clinical-events").with(as(role))).andExpect(status().isOk());
        }
        for (String role : NO_READ_ROLES) {
            mockMvc.perform(get(URL + "/safety").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(get(URL + "/safety/summary").with(as(role))).andExpect(status().isForbidden());
            mockMvc.perform(get(URL + "/clinical-events").with(as(role))).andExpect(status().isForbidden());
        }
        verify(reads).board(TENANT_ID, USER_ID, "OWNER");
        verify(reads).board(TENANT_ID, USER_ID, "DOCTOR");
        verify(reads).board(TENANT_ID, USER_ID, "NURSE");
    }

    @Test
    void anUnauthenticatedCallIs401() throws Exception {
        mockMvc.perform(get(URL + "/safety")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/safety/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/clinical-events")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").contentType(JSON)
                .content("{\"version\":3}")).andExpect(status().isUnauthorized());
    }

    @Test
    void moduleOffIs403WithTheModuleOffType() throws Exception {
        when(reads.board(any(), any(), any())).thenThrow(new ModuleOffException("In-patients is not switched on."));

        mockMvc.perform(get(URL + "/safety").with(as("DOCTOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("module-off")));
    }

    // ---- GET /safety/summary ----

    @Test
    void theSummaryIsCountsOnly() throws Exception {
        when(reads.summary(TENANT_ID, USER_ID, "NURSE")).thenReturn(new Counts(3, 1, "AAAAAAAAAAAAAAAAAAAAAA", NOW));

        mockMvc.perform(get(URL + "/safety/summary").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(3))
                .andExpect(jsonPath("$.pastTarget").value(1))
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.changeToken").value("AAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(jsonPath("$.length()").value(4));
    }

    // ---- GET /clinical-events ----

    @Test
    void theAuditTrailHasTheContractShapeAndPassesLimitAndCursorAsText() throws Exception {
        Event event = new Event(UUID.randomUUID(), "esc", "Escalation closed: Treatment changed",
                Instant.parse("2026-11-11T05:55:00Z"), "Dr Meera Joshi", ADMISSION_ID, "Test Patient", "202");
        when(reads.events(TENANT_ID, USER_ID, "OWNER", "50", "abc_-1")).thenReturn(new EventPage(NOW,
                List.of(event), "next-cursor"));

        mockMvc.perform(get(URL + "/clinical-events?limit=50&cursor=abc_-1").with(as("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.items[0].kind").value("esc"))
                .andExpect(jsonPath("$.items[0].text").value("Escalation closed: Treatment changed"))
                .andExpect(jsonPath("$.items[0].at").value("2026-11-11T05:55:00Z"))
                .andExpect(jsonPath("$.items[0].actorName").value("Dr Meera Joshi"))
                .andExpect(jsonPath("$.items[0].admissionId").value(ADMISSION_ID.toString()))
                .andExpect(jsonPath("$.items[0].patientName").value("Test Patient"))
                .andExpect(jsonPath("$.items[0].bedCode").value("202"));
    }

    @Test
    void withNoParametersTheServiceGetsNullsAndTheDefaultIsItsOwn() throws Exception {
        when(reads.events(any(), any(), any(), any(), any())).thenReturn(new EventPage(NOW, List.of(), null));

        mockMvc.perform(get(URL + "/clinical-events").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(Matchers.nullValue()));

        verify(reads).events(TENANT_ID, USER_ID, "DOCTOR", null, null);
    }

    @Test
    void aBadLimitOrCursorIs400WithTheFieldError() throws Exception {
        when(reads.events(any(), any(), any(), eq("0"), any()))
                .thenThrow(new InvalidInputException("limit", "Reload the page and try again."));

        mockMvc.perform(get(URL + "/clinical-events?limit=0").with(as("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Reload the page and try again."));
    }

    // ---- POST acknowledge ----

    @Test
    void acknowledgeAnswers200WithTheRowAndPassesTheVersionAsTheJsonThatArrived() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenReturn(result(false, Status.ACK));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-11-11T06:00:00Z"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.escalation.id").value(ESCALATION_ID.toString()))
                .andExpect(jsonPath("$.escalation.status").value("ACK"))
                .andExpect(jsonPath("$.escalation.acknowledgedByName").value("Dr Meera Joshi"));

        ArgumentCaptor<VersionInput> sent = ArgumentCaptor.forClass(VersionInput.class);
        verify(actions).acknowledge(eq(TENANT_ID), eq(USER_ID), eq("DOCTOR"), eq(ESCALATION_ID), sent.capture());
        assertThat(sent.getValue().version().isIntegralNumber()).isTrue();
        assertThat(sent.getValue().version().longValue()).isEqualTo(3);
    }

    @Test
    void aVersionSentAsAStringOrFractionReachesTheServiceAsNotANumberSoItIsRefusedThere() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenReturn(result(false, Status.ACK));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("OWNER"))
                .contentType(JSON).content("{\"version\":\"3\"}")).andExpect(status().isOk());
        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("OWNER"))
                .contentType(JSON).content("{\"version\":3.5}")).andExpect(status().isOk());

        ArgumentCaptor<VersionInput> sent = ArgumentCaptor.forClass(VersionInput.class);
        verify(actions, org.mockito.Mockito.times(2)).acknowledge(any(), any(), any(), any(), sent.capture());
        assertThat(sent.getAllValues().get(0).version().isTextual()).isTrue();
        assertThat(sent.getAllValues().get(1).version().isIntegralNumber()).isFalse();
    }

    @Test
    void aReplayedAcknowledgeAnswers200WithReplayedTrue() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenReturn(result(true, Status.ACK));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void onlyOwnerAndDoctorMayAcknowledgeAndRespond() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenReturn(result(false, Status.ACK));
        when(actions.respond(any(), any(), any(), any(), any())).thenReturn(result(false, Status.DONE));

        for (String role : new String[] {"OWNER", "DOCTOR"}) {
            mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as(role))
                    .contentType(JSON).content("{\"version\":3}")).andExpect(status().isOk());
            mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/respond").with(as(role))
                    .contentType(JSON).content("{\"version\":3,\"action\":\"NO_CHANGE\",\"note\":\"Plan\"}"))
                    .andExpect(status().isOk());
        }
        for (String role : new String[] {"NURSE", "VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST",
            "COUNSELLOR"}) {
            mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as(role))
                    .contentType(JSON).content("{\"version\":3}")).andExpect(status().isForbidden());
            mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/respond").with(as(role))
                    .contentType(JSON).content("{\"version\":3,\"action\":\"NO_CHANGE\",\"note\":\"Plan\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(actions, org.mockito.Mockito.times(2)).acknowledge(any(), any(), any(), any(), any());
        verify(actions, org.mockito.Mockito.times(2)).respond(any(), any(), any(), any(), any());
    }

    @Test
    void escalationStateAndChangedAre409WithTheirTypesAndMessages() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenThrow(new ConflictException(
                "escalation-state", "Escalation is acknowledged", "Dr Ravi Shah already acknowledged this escalation."));
        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("escalation-state")))
                .andExpect(jsonPath("$.detail").value("Dr Ravi Shah already acknowledged this escalation."));

        when(actions.respond(any(), any(), any(), any(), any())).thenThrow(new ConflictException(
                "escalation-changed", "Escalation changed",
                "The score is now EWS 8 (high). Review it and try again."));
        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/respond").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3,\"action\":\"NO_CHANGE\",\"note\":\"Plan\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("escalation-changed")))
                .andExpect(jsonPath("$.detail").value("The score is now EWS 8 (high). Review it and try again."));
    }

    @Test
    void anUnknownEscalationIs404AndABadIdIs400() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenThrow(new NotFoundException("Escalation not found"));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3}")).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/escalations/not-a-uuid/acknowledge").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3}")).andExpect(status().isBadRequest());
        verify(actions, never()).respond(any(), any(), any(), any(), any());
    }

    // ---- POST respond ----

    @Test
    void respondPassesTheTypedBodyAndAnswersTheClosedRow() throws Exception {
        when(actions.respond(any(), any(), any(), any(), any())).thenReturn(result(false, Status.DONE));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/respond").with(as("OWNER"))
                .contentType(JSON).content("{\"version\":4,\"action\":\"TREATMENT_CHANGED\","
                        + "\"note\":\"O2 up to 6 L\\nrepeat vitals in 30 min\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalation.status").value("DONE"))
                .andExpect(jsonPath("$.escalation.response.note").value("Plan"));

        ArgumentCaptor<ResponseInput> sent = ArgumentCaptor.forClass(ResponseInput.class);
        verify(actions).respond(eq(TENANT_ID), eq(USER_ID), eq("OWNER"), eq(ESCALATION_ID), sent.capture());
        assertThat(sent.getValue().version().longValue()).isEqualTo(4);
        assertThat(sent.getValue().action()).isEqualTo("TREATMENT_CHANGED");
        assertThat(sent.getValue().note()).isEqualTo("O2 up to 6 L\nrepeat vitals in 30 min");
    }

    @Test
    void respondValidationIs400WithFieldErrorsAndTheFirstMessageAsDetail() throws Exception {
        when(actions.respond(any(), any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("action", "Choose what was done"),
                new FieldMessage("note", "Write the plan and when vitals are next due.")), true));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/respond").with(as("DOCTOR"))
                .contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("validation")))
                .andExpect(jsonPath("$.detail").value("Choose what was done"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("note"));
    }

    @Test
    void theResponseBodyNeverEchoesThePlanInAProblem() throws Exception {
        when(actions.respond(any(), any(), any(), any(), any())).thenThrow(new InvalidFieldsException(List.of(
                new FieldMessage("note", "Plan cannot contain control or text-direction characters")), true));

        String problem = mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/respond").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3,\"action\":\"NO_CHANGE\",\"note\":\"Secret plan text\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(problem).doesNotContain("Secret plan text");
    }

    // ---- POST call-senior ----

    @Test
    void callSeniorIsForNurseOnlyAndPassesBothNumbers() throws Exception {
        when(actions.callSenior(any(), any(), any(), any(), any())).thenReturn(result(false, Status.OPEN));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/call-senior").with(as("NURSE"))
                .contentType(JSON).content("{\"version\":3,\"seniorCalls\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.escalation.seniorCalls").value(1));
        for (String role : new String[] {"OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST",
            "COUNSELLOR"}) {
            mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/call-senior").with(as(role))
                    .contentType(JSON).content("{\"version\":3,\"seniorCalls\":1}"))
                    .andExpect(status().isForbidden());
        }

        ArgumentCaptor<SeniorCallInput> sent = ArgumentCaptor.forClass(SeniorCallInput.class);
        verify(actions).callSenior(eq(TENANT_ID), eq(USER_ID), eq("NURSE"), eq(ESCALATION_ID), sent.capture());
        assertThat(sent.getValue().version().longValue()).isEqualTo(3);
        assertThat(sent.getValue().seniorCalls().longValue()).isEqualTo(1);
    }

    @Test
    void callSeniorOnAnAcknowledgedEscalationIs409State() throws Exception {
        when(actions.callSenior(any(), any(), any(), any(), any())).thenThrow(new ConflictException(
                "escalation-state", "Escalation is acknowledged", "Dr Meera Joshi already acknowledged this escalation."));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/call-senior").with(as("NURSE"))
                .contentType(JSON).content("{\"version\":3,\"seniorCalls\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("escalation-state")));
    }

    @Test
    void theClinicAndTheUserAlwaysComeFromTheSignInNeverFromTheBody() throws Exception {
        when(actions.acknowledge(any(), any(), any(), any(), any())).thenReturn(result(false, Status.ACK));

        mockMvc.perform(post(URL + "/escalations/" + ESCALATION_ID + "/acknowledge").with(as("DOCTOR"))
                .contentType(JSON).content("{\"version\":3,\"tenantId\":\"" + UUID.randomUUID()
                        + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());

        verify(actions).acknowledge(eq(TENANT_ID), eq(USER_ID), eq("DOCTOR"), eq(ESCALATION_ID), any());
    }
}
