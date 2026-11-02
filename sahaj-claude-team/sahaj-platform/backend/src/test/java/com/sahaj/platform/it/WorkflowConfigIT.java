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
        return """
                {"steps":[{"key":"token","on":true},{"key":"queue","on":true},
                 {"key":"vitals","on":true,"owner":"NURSE"},{"key":"consult","on":true},
                 {"key":"counsel","on":%s,"owner":"DOCTOR"},{"key":"casepaper","on":true},
                 {"key":"billing","on":true},{"key":"followup","on":true}]}
                """.formatted(counselOn);
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
    void publishWithNoDraftOrAnUnchangedDraftIsRefusedWith400() throws Exception {
        Clinic clinic = newClinic();
        // No draft was ever saved.
        mvc.perform(post(BASE + "/publish").with(clinic.owner()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        // A draft saved identical to the current version.
        assertThat(putDraft(clinic.owner(), steps(false)).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(post(BASE + "/publish").with(clinic.owner()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "validation"));
        assertThat(getJson(BASE + "/versions", clinic.owner())).hasSize(1);

        // Publishing twice in a row: the second has nothing new.
        putDraft(clinic.owner(), steps(true));
        mvc.perform(post(BASE + "/publish").with(clinic.owner())).andExpect(status().isOk());
        mvc.perform(post(BASE + "/publish").with(clinic.owner())).andExpect(status().isBadRequest());
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
        // Exactly one wins. The loser either lost the insert race (409) or arrived after the winner
        // committed and found nothing new to publish (400).
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
        assertThat(codes).allMatch(c -> c == 200 || c == 409 || c == 400);
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
        long oldStamp = db().inTenant(clinic.tenantId(), c -> DbAccess.count(c,
                "select workflow_version_no from opd.visit where id = ?", oldVisit));
        long newStamp = db().inTenant(clinic.tenantId(), c -> DbAccess.count(c,
                "select workflow_version_no from opd.visit where id = ?", newVisit));
        assertThat(oldStamp).isEqualTo(1);
        assertThat(newStamp).isEqualTo(2);
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
