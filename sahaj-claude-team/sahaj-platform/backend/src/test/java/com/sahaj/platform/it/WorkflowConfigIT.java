package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-12 against the real database: lazy v1, draft, publish, version history, version-stamped visits,
 * owner-only writes, row level security and the append-only grant on {@code opd.workflow_version}.
 */
@OnTestDatabase
class WorkflowConfigIT extends OpdJourneySupport {

    private static final String BASE = "/api/v1/opd/workflow";

    private static String steps(boolean counselOn) {
        return steps(true, counselOn);
    }

    private static String steps(boolean vitalsOn, boolean counselOn) {
        return """
                {"steps":[{"key":"token","on":true},{"key":"queue","on":true},
                 {"key":"vitals","on":%s,"owner":"NURSE"},{"key":"consult","on":true},
                 {"key":"counsel","on":%s,"owner":"DOCTOR"},{"key":"casepaper","on":true},
                 {"key":"billing","on":true},{"key":"followup","on":true}]}
                """.formatted(vitalsOn, counselOn);
    }

    private void publish(Clinic clinic, String body) throws Exception {
        assertThat(putDraft(clinic.owner(), body).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(post(BASE + "/publish").with(clinic.owner())).andExpect(status().isOk());
    }

    private JsonNode issueJson(Clinic clinic, UUID patientId) throws Exception {
        return json(mvc.perform(post("/api/v1/opd/visits").with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\""
                                + clinic.doctorMembershipId() + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private int storedVersion(Clinic clinic, UUID visitId) throws Exception {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c,
                "select workflow_version_no from opd.visit where id = ?", visitId)).intValue();
    }

    private MvcResult putDraft(RequestPostProcessor auth, String body) throws Exception {
        return mvc.perform(put(BASE + "/draft").with(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private static boolean stepOn(JsonNode steps, String key) {
        for (JsonNode s : steps) {
            if (s.get("key").asText().equals(key)) {
                return s.get("on").asBoolean();
            }
        }
        throw new AssertionError("no step " + key);
    }

    private UUID issue(Clinic clinic, UUID patientId) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/opd/visits").with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\""
                                + clinic.doctorMembershipId() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return readId(r);
    }

    private JsonNode move(Clinic clinic, UUID visitId, String stage, int expected) throws Exception {
        MvcResult r = mvc.perform(patch("/api/v1/opd/visits/" + visitId + "/stage").with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"" + stage + "\"}"))
                .andExpect(status().is(expected)).andReturn();
        return json(r);
    }

    @Test
    void currentLazilyCreatesVersionOneWithDocumentedDefaultsAndIsStable() throws Exception {
        Clinic clinic = newClinic();
        JsonNode first = getJson(BASE + "/current", clinic.owner());
        assertThat(first.get("versionNo").asInt()).isEqualTo(1);
        JsonNode steps = first.get("steps");
        assertThat(steps).hasSize(8);
        List<String> keys = new ArrayList<>();
        steps.forEach(s -> keys.add(s.get("key").asText()));
        assertThat(keys).containsExactly("token", "queue", "vitals", "consult", "counsel", "casepaper", "billing",
                "followup");
        assertThat(stepOn(steps, "vitals")).isTrue();
        assertThat(steps.get(2).get("owner").asText()).isEqualTo("NURSE");
        assertThat(stepOn(steps, "counsel")).isFalse();
        assertThat(steps.get(4).get("owner").asText()).isEqualTo("DOCTOR");
        assertThat(steps.get(0).has("owner")).isFalse();
        assertThat(first.get("publishedAt").asText()).isNotBlank();

        JsonNode second = getJson(BASE + "/current", clinic.owner());
        assertThat(second.get("versionNo").asInt()).isEqualTo(1);
        assertThat(second.get("publishedAt").asText()).isEqualTo(first.get("publishedAt").asText());
        assertThat(getJson(BASE + "/versions", clinic.owner())).hasSize(1);
    }

    @Test
    void firstVersionFollowsTheSetupWizardVitalsChoice() throws Exception {
        Clinic clinic = newClinic(false);
        assertThat(stepOn(getJson(BASE + "/current", clinic.owner()).get("steps"), "vitals")).isFalse();
    }

    @Test
    void draftBeforeAnyEditIsACopyOfTheCurrentVersion() throws Exception {
        Clinic clinic = newClinic();
        JsonNode draft = getJson(BASE + "/draft", clinic.owner());
        assertThat(draft.get("steps")).hasSize(8);
        assertThat(stepOn(draft.get("steps"), "counsel")).isFalse();
    }

    @Test
    void ownerDraftThenPublishMakesVersionTwoAndHistoryListsBothNewestFirst() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());

        MvcResult saved = putDraft(clinic.owner(), steps(true));
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        // A draft does not touch the live version.
        assertThat(stepOn(getJson(BASE + "/current", clinic.owner()).get("steps"), "counsel")).isFalse();
        assertThat(stepOn(getJson(BASE + "/draft", clinic.owner()).get("steps"), "counsel")).isTrue();

        mvc.perform(post(BASE + "/publish").with(clinic.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.steps[4].on").value(true));

        assertThat(getJson(BASE + "/current", clinic.owner()).get("versionNo").asInt()).isEqualTo(2);
        JsonNode versions = getJson(BASE + "/versions", clinic.owner());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("versionNo").asInt()).isEqualTo(1);
        assertThat(stepOn(versions.get(1).get("steps"), "counsel")).isFalse();
    }

    @Test
    void publishWithNoDraftOrAnUnchangedDraftIsRefusedWith409WorkflowConflict() throws Exception {
        Clinic clinic = newClinic();
        // No draft was ever saved.
        mvc.perform(post(BASE + "/publish").with(clinic.owner()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "workflow-conflict"));
        // A draft saved identical to the current version.
        assertThat(putDraft(clinic.owner(), steps(false)).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(post(BASE + "/publish").with(clinic.owner()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "workflow-conflict"));
        assertThat(getJson(BASE + "/versions", clinic.owner())).hasSize(1);

        // Publishing twice in a row: the second has nothing new.
        putDraft(clinic.owner(), steps(true));
        mvc.perform(post(BASE + "/publish").with(clinic.owner())).andExpect(status().isOk());
        mvc.perform(post(BASE + "/publish").with(clinic.owner()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "workflow-conflict"));
    }

    @Test
    void publishingWritesAWorkflowPublishedAuditRowWithBeforeAndAfterSteps() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());
        putDraft(clinic.owner(), steps(false, true));
        mvc.perform(post(BASE + "/publish").with(clinic.owner())).andExpect(status().isOk());

        db().inTenant(clinic.tenantId(), c -> {
            String where = " from platform.audit_event where action = 'WORKFLOW_PUBLISHED' and entity_id = ?";
            assertThat(DbAccess.count(c, "select count(*)" + where, clinic.tenantId())).isEqualTo(1);
            assertThat(DbAccess.text(c, "select entity_type" + where, clinic.tenantId())).isEqualTo("WORKFLOW");
            assertThat(DbAccess.text(c, "select actor_user_id::text" + where, clinic.tenantId()))
                    .isEqualTo(clinic.ownerUserId().toString());
            assertThat(DbAccess.text(c, "select before_json ->> 'versionNo'" + where, clinic.tenantId()))
                    .isEqualTo("1");
            assertThat(DbAccess.text(c, "select after_json ->> 'versionNo'" + where, clinic.tenantId()))
                    .isEqualTo("2");
            // before: vitals on, counsel off. after: vitals off, counsel on. Eight steps each side.
            assertThat(DbAccess.text(c, "select jsonb_array_length(before_json -> 'steps')::text" + where,
                    clinic.tenantId())).isEqualTo("8");
            assertThat(DbAccess.text(c, "select jsonb_array_length(after_json -> 'steps')::text" + where,
                    clinic.tenantId())).isEqualTo("8");
            assertThat(DbAccess.text(c, "select before_json -> 'steps' -> 2 ->> 'on'" + where, clinic.tenantId()))
                    .isEqualTo("true");
            assertThat(DbAccess.text(c, "select before_json -> 'steps' -> 4 ->> 'on'" + where, clinic.tenantId()))
                    .isEqualTo("false");
            assertThat(DbAccess.text(c, "select after_json -> 'steps' -> 2 ->> 'on'" + where, clinic.tenantId()))
                    .isEqualTo("false");
            assertThat(DbAccess.text(c, "select after_json -> 'steps' -> 4 ->> 'on'" + where, clinic.tenantId()))
                    .isEqualTo("true");
            assertThat(DbAccess.text(c, "select after_json -> 'steps' -> 4 ->> 'owner'" + where, clinic.tenantId()))
                    .isEqualTo("DOCTOR");
            return null;
        });
    }

    @Test
    void aLostPublishRaceGets409AndOnlyOneVersionIsAdded() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());
        putDraft(clinic.owner(), steps(true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(BASE + "/publish").with(clinic.owner())).andReturn();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            MvcResult r = f.get();
            codes.add(r.getResponse().getStatus());
            if (r.getResponse().getStatus() == 409) {
                assertThat(r.getResponse().getContentType()).contains("problem+json");
                assertThat(json(r).get("type").asText()).isEqualTo(PROBLEM_BASE + "workflow-conflict");
            }
        }
        pool.shutdown();
        // Exactly one wins and the other is always 409 workflow-conflict: a loser that races the insert
        // gets "version already exists", and a loser that arrives after the winner committed finds the
        // draft equal to the live version ("nothing new to publish"), which is the same 409.
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(getJson(BASE + "/versions", clinic.owner())).hasSize(2);
    }

    @Test
    void invalidDraftsAreRefusedWith400ProblemDetails() throws Exception {
        Clinic clinic = newClinic();
        String[] bad = {
                "{\"steps\":[]}",
                // billing switched off
                steps(false).replace("{\"key\":\"billing\",\"on\":true}", "{\"key\":\"billing\",\"on\":false}"),
                // unknown owner
                steps(false).replace("\"owner\":\"DOCTOR\"", "\"owner\":\"JANITOR\""),
                // unknown key
                steps(false).replace("\"followup\"", "\"bogus\""),
                // an owner sent on a step that has none (rejected, not silently dropped)
                steps(false).replace("{\"key\":\"token\",\"on\":true}", "{\"key\":\"token\",\"on\":true,\"owner\":\"NURSE\"}"),
                // token and queue swapped: keys must be in the fixed order
                steps(false).replace("{\"key\":\"token\",\"on\":true},{\"key\":\"queue\",\"on\":true}",
                        "{\"key\":\"queue\",\"on\":true},{\"key\":\"token\",\"on\":true}"),
                // casepaper before consult
                "{\"steps\":[{\"key\":\"token\",\"on\":true},{\"key\":\"queue\",\"on\":true},"
                        + "{\"key\":\"casepaper\",\"on\":true},{\"key\":\"vitals\",\"on\":true,\"owner\":\"NURSE\"},"
                        + "{\"key\":\"consult\",\"on\":true},{\"key\":\"counsel\",\"on\":false,\"owner\":\"DOCTOR\"},"
                        + "{\"key\":\"billing\",\"on\":true},{\"key\":\"followup\",\"on\":true}]}"
        };
        for (String body : bad) {
            mvc.perform(put(BASE + "/draft").with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Test
    void visitKeepsItsOwnVersionsStagePathAfterANewVersionIsPublished() throws Exception {
        Clinic clinic = newClinic();
        UUID patientOld = newPatient(clinic);
        UUID patientNew = newPatient(clinic);

        UUID oldVisit = issue(clinic, patientOld); // stamped v1: no counsel
        assertThat(getJson(BASE + "/current", clinic.owner()).get("versionNo").asInt()).isEqualTo(1);

        putDraft(clinic.owner(), steps(true));
        mvc.perform(post(BASE + "/publish").with(clinic.owner())).andExpect(status().isOk());

        // Old visit: vitals (first stage) -> with doctor -> billing, never counsel.
        assertThat(move(clinic, oldVisit, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        move(clinic, oldVisit, "COUNSEL", 400);
        assertThat(move(clinic, oldVisit, "BILLING", 200).get("stage").asText()).isEqualTo("BILLING");

        // New visit issued after publish: stamped v2, goes through counsel.
        UUID newVisit = issue(clinic, patientNew);
        assertThat(move(clinic, newVisit, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        move(clinic, newVisit, "BILLING", 400); // cannot skip counsel
        assertThat(move(clinic, newVisit, "COUNSEL", 200).get("stage").asText()).isEqualTo("COUNSEL");
        assertThat(move(clinic, newVisit, "BILLING", 200).get("stage").asText()).isEqualTo("BILLING");

        // The version stamp is in the database.
        assertThat(storedVersion(clinic, oldVisit)).isEqualTo(1);
        assertThat(storedVersion(clinic, newVisit)).isEqualTo(2);
    }

    @Test
    void aVersionOneVisitStuckInWaitingStillMovesToVitalsAfterV2SwitchesVitalsOff() throws Exception {
        Clinic clinic = newClinic(); // v1: vitals on, counselling off
        UUID patientOld = newPatient(clinic);
        UUID patientNew = newPatient(clinic);

        JsonNode oldIssued = issueJson(clinic, patientOld);
        UUID oldVisit = UUID.fromString(oldIssued.get("id").asText());
        assertThat(oldIssued.get("stage").asText()).isEqualTo("VITALS");
        assertThat(oldIssued.get("workflowVersionNo").asInt()).isEqualTo(1);
        // The owner sends the card back to Waiting, so it is sitting in WAITING when v2 is published.
        assertThat(move(clinic, oldVisit, "WAITING", 200).get("stage").asText()).isEqualTo("WAITING");

        publish(clinic, steps(false, false)); // v2: vitals off

        // v1 steps still apply to the old card: WAITING -> VITALS is allowed, VITALS -> WITH_DOCTOR follows.
        JsonNode toVitals = move(clinic, oldVisit, "VITALS", 200);
        assertThat(toVitals.get("stage").asText()).isEqualTo("VITALS");
        assertThat(toVitals.get("workflowVersionNo").asInt()).isEqualTo(1);
        assertThat(move(clinic, oldVisit, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");

        // A visit issued under v2 has no vitals stage: it starts past it and WAITING -> WITH_DOCTOR is the move.
        JsonNode newIssued = issueJson(clinic, patientNew);
        UUID newVisit = UUID.fromString(newIssued.get("id").asText());
        assertThat(newIssued.get("workflowVersionNo").asInt()).isEqualTo(2);
        assertThat(newIssued.get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(move(clinic, newVisit, "WAITING", 200).get("stage").asText()).isEqualTo("WAITING");
        move(clinic, newVisit, "VITALS", 400); // v2 has no vitals stage
        JsonNode toDoctor = move(clinic, newVisit, "WITH_DOCTOR", 200);
        assertThat(toDoctor.get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(toDoctor.get("workflowVersionNo").asInt()).isEqualTo(2);
    }

    @Test
    void whenV2TurnsCounsellingOnAVersionOneVisitGoesToBillingAndAVersionTwoVisitToCounsel() throws Exception {
        Clinic clinic = newClinic();
        UUID patientOld = newPatient(clinic);
        UUID patientNew = newPatient(clinic);
        UUID oldVisit = UUID.fromString(issueJson(clinic, patientOld).get("id").asText());
        assertThat(move(clinic, oldVisit, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");

        publish(clinic, steps(true, true)); // v2: counselling on

        // The v1 visit sits in WITH_DOCTOR: counselling is not one of its stages, billing is next.
        move(clinic, oldVisit, "COUNSEL", 400);
        JsonNode toBilling = move(clinic, oldVisit, "BILLING", 200);
        assertThat(toBilling.get("stage").asText()).isEqualTo("BILLING");
        assertThat(toBilling.get("workflowVersionNo").asInt()).isEqualTo(1);

        UUID newVisit = UUID.fromString(issueJson(clinic, patientNew).get("id").asText());
        assertThat(move(clinic, newVisit, "WITH_DOCTOR", 200).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        move(clinic, newVisit, "BILLING", 400);
        JsonNode toCounsel = move(clinic, newVisit, "COUNSEL", 200);
        assertThat(toCounsel.get("stage").asText()).isEqualTo("COUNSEL");
        assertThat(toCounsel.get("workflowVersionNo").asInt()).isEqualTo(2);
    }

    @Test
    void aTokenCreatedFromAnAppointmentIsStampedWithTheCurrentPublishedVersion() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());
        publish(clinic, steps(true, true)); // current is now v2
        UUID patient = newPatient(clinic);

        MvcResult booked = mvc.perform(post("/api/v1/opd/appointments").with(clinic.as("FRONT_DESK"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\""
                                + clinic.doctorMembershipId() + "\",\"apptDate\":\"" + LocalDate.now().plusDays(3)
                                + "\",\"apptTime\":\"10:00\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\","
                                + "\"note\":\"Check-up\"}"))
                .andExpect(status().isCreated()).andReturn();
        MvcResult tokened = mvc.perform(post("/api/v1/opd/appointments/" + readId(booked) + "/token")
                        .with(clinic.as("FRONT_DESK")))
                .andExpect(status().isOk()).andReturn();
        UUID visitId = UUID.fromString(json(tokened).get("visitId").asText());

        assertThat(storedVersion(clinic, visitId)).isEqualTo(2);
        JsonNode board = getJson("/api/v1/opd/visits/today", clinic.owner());
        assertThat(versionOf(board, visitId)).isEqualTo(2);
    }

    @Test
    void todaysVisitsCarryTheirOwnWorkflowVersionNo() throws Exception {
        Clinic clinic = newClinic();
        UUID oldVisit = UUID.fromString(issueJson(clinic, newPatient(clinic)).get("id").asText());
        publish(clinic, steps(true, true));
        UUID newVisit = UUID.fromString(issueJson(clinic, newPatient(clinic)).get("id").asText());

        JsonNode board = getJson("/api/v1/opd/visits/today", clinic.owner());
        assertThat(board).hasSize(2);
        for (JsonNode visit : board) {
            assertThat(visit.get("workflowVersionNo").isInt()).isTrue();
        }
        assertThat(versionOf(board, oldVisit)).isEqualTo(1);
        assertThat(versionOf(board, newVisit)).isEqualTo(2);
        JsonNode mine = getJson("/api/v1/opd/visits/today?mine=true", clinic.as("NURSE"));
        for (JsonNode visit : mine) {
            assertThat(visit.get("workflowVersionNo").isInt()).isTrue();
        }
    }

    @Test
    void mineFiltersEachVisitByItsOwnVersionsOwnersNotTheCurrentOnes() throws Exception {
        Clinic clinic = newClinic(); // v1: the nurse owns vitals, counselling off
        UUID oldVisit = UUID.fromString(issueJson(clinic, newPatient(clinic)).get("id").asText());
        publish(clinic, """
                {"steps":[{"key":"token","on":true},{"key":"queue","on":true},
                 {"key":"vitals","on":true,"owner":"DOCTOR"},{"key":"consult","on":true},
                 {"key":"counsel","on":true,"owner":"NURSE"},{"key":"casepaper","on":true},
                 {"key":"billing","on":true},{"key":"followup","on":true}]}
                """); // v2: the doctor owns vitals, the nurse owns counselling
        UUID newVisit = UUID.fromString(issueJson(clinic, newPatient(clinic)).get("id").asText());

        JsonNode nurse = getJson("/api/v1/opd/visits/today?mine=true", clinic.as("NURSE"));
        assertThat(ids(nurse)).containsExactly(oldVisit.toString());
        JsonNode doctor = getJson("/api/v1/opd/visits/today?mine=true", clinic.as("DOCTOR"));
        assertThat(ids(doctor)).containsExactly(newVisit.toString());

        // Both visits move on; the counselling card exists only for the v2 visit.
        move(clinic, oldVisit, "WITH_DOCTOR", 200);
        move(clinic, newVisit, "WITH_DOCTOR", 200);
        move(clinic, newVisit, "COUNSEL", 200);
        JsonNode nurseLater = getJson("/api/v1/opd/visits/today?mine=true", clinic.as("NURSE"));
        assertThat(ids(nurseLater)).containsExactly(newVisit.toString());
        JsonNode doctorLater = getJson("/api/v1/opd/visits/today?mine=true", clinic.as("DOCTOR"));
        assertThat(ids(doctorLater)).containsExactly(oldVisit.toString());
    }

    private static java.util.List<String> ids(JsonNode board) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        board.forEach(visit -> ids.add(visit.get("id").asText()));
        return ids;
    }

    private static int versionOf(JsonNode board, UUID visitId) {
        for (JsonNode visit : board) {
            if (visit.get("id").asText().equals(visitId.toString())) {
                return visit.get("workflowVersionNo").asInt();
            }
        }
        throw new AssertionError("visit " + visitId + " is not on the board");
    }

    @Test
    void anOwnerOnAnyFixedStepIsRefusedWith400AndNothingIsSaved() throws Exception {
        Clinic clinic = newClinic();
        // Only vitals and counselling have an owner; token, queue, consultation, case paper, billing and
        // follow-up must be refused for any owner value, a valid role or not (rejected, not silently dropped).
        for (String key : new String[] {"token", "queue", "consult", "casepaper", "billing", "followup"}) {
            for (String owner : new String[] {"NURSE", "OWNER"}) {
                String body = steps(false).replace("{\"key\":\"" + key + "\",\"on\":true}",
                        "{\"key\":\"" + key + "\",\"on\":true,\"owner\":\"" + owner + "\"}");
                assertThat(body).contains("\"key\":\"" + key + "\",\"on\":true,\"owner\":");
                mvc.perform(put(BASE + "/draft").with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                        .andExpect(jsonPath("$.status").value(400));
            }
        }
        // The refused bodies left no draft behind: the draft is still a copy of the live version.
        assertThat(stepOn(getJson(BASE + "/draft", clinic.owner()).get("steps"), "counsel")).isFalse();
        assertThat(getJson(BASE + "/draft", clinic.owner()).get("steps").get(0).has("owner")).isFalse();
    }

    @Test
    void ownerRolesAreCheckedForVitalsAndCounsellingAndEveryAllowedRoleIsAccepted() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "OWNER"}) {
            String body = steps(true, true).replace("\"owner\":\"NURSE\"", "\"owner\":\"" + role + "\"");
            MvcResult saved = putDraft(clinic.owner(), body);
            assertThat(saved.getResponse().getStatus()).as("owner %s", role).isEqualTo(200);
            assertThat(getJson(BASE + "/draft", clinic.owner()).get("steps").get(2).get("owner").asText())
                    .isEqualTo(role);
        }
        // Roles that are not one of the four are refused, on both owner-bearing steps.
        for (String role : new String[] {"BILLING", "PHARMACIST", "janitor", ""}) {
            assertThat(putDraft(clinic.owner(), steps(true, true).replace("\"owner\":\"NURSE\"",
                    "\"owner\":\"" + role + "\"")).getResponse().getStatus()).as("vitals owner %s", role)
                    .isEqualTo(400);
            assertThat(putDraft(clinic.owner(), steps(true, true).replace("\"owner\":\"DOCTOR\"",
                    "\"owner\":\"" + role + "\"")).getResponse().getStatus()).as("counsel owner %s", role)
                    .isEqualTo(400);
        }
        // An omitted owner on vitals and counselling falls back to the documented default.
        String noOwners = """
                {"steps":[{"key":"token","on":true},{"key":"queue","on":true},{"key":"vitals","on":true},
                 {"key":"consult","on":true},{"key":"counsel","on":true},{"key":"casepaper","on":true},
                 {"key":"billing","on":true},{"key":"followup","on":true}]}
                """;
        assertThat(putDraft(clinic.owner(), noOwners).getResponse().getStatus()).isEqualTo(200);
        JsonNode draft = getJson(BASE + "/draft", clinic.owner()).get("steps");
        assertThat(draft.get(2).get("owner").asText()).isEqualTo("NURSE");
        assertThat(draft.get(4).get("owner").asText()).isEqualTo("DOCTOR");
    }

    @Test
    void stepsOutOfTheFixedOrderOrWithTheWrongCountAreRefusedWith400() throws Exception {
        Clinic clinic = newClinic();
        String t = "{\"key\":\"token\",\"on\":true}";
        String q = "{\"key\":\"queue\",\"on\":true}";
        String v = "{\"key\":\"vitals\",\"on\":true,\"owner\":\"NURSE\"}";
        String c = "{\"key\":\"consult\",\"on\":true}";
        String k = "{\"key\":\"counsel\",\"on\":false,\"owner\":\"DOCTOR\"}";
        String p = "{\"key\":\"casepaper\",\"on\":true}";
        String b = "{\"key\":\"billing\",\"on\":true}";
        String f = "{\"key\":\"followup\",\"on\":true}";
        String[][] orders = {
                {t, v, q, c, k, p, b, f}, // vitals before queue
                {q, t, v, c, k, p, b, f}, // queue before token
                {t, q, c, v, k, p, b, f}, // consultation before vitals
                {t, q, v, c, p, k, b, f}, // case paper before counselling
                {t, q, v, c, k, b, p, f}, // billing before case paper
                {t, q, v, c, k, p, f, b}, // follow-up before billing
                {t, q, v, c, k, p, b, b}, // billing twice, follow-up missing
                {t, q, v, c, k, p, b},    // seven steps
                {t, q, v, c, k, p, b, f, f} // nine steps
        };
        for (String[] order : orders) {
            String body = "{\"steps\":[" + String.join(",", order) + "]}";
            mvc.perform(put(BASE + "/draft").with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400));
        }
        // The right order is accepted.
        assertThat(putDraft(clinic.owner(), "{\"steps\":[" + String.join(",", t, q, v, c, k, p, b, f) + "]}")
                .getResponse().getStatus()).isEqualTo(200);
        // A step without on/off, and a missing list, are refused too.
        assertThat(putDraft(clinic.owner(), steps(true).replace("{\"key\":\"casepaper\",\"on\":true}",
                "{\"key\":\"casepaper\"}")).getResponse().getStatus()).isEqualTo(400);
        assertThat(putDraft(clinic.owner(), "{\"steps\":null}").getResponse().getStatus()).isEqualTo(400);
        assertThat(putDraft(clinic.owner(), "{}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void fourConcurrentPublishesGiveOneVersionAndThreeWorkflowConflicts() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());
        putDraft(clinic.owner(), steps(true));

        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(BASE + "/publish").with(clinic.owner())).andReturn();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            MvcResult r = f.get();
            codes.add(r.getResponse().getStatus());
            if (r.getResponse().getStatus() == 409) {
                // Both loser paths (insert lost the race; draft already equals the live version) are this problem.
                assertThat(r.getResponse().getContentType()).contains("problem+json");
                assertThat(json(r).get("type").asText()).isEqualTo(PROBLEM_BASE + "workflow-conflict");
                assertThat(json(r).get("status").asInt()).isEqualTo(409);
            }
        }
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409, 409, 409);
        JsonNode versions = getJson(BASE + "/versions", clinic.owner());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("versionNo").asInt()).isEqualTo(2);
        long audits = db().inTenant(clinic.tenantId(), c -> DbAccess.count(c,
                "select count(*) from platform.audit_event where action = 'WORKFLOW_PUBLISHED' and entity_id = ?",
                clinic.tenantId()));
        assertThat(audits).as("only the winning publish is audited").isEqualTo(1);
    }

    @Test
    void aVisitInTheVitalsStageWhenV2SwitchesVitalsOffFinishesVitalsUnderVersionOneAndTheNurseStillOwnsIt()
            throws Exception {
        Clinic clinic = newClinic(); // v1: vitals on, owned by the nurse
        UUID inFlight = UUID.fromString(issueJson(clinic, newPatient(clinic)).get("id").asText());
        assertThat(getJson("/api/v1/opd/visits/today", clinic.owner()).get(0).get("stage").asText())
                .isEqualTo("VITALS");

        publish(clinic, steps(false, false)); // v2: vitals off

        // The card is still in Vitals, still version 1, and still on the nurse's own queue.
        JsonNode board = getJson("/api/v1/opd/visits/today", clinic.owner());
        assertThat(board).hasSize(1);
        assertThat(board.get(0).get("stage").asText()).isEqualTo("VITALS");
        assertThat(versionOf(board, inFlight)).isEqualTo(1);
        assertThat(ids(getJson("/api/v1/opd/visits/today?mine=true", clinic.as("NURSE"))))
                .containsExactly(inFlight.toString());

        // A visit issued now is stamped v2 and skips vitals, so it never reaches the nurse's queue.
        UUID fresh = UUID.fromString(issueJson(clinic, newPatient(clinic)).get("id").asText());
        assertThat(ids(getJson("/api/v1/opd/visits/today?mine=true", clinic.as("NURSE"))))
                .containsExactly(inFlight.toString()).doesNotContain(fresh.toString());

        // The nurse finishes the vitals step of the old card: one step forward, to the doctor.
        MvcResult moved = mvc.perform(patch("/api/v1/opd/visits/" + inFlight + "/stage").with(clinic.as("NURSE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"WITH_DOCTOR\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(moved).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(json(moved).get("workflowVersionNo").asInt()).isEqualTo(1);
        assertThat(storedVersion(clinic, inFlight)).isEqualTo(1);
        assertThat(ids(getJson("/api/v1/opd/visits/today?mine=true", clinic.as("NURSE")))).isEmpty();
        // A v2 card has no vitals stage, so it cannot be moved into one.
        mvc.perform(patch("/api/v1/opd/visits/" + fresh + "/stage").with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"VITALS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTokenFromAnAppointmentUsesThePublishedVersionNotAnUnpublishedDraft() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());
        putDraft(clinic.owner(), steps(false, true)); // a draft only: v1 stays live
        UUID patient = newPatient(clinic);

        MvcResult booked = mvc.perform(post("/api/v1/opd/appointments").with(clinic.as("FRONT_DESK"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\""
                                + clinic.doctorMembershipId() + "\",\"apptDate\":\"" + LocalDate.now().plusDays(2)
                                + "\",\"apptTime\":\"11:00\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\","
                                + "\"note\":\"Check-up\"}"))
                .andExpect(status().isCreated()).andReturn();
        MvcResult tokened = mvc.perform(post("/api/v1/opd/appointments/" + readId(booked) + "/token")
                        .with(clinic.as("FRONT_DESK")))
                .andExpect(status().isOk()).andReturn();
        UUID visitId = UUID.fromString(json(tokened).get("visitId").asText());

        assertThat(storedVersion(clinic, visitId)).isEqualTo(1);
        JsonNode visit = getJson("/api/v1/opd/visits/today", clinic.owner()).get(0);
        assertThat(visit.get("workflowVersionNo").asInt()).isEqualTo(1);
        assertThat(visit.get("stage").asText()).isEqualTo("VITALS"); // v1 has vitals on
    }

    @Test
    void nonOwnerRolesGet403OnWritesButAnyRoleCanRead() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING"}) {
            mvc.perform(put(BASE + "/draft").with(clinic.as(role)).contentType(MediaType.APPLICATION_JSON)
                            .content(steps(true)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403));
            mvc.perform(post(BASE + "/publish").with(clinic.as(role))).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/current").with(clinic.as(role))).andExpect(status().isOk());
            mvc.perform(get(BASE + "/draft").with(clinic.as(role))).andExpect(status().isOk());
            mvc.perform(get(BASE + "/versions").with(clinic.as(role))).andExpect(status().isOk());
        }
        assertThat(stepOn(getJson(BASE + "/draft", clinic.owner()).get("steps"), "counsel")).isFalse();
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get(BASE + "/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherTenantCannotSeeTheDraftOrTheVersions() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        putDraft(a.owner(), steps(true));
        mvc.perform(post(BASE + "/publish").with(a.owner())).andExpect(status().isOk());
        putDraft(a.owner(), steps(false));

        // B sees its own defaults only.
        assertThat(getJson(BASE + "/current", b.owner()).get("versionNo").asInt()).isEqualTo(1);
        assertThat(getJson(BASE + "/versions", b.owner())).hasSize(1);
        assertThat(stepOn(getJson(BASE + "/draft", b.owner()).get("steps"), "counsel")).isFalse();
        assertThat(getJson(BASE + "/versions", a.owner())).hasSize(2);

        // And directly at the database: B's session sees none of A's rows.
        long seen = db().inTenant(b.tenantId(), c -> DbAccess.count(c,
                "select count(*) from opd.workflow_version where tenant_id = ?", a.tenantId()));
        assertThat(seen).isZero();
        long seenDrafts = db().inTenant(b.tenantId(), c -> DbAccess.count(c,
                "select count(*) from opd.workflow_draft where tenant_id = ?", a.tenantId()));
        assertThat(seenDrafts).isZero();
    }

    @Test
    void appUserCannotUpdateOrDeleteAPublishedVersion() throws Exception {
        Clinic clinic = newClinic();
        getJson(BASE + "/current", clinic.owner());
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "update opd.workflow_version set steps = '[]'::jsonb where tenant_id = ?",
                    clinic.tenantId());
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "delete from opd.workflow_version where tenant_id = ?", clinic.tenantId());
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThat(getJson(BASE + "/versions", clinic.owner())).hasSize(1);
    }
}
