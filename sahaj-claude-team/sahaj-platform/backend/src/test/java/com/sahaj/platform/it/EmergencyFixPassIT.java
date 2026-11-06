package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-28 review fix pass (code review 1, 5, 9 and security review M1(a), M3, L1, L2), through the API on the real
 * {@code sahaj_test} database:
 * <ul>
 *   <li>the mobile is null on the row after release, refer and admit, and the hand-off returns it while the case is
 *       open (M1(a), code review 9);</li>
 *   <li>the hand-off writes exactly one audit row {@code IPD_ER_HANDOFF_OPENED} for each 200 answer and none for a 403,
 *       404, 409 or 401 (M3);</li>
 *   <li>a repeat of a client key by another user, or after the case closed, returns exactly id, level and status; the
 *       original registrant gets the full card while the case is open (L1);</li>
 *   <li>strict JSON: identityUnknown missing, null or not a boolean; age and level as strings; PUT level as a string
 *       (code review 5(b), 5(c));</li>
 *   <li>IpdText by Unicode code point, for the emergency name and complaint, a SAH-26 ward name and a SAH-23 reason
 *       (L2), and the new "a letter" rules;</li>
 *   <li>a read of the queue, the counts, the tile numbers and the hand-off when a stored level is higher than the
 *       setup's scale gives 200, not 500 (code review 1).</li>
 * </ul>
 * Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencyFixPassIT extends EmergencySupport {

    private static final String RELOAD = "Reload the page and try again.";
    private static final String AGE_MESSAGE = "Enter the age as a whole number from 0 to 120, or leave it empty";
    private static final String NAME_CONTROL = "Name cannot contain control or text-direction characters";
    private static final String COMPLAINT_CONTROL = "Complaint cannot contain control or text-direction characters";
    private static final String WARD_CONTROL = "Ward name cannot contain control or text-direction characters";
    private static final String REASON_CONTROL = "Reason cannot contain control or text-direction characters";
    private static final Object MISSING = new Object();
    private static final ObjectMapper ESCAPING = new ObjectMapper(JsonFactory.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build());

    // ---------------- helpers ----------------

    private RequestPostProcessor otherPerson(Clinic clinic, String role) throws Exception {
        Member member = addMember(clinic, role);
        return bearer(login(member.mobile()).accessToken());
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** JSON in which every non-ASCII UTF-16 unit is a \\uXXXX escape, so that a lone surrogate reaches the server intact. */
    private String escaped(Object value) throws Exception {
        return ESCAPING.writeValueAsString(value);
    }

    private long statusOf(org.springframework.test.web.servlet.ResultActions actions) {
        return actions.andReturn().getResponse().getStatus();
    }

    // ---------------- M1(a): the mobile ----------------

    @Test
    void theMobileIsNullOnTheRowAfterReleaseReferAndAdmitAndTheHandOffReturnsItOnlyWhileTheCaseIsOpen() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        String m1 = uniqueMobile();
        String m2 = uniqueMobile();
        String m3 = uniqueMobile();
        String m4 = uniqueMobile();
        String m5 = uniqueMobile();
        String a = registerWithMobile(clinic, "Mob Alpha", 2, m1);
        String b = registerWithMobile(clinic, "Mob Bravo", 3, m2);
        String c = registerWithMobile(clinic, "Mob Charlie", 3, m3);
        Map<String, Object> unknown = unknownBody("M", 50, 3);
        unknown.put("mobile", m4);
        String d = register(clinic, unknown).get("actedCase").get("id").asText();
        String e = registerWithMobile(clinic, "Mob Echo", 3, m5);

        // while open: stored, handed over by the hand-off, not changed by seen, re-triage, a refused release or a failed admit
        assertThat(col(clinic, a, "mobile")).isEqualTo(m1);
        assertThat(optionsFor(clinic, "OWNER", a).get("emergencyCase").get("mobile").asText()).isEqualTo(m1);
        seen(clinic, a);
        retriage(clinic, a, 3);
        assertThat(col(clinic, a, "mobile")).as("seen and re-triaged").isEqualTo(m1);
        assertThat(statusOf(act(clinic.as("FRONT_DESK"), a, "release"))).isEqualTo(403);
        assertThat(statusOf(admitCall(clinic.owner(), admitBody(clinic, newPatient(clinic), UUID.randomUUID(), a))))
                .as("a failed admit").isBetween(400L, 499L);
        assertThat(col(clinic, a, "mobile")).as("after refusals").isEqualTo(m1);
        assertThat(col(clinic, a, "status")).isEqualTo("SEEN");
        assertThat(optionsFor(clinic, "NURSE", a).get("emergencyCase").get("mobile").asText()).isEqualTo(m1);

        // release, refer and admit clear it in the update that closes the case
        String released = release(clinic, a).toString();
        String referred = refer(clinic, b).toString();
        JsonNode admission = ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, newPatient(clinic), bed, c)), 201);
        assertThat(col(clinic, a, "status")).isEqualTo("RELEASED");
        assertThat(col(clinic, b, "status")).isEqualTo("REFERRED");
        assertThat(col(clinic, c, "status")).isEqualTo("ADMITTED");
        assertThat(col(clinic, a, "mobile")).as("released").isNull();
        assertThat(col(clinic, b, "mobile")).as("referred").isNull();
        assertThat(col(clinic, c, "mobile")).as("admitted").isNull();
        assertThat(col(clinic, c, "admission_id")).isEqualTo(admission.get("id").asText());
        release(clinic, d);
        assertThat(col(clinic, d, "mobile")).as("an unknown patient's mobile too").isNull();

        // no closed row holds a mobile; the open one keeps its number
        assertThat(count(clinic, "select count(*) from ipd.emergency_case where status in ('RELEASED','REFERRED','ADMITTED') "
                + "and mobile is not null")).isZero();
        assertThat(values(clinic, "select string_agg(mobile, ',') from ipd.emergency_case where mobile is not null"))
                .containsExactly(m5);
        assertThat(col(clinic, e, "mobile")).isEqualTo(m5);

        // the answers never carried it, and the hand-off of a closed case is refused without it
        for (String body : List.of(released, referred, admission.toString())) {
            assertThat(body).doesNotContain(m1).doesNotContain(m2).doesNotContain(m3);
        }
        for (String id : List.of(a, b, c, d)) {
            String refusal = call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, clinic.owner(), null).andReturn().getResponse()
                    .getContentAsString();
            assertCaseClosed(objectMapper.readTree(refusal));
            assertThat(refusal).doesNotContain(m1).doesNotContain(m2).doesNotContain(m3).doesNotContain(m4);
        }
        assertThat(optionsFor(clinic, "OWNER", e).get("emergencyCase").get("mobile").asText()).isEqualTo(m5);
    }

    private String registerWithMobile(Clinic clinic, String name, int level, String mobile) throws Exception {
        Map<String, Object> body = caseBody(name, level);
        body.put("mobile", mobile);
        return register(clinic, body).get("actedCase").get("id").asText();
    }

    // ---------------- M3: the hand-off audit row ----------------

    @Test
    void theHandOffWritesOneAuditRowForEach200AnswerAndNoneForAnAnswerThatIsRefused() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        newBed(clinic, "gen");
        String id = reg(clinic, "Audit Subject", 2).get("id").asText();
        String closed = reg(clinic, "Audit Closed", 3).get("id").asText();
        release(clinic, closed);
        Clinic off = newClinic();
        Written before = written(clinic);
        Written otherBefore = written(other);
        Written offBefore = written(off);
        String url = OPTIONS + "?emergencyCaseId=" + id;

        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            assertType(problem(call(HttpMethod.GET, url, clinic.as(role), null), 403), "forbidden");
        }
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + UUID.randomUUID(), clinic.owner(), null), 404), "not-found");
        assertCaseClosed(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + closed, clinic.owner(), null), 409));
        assertType(problem(call(HttpMethod.GET, url, other.owner(), null), 404), "not-found");     // another clinic's case
        assertType(problem(call(HttpMethod.GET, url, anonymous(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + id, off.owner(), null), 403), "module-off");
        assertThat(statusOf(call(HttpMethod.GET, OPTIONS + "?emergencyCaseId=not-a-uuid", clinic.owner(), null))).isBetween(400L, 404L);
        ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);                                    // no case: SAH-23 answer
        assertThat(written(clinic)).as("403, 404, 409, 401 and a read without a case write nothing").isEqualTo(before);
        assertThat(written(other)).isEqualTo(otherBefore);
        assertThat(written(off)).isEqualTo(offBefore);
        assertThat(audits(clinic, "IPD_ER_HANDOFF_OPENED")).isZero();
        assertThat(audits(other, "IPD_ER_HANDOFF_OPENED")).isZero();

        // each 200 answer writes one row: three roles, then Read-only
        for (String role : new String[] {"OWNER", "NURSE", "FRONT_DESK"}) {
            long rows = audits(clinic, "IPD_ER_HANDOFF_OPENED");
            optionsFor(clinic, role, id);
            assertThat(audits(clinic, "IPD_ER_HANDOFF_OPENED")).as(role).isEqualTo(rows + 1);
        }
        optionsFor(clinic, "OWNER", id);
        expireTrial(clinic);
        optionsFor(clinic, "OWNER", id);
        assertThat(audits(clinic, "IPD_ER_HANDOFF_OPENED")).as("the Read-only answer is audited too").isEqualTo(5);
        for (String[] row : audit(clinic, "IPD_ER_HANDOFF_OPENED")) {
            assertThat(row[0]).isEqualTo("IPD_EMERGENCY_CASE");
            assertThat(row[1]).isEqualTo(id);
            assertThat(row[2]).isNull();
            assertThat(keys(objectMapper.readTree(row[3]))).containsExactly("caseId");
            assertThat(row[4]).isEqualTo(clinic.ownerUserId().toString());
        }
        assertThat(audits(other, "IPD_ER_HANDOFF_OPENED")).as("another clinic sees none of them").isZero();
        assertThat(count(clinic, "select count(*) from patient.record_access")).isZero();
    }

    // ---------------- L1: a repeat of the client key ----------------

    @Test
    void aRepeatOfAClientKeyByAnotherUserOrAfterTheCaseClosedReturnsExactlyIdLevelAndStatus() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor nurse = otherPerson(clinic, "NURSE");
        RequestPostProcessor frontDesk = otherPerson(clinic, "FRONT_DESK");
        String name = "Repeat Subject " + uniqueMobile().substring(5);
        String complaint = "ZXQ-" + UUID.randomUUID();
        String mobile = uniqueMobile();
        Map<String, Object> body = caseBody(name, 2);
        body.put("complaint", complaint);
        body.put("mobile", mobile);
        JsonNode first = register(clinic, body).get("actedCase");
        String id = first.get("id").asText();
        Written before = written(clinic);

        // the registrant, case open: the full card, even when the repeated body is different
        Map<String, Object> different = new LinkedHashMap<>(body);
        different.put("name", "A Different Name");
        different.put("level", 1);
        for (Map<String, Object> repeat : List.of(body, different)) {
            JsonNode own = ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(repeat), 200).get("actedCase");
            assertThat(own.get("id").asText()).isEqualTo(id);
            assertThat(own.get("displayName").asText()).isEqualTo(name);
            assertThat(own.get("complaint").asText()).isEqualTo(complaint);
            assertThat(own.get("status").asText()).isEqualTo("WAITING");
        }
        // another real user of the clinic, case open: id, level, status only
        for (RequestPostProcessor who : List.of(nurse, frontDesk)) {
            JsonNode answer = ok(HttpMethod.POST, ER_CASES, who, js(body), 200);
            JsonNode ref = answer.get("actedCase");
            assertThat(keys(ref)).containsExactly("id", "level", "status");
            assertThat(ref.get("id").asText()).isEqualTo(id);
            assertThat(ref.get("level").asInt()).isEqualTo(2);
            assertThat(ref.get("status").asText()).isEqualTo("WAITING");
            assertThat(answer.has("counts") && answer.has("cases")).as("it is still the queue answer").isTrue();
        }
        assertThat(written(clinic)).as("a repeat writes nothing").isEqualTo(before);

        // the level and the status follow the case
        retriage(clinic, id, 1);
        seen(clinic, id);
        Written seenState = written(clinic);
        JsonNode ref = ok(HttpMethod.POST, ER_CASES, nurse, js(body), 200).get("actedCase");
        assertThat(keys(ref)).containsExactly("id", "level", "status");
        assertThat(ref.get("level").asInt()).isOne();
        assertThat(ref.get("status").asText()).isEqualTo("SEEN");
        JsonNode own = ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(body), 200).get("actedCase");
        assertThat(own.get("status").asText()).isEqualTo("SEEN");
        assertThat(own.get("displayName").asText()).isEqualTo(name);
        assertThat(written(clinic)).isEqualTo(seenState);

        // after the case closed, nobody gets the card, the registrant included; the answer holds none of the typed values
        release(clinic, id);
        Written closedState = written(clinic);
        for (RequestPostProcessor who : List.of(clinic.owner(), nurse, frontDesk)) {
            String raw = call(HttpMethod.POST, ER_CASES, who, js(body)).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode closedRef = objectMapper.readTree(raw).get("actedCase");
            assertThat(keys(closedRef)).containsExactly("id", "level", "status");
            assertThat(closedRef.get("id").asText()).isEqualTo(id);
            assertThat(closedRef.get("level").asInt()).isOne();
            assertThat(closedRef.get("status").asText()).isEqualTo("RELEASED");
            assertThat(raw).doesNotContain(name).doesNotContain(complaint).doesNotContain(mobile);
        }
        assertThat(written(clinic)).isEqualTo(closedState);

        // referred and admitted cases answer the same way, with their own status
        Map<String, Object> second = caseBody("Repeat Referred", 3);
        refer(clinic, register(clinic, second).get("actedCase").get("id").asText());
        assertThat(keys(ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(second), 200).get("actedCase"))).containsExactly("id", "level", "status");
        assertThat(ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(second), 200).get("actedCase").get("status").asText()).isEqualTo("REFERRED");
        Map<String, Object> third = caseBody("Repeat Admitted", 3);
        String thirdId = register(clinic, third).get("actedCase").get("id").asText();
        ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(admitBody(clinic, newPatient(clinic), newBed(clinic, "gen"), thirdId)), 201);
        JsonNode admitted = ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(third), 200).get("actedCase");
        assertThat(keys(admitted)).containsExactly("id", "level", "status");
        assertThat(admitted.get("status").asText()).isEqualTo("ADMITTED");

        // a role that may not register is still refused before the key is looked at
        assertType(problem(registerCall(clinic.as("BILLING"), body), 403), "forbidden");
    }

    // ---------------- code review 5(b), 5(c): strict JSON ----------------

    @Test
    void identityUnknownMissingNullOrNotABooleanIsAFieldErrorAndNeverReadAsFalse() throws Exception {
        Clinic clinic = hospital();
        Written before = written(clinic);
        Object[] bad = {MISSING, null, "true", "false", "", "yes", 1, 0, List.of(true), Map.of("v", true)};
        for (Object value : bad) {
            String label = value == MISSING ? "missing" : String.valueOf(value);
            // a good body: only the flag is wrong
            assertThat(fieldsOf(refusedWith(clinic, value, null, null))).as(label).containsExactly("identityUnknown");
            // the name checks are skipped when the flag is wrong
            assertThat(fieldsOf(refusedWith(clinic, value, "name", ""))).as(label + " with an empty name").containsExactly("identityUnknown");
            // the other errors come after it, in the order of the contract
            assertThat(fieldsOf(refusedWith(clinic, value, "sex", "X"))).as(label + " with a bad sex").containsExactly("identityUnknown", "sex");
            assertThat(fieldsOf(refusedWith(clinic, value, "level", 9))).as(label + " with a bad level")
                    .containsExactly("identityUnknown", "level");
            assertThat(messageOf(refusedWith(clinic, value, null, null), "identityUnknown")).as(label).isEqualTo(RELOAD);
        }
        assertThat(written(clinic)).as("nothing was stored: a missing flag is not false").isEqualTo(before);
        assertThat(caseRows(clinic)).isZero();
        // positive controls: a real boolean is accepted either way
        assertThat(register(clinic, caseBody("Flag false", 2)).get("actedCase").get("identityPending").asBoolean()).isFalse();
        assertThat(register(clinic, unknownBody("M", 40, 2)).get("actedCase").get("identityPending").asBoolean()).isTrue();
    }

    private JsonNode refusedWith(Clinic clinic, Object flag, String field, Object value) throws Exception {
        Map<String, Object> body = caseBody("Asha Patil", 2);
        if (flag == MISSING) {
            body.remove("identityUnknown");
        } else {
            body.put("identityUnknown", flag);
        }
        if (field != null) {
            body.put(field, value);
        }
        JsonNode p = problem(registerCall(clinic.owner(), body), 400);
        assertType(p, "validation");
        return p;
    }

    @Test
    void anAgeOrALevelSentAsAStringOrAnotherTypeIsRefusedWithThatFieldsMessageAndNeverConverted() throws Exception {
        Clinic clinic = hospital();
        Written before = written(clinic);
        Object[] notNumbers = {"40", "", "forty", true, List.of(40), Map.of("v", 40)};
        for (Object value : notNumbers) {
            Map<String, Object> ageBody = caseBody("Asha Patil", 2);
            ageBody.put("age", value);
            JsonNode p = problem(registerCall(clinic.owner(), ageBody), 400);
            assertType(p, "validation");
            assertThat(fieldsOf(p)).as("age " + value).containsExactly("age");
            assertThat(messageOf(p, "age")).as("age " + value).isEqualTo(AGE_MESSAGE);
        }
        Object[] notLevels = {"2", "1", "", "high", true, List.of(2), Map.of("v", 2)};
        for (Object value : notLevels) {
            Map<String, Object> levelBody = caseBody("Asha Patil", 2);
            levelBody.put("level", value);
            JsonNode p = problem(registerCall(clinic.owner(), levelBody), 400);
            assertThat(fieldsOf(p)).as("level " + value).containsExactly("level");
            assertThat(messageOf(p, "level")).as("level " + value).isEqualTo(LEVEL_MESSAGE);
        }
        // together, and with a string flag: three messages in the order of the contract
        Map<String, Object> all = caseBody("Asha Patil", 2);
        all.put("identityUnknown", "true");
        all.put("age", "40");
        all.put("level", "2");
        JsonNode p = problem(registerCall(clinic.owner(), all), 400);
        assertThat(fieldsOf(p)).containsExactly("identityUnknown", "age", "level");
        assertThat(messageOf(p, "identityUnknown")).isEqualTo(RELOAD);
        assertThat(messageOf(p, "age")).isEqualTo(AGE_MESSAGE);
        assertThat(messageOf(p, "level")).isEqualTo(LEVEL_MESSAGE);
        assertThat(written(clinic)).isEqualTo(before);
        assertThat(caseRows(clinic)).isZero();
        // a number is still accepted
        Map<String, Object> good = caseBody("Asha Patil", 2);
        assertThat(register(clinic, good).get("actedCase").get("age").asInt()).isEqualTo(40);
    }

    @Test
    void aReTriageWithTheLevelAsAStringOrAnotherTypeIsRefusedWithTheLevelMessage() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Asha Patil", 2).get("id").asText();
        Written before = written(clinic);
        for (String json : new String[] {"{\"level\":\"2\"}", "{\"level\":\"1\"}", "{\"level\":\"3\"}", "{\"level\":\"\"}",
                "{\"level\":true}", "{\"level\":[1]}", "{\"level\":{\"v\":1}}", "{\"level\":\"one\"}"}) {
            JsonNode p = problem(retriageCall(clinic.owner(), id, json), 400);
            assertType(p, "validation");
            assertThat(fieldsOf(p)).as(json).containsExactly("level");
            assertThat(messageOf(p, "level")).as(json).isEqualTo(LEVEL_MESSAGE);
        }
        assertThat(written(clinic)).as("nothing changed").isEqualTo(before);
        assertThat(col(clinic, id, "level")).isEqualTo("2");
        assertThat(retriage(clinic, id, 1).get("actedCase").get("level").asInt()).as("a number is still accepted").isOne();
    }

    // ---------------- security L2: IpdText by code point ----------------

    /** Characters the server must refuse in a name, a complaint, a ward name and a reason, with a label for the message. */
    private static Map<String, String> refusedCharacters() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tag character U+E0001", cp(0xE0001));
        m.put("tag letter A U+E0041", cp(0xE0041));
        m.put("cancel tag U+E007F", cp(0xE007F));
        m.put("Hangul filler U+3164", cp(0x3164));
        m.put("Hangul choseong filler U+115F", cp(0x115F));
        m.put("Hangul jungseong filler U+1160", cp(0x1160));
        m.put("halfwidth Hangul filler U+FFA0", cp(0xFFA0));
        m.put("braille blank U+2800", cp(0x2800));
        m.put("soft hyphen U+00AD", cp(0x00AD));
        m.put("Mongolian vowel separator U+180E", cp(0x180E));
        m.put("combining grapheme joiner U+034F", cp(0x034F));
        m.put("variation selector U+FE0F", cp(0xFE0F));
        m.put("variation selector U+FE00", cp(0xFE00));
        m.put("line separator U+2028", cp(0x2028));
        m.put("paragraph separator U+2029", cp(0x2029));
        m.put("interlinear mark U+FFF9", cp(0xFFF9));
        m.put("deprecated format U+206A", cp(0x206A));
        m.put("zero-width space U+200B", cp(0x200B));
        m.put("right-to-left override U+202E", cp(0x202E));
        m.put("byte order mark U+FEFF", cp(0xFEFF));
        m.put("next line U+0085", cp(0x85));
        m.put("delete U+007F", cp(0x7F));
        m.put("private use U+E000", cp(0xE000));
        m.put("supplementary private use U+F0000", cp(0xF0000));
        m.put("unassigned U+0378", cp(0x0378));
        m.put("noncharacter U+10FFFF", cp(0x10FFFF));
        m.put("lone high surrogate", String.valueOf((char) 0xD800));
        m.put("lone low surrogate", String.valueOf((char) 0xDC00));
        return m;
    }

    @Test
    void theNameAndTheComplaintRefuseEveryInvisibleBlankLookingAndSurrogateCodePointInAnyPlace() throws Exception {
        Clinic clinic = hospital();
        Written before = written(clinic);
        for (Map.Entry<String, String> e : refusedCharacters().entrySet()) {
            String c = e.getValue();
            for (String text : new String[] {"Ra" + c + "vi", c + "Ravi", "Ravi" + c}) {
                String label = e.getKey() + " in " + text.length() + " units, at " + text.indexOf(c);
                Map<String, Object> nameBody = caseBody(text, 2);
                JsonNode p = problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(nameBody)), 400);
                assertThat(fieldsOf(p)).as("name: " + label).containsExactly("name");
                assertThat(messageOf(p, "name")).as("name: " + label).isEqualTo(NAME_CONTROL);

                Map<String, Object> complaintBody = caseBody("Asha Patil", 2);
                complaintBody.put("complaint", text);
                p = problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(complaintBody)), 400);
                assertThat(fieldsOf(p)).as("complaint: " + label).containsExactly("complaint");
                assertThat(messageOf(p, "complaint")).as("complaint: " + label).isEqualTo(COMPLAINT_CONTROL);
            }
        }
        // the control message wins over "no letter", and both fields can fail together
        Map<String, Object> both = caseBody("12" + cp(0x200B) + "3", 2);
        both.put("complaint", "--" + cp(0xE0001));
        JsonNode p = problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(both)), 400);
        assertThat(fieldsOf(p)).containsExactly("name", "complaint");
        assertThat(messageOf(p, "name")).isEqualTo(NAME_CONTROL);
        assertThat(messageOf(p, "complaint")).isEqualTo(COMPLAINT_CONTROL);
        // an unknown patient's hidden name is not read at all; the complaint still is
        Map<String, Object> hidden = unknownBody("M", 40, 2);
        hidden.put("name", cp(0x3164));
        assertThat(register(clinic, hidden).get("actedCase").get("displayName").asText()).isEqualTo("Unknown male, about 40");
        Map<String, Object> hiddenBadComplaint = unknownBody("M", 40, 2);
        hiddenBadComplaint.put("complaint", "Pain" + cp(0xE0001));
        assertThat(messageOf(problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(hiddenBadComplaint)), 400), "complaint"))
                .isEqualTo(COMPLAINT_CONTROL);
        assertThat(caseRows(clinic)).as("only the unknown patient was stored").isOne();
        assertThat(written(clinic).events()).isOne();
        assertThat(written(clinic).cases()).isOne();
        assertThat(before.cases()).isZero();
    }

    @Test
    void aNameNeedsAtLeastOneLetterAndAComplaintAtLeastOneLetterOrDigitInAnyScript() throws Exception {
        Clinic clinic = hospital();
        for (String noLetter : new String[] {"12345", "0", "---", "...", " . ", "+91 98765 43210", "😀😀", "😀", cp(0x0301),
                "१२३", "٣٤٥", "\"'", "(1)"}) {
            Map<String, Object> body = caseBody(noLetter, 2);
            JsonNode p = problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(body)), 400);
            assertThat(fieldsOf(p)).as("name " + noLetter).containsExactly("name");
            assertThat(messageOf(p, "name")).as("name " + noLetter).isEqualTo(NAME_REQUIRED);
        }
        for (String noLetterOrDigit : new String[] {"...", "!!!", "-", "?", "😀", "😀 😀", cp(0x0301), " . , ; ", "()"}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("complaint", noLetterOrDigit);
            JsonNode p = problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(body)), 400);
            assertThat(fieldsOf(p)).as("complaint " + noLetterOrDigit).containsExactly("complaint");
            assertThat(messageOf(p, "complaint")).as("complaint " + noLetterOrDigit).isEqualTo(COMPLAINT_REQUIRED);
        }
        assertThat(caseRows(clinic)).isZero();

        // accepted: a letter of any script, with digits, punctuation and emoji around it; a digit alone is enough for a complaint
        for (String name : new String[] {"Ravi", "O'Neil-Smith", "Ravi 2", "😀 Ravi", "अशोक पाटील", "ராஜா", "محمد", "李小龍", cp(0x20000) + cp(0x20001),
                "x", "Zoe" + cp(0x0308), "ß"}) {
            Map<String, Object> body = caseBody(name, 2);
            JsonNode acted = ok(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(body), 201).get("actedCase");
            String expected = Normalizer.normalize(name, Normalizer.Form.NFC);
            assertThat(acted.get("displayName").asText()).as("name " + name).isEqualTo(expected);
            assertThat(col(clinic, acted.get("id").asText(), "display_name")).isEqualTo(expected);
        }
        for (String complaint : new String[] {"5", "102", "१", "x", "Fever 🤒", "BP 140/90", "😀 fever"}) {
            Map<String, Object> body = caseBody("Asha Patil", 2);
            body.put("complaint", complaint);
            JsonNode acted = ok(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(body), 201).get("actedCase");
            assertThat(acted.get("complaint").asText()).as("complaint " + complaint).isEqualTo(complaint);
        }
        // a letter outside the basic plane counts once toward the 80 limit
        String supplementary = cp(0x20000).repeat(80);
        assertThat(ok(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(caseBody(supplementary, 2)), 201).get("actedCase")
                .get("displayName").asText()).isEqualTo(supplementary);
        assertThat(messageOf(problem(call(HttpMethod.POST, ER_CASES, clinic.owner(), escaped(caseBody(supplementary + cp(0x20000), 2))), 400),
                "name")).isEqualTo("Enter a name of up to 80 characters");
    }

    @Test
    void aWardNameAndAReasonForAdmissionRefuseTheSameWiderSetOfCharacters() throws Exception {
        Clinic clinic = hospital();
        UUID patient = newPatient(clinic);
        UUID bed = newBed(clinic, "gen");
        long wards = rows(clinic, "ward");
        long admissions = rows(clinic, "admission");
        Written before = written(clinic);
        for (Map.Entry<String, String> e : refusedCharacters().entrySet()) {
            String text = "Ward " + e.getValue() + "A";
            Map<String, Object> ward = new LinkedHashMap<>();
            ward.put("name", text);
            JsonNode p = problem(call(HttpMethod.POST, WARDS, clinic.owner(), escaped(ward)), 400);
            assertThat(messageOf(p, "name")).as("ward name with " + e.getKey()).isEqualTo(WARD_CONTROL);

            Map<String, Object> admit = admitBody(clinic, patient, bed, null);
            admit.put("reason", "Fever " + e.getValue());
            p = problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), escaped(admit)), 400);
            assertThat(messageOf(p, "reason")).as("reason with " + e.getKey()).isEqualTo(REASON_CONTROL);
        }
        // the end of the name is not trimmed away before the check
        Map<String, Object> trailing = new LinkedHashMap<>();
        trailing.put("name", "ICU " + cp(0xE0001));
        assertThat(messageOf(problem(call(HttpMethod.POST, WARDS, clinic.owner(), escaped(trailing)), 400), "name")).isEqualTo(WARD_CONTROL);
        assertThat(rows(clinic, "ward")).isEqualTo(wards);
        assertThat(rows(clinic, "admission")).isEqualTo(admissions);
        assertThat(written(clinic)).isEqualTo(before);
        // ordinary names are still fine
        Map<String, Object> fine = new LinkedHashMap<>();
        fine.put("name", "Ward 3 " + cp(0x20000));
        ok(HttpMethod.POST, WARDS, clinic.owner(), escaped(fine), 201);
    }

    // ---------------- code review 1: a level above the scale is not a 500 ----------------

    @Test
    void aLevelFiveCaseWhileTheSetupIsThreeColoursIsReadAsAnAnswerAndNotAnError() throws Exception {
        Clinic clinic = hospital();
        newBed(clinic, "gen");
        setScale(clinic, 5);
        String four = regWaited(clinic, "Level four", 4, 130).get("id").asText();
        String five = regWaited(clinic, "Level five", 5, 130).get("id").asText();
        String one = regWaited(clinic, "Level one", 1, 1).get("id").asText();
        // the API moves cases with the scale; to reach the state a racing read could meet, set the scale back directly
        // in the table (sahaj_app may update ipd.config) without mapping the cases
        commitAsTenant(clinic.tenantId(), "update ipd.config set triage_scale = 3");
        assertThat(col(clinic, five, "level")).as("the stored level is above the scale").isEqualTo("5");
        Written before = written(clinic);

        JsonNode q = ok(HttpMethod.GET, ER, clinic.owner(), null, 200);
        assertThat(q.get("scale").asInt()).isEqualTo(3);
        assertThat(q.get("levels")).hasSize(3);
        assertThat(ids(q)).containsExactlyInAnyOrder(one, four, five);
        int over = 0;
        for (JsonNode c : q.get("cases")) {
            int level = c.get("level").asInt();
            assertThat(level).as("a card is inside the scale of its answer").isBetween(1, 3);
            JsonNode scaleLevel = q.get("levels").get(level - 1);
            assertThat(c.get("levelName").asText()).isEqualTo(scaleLevel.get("name").asText());
            assertThat(c.get("targetMinutes").asInt()).isEqualTo(scaleLevel.get("targetMinutes").asInt());
            assertThat(c.get("overTarget").asBoolean())
                    .isEqualTo(c.get("status").asText().equals("WAITING") && c.get("waitingMinutes").asInt() > c.get("targetMinutes").asInt());
            over += c.get("overTarget").asBoolean() ? 1 : 0;
        }
        assertThat(q.get("counts").get("queue").asInt()).isEqualTo(3);
        assertThat(q.get("counts").get("overTarget").asInt()).isEqualTo(over);

        JsonNode summary = ok(HttpMethod.GET, ER_SUMMARY, clinic.owner(), null, 200);
        assertThat(summary.get("queue").asInt()).isEqualTo(3);
        assertThat(summary.get("overTarget").asInt()).isEqualTo(over);
        JsonNode tile = ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200).get("emergency");
        assertThat(tile.get("queue").asInt()).isEqualTo(3);
        assertThat(tile.get("overTarget").asInt()).isEqualTo(over);
        JsonNode hand = optionsFor(clinic, "OWNER", five).get("emergencyCase");
        assertThat(hand.get("level").asInt()).isBetween(1, 3);
        assertThat(hand.get("levelName").asText()).isEqualTo(q.get("levels").get(hand.get("level").asInt() - 1).get("name").asText());
        Written afterReads = written(clinic);
        assertThat(afterReads.dump()).as("the reads changed no case").isEqualTo(before.dump());
        assertThat(afterReads.events()).isEqualTo(before.events());
        // the same for the actions that build the queue answer
        assertThat(seen(clinic, four).get("cases")).hasSize(3);
        assertThat(retriage(clinic, five, 3).get("actedCase").get("level").asInt()).isEqualTo(3);
        assertThat(release(clinic, five).get("cases")).hasSize(2);

    }
}
