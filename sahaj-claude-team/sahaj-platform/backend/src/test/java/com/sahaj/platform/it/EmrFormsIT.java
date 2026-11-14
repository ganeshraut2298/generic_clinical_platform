package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-59 (ADR-022 D11): the form builder as an API. The six starter forms are read-only global data; a form is created from a
 * starter, saved as a draft (structure checked on every save), sent for approval, approved or sent back, published, retired,
 * and every version is immutable. Only the owner changes anything ("Medical director" is the owner), every role reads, a
 * clinic never sees another clinic's forms, the draft body may be up to 256 KB. Real database as {@code sahaj_app}. Test
 * plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrFormsIT extends EmrPhase3Support {

    private static final String[] STARTERS = {"diabetic_foot", "gcs", "morse", "mlc_injury", "pre_anaesthetic", "untitled"};
    private static final int[] STARTER_FIELDS = {17, 6, 9, 12, 9, 1};

    private JsonNode problemWith(ObjectNode body, JsonNode view, Clinic clinic, int status, String slug) throws Exception {
        return problemOf(HttpMethod.PUT, FORMS + "/" + view.get("formKey").asText() + "/draft", clinic.owner(), body.toString(), status, slug);
    }

    private JsonNode firstFieldError(JsonNode problem) {
        return problem.get("fieldErrors").get(0);
    }

    /** A form made from the untitled starter with these fields, saved as a draft (approval as given). */
    private JsonNode draftWith(Clinic clinic, boolean approval, ObjectNode... fields) throws Exception {
        JsonNode created = createForm(clinic, "untitled", null);
        ObjectNode draft = formDraftBody(created);
        ((ObjectNode) draft.get("settings")).put("approvalRequired", approval);
        ArrayNode list = draft.putArray("fields");
        for (ObjectNode field : fields) {
            list.add(field);
        }
        return saveFormDraft(clinic, created, draft, 200);
    }

    // ================= starters =================

    @Test
    void sixStarterFormsAreReadByEveryRoleWithTheirNameDescriptionSuitedForAndFullDefinition() throws Exception {
        Clinic clinic = newClinic();
        JsonNode starters = get(FORM_STARTERS, clinic.owner());
        assertThat(texts(starters, "starterKey")).containsExactly(STARTERS);
        for (JsonNode s : starters) {
            assertThat(s.get("name").asText()).isNotBlank();
            assertThat(s.get("description").asText()).isNotBlank();
            assertThat(s.get("suitedFor").asText()).isNotBlank();
            assertThat(s.has("definition")).as("the list has no definitions").isFalse();
        }
        for (int i = 0; i < STARTERS.length; i++) {
            JsonNode detail = get(FORM_STARTERS + "/" + STARTERS[i], clinic.owner());
            assertThat(detail.get("definition").get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(detail.get("definition").get("fields")).as(STARTERS[i]).hasSize(STARTER_FIELDS[i]);
            assertThat(detail.get("definition").get("settings").get("emrPart").asText()).isEqualTo("ASSESSMENT");
            assertThat(detail.get("name").asText()).isEqualTo(detail.get("definition").get("name").asText());
        }
        for (String role : ALL_ROLES) {
            assertThat(get(FORM_STARTERS, clinic.as(role))).as(role).hasSize(6);
            assertThat(get(FORM_STARTERS + "/gcs", clinic.as(role)).get("starterKey").asText()).isEqualTo("gcs");
        }
        problemOf(HttpMethod.GET, FORM_STARTERS, anonymous(), null, 401, "unauthorized");
        problemOf(HttpMethod.GET, FORM_STARTERS + "/nope", clinic.owner(), null, 404, "form-starter-not-found");
        assertThat(globalCount("select count(*) from opd.emr_form_starter")).isEqualTo(6);
        assertThat(globalCount("select count(*) from opd.emr_form_starter where jsonb_array_length(definition -> 'fields') > 0")).isEqualTo(6);
    }

    @Test
    void theStarterContentIsAsDrawnForTheScoredFormsAndHoldsNoPatientData() throws Exception {
        Clinic clinic = newClinic();
        JsonNode gcs = get(FORM_STARTERS + "/gcs", clinic.owner()).get("definition");
        List<String> types = texts(gcs.get("fields"), "type");
        assertThat(types).contains("SINGLE_CHOICE", "SCORE", "SCORE_BAND");
        JsonNode band = null;
        for (JsonNode f : gcs.get("fields")) {
            if ("SCORE_BAND".equals(f.get("type").asText())) {
                band = f;
            }
        }
        assertThat(band.get("band").get("bands").size()).isGreaterThanOrEqualTo(2);
        JsonNode morse = get(FORM_STARTERS + "/morse", clinic.owner()).get("definition");
        assertThat(morse.get("settings").get("useIn").get("places").toString()).contains("IPD_WARD");
        for (String key : STARTERS) {
            String dump = get(FORM_STARTERS + "/" + key, clinic.owner()).toString();
            assertThat(dump).as(key).doesNotContainPattern("[0-9]{10}").doesNotContain("Ramesh");
        }
        assertThat(globalRows("select obj_description('opd.emr_form_starter'::regclass, 'pg_class')").get(0).get(0))
                .contains("not clinically reviewed");
    }

    @Test
    void everyRoleIncludingTheOwnerIsRefusedAWriteOnAStarterAndNothingChanges() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ALL_ROLES) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
                problemOf(method, FORM_STARTERS, clinic.as(role), "{\"name\":\"Hacked\"}", 403, "forbidden");
                problemOf(method, FORM_STARTERS + "/gcs", clinic.as(role), "{\"name\":\"Hacked\"}", 403, "forbidden");
            }
        }
        assertThat(globalRows("select name from opd.emr_form_starter where starter_key = 'gcs'").get(0).get(0)).isNotEqualTo("Hacked");
    }

    // ================= create =================

    @Test
    void useThisStarterMakesTheClinicsOwnDraftVersionOneAndTheStarterNeverChanges() throws Exception {
        Clinic clinic = newClinic();
        JsonNode before = get(FORM_STARTERS + "/gcs", clinic.owner());
        JsonNode form = createForm(clinic, "gcs", null);
        assertThat(form.get("formKey").asText()).matches("^f_[a-z2-7]{10}$");
        assertThat(form.get("name").asText()).isEqualTo(before.get("name").asText());
        assertThat(form.get("state").asText()).isEqualTo("DRAFT");
        assertThat(form.get("chipVersionNo").asInt()).isEqualTo(1);
        assertThat(form.get("liveVersionNo").asInt()).isZero();
        assertThat(form.get("starterKey").asText()).isEqualTo("gcs");
        assertThat(form.get("fieldCount").asInt()).isEqualTo(6);
        assertThat(form.get("emrPart").asText()).isEqualTo("ASSESSMENT");
        assertThat(form.get("canEdit").asBoolean()).isTrue();
        assertThat(form.get("canApprove").asBoolean()).isTrue();
        assertThat(form.get("definition").get("settings").get("approvalRequired").asBoolean()).as("approval is on by default").isTrue();
        assertThat(form.get("sentBackReason").isNull()).isTrue();
        assertThat(form.get("versions")).hasSize(1);
        assertThat(form.get("versions").get(0).get("versionNo").asInt()).isEqualTo(1);
        assertThat(form.get("versions").get(0).get("state").asText()).isEqualTo("DRAFT");
        assertThat(get(FORM_STARTERS + "/gcs", clinic.owner())).as("the starter is untouched").isEqualTo(before);
        // a blank form takes the name sent; the same name twice (any letter case) is refused
        JsonNode blank = createForm(clinic, "untitled", "Wound review");
        assertThat(blank.get("name").asText()).isEqualTo("Wound review");
        assertThat(blank.get("fieldCount").asInt()).isEqualTo(1);
        JsonNode problem = problemKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"untitled\",\"name\":\"WOUND REVIEW\"}", key(), 400, "validation");
        assertThat(firstFieldError(problem).get("field").asText()).isEqualTo("name");
        assertThat(firstFieldError(problem).get("message").asText()).isEqualTo("A form with this name already exists.");
        // no name sent: the starter's name, made unique with " (2)"
        JsonNode second = createForm(clinic, "gcs", null);
        assertThat(second.get("name").asText()).isEqualTo(before.get("name").asText() + " (2)");
        assertThat(get(FORMS, clinic.owner()).get("forms")).hasSize(3);
        assertThat(texts(get(FORMS, clinic.owner()).get("forms"), "name")).isSorted();
    }

    @Test
    void theSameIdempotencyKeyReturnsTheFirstFormAndAnotherBodyWithItIs409AndAMissingOrBadKeyIs400() throws Exception {
        Clinic clinic = newClinic();
        String key = key();
        JsonNode first = okKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"gcs\",\"name\":\"Same key form\"}", key, 201);
        JsonNode again = okKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"gcs\",\"name\":\"Same key form\"}", key, 200);
        assertThat(again.get("formKey").asText()).isEqualTo(first.get("formKey").asText());
        problemKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"gcs\",\"name\":\"Another name\"}", key, 409, "idempotency-conflict");
        problemKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"morse\",\"name\":\"Same key form\"}", key, 409, "idempotency-conflict");
        assertThat(get(FORMS, clinic.owner()).get("forms")).hasSize(1);
        assertThat(auditCount(clinic, "EMR_FORM_CREATED")).isEqualTo(1);
        for (String bad : new String[] {null, "short", "has spaces in it 12345", "a".repeat(65), "bad!key!bad!key"}) {
            problemKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"gcs\",\"name\":\"Other\"}", bad, 400, "validation");
        }
        problemKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"nope\",\"name\":\"Other\"}", key(), 404, "form-starter-not-found");
        for (String body : new String[] {"{}", "{\"starterKey\":null}", "{\"starterKey\":\"gcs\",\"name\":\"" + "n".repeat(81) + "\"}",
                "{\"starterKey\":\"gcs\",\"name\":\"\"}", "{\"starterKey\":\"gcs\",\"zxqextra\":1}"}) {
            assertThat(callKeyed(HttpMethod.POST, FORMS, clinic.owner(), body, key()).andReturn().getResponse().getStatus()).as(body).isEqualTo(400);
        }
        assertThat(get(FORMS, clinic.owner()).get("forms")).hasSize(1);
    }

    @Test
    void aClinicHasAtMostOneHundredForms() throws Exception {
        Clinic clinic = newClinic();
        for (int i = 0; i < 100; i++) {
            okKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"untitled\",\"name\":\"Limit form " + i + "\"}", key(), 201);
        }
        problemKeyed(HttpMethod.POST, FORMS, clinic.owner(), "{\"starterKey\":\"untitled\",\"name\":\"One too many\"}", key(), 400, "form-limit");
        assertThat(get(FORMS, clinic.owner()).get("forms")).hasSize(100);
        // another clinic is not counted
        createForm(newClinic(), "untitled", "Other clinic form");
    }

    // ================= who may do what =================

    @Test
    void everyRoleReadsTheListAndAFormButOnlyTheOwnerChangesAnythingAndATokenThatClaimsOwnerIsRefusedToo() throws Exception {
        Clinic clinic = newClinic();
        java.util.Map<String, org.springframework.test.web.servlet.request.RequestPostProcessor> tokens = tokensOfEveryRole(clinic);
        JsonNode form = createForm(clinic, "gcs", null);
        String path = FORMS + "/" + form.get("formKey").asText();
        for (String role : ALL_ROLES) {
            JsonNode list = get(FORMS, tokens.get(role));
            boolean owner = "OWNER".equals(role);
            assertThat(list.get("canEdit").asBoolean()).as(role).isEqualTo(owner);
            assertThat(list.get("canApprove").asBoolean()).as(role).isEqualTo(owner);
            JsonNode view = get(path, tokens.get(role));
            assertThat(view.get("canEdit").asBoolean()).as(role).isEqualTo(owner);
            assertThat(view.get("definition").get("fields")).hasSize(6);
        }
        ObjectNode draft = formDraftBody(form);
        String revision = revisionBody(form);
        for (String role : NON_OWNER_ROLES) {
            problemKeyed(HttpMethod.POST, FORMS, tokens.get(role), "{\"starterKey\":\"gcs\",\"name\":\"Nope\"}", key(), 403, "forbidden");
            problemOf(HttpMethod.PUT, path + "/draft", tokens.get(role), draft.toString(), 403, "forbidden");
            for (String action : new String[] {"submit", "approve", "publish", "retire"}) {
                problemOf(HttpMethod.POST, path + "/" + action, tokens.get(role), revision, 403, "forbidden");
            }
            problemOf(HttpMethod.POST, path + "/send-back", tokens.get(role), "{\"revision\":0,\"reason\":\"No\"}", 403, "forbidden");
        }
        // a nurse whose token says OWNER: the service re-reads the membership
        Staff nurse = addNonDoctor(clinic, "NURSE");
        problemOf(HttpMethod.PUT, path + "/draft", nurse.tokenClaiming(clinic, "OWNER"), draft.toString(), 403, "forbidden");
        problemKeyed(HttpMethod.POST, FORMS, nurse.tokenClaiming(clinic, "OWNER"), "{\"starterKey\":\"gcs\",\"name\":\"Nope\"}", key(), 403, "forbidden");
        problemOf(HttpMethod.POST, path + "/publish", nurse.tokenClaiming(clinic, "OWNER"), revision, 403, "forbidden");
        problemOf(HttpMethod.GET, FORMS, anonymous(), null, 401, "unauthorized");
        problemOf(HttpMethod.PUT, path + "/draft", anonymous(), draft.toString(), 401, "unauthorized");
        assertThat(get(path, clinic.owner())).as("nothing changed").isEqualTo(form);
        assertThat(auditCount(clinic, "EMR_FORM_DRAFT_SAVED")).isZero();
    }

    // ================= the state machine =================

    @Test
    void withApprovalOnADraftIsSentForApprovalSentBackWithAReasonSentAgainAndApprovedIntoAnImmutableVersionOne() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = createForm(clinic, "gcs", "Approval form");
        JsonNode draft = saveFormDraft(clinic, created, formDraftBody(created), 200);
        assertThat(draft.get("state").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("revision").asLong()).isGreaterThan(created.get("revision").asLong());
        // publish is for approval off only
        problemOf(HttpMethod.POST, FORMS + "/" + draft.get("formKey").asText() + "/publish", clinic.owner(), revisionBody(draft), 409, "form-state");
        // approve and send back need a waiting version
        problemOf(HttpMethod.POST, FORMS + "/" + draft.get("formKey").asText() + "/approve", clinic.owner(), revisionBody(draft), 409, "form-state");
        problemOf(HttpMethod.POST, FORMS + "/" + draft.get("formKey").asText() + "/send-back", clinic.owner(),
                "{\"revision\":" + draft.get("revision").asLong() + ",\"reason\":\"Why\"}", 409, "form-state");
        JsonNode waiting = formAction(clinic, draft, "submit", 200);
        assertThat(waiting.get("state").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.get("liveVersionNo").asInt()).isZero();
        problemOf(HttpMethod.POST, FORMS + "/" + waiting.get("formKey").asText() + "/submit", clinic.owner(), revisionBody(waiting), 409, "form-state");
        // the editor sends it back with a reason
        JsonNode back = post(FORMS + "/" + waiting.get("formKey").asText() + "/send-back", clinic.owner(),
                "{\"revision\":" + waiting.get("revision").asLong() + ",\"reason\":\"Add the eye score\"}", 200);
        assertThat(back.get("state").asText()).isEqualTo("SENT_BACK");
        assertThat(back.get("sentBackReason").asText()).isEqualTo("Add the eye score");
        problemOf(HttpMethod.POST, FORMS + "/" + back.get("formKey").asText() + "/approve", clinic.owner(), revisionBody(back), 409, "form-state");
        JsonNode again = formAction(clinic, back, "submit", 200);
        assertThat(again.get("state").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(again.get("sentBackReason").isNull()).as("the reason goes when it is sent again").isTrue();
        // a clinic with one owner approves its own form
        JsonNode live = formAction(clinic, again, "approve", 200);
        assertThat(live.get("state").asText()).isEqualTo("LIVE");
        assertThat(live.get("liveVersionNo").asInt()).isEqualTo(1);
        assertThat(live.get("chipVersionNo").asInt()).isEqualTo(1);
        assertThat(live.get("versions")).hasSize(1);
        assertThat(live.get("versions").get(0).get("state").asText()).isEqualTo("LIVE");
        JsonNode version = get(FORMS + "/" + live.get("formKey").asText() + "/versions/1", clinic.owner());
        assertThat(version.get("versionNo").asInt()).isEqualTo(1);
        assertThat(version.get("definition").get("fields")).hasSize(6);
        assertThat(version.get("publishedByName").asText()).isNotBlank();
        assertThat(version.get("approvedByName").asText()).as("approved by the owner").isNotBlank();
        // approving again, or sending a live form with no draft for approval, is a state error
        problemOf(HttpMethod.POST, FORMS + "/" + live.get("formKey").asText() + "/approve", clinic.owner(), revisionBody(live), 409, "form-state");
        problemOf(HttpMethod.POST, FORMS + "/" + live.get("formKey").asText() + "/submit", clinic.owner(), revisionBody(live), 409, "form-state");
    }

    @Test
    void withApprovalOffPublishMakesTheDraftLiveAtOnceAndSubmitIsRefused() throws Exception {
        Clinic clinic = newClinic();
        JsonNode draft = draftWith(clinic, false, formField("note", "LONG_TEXT", "Note"));
        problemOf(HttpMethod.POST, FORMS + "/" + draft.get("formKey").asText() + "/submit", clinic.owner(), revisionBody(draft), 409, "form-state");
        JsonNode live = formAction(clinic, draft, "publish", 200);
        assertThat(live.get("state").asText()).isEqualTo("LIVE");
        assertThat(live.get("liveVersionNo").asInt()).isEqualTo(1);
        assertThat(get(FORMS + "/" + live.get("formKey").asText() + "/versions/1", clinic.owner()).get("approvedByName").isNull())
                .as("published without approval").isTrue();
        problemOf(HttpMethod.POST, FORMS + "/" + live.get("formKey").asText() + "/publish", clinic.owner(), revisionBody(live), 409, "form-state");
    }

    @Test
    void editingALiveFormMakesDraftVersionTwoWhileVersionOneStaysInUseAndEveryVersionStaysExactlyAsPublished() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v1 = formAction(clinic, draftWith(clinic, false, formField("note", "LONG_TEXT", "Note")), "publish", 200);
        String key = v1.get("formKey").asText();
        JsonNode versionOne = get(FORMS + "/" + key + "/versions/1", clinic.owner());
        ObjectNode edit = formDraftBody(v1);
        edit.put("name", "Renamed form");
        ((ArrayNode) edit.get("fields")).add(formField("extra", "SHORT_TEXT", "Extra"));
        JsonNode draft = saveFormDraft(clinic, v1, edit, 200);
        assertThat(draft.get("liveVersionNo").asInt()).as("version 1 is still the live one").isEqualTo(1);
        assertThat(draft.get("chipVersionNo").asInt()).isEqualTo(2);
        assertThat(draft.get("definition").get("fields")).as("the builder works on the draft").hasSize(2);
        assertThat(texts(draft.get("versions"), "versionNo")).containsExactly("2", "1");
        assertThat(draft.get("versions").get(0).get("state").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("versions").get(1).get("state").asText()).isEqualTo("LIVE");
        assertThat(get(FORMS + "/" + key + "/versions/1", clinic.owner())).as("version 1 is untouched by the draft").isEqualTo(versionOne);
        problemOf(HttpMethod.GET, FORMS + "/" + key + "/versions/2", clinic.owner(), null, 404, "version-not-found");
        JsonNode v2 = formAction(clinic, draft, "publish", 200);
        assertThat(v2.get("liveVersionNo").asInt()).isEqualTo(2);
        assertThat(v2.get("name").asText()).isEqualTo("Renamed form");
        assertThat(texts(v2.get("versions"), "state")).containsExactly("LIVE", "SUPERSEDED");
        assertThat(get(FORMS + "/" + key + "/versions/1", clinic.owner())).as("an old version is kept exactly").isEqualTo(versionOne);
        assertThat(get(FORMS + "/" + key + "/versions/2", clinic.owner()).get("definition").get("fields")).hasSize(2);
        assertThat(count(clinic, "select count(*) from opd.emr_form_version")).isEqualTo(2);
        for (String bad : new String[] {"0", "100001", "-1", "abc"}) {
            assertThat(call(HttpMethod.GET, FORMS + "/" + key + "/versions/" + bad, clinic.owner(), null).andReturn().getResponse().getStatus())
                    .as(bad).isEqualTo(400);
        }
        problemOf(HttpMethod.GET, FORMS + "/" + key + "/versions/1", newClinic().owner(), null, 404, "form-not-found");
    }

    @Test
    void aStaleRevisionIs409FormChangedForEverySaveAndActionAndNothingIsApplied() throws Exception {
        Clinic clinic = newClinic();
        JsonNode draft = draftWith(clinic, false, formField("note", "LONG_TEXT", "Note"));
        String path = FORMS + "/" + draft.get("formKey").asText();
        ObjectNode stale = formDraftBody(draft);
        stale.put("revision", draft.get("revision").asLong() + 7);
        problemOf(HttpMethod.PUT, path + "/draft", clinic.owner(), stale.toString(), 409, "form-changed");
        String staleBody = "{\"revision\":" + (draft.get("revision").asLong() + 7) + "}";
        for (String action : new String[] {"publish", "retire"}) {
            problemOf(HttpMethod.POST, path + "/" + action, clinic.owner(), staleBody, 409, "form-changed");
        }
        assertThat(get(path, clinic.owner())).isEqualTo(draft);
        // two owners save at the same moment from the same revision: one wins, one is told
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> save = () -> {
                go.await();
                return call(HttpMethod.PUT, path + "/draft", clinic.owner(), formDraftBody(draft).toString()).andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Future<Integer> one = pool.submit(save);
            java.util.concurrent.Future<Integer> two = pool.submit(save);
            go.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(one.get(), two.get()));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retiringAFormKeepsItAndEveryVersionRefusesFurtherChangesAndRetiringTwiceWritesNothing() throws Exception {
        Clinic clinic = newClinic();
        JsonNode live = formAction(clinic, draftWith(clinic, false, formField("note", "LONG_TEXT", "Note")), "publish", 200);
        String path = FORMS + "/" + live.get("formKey").asText();
        JsonNode retired = formAction(clinic, live, "retire", 200);
        assertThat(retired.get("state").asText()).isEqualTo("RETIRED");
        assertThat(retired.get("liveVersionNo").asInt()).isEqualTo(1);
        assertThat(get(path + "/versions/1", clinic.owner()).get("definition").get("fields")).hasSize(1);
        assertThat(auditCount(clinic, "EMR_FORM_RETIRED")).isEqualTo(1);
        JsonNode again = formAction(clinic, retired, "retire", 200);
        assertThat(again.get("state").asText()).isEqualTo("RETIRED");
        assertThat(auditCount(clinic, "EMR_FORM_RETIRED")).as("retiring twice writes nothing").isEqualTo(1);
        problemOf(HttpMethod.PUT, path + "/draft", clinic.owner(), formDraftBody(retired).toString(), 409, "form-state");
        for (String action : new String[] {"submit", "approve", "publish"}) {
            problemOf(HttpMethod.POST, path + "/" + action, clinic.owner(), revisionBody(retired), 409, "form-state");
        }
        assertThat(get(FORMS, clinic.owner()).get("forms")).as("a retired form is still listed").hasSize(1);
        assertThat(count(clinic, "select count(*) from opd.emr_form where status = 'RETIRED'")).isEqualTo(1);
        assertThat(count(clinic, "select count(*) from opd.emr_form_version")).isEqualTo(1);
    }

    @Test
    void allSixStartersSaveAsDraftUnchangedAndPassThePublishChecksAndGoLive() throws Exception {
        Clinic clinic = newClinic();
        for (String starter : STARTERS) {
            JsonNode created = createForm(clinic, starter, null);
            JsonNode saved = saveFormDraft(clinic, created, formDraftBody(created), 200);
            JsonNode waiting = formAction(clinic, saved, "submit", 200);
            JsonNode live = formAction(clinic, waiting, "approve", 200);
            assertThat(live.get("liveVersionNo").asInt()).as(starter).isEqualTo(1);
            assertThat(live.get("definition").get("fields")).as(starter).hasSize(created.get("definition").get("fields").size());
        }
        assertThat(count(clinic, "select count(*) from opd.emr_form_version")).isEqualTo(6);
    }

    // ================= structure checks on every save, publish checks at submit =================

    @Test
    void aDraftWithNoFieldOrNoDepartmentSavesButCannotBeSentForApprovalOrPublished() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = createForm(clinic, "untitled", "Empty form");
        ObjectNode empty = formDraftBody(created);
        empty.putArray("fields");
        ((ObjectNode) empty.get("settings")).put("approvalRequired", false);
        JsonNode saved = saveFormDraft(clinic, created, empty, 200);
        assertThat(saved.get("fieldCount").asInt()).isZero();
        JsonNode problem = problemOf(HttpMethod.POST, FORMS + "/" + saved.get("formKey").asText() + "/publish", clinic.owner(), revisionBody(saved), 400, "form-not-publishable");
        assertThat(problem.toString()).contains("Add at least one field.");
        ObjectNode noPlace = formDraftBody(saved);
        noPlace.set("fields", objectMapper.createArrayNode().add(formField("note", "LONG_TEXT", "Note")));
        ObjectNode useIn = (ObjectNode) noPlace.get("settings").get("useIn");
        useIn.putArray("departmentKeys");
        JsonNode second = saveFormDraft(clinic, saved, noPlace, 200);
        JsonNode problem2 = problemOf(HttpMethod.POST, FORMS + "/" + second.get("formKey").asText() + "/publish", clinic.owner(), revisionBody(second), 400, "form-not-publishable");
        assertThat(problem2.toString()).contains("Pick at least one department in Form settings first.");
        assertThat(count(clinic, "select count(*) from opd.emr_form_version")).isZero();
        assertThat(get(FORMS + "/" + second.get("formKey").asText(), clinic.owner()).get("state").asText()).isEqualTo("DRAFT");
    }

    @Test
    void aScoreBandMustReadAScoreHaveAscendingBandsAndStartAtOrBelowTheLowestPossibleScore() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode severity = choiceField("sev", "SINGLE_CHOICE", "Severity", "mild:1", "severe:4");
        ObjectNode total = formField("total", "SCORE", "Total");
        ObjectNode score = total.putObject("score");
        score.putArray("sourceFieldKeys").add("sev");
        score.putArray("rules");
        ObjectNode band = formField("band", "SCORE_BAND", "Band");
        ObjectNode setup = band.putObject("band");
        setup.put("sourceFieldKey", "total");
        ArrayNode bands = setup.putArray("bands");
        bands.addObject().put("from", 3).put("label", "Low");
        bands.addObject().put("from", 6).put("label", "High");
        JsonNode draft = draftWith(clinic, false, severity, total, band);
        JsonNode high = problemOf(HttpMethod.POST, FORMS + "/" + draft.get("formKey").asText() + "/publish", clinic.owner(), revisionBody(draft), 400, "form-not-publishable");
        assertThat(high.toString()).as("the lowest possible total is 1").contains("The first band must start at or below the lowest possible score (1)");
        // not ascending
        ObjectNode flat = formDraftBody(draft);
        ArrayNode flatBands = (ArrayNode) flat.get("fields").get(2).get("band").get("bands");
        flatBands.removeAll();
        flatBands.addObject().put("from", 0).put("label", "Low");
        flatBands.addObject().put("from", 5).put("label", "Mid");
        flatBands.addObject().put("from", 5).put("label", "High");
        JsonNode flatDraft = saveFormDraft(clinic, draft, flat, 200);
        JsonNode notAscending = problemOf(HttpMethod.POST, FORMS + "/" + flatDraft.get("formKey").asText() + "/publish", clinic.owner(), revisionBody(flatDraft), 400, "form-not-publishable");
        assertThat(notAscending.toString()).contains("Bands must run from the lowest score to the highest");
        // fixed: it goes live
        ObjectNode fixed = formDraftBody(flatDraft);
        ArrayNode fixedBands = (ArrayNode) fixed.get("fields").get(2).get("band").get("bands");
        fixedBands.removeAll();
        fixedBands.addObject().put("from", 0).put("label", "Low");
        fixedBands.addObject().put("from", 5).put("label", "High");
        JsonNode fixedDraft = saveFormDraft(clinic, flatDraft, fixed, 200);
        assertThat(formAction(clinic, fixedDraft, "publish", 200).get("liveVersionNo").asInt()).isEqualTo(1);
    }

    @Test
    void theStructureOfADraftIsCheckedOnEverySaveAndABadDraftIs400FormInvalidWithTheFieldNamedAndNothingIsStored() throws Exception {
        Clinic clinic = newClinic();
        JsonNode form = draftWith(clinic, false, formField("sev_yn", "YES_NO", "Is it severe"), formField("note", "LONG_TEXT", "Note"));
        List<ObjectNode> bad = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        // 0: duplicate keys
        ObjectNode b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(1)).put("key", "sev_yn");
        bad.add(b);
        expected.add("fields[1].key");
        // 1: key outside the pattern
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(0)).put("key", "Bad Key");
        bad.add(b);
        expected.add("fields[0].key");
        // 2: empty label
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(0)).put("label", "");
        bad.add(b);
        expected.add("fields[0].label");
        // 3: markup in a label
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(0)).put("label", "<b>bold</b>");
        bad.add(b);
        expected.add("fields[0].label");
        // 4: unknown type
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(0)).put("type", "FREE_TEXT");
        bad.add(b);
        expected.add("fields[0].type");
        // 5: width outside 1 to 6
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(0)).put("width", 7);
        bad.add(b);
        expected.add("fields[0].width");
        // 6: a choice field with no options
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(1)).put("type", "SINGLE_CHOICE");
        bad.add(b);
        expected.add("fields[1].options");
        // 7: show-if that points to a later field
        b = formDraftBody(form);
        ObjectNode showIf = ((ObjectNode) b.get("fields").get(0)).putObject("showIf");
        showIf.put("fieldKey", "note");
        showIf.put("optionKey", "yes");
        bad.add(b);
        expected.add("fields[0].showIf");
        // 8: show-if on an option that does not exist
        b = formDraftBody(form);
        showIf = ((ObjectNode) b.get("fields").get(1)).putObject("showIf");
        showIf.put("fieldKey", "sev_yn");
        showIf.put("optionKey", "maybe");
        bad.add(b);
        expected.add("fields[1].showIf");
        // 9: limits on a field that is not a number
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(1)).put("min", 1);
        bad.add(b);
        expected.add("fields[1].min");
        // 10: lowest above highest
        b = formDraftBody(form);
        ((ObjectNode) b.get("fields").get(1)).put("type", "NUMBER").put("min", 9).put("max", 2);
        bad.add(b);
        expected.add("fields[1].min");
        // 11: a department the clinic does not have
        b = formDraftBody(form);
        ((ArrayNode) b.get("settings").get("useIn").get("departmentKeys")).add("zz_nothing");
        bad.add(b);
        expected.add("settings.useIn.departmentKeys");
        // 12: a place that is not Emergency or IPD ward
        b = formDraftBody(form);
        ((ArrayNode) b.get("settings").get("useIn").get("places")).add("PHARMACY");
        bad.add(b);
        expected.add("settings.useIn.places");
        // 13: a print language outside the list
        b = formDraftBody(form);
        ((ObjectNode) b.get("settings")).put("printLanguage", "FR");
        bad.add(b);
        expected.add("settings.printLanguage");
        // 14: a name of 81 characters
        b = formDraftBody(form);
        b.put("name", "n".repeat(81));
        bad.add(b);
        expected.add("name");
        // 15: 81 fields
        b = formDraftBody(form);
        ArrayNode eighty = b.putArray("fields");
        for (int i = 0; i < 81; i++) {
            eighty.add(formField("f" + i, "SHORT_TEXT", "Field " + i));
        }
        bad.add(b);
        expected.add("fields");
        // 16: a score that adds up a field the form does not have
        b = formDraftBody(form);
        ObjectNode total = formField("total", "SCORE", "Total");
        total.putObject("score").putArray("sourceFieldKeys").add("ghost");
        total.with("score").putArray("rules");
        ((ArrayNode) b.get("fields")).add(total);
        bad.add(b);
        expected.add("fields[2].score");
        // 17: a band that reads a field that is not a score
        b = formDraftBody(form);
        ObjectNode band = formField("band", "SCORE_BAND", "Band");
        ObjectNode setup = band.putObject("band");
        setup.put("sourceFieldKey", "note");
        setup.putArray("bands").addObject().put("from", 0).put("label", "Low");
        ((ArrayNode) b.get("fields")).add(band);
        bad.add(b);
        expected.add("fields[2].band");
        for (int i = 0; i < bad.size(); i++) {
            JsonNode problem = problemWith(bad.get(i), form, clinic, 400, "form-invalid");
            assertThat(firstFieldError(problem).get("field").asText()).as("case " + i).isEqualTo(expected.get(i));
            assertThat(problem.toString()).as("case " + i).doesNotContain("FREE_TEXT").doesNotContain("zz_nothing");
        }
        assertThat(get(FORMS + "/" + form.get("formKey").asText(), clinic.owner())).as("nothing was stored").isEqualTo(form);
        assertThat(auditCount(clinic, "EMR_FORM_DRAFT_SAVED")).isEqualTo(1);
        // a closed body: an unknown property is refused and a clinic id is ignored
        ObjectNode extra = formDraftBody(form);
        extra.put("zxqextra", 1);
        assertThat(problemWith(extra, form, clinic, 400, "validation").toString()).doesNotContain("zxqextra");
        ObjectNode withClinic = formDraftBody(form);
        withClinic.put("tenantId", UUID.randomUUID().toString());
        withClinic.put("clinicId", UUID.randomUUID().toString());
        saveFormDraft(clinic, form, withClinic, 200);
    }

    @Test
    void aFieldThatAnotherFieldReadsCannotBeRemovedAndTheTwoFormsOfAClinicCannotShareANameIgnoringCase() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode severity = choiceField("sev", "SINGLE_CHOICE", "Severity", "mild:1", "severe:4");
        ObjectNode detail = formField("detail", "SHORT_TEXT", "Detail");
        ObjectNode showIf = detail.putObject("showIf");
        showIf.put("fieldKey", "sev");
        showIf.put("optionKey", "severe");
        JsonNode form = draftWith(clinic, false, severity, detail);
        ObjectNode removed = formDraftBody(form);
        ((ArrayNode) removed.get("fields")).remove(0);
        JsonNode problem = problemWith(removed, form, clinic, 400, "form-invalid");
        assertThat(problem.toString()).contains("sev").contains("detail");
        // both go together: allowed
        ObjectNode both = formDraftBody(form);
        both.putArray("fields").add(formField("note", "LONG_TEXT", "Note"));
        saveFormDraft(clinic, form, both, 200);
        // names
        JsonNode other = createForm(clinic, "untitled", "Second form");
        ObjectNode rename = formDraftBody(other);
        rename.put("name", get(FORMS + "/" + form.get("formKey").asText(), clinic.owner()).get("name").asText().toUpperCase());
        JsonNode clash = problemWith(rename, other, clinic, 400, "form-invalid");
        assertThat(firstFieldError(clash).get("field").asText()).isEqualTo("name");
        // the same name in another clinic is fine
        createForm(newClinic(), "untitled", form.get("name").asText());
    }

    // ================= the size limit =================

    @Test
    void aDraftBodyUpToTwoHundredFiftySixKilobytesIsAcceptedAndOneOverIs413WhileOtherEmrBodiesStayAtSixtyFour() throws Exception {
        Clinic clinic = newClinic();
        ArrayNode list = objectMapper.createArrayNode();
        for (int f = 0; f < 40; f++) {
            ObjectNode field = choiceField("big" + f, "DROPDOWN", "Big field " + f, "o0:0");
            ArrayNode options = field.putArray("options");
            for (int o = 0; o < 30; o++) {
                ObjectNode option = options.addObject();
                option.put("key", "o" + o);
                option.put("label", "L".repeat(100));
                option.put("score", 0);
            }
            list.add(field);
        }
        JsonNode created = createForm(clinic, "untitled", "Big form");
        ObjectNode body = formDraftBody(created);
        body.set("fields", list);
        int bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(bytes).as("over the 64 KB of other bodies, under 256 KB").isBetween(70_000, 250_000);
        JsonNode saved = saveFormDraft(clinic, created, body, 200);
        assertThat(saved.get("fieldCount").asInt()).isEqualTo(40);
        // over 256 KB: 413 before anything is read
        ObjectNode over = formDraftBody(saved);
        over.put("padding", "x".repeat(260 * 1024));
        problemOf(HttpMethod.PUT, FORMS + "/" + saved.get("formKey").asText() + "/draft", clinic.owner(), over.toString(), 413, "payload-too-large");
        assertThat(get(FORMS + "/" + saved.get("formKey").asText(), clinic.owner()).get("fieldCount").asInt()).isEqualTo(40);
        // another EMR path keeps its 64 KB
        Rec rec = startRecord(clinic, addDoctor(clinic, "General physician"));
        ObjectNode big = draftBody(0, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"" + "x".repeat(70_000) + "\"}"));
        assertThat(call(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), big.toString()).andReturn().getResponse().getStatus()).isEqualTo(413);
    }

    // ================= audit and tenant isolation =================

    @Test
    void everyChangeWritesOneAuditEntryWithIdsAndCountsAndNoFieldLabels() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = createForm(clinic, "gcs", "Audit form");
        JsonNode draft = saveFormDraft(clinic, created, formDraftBody(created), 200);
        JsonNode waiting = formAction(clinic, draft, "submit", 200);
        JsonNode back = post(FORMS + "/" + waiting.get("formKey").asText() + "/send-back", clinic.owner(),
                "{\"revision\":" + waiting.get("revision").asLong() + ",\"reason\":\"Needs work\"}", 200);
        JsonNode again = formAction(clinic, back, "submit", 200);
        JsonNode live = formAction(clinic, again, "approve", 200);
        formAction(clinic, live, "retire", 200);
        for (String action : new String[] {"EMR_FORM_CREATED", "EMR_FORM_DRAFT_SAVED", "EMR_FORM_SENT_BACK", "EMR_FORM_APPROVED", "EMR_FORM_RETIRED"}) {
            assertThat(auditCount(clinic, action)).as(action).isEqualTo(1);
        }
        assertThat(auditCount(clinic, "EMR_FORM_SUBMITTED")).isEqualTo(2);
        List<List<String>> approved = auditRows(clinic, "EMR_FORM_APPROVED");
        assertThat(approved.get(0).get(0)).isEqualTo("EMR_FORM");
        assertThat(approved.get(0).get(3)).isEqualTo(clinic.ownerUserId().toString());
        JsonNode after = objectMapper.readTree(approved.get(0).get(2));
        assertThat(after.get("formKey").asText()).isEqualTo(live.get("formKey").asText());
        assertThat(after.get("versionNo").asInt()).isEqualTo(1);
        assertThat(after.get("fieldCount").asInt()).isEqualTo(6);
        for (String action : new String[] {"EMR_FORM_CREATED", "EMR_FORM_DRAFT_SAVED", "EMR_FORM_SUBMITTED", "EMR_FORM_SENT_BACK", "EMR_FORM_APPROVED", "EMR_FORM_RETIRED"}) {
            for (List<String> row : auditRows(clinic, action)) {
                for (JsonNode field : get(FORM_STARTERS + "/gcs", clinic.owner()).get("definition").get("fields")) {
                    assertThat(row.get(1) + row.get(2)).as(action).doesNotContain(field.get("label").asText());
                }
            }
        }
    }

    @Test
    void anotherClinicNeverSeesChangesOrVersionsOfAFormAndTheSameFormKeyIsNotFoundThere() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        JsonNode form = formAction(a, draftWith(a, false, formField("note", "LONG_TEXT", "Note")), "publish", 200);
        String path = FORMS + "/" + form.get("formKey").asText();
        assertThat(get(FORMS, b.owner()).get("forms")).isEmpty();
        problemOf(HttpMethod.GET, path, b.owner(), null, 404, "form-not-found");
        problemOf(HttpMethod.GET, path + "/versions/1", b.owner(), null, 404, "form-not-found");
        problemOf(HttpMethod.PUT, path + "/draft", b.owner(), formDraftBody(form).toString(), 404, "form-not-found");
        for (String action : new String[] {"submit", "approve", "publish", "retire"}) {
            problemOf(HttpMethod.POST, path + "/" + action, b.owner(), revisionBody(form), 404, "form-not-found");
        }
        problemOf(HttpMethod.POST, path + "/send-back", b.owner(), "{\"revision\":0,\"reason\":\"No\"}", 404, "form-not-found");
        problemOf(HttpMethod.GET, FORMS + "/f_aaaaaaaaaa", a.owner(), null, 404, "form-not-found");
        assertThat(call(HttpMethod.GET, FORMS + "/not-a-key", a.owner(), null).andReturn().getResponse().getStatus()).isIn(400, 404);
        assertThat(get(path, a.owner()).get("liveVersionNo").asInt()).isEqualTo(1);
        // the database holds the same wall: clinic B reads none of clinic A's rows
        assertThat(count(b, "select count(*) from opd.emr_form")).isZero();
        assertThat(count(b, "select count(*) from opd.emr_form_version")).isZero();
        assertThat(count(a, "select count(*) from opd.emr_form")).isEqualTo(1);
    }

    @Test
    void aVersionCannotBeChangedOrDeletedByTheAppOrByTheTableOwnerAndAFormHasNoDelete() throws Exception {
        Clinic clinic = newClinic();
        JsonNode form = formAction(clinic, draftWith(clinic, false, formField("note", "LONG_TEXT", "Note")), "publish", 200);
        assertThat(form.get("liveVersionNo").asInt()).isEqualTo(1);
        db().inTenant(clinic.tenantId(), c -> {
            assertThat(expectError(c, "update opd.emr_form_version set definition = '{}'::jsonb").state()).isEqualTo("42501");
            assertThat(expectError(c, "delete from opd.emr_form_version").state()).isEqualTo("42501");
            assertThat(expectError(c, "truncate opd.emr_form_version").state()).isIn("42501", "0A000");
            assertThat(expectError(c, "delete from opd.emr_form").state()).isEqualTo("42501");
            assertThat(expectError(c, "update opd.emr_form set form_key = 'f_zzzzzzzzzz'").state()).isEqualTo("42501");
            assertThat(expectError(c, "update opd.emr_form set tenant_id = gen_random_uuid()").state()).isEqualTo("42501");
            return null;
        });
        db().asMigrator(c -> {
            // the table owner has no row policy (the policies are for sahaj_app, the tables are FORCEd): it sees no row, so a row
            // update or delete changes nothing; the statement-level truncate trigger is the owner's wall
            assertThat(DbAccess.count(c, "select count(*) from opd.emr_form_version")).as("the owner sees no row").isZero();
            assertThat(expectError(c, "truncate opd.emr_form_version").state()).isIn("42501", "0A000");
            return null;
        });
        assertThat(count(clinic, "select count(*) from opd.emr_form_version")).isEqualTo(1);
    }
}
