package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared helpers of the SAH-37 integration tests (IPD patient card setup, ADR-015): the urls, the request bodies, small
 * editors for a setup held as a JSON tree (so a test can break exactly one rule), the stored state read by SQL, and a
 * second owner of the same clinic. Nothing here knows the application classes: the setup is whatever the API sends, and
 * the expected texts are written out in the tests from docs/architecture/SAH-37-patient-card-setup.md section 4.
 */
abstract class CardSetupSupport extends RoundSupport {

    static final String CARD_SETUP = IPD + "/card-setup";
    static final String DRAFT = CARD_SETUP + "/draft";
    static final String PUBLISH = CARD_SETUP + "/publish";
    static final String CHANGED_TEXT = "The card setup was changed by someone else. Reload to see it.";
    static final String AUDIT_EVENTS = "/api/v1/platform/audit-events?limit=100";

    static final String[] SECTION_KEYS = {"header", "status", "stay", "care", "vitals", "clinical", "billing"};
    static final String[] ACTION_KEYS = {"nursing", "emr", "transfer", "discharge", "deposit"};
    /** The roles that have the Admitted list. */
    static final String[] LIST_ROLES = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"};
    /** The roles with no IPD card access at all. */
    static final String[] NO_CARD_ROLES = {"BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"};

    // ---------------- reading ----------------

    protected JsonNode view(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, CARD_SETUP, auth, null, 200);
    }

    /** A copy of the standard setup as the server sends it, to edit. */
    protected ObjectNode standard(RequestPostProcessor auth) throws Exception {
        return view(auth).get("standard").deepCopy();
    }

    protected JsonNode listOf(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, ADMISSIONS, auth, null, 200);
    }

    protected JsonNode listAll(RequestPostProcessor owner) throws Exception {
        return ok(HttpMethod.GET, ADMISSIONS + "?cardFacts=all", owner, null, 200);
    }

    // ---------------- bodies and calls ----------------

    protected String writeBody(Integer baseVersion, Long draftRevision, JsonNode setup) {
        ObjectNode body = objectMapper.createObjectNode();
        if (baseVersion != null) {
            body.put("baseVersion", baseVersion);
        }
        if (draftRevision == null) {
            body.putNull("draftRevision");
        } else {
            body.put("draftRevision", draftRevision);
        }
        if (setup != null) {
            body.set("setup", setup);
        }
        return body.toString();
    }

    protected ResultActions putDraft(RequestPostProcessor auth, Integer base, Long revision, JsonNode setup) throws Exception {
        return call(HttpMethod.PUT, DRAFT, auth, writeBody(base, revision, setup));
    }

    protected ResultActions publish(RequestPostProcessor auth, Integer base, Long revision, JsonNode setup) throws Exception {
        return call(HttpMethod.POST, PUBLISH, auth, writeBody(base, revision, setup));
    }

    protected ResultActions discard(RequestPostProcessor auth, Object revision) throws Exception {
        return call(HttpMethod.DELETE, DRAFT + "?draftRevision=" + revision, auth, null);
    }

    protected JsonNode saveOk(RequestPostProcessor auth, int base, Long revision, JsonNode setup) throws Exception {
        return ok(HttpMethod.PUT, DRAFT, auth, writeBody(base, revision, setup), 200);
    }

    protected JsonNode publishOk(RequestPostProcessor auth, int base, Long revision, JsonNode setup) throws Exception {
        return ok(HttpMethod.POST, PUBLISH, auth, writeBody(base, revision, setup), 200);
    }

    /** Publishes the setup on top of the live version the owner sees now (no draft held), returns the answer. */
    protected JsonNode publishNow(Clinic clinic, JsonNode setup) throws Exception {
        JsonNode v = view(clinic.owner());
        JsonNode draft = v.get("draft");
        Long revision = draft == null || draft.isNull() ? null : draft.get("revision").asLong();
        return publishOk(clinic.owner(), v.get("live").get("version").asInt(), revision, setup);
    }

    /** The draft revision of an answer that holds a draft: an opaque token (ADR-015 amendment A2), never assumed to be 1, 2, 3. */
    protected static long revisionOf(JsonNode answer) {
        return answer.get("draft").get("revision").asLong();
    }

    /** The shared forbidden text (contract amendment A1 of ADR-015): the same words for every card-setup operation and for cardFacts. */
    protected static final String FORBIDDEN_TEXT = "You do not have access to this.";

    protected JsonNode forbiddenOf(ResultActions actions) throws Exception {
        JsonNode p = problem(actions, 403);
        assertType(p, "forbidden");
        assertThat(p.get("detail").asText()).as("the shared 403 text, not the old owner-only text").isEqualTo(FORBIDDEN_TEXT);
        return p;
    }

    protected JsonNode conflictOf(ResultActions actions) throws Exception {
        JsonNode p = problem(actions, 409);
        assertType(p, "card-setup-changed");
        assertThat(p.get("detail").asText()).isEqualTo(CHANGED_TEXT);
        return p;
    }

    protected static boolean hasError(JsonNode problem, String path, String message) {
        for (JsonNode e : problem.get("fieldErrors")) {
            if (path.equals(e.get("field").asText()) && message.equals(e.get("message").asText())) {
                return true;
            }
        }
        return false;
    }

    protected static boolean hasMessage(JsonNode problem, String message) {
        for (JsonNode e : problem.get("fieldErrors")) {
            if (message.equals(e.get("message").asText())) {
                return true;
            }
        }
        return false;
    }

    // ---------------- editing a setup held as a tree ----------------

    protected static ObjectNode edit(JsonNode setup, Consumer<ObjectNode> change) {
        ObjectNode copy = setup.deepCopy();
        change.accept(copy);
        return copy;
    }

    protected static int sectionIndex(JsonNode setup, String key) {
        ArrayNode sections = (ArrayNode) setup.get("sections");
        for (int i = 0; i < sections.size(); i++) {
            if (key.equals(sections.get(i).get("key").asText())) {
                return i;
            }
        }
        throw new AssertionError("no section " + key);
    }

    protected static ObjectNode sectionOf(JsonNode setup, String key) {
        return (ObjectNode) setup.get("sections").get(sectionIndex(setup, key));
    }

    /** The section key and the position of a field, as "sectionIndex:fieldIndex". */
    protected static int[] positionOf(JsonNode setup, String fieldKey) {
        ArrayNode sections = (ArrayNode) setup.get("sections");
        for (int i = 0; i < sections.size(); i++) {
            ArrayNode fields = (ArrayNode) sections.get(i).get("fields");
            for (int j = 0; j < fields.size(); j++) {
                if (fieldKey.equals(fields.get(j).get("key").asText())) {
                    return new int[] {i, j};
                }
            }
        }
        throw new AssertionError("no field " + fieldKey);
    }

    protected static ObjectNode fieldOf(JsonNode setup, String fieldKey) {
        int[] at = positionOf(setup, fieldKey);
        return (ObjectNode) setup.get("sections").get(at[0]).get("fields").get(at[1]);
    }

    protected static String fieldPath(JsonNode setup, String fieldKey) {
        int[] at = positionOf(setup, fieldKey);
        return "setup.sections[" + at[0] + "].fields[" + at[1] + "]";
    }

    protected static ObjectNode actionOf(JsonNode setup, String actionKey) {
        for (JsonNode a : setup.get("actions")) {
            if (actionKey.equals(a.get("key").asText())) {
                return (ObjectNode) a;
            }
        }
        throw new AssertionError("no action " + actionKey);
    }

    protected static void fieldOn(ObjectNode setup, String fieldKey, boolean on) {
        fieldOf(setup, fieldKey).put("on", on);
    }

    protected static void fieldLabel(ObjectNode setup, String fieldKey, String label) {
        fieldOf(setup, fieldKey).put("label", label);
    }

    protected static void sectionOn(ObjectNode setup, String sectionKey, boolean on) {
        sectionOf(setup, sectionKey).put("on", on);
    }

    protected static void actionOn(ObjectNode setup, String actionKey, boolean on) {
        actionOf(setup, actionKey).put("on", on);
    }

    /** Takes the field out of its section and appends it at the end of the other section. */
    protected static void moveField(ObjectNode setup, String fieldKey, String toSection) {
        int[] at = positionOf(setup, fieldKey);
        ArrayNode from = (ArrayNode) setup.get("sections").get(at[0]).get("fields");
        JsonNode node = from.remove(at[1]);
        ((ArrayNode) sectionOf(setup, toSection).get("fields")).add(node);
    }

    /** Moves a section to a new index of the section list. */
    protected static void moveSection(ObjectNode setup, String sectionKey, int toIndex) {
        ArrayNode sections = (ArrayNode) setup.get("sections");
        JsonNode node = sections.remove(sectionIndex(setup, sectionKey));
        sections.insert(toIndex, node);
    }

    protected static void removeField(ObjectNode setup, String fieldKey) {
        int[] at = positionOf(setup, fieldKey);
        ((ArrayNode) setup.get("sections").get(at[0]).get("fields")).remove(at[1]);
    }

    /** The keys of the fields of one section, in order, whether on or off. */
    protected static List<String> fieldKeys(JsonNode setup, String sectionKey) {
        List<String> keys = new ArrayList<>();
        sectionOf(setup, sectionKey).get("fields").forEach(f -> keys.add(f.get("key").asText()));
        return keys;
    }

    protected static List<String> sectionKeys(JsonNode setup) {
        List<String> keys = new ArrayList<>();
        setup.get("sections").forEach(s -> keys.add(s.get("key").asText()));
        return keys;
    }

    protected static List<String> texts(List<JsonNode> nodes) {
        List<String> out = new ArrayList<>();
        nodes.forEach(n -> out.add(n.asText()));
        return out;
    }

    protected static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---------------- stored state, by SQL ----------------

    /** Everything a refused write must leave as it was: the version rows, the draft row, the audit lines. */
    protected record Stored(long versions, long drafts, List<String> draft, long cardAudits, long access) {
    }

    protected Stored stored(Clinic clinic) throws SQLException {
        return new Stored(rows(clinic, "card_setup_version"), rows(clinic, "card_setup_draft"),
                values(clinic, "select revision::text, base_version::text, setup::text, saved_by::text, saved_at::text "
                        + "from ipd.card_setup_draft"),
                count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_CARD\\_%'"),
                count(clinic, "select count(*) from patient.record_access"));
    }

    // ---------------- a second owner ----------------

    /** A second, real person with the OWNER role in the same clinic (the clinic's first owner stays an owner). */
    protected Staff secondOwner(Clinic clinic) throws Exception {
        Session session = signUp(uniqueMobile());
        addMembership(clinic.tenantId(), session.userId(), "OWNER");
        return new Staff(session.userId(), "Second owner", "OWNER", tokenOf(clinic, session.userId(), "OWNER"));
    }
}
