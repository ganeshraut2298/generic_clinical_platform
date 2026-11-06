package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-31 review fix pass on the real {@code sahaj_test} database (docs/reviews/SAH-31-security.md S1, S2, S3, S4 and
 * docs/reviews/SAH-31-code.md R3): the wider allergy keywords and the "unknown" texts (S1), the patient audit rows that say
 * only whether an allergy or chronic text exists (S2), the pharmacist list as a record access (S3), and the merge of two
 * patient records that never loses an allergy (S4). The R12 regressions are the existing order, block, needs-reason, replay,
 * stop, verify and reject tests of this story. Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineReviewFixIT extends MedicineSupport {

    private static final String NONE_KNOWN = "None known";
    private static final String MARKER = "zq7x";
    private static final String NOT_RECORDED = "Allergies are not recorded. Ask the patient and record them before the first dose.";

    // ---------------- helpers ----------------

    private JsonNode newPatient(Clinic clinic, String allergy, String chronic) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Asha Joshi " + uniqueMobile().substring(5));
        p.put("age", 41);
        p.put("sex", "F");
        p.put("mobile", uniqueMobile());
        p.put("area", "Kothrud");
        p.put("language", "mr");
        if (allergy != null) {
            p.put("allergy", allergy);
        }
        if (chronic != null) {
            p.put("chronic", chronic);
        }
        return ok(HttpMethod.POST, PATIENTS, clinic.owner(), js(p), 201);
    }

    private JsonNode merge(Clinic clinic, String keepId, String mergeId) throws Exception {
        return ok(HttpMethod.POST, PATIENTS + "/merge", clinic.owner(),
                js(Map.of("keepId", keepId, "mergeId", mergeId)), 200);
    }

    private long accessTotal(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from patient.record_access");
    }

    private JsonNode firstHardStop(JsonNode answer) {
        return answer.get("check").get("hardStops").get(0);
    }

    // ================= S1: allergy keywords and "unknown" texts =================

    @Test
    void everyNewAllergyKeywordIsAHardStopForItsDrugsAndTheAllergyTextIsNeverInAnEventAnAuditRowOrALogLine() throws Exception {
        // penicillin class: amox and pip; sulfa class: cotri
        String[][] cases = {{"Augmentin", "amox", "ALLERGY_PENICILLIN"}, {"PCN", "pip", "ALLERGY_PENICILLIN"},
                {"Ampicillin", "amox", "ALLERGY_PENICILLIN"}, {"Cloxacillin", "pip", "ALLERGY_PENICILLIN"},
                {"Amoxycillin", "amox", "ALLERGY_PENICILLIN"}, {"Sulphonamide", "cotri", "ALLERGY_SULFA"},
                {"Septran", "cotri", "ALLERGY_SULFA"}, {"Bactrim", "cotri", "ALLERGY_SULFA"},
                {"Co-trimoxazole", "cotri", "ALLERGY_SULFA"}, {"  AUGMENTIN  ", "pip", "ALLERGY_PENICILLIN"},
                {"septran DS", "cotri", "ALLERGY_SULFA"}};
        World w = world(NONE_KNOWN);
        String logs;
        try (LogCapture capture = new LogCapture()) {
            for (String[] c : cases) {
                setAllergy(w.clinic(), w.adm(), c[0].trim() + " " + MARKER);
                JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                        js(orderBody(newKey(), c[1], "od", null, null)), 200);
                assertThat(a.get("outcome").asText()).as(c[0]).isEqualTo("BLOCKED");
                assertThat(firstHardStop(a).get("code").asText()).as(c[0]).isEqualTo(c[2]);
                assertThat(a.get("check").get("allergyStatus").asText()).as(c[0]).isEqualTo("RECORDED");
                // the doctor sees the full text on screen
                assertThat(firstHardStop(a).get("text").asText()).contains(MARKER);
                assertThat(emr(w.doctor().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).isEqualTo("RECORDED");
            }
            logs = capture.allText();
        }
        assertThat(orderCount(w.clinic(), w.adm().id())).as("nothing stored").isZero();
        List<String> events = eventLines(w.clinic(), w.adm().id());
        assertThat(events.stream().filter(e -> e.contains("Order blocked: ")).count()).isEqualTo(cases.length);
        assertThat(String.join("\n", events)).doesNotContain(MARKER).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
        // every audit row of the clinic (the patient rows too, since review S2), the stored warnings and the logs
        assertThat(allAuditText(w.clinic())).doesNotContain(MARKER).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
        assertThat(logs).doesNotContain(MARKER).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
        assertThat(count(w.clinic(), "select count(*) from platform.audit_event where action = 'IPD_MED_ORDER_BLOCKED'"))
                .isEqualTo(cases.length);
    }

    @Test
    void aKeywordOfOneClassDoesNotStopTheOtherClassAndANearMissIsNotAKeyword() throws Exception {
        World w = world("Septran " + MARKER);
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "amox", "od", null, null)),
                201).get("outcome").asText()).as("a sulfa keyword does not stop a penicillin drug").isEqualTo("PLACED");
        setAllergy(w.clinic(), w.adm(), "Augmentin " + MARKER);
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "cotri", "od", null, null)),
                201).get("outcome").asText()).as("a penicillin keyword does not stop a sulfa drug").isEqualTo("PLACED");
        // an unrelated allergy stops neither, and is a recorded allergy (no warning)
        setAllergy(w.clinic(), w.adm(), "Peanuts " + MARKER);
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "pip", "od", null, null)),
                201).get("outcome").asText()).isEqualTo("PLACED");
    }

    @Test
    void unknownNotKnownAndAQuestionMarkAloneAreNotRecordedWithTheChipAndTheWarningWhileATextThatSaysMoreIsRecorded() throws Exception {
        World w = world(NONE_KNOWN);
        for (String text : new String[] {"unknown", "Not known", "?", "  UNKNOWN  ", "NOT KNOWN", "Unknown"}) {
            setAllergy(w.clinic(), w.adm(), text);
            assertThat(emr(w.doctor().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).as(text)
                    .isEqualTo("NOT_RECORDED");
            JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                    js(orderBody(newKey(), "pcm", "od", null, null)), 200);
            assertThat(a.get("outcome").asText()).as(text).isEqualTo("NEEDS_REASON");
            assertThat(a.get("check").get("allergyStatus").asText()).isEqualTo("NOT_RECORDED");
            JsonNode warning = a.get("check").get("warnings").get(0);
            assertThat(warning.get("code").asText()).isEqualTo("ALLERGIES_NOT_RECORDED");
            assertThat(warning.get("severity").asText()).isEqualTo("MODERATE");
            assertThat(warning.get("text").asText()).isEqualTo(NOT_RECORDED);
        }
        assertThat(orderCount(w.clinic(), w.adm().id())).isZero();
        // the queue shows the same chip for a placed order
        setAllergy(w.clinic(), w.adm(), "?");
        String id = orderIdOf(placeWithReason(w.doctor().auth(), w.adm().id(), "pcm", "BENEFIT_MONITORED"));
        JsonNode row = queueRow(queue(w.pharmacist().auth()), id);
        assertThat(row.get("patient").get("allergyStatus").asText()).isEqualTo("NOT_RECORDED");
        assertThat(finding(row.get("check"), "warnings", "ALLERGIES_NOT_RECORDED")).isNotNull();
        // text that says more than the placeholder is a recorded allergy, no warning
        for (String text : new String[] {"Unknown rash with fish", "Not known to react to anything but dust", "??"}) {
            setAllergy(w.clinic(), w.adm(), text);
            assertThat(emr(w.doctor().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).as(text)
                    .isEqualTo("RECORDED");
        }
        setAllergy(w.clinic(), w.adm(), "Unknown rash with fish");
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "ondan", "od", null, null)),
                201).get("outcome").asText()).isEqualTo("PLACED");
        // and "None known" is still its own chip
        setAllergy(w.clinic(), w.adm(), NONE_KNOWN);
        assertThat(emr(w.doctor().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).isEqualTo("NONE_KNOWN");
    }

    @Test
    void aCephalosporinWithABrandOrAbbreviationOfPenicillinNeedsAReasonAndIsNotBlocked() throws Exception {
        for (String allergy : new String[] {"Augmentin " + MARKER, "PCN " + MARKER, "Ampicillin " + MARKER}) {
            World w = world(allergy);
            JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                    js(orderBody(newKey(), "ceft", "od", null, null)), 200);
            assertThat(a.get("outcome").asText()).as(allergy).isEqualTo("NEEDS_REASON");
            assertThat(a.get("check").get("hardStops")).isEmpty();
            assertThat(finding(a.get("check"), "warnings", "ALLERGY_CROSS_CEPHALOSPORIN")).isNotNull();
            JsonNode placed = placeWithReason(w.doctor().auth(), w.adm().id(), "ceft", "ALLERGY_HISTORY_CHECKED");
            assertThat(state(w.clinic(), orderIdOf(placed)).warnings()).doesNotContain(MARKER);
        }
    }

    @Test
    void theQueueRowAndVerifyAgreeWithTheOrderFormOnTheNewKeywordsAndAHardStopIsOrderBlockedAfterTheRightPin() throws Exception {
        World w = world(NONE_KNOWN);
        String amox = orderIdOf(place(w.doctor().auth(), w.adm().id(), "amox"));
        String cotri = orderIdOf(place(w.doctor().auth(), w.adm().id(), "cotri"));

        setAllergy(w.clinic(), w.adm(), "Augmentin " + MARKER);
        JsonNode q = queue(w.pharmacist().auth(), "limit=100");
        JsonNode amoxRow = queueRow(q, amox);
        assertThat(amoxRow.get("canVerify").asBoolean()).isFalse();
        assertThat(amoxRow.get("verifyLockReason").asText()).isEqualTo("HARD_STOP");
        assertThat(amoxRow.get("check").get("hardStops").get(0).get("code").asText()).isEqualTo("ALLERGY_PENICILLIN");
        assertThat(amoxRow.get("patient").get("allergyStatus").asText()).isEqualTo("RECORDED");
        assertThat(queueRow(q, cotri).get("canVerify").asBoolean()).isTrue();
        // the order form says the same about the same medicine
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "pip", "od", null, null)), 200)
                .get("outcome").asText()).isEqualTo("BLOCKED");
        JsonNode p = problem(verifyCall(w.pharmacist(), amox, w.pin(), amoxRow.get("check").get("checkToken").asText()), 409);
        assertType(p, "order-blocked");
        assertThat(p.get("detail").asText()).isEqualTo(HARD_STOP_ON_ORDER);
        assertThat(p.toString()).doesNotContain(MARKER).doesNotContain("Augmentin");
        assertThat(state(w.clinic(), amox).status()).isEqualTo("PENDING");

        // the allergy changes to a sulfa brand: the rows swap
        setAllergy(w.clinic(), w.adm(), "Septran " + MARKER);
        q = queue(w.pharmacist().auth(), "limit=100");
        JsonNode cotriRow = queueRow(q, cotri);
        assertThat(cotriRow.get("canVerify").asBoolean()).isFalse();
        assertThat(cotriRow.get("verifyLockReason").asText()).isEqualTo("HARD_STOP");
        assertThat(cotriRow.get("check").get("hardStops").get(0).get("code").asText()).isEqualTo("ALLERGY_SULFA");
        assertThat(queueRow(q, amox).get("canVerify").asBoolean()).isTrue();
        assertThat(problem(verifyCall(w.pharmacist(), cotri, w.pin(), cotriRow.get("check").get("checkToken").asText()), 409)
                .get("detail").asText()).isEqualTo(HARD_STOP_ON_ORDER);
        verifyOk(w.pharmacist(), w.pin(), amox);
        assertThat(state(w.clinic(), amox).status()).isEqualTo("ACTIVE");
        assertThat(state(w.clinic(), cotri).status()).isEqualTo("PENDING");
        // reject stays allowed on the hard-stopped order
        rejectOk(w.pharmacist().auth(), cotri, "ALLERGY_CONFLICT");
        assertThat(state(w.clinic(), cotri).status()).isEqualTo("REJECTED");
    }

    // ================= S2: the patient audit rows hold flags, never the texts =================

    @Test
    void thePatientAuditRowsOfRegisterUpdateAndMergeHoldTheTwoRecordedFlagsAndNeitherText() throws Exception {
        Clinic clinic = hospital();
        JsonNode keep = newPatient(clinic, "Penicillin hives " + MARKER, "Diabetes " + MARKER);
        JsonNode bare = newPatient(clinic, null, null);
        String keepId = keep.get("id").asText();
        String bareId = bare.get("id").asText();
        ok(HttpMethod.PATCH, PATIENTS + "/" + bareId, clinic.owner(),
                js(Map.of("allergy", "Latex " + MARKER, "chronic", "Asthma " + MARKER)), 200);
        ok(HttpMethod.PATCH, PATIENTS + "/" + bareId, clinic.owner(), js(Map.of("allergy", "Latex2 " + MARKER)), 200);
        merge(clinic, keepId, bareId);

        List<String[]> registered = audit(clinic, "PATIENT_REGISTERED");
        String[] registeredKeep = auditOf(registered, keepId);
        assertThat(registeredKeep[2]).as("a registration has no before").isNull();
        JsonNode after = objectMapper.readTree(registeredKeep[3]);
        assertThat(after.get("allergyRecorded").isBoolean()).isTrue();
        assertThat(after.get("allergyRecorded").asBoolean()).isTrue();
        assertThat(after.get("chronicRecorded").asBoolean()).isTrue();
        assertThat(after.has("allergy")).as("no allergy key").isFalse();
        assertThat(after.has("chronic")).as("no chronic key").isFalse();
        JsonNode afterBare = objectMapper.readTree(auditOf(registered, bareId)[3]);
        assertThat(afterBare.get("allergyRecorded").asBoolean()).isFalse();
        assertThat(afterBare.get("chronicRecorded").asBoolean()).isFalse();

        List<String[]> updated = audit(clinic, "PATIENT_UPDATED");
        assertThat(updated).hasSize(2);
        JsonNode firstBefore = objectMapper.readTree(updated.get(0)[2]);
        JsonNode firstAfter = objectMapper.readTree(updated.get(0)[3]);
        assertThat(firstBefore.get("allergyRecorded").asBoolean()).isFalse();
        assertThat(firstAfter.get("allergyRecorded").asBoolean()).isTrue();
        assertThat(firstAfter.get("chronicRecorded").asBoolean()).isTrue();
        JsonNode secondBefore = objectMapper.readTree(updated.get(1)[2]);
        assertThat(secondBefore.get("allergyRecorded").asBoolean()).isTrue();

        List<String[]> merged = audit(clinic, "PATIENT_MERGED");
        assertThat(merged).hasSize(2);
        for (String[] row : merged) {
            for (int column : new int[] {2, 3}) {
                JsonNode json = objectMapper.readTree(row[column]);
                assertThat(json.get("allergyRecorded").isBoolean()).isTrue();
                assertThat(json.get("chronicRecorded").isBoolean()).isTrue();
                assertThat(json.has("allergy") || json.has("chronic")).isFalse();
            }
        }
        // none of the texts anywhere in the clinic's audit trail, and the other ordinary fields stay readable
        String all = allAuditText(clinic);
        for (String text : new String[] {"Penicillin", "Diabetes", "Latex", "Asthma", MARKER}) {
            assertThat(all).as("audit text must not hold " + text).doesNotContain(text);
        }
        assertThat(after.get("language").asText()).isEqualTo("mr");
        assertThat(after.get("age").asInt()).isEqualTo(41);
        // the patient's name and mobile are still not in the rows
        assertThat(all).doesNotContain(keep.get("name").asText()).doesNotContain(keep.get("mobile").asText());
    }

    private static String[] auditOf(List<String[]> rows, String entityId) {
        return rows.stream().filter(r -> r[1].equals(entityId)).findFirst().orElseThrow();
    }

    // ================= S3: the pharmacist list is a record access =================

    @Test
    void aServedPageWritesOneAccessRowPerDistinctPatientEachTimeAndTheSummaryWritesNothing() throws Exception {
        World w = world(NONE_KNOWN);
        Adm second = admitWithAllergy(w.clinic(), NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "pcm");          // oldest: patient 1
        place(w.doctor().auth(), second.id(), "pcm");           // patient 2
        place(w.doctor().auth(), w.adm().id(), "panto");        // patient 1 again
        long total = accessTotal(w.clinic());
        long p1 = recordAccess(w.clinic(), w.adm().patientId());
        long p2 = recordAccess(w.clinic(), second.patientId());
        MedSnap before = medSnap(w.clinic(), w.adm());

        JsonNode page = queue(w.pharmacist().auth(), "limit=100");
        assertThat(page.get("items")).hasSize(3);
        assertThat(accessTotal(w.clinic())).as("two patients on the page, three orders").isEqualTo(total + 2);
        assertThat(recordAccess(w.clinic(), w.adm().patientId())).isEqualTo(p1 + 1);
        assertThat(recordAccess(w.clinic(), second.patientId())).isEqualTo(p2 + 1);
        assertThat(count(w.clinic(), "select count(*) from patient.record_access where user_id = ?::uuid and patient_id in (?::uuid, ?::uuid)",
                w.pharmacist().userId().toString(), w.adm().patientId().toString(), second.patientId().toString()))
                .as("the rows name the person who opened the list").isGreaterThanOrEqualTo(2);

        queue(w.pharmacist().auth());
        assertThat(accessTotal(w.clinic())).as("a second call is a second access").isEqualTo(total + 4);

        // the first page of two rows holds both patients' oldest orders; a page of one holds only the oldest patient
        long beforePaging = accessTotal(w.clinic());
        JsonNode first = queue(w.pharmacist().auth(), "limit=1");
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("waiting").asInt()).as("the count is the true count, not the page").isEqualTo(3);
        assertThat(first.get("nextCursor").isNull()).isFalse();
        assertThat(accessTotal(w.clinic())).isEqualTo(beforePaging + 1);
        JsonNode next = queue(w.pharmacist().auth(), "limit=1&cursor=" + first.get("nextCursor").asText());
        assertThat(next.get("items").get(0).get("patient").get("name").asText()).isEqualTo(second.name());
        assertThat(accessTotal(w.clinic())).isEqualTo(beforePaging + 2);

        // the summary is counts only and writes nothing; neither does anything else but the access rows
        long beforeSummary = accessTotal(w.clinic());
        MedSnap mid = medSnap(w.clinic(), w.adm());
        summary(w.pharmacist().auth());
        summary(w.owner().auth());
        assertThat(accessTotal(w.clinic())).isEqualTo(beforeSummary);
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(mid);
        assertThat(mid.events()).isEqualTo(before.events());
        assertThat(mid.audits()).as("a list writes no audit event").isEqualTo(before.audits());
        assertThat(mid.orders()).isEqualTo(before.orders());
        assertThat(mid.pinRows()).isEqualTo(before.pinRows());
    }

    @Test
    void aRefusedCallWritesNoAccessRow() throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "pcm");
        long total = accessTotal(w.clinic());
        // role
        for (String role : NON_CHECK_ROLES) {
            problem(call(HttpMethod.GET, CHECKS, staff(w.clinic(), role).auth(), null), 403);
        }
        // module off (a clinic that never started a trial), role first
        Clinic off = newClinic();
        JsonNode offProblem = problem(call(HttpMethod.GET, CHECKS, off.as("PHARMACIST"), null), 403);
        assertType(offProblem, "module-off");
        // bad limit and bad cursor
        for (String query : new String[] {"limit=0", "limit=101", "limit=abc", "cursor=abc", "cursor=" + "A".repeat(31)}) {
            assertType(problem(call(HttpMethod.GET, CHECKS + "?" + query, w.pharmacist().auth(), null), 400), "validation");
        }
        // no session
        assertType(problem(call(HttpMethod.GET, CHECKS, noSession(), null), 401), "unauthorized");
        assertThat(accessTotal(w.clinic())).as("no access row for a refused call").isEqualTo(total);
        assertThat(accessTotal(off)).isZero();
        // and the call that works afterwards writes its row
        queue(w.pharmacist().auth());
        assertThat(accessTotal(w.clinic())).isEqualTo(total + 1);
    }

    @Test
    void anotherClinicsPatientIsNeverOnThePageAndWritesNoRowThere() throws Exception {
        World a = world(NONE_KNOWN);
        World b = world(NONE_KNOWN);
        place(a.doctor().auth(), a.adm().id(), "pcm");
        place(b.doctor().auth(), b.adm().id(), "pcm");
        long inB = accessTotal(b.clinic());
        JsonNode page = queue(a.pharmacist().auth(), "limit=100");
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("patient").get("name").asText()).isEqualTo(a.adm().name());
        assertThat(accessTotal(b.clinic())).as("the other clinic's trail is untouched").isEqualTo(inB);
        assertThat(recordAccess(b.clinic(), b.adm().patientId())).isZero();
    }

    @Test
    void verifyAndRejectAnswerWriteAccessRowsForThePatientsOnTheFirstPageTheyReturnAndNothingForAnEmptyPage() throws Exception {
        World w = world(NONE_KNOWN);
        Adm second = admitWithAllergy(w.clinic(), NONE_KNOWN);
        String o1 = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String o2 = orderIdOf(place(w.doctor().auth(), second.id(), "pcm"));
        String o3 = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String token = tokenOfOrder(w.pharmacist().auth(), o1);

        long p1 = recordAccess(w.clinic(), w.adm().patientId());
        long p2 = recordAccess(w.clinic(), second.patientId());
        long total = accessTotal(w.clinic());
        MedSnap before = medSnap(w.clinic(), w.adm());
        freshPinAllowance(w.pharmacist());
        JsonNode verified = ok(HttpMethod.POST, verifyUrl(o1), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
        assertThat(verified.get("checks").get("items")).hasSize(2);
        assertThat(recordAccess(w.clinic(), w.adm().patientId())).as("o3 of patient 1 is on the page").isEqualTo(p1 + 1);
        assertThat(recordAccess(w.clinic(), second.patientId())).as("o2 of patient 2 is on the page").isEqualTo(p2 + 1);
        assertThat(accessTotal(w.clinic())).isEqualTo(total + 2);
        MedSnap afterVerify = medSnap(w.clinic(), w.adm());
        assertThat(afterVerify.events()).isEqualTo(before.events() + 1);
        assertThat(afterVerify.audits()).as("only the verify is audited, not the page").isEqualTo(before.audits() + 1);

        // reject o2: the page that comes back holds only o3 of patient 1
        JsonNode rejected = rejectOk(w.pharmacist().auth(), o2, "WRONG_DOSE");
        assertThat(rejected.get("checks").get("items")).hasSize(1);
        assertThat(queueRow(rejected.get("checks"), o3)).isNotNull();
        assertThat(recordAccess(w.clinic(), w.adm().patientId())).isEqualTo(p1 + 2);
        assertThat(recordAccess(w.clinic(), second.patientId())).as("patient 2 is not on the page any more").isEqualTo(p2 + 1);

        // the last waiting order: the page that comes back is empty and writes nothing
        long beforeLast = accessTotal(w.clinic());
        JsonNode last = rejectOk(w.pharmacist().auth(), o3, "DUPLICATE_THERAPY");
        assertThat(last.get("checks").get("items")).isEmpty();
        assertThat(last.get("checks").get("waiting").asInt()).isZero();
        assertThat(accessTotal(w.clinic())).isEqualTo(beforeLast);
    }

    @Test
    void aVerifyThatIsRefusedWritesNoAccessRowAndNeitherDoesAWrongPin() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        place(w.doctor().auth(), w.adm().id(), "panto");
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        long total = accessTotal(w.clinic());
        assertType(problem(verifyCall(w.pharmacist(), id, "9306".equals(w.pin()) ? "9307" : "9306", token), 403), "wrong-pin");
        // a stale token
        assertType(problem(verifyCall(w.pharmacist(), id, w.pin(), "A".repeat(22)), 409), "order-checks-changed");
        // the doctor cannot verify; the nurse cannot reject
        assertType(problem(call(HttpMethod.POST, verifyUrl(id), w.doctor().auth(), verifyBody(w.pin(), token)), 403), "forbidden");
        assertType(problem(rejectCall(w.nurse().auth(), id, "WRONG_DOSE"), 403), "forbidden");
        // a bad reason
        assertType(problem(rejectCall(w.pharmacist().auth(), id, "NOT_A_REASON"), 400), "validation");
        assertThat(accessTotal(w.clinic())).as("nothing was served").isEqualTo(total);
    }

    @Test
    void twelveRightPinsInARowAreNeverRateLimitedAndAWrongPinAfterThemIsAnsweredNormally() throws Exception {
        World w = world(NONE_KNOWN);
        Adm second = admitWithAllergy(w.clinic(), NONE_KNOWN);
        List<String> ids = new ArrayList<>();
        for (String drug : new String[] {"pcm", "panto", "ondan", "ns", "ceft", "amox", "pip", "cotri", "met", "ins", "kcl"}) {
            ids.add(orderIdOf(place(w.doctor().auth(), w.adm().id(), drug)));
        }
        ids.add(orderIdOf(place(w.doctor().auth(), second.id(), "pcm")));
        freshPinAllowance(w.pharmacist());
        for (int i = 0; i < ids.size(); i++) {
            // no freshPinAllowance here: right PINs must never use the allowance
            String token = tokenOfOrder(w.pharmacist().auth(), ids.get(i));
            ok(HttpMethod.POST, verifyUrl(ids.get(i)), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
            assertThat(state(w.clinic(), ids.get(i)).status()).as("order " + (i + 1)).isEqualTo("ACTIVE");
        }
        String next = orderIdOf(place(w.doctor().auth(), second.id(), "panto"));
        String token = tokenOfOrder(w.pharmacist().auth(), next);
        JsonNode wrong = problem(call(HttpMethod.POST, verifyUrl(next), w.pharmacist().auth(),
                verifyBody("9306".equals(w.pin()) ? "9307" : "9306", token)), 403);
        assertType(wrong, "wrong-pin");
        assertThat(wrong.get("detail").asText()).isEqualTo("That PIN is not right. 4 tries left.");
    }

    // ================= S4: merging two patient records never loses an allergy =================

    @Test
    void mergeKeepsTheTextOfWhicheverRecordHasOneAndCombinesTwoDifferentTextsWithoutCopies() throws Exception {
        Clinic clinic = hospital();
        String[][] cases = {
                // kept, merged-away, expected
                {"Peanuts", null, "Peanuts"},
                {null, "Peanuts", "Peanuts"},
                {"Peanuts", "Dust", "Peanuts; Dust"},
                {"Dust", "Peanuts", "Dust; Peanuts"},
                {"peanuts", "PEANUTS", "peanuts"},
                {"Peanuts", " peanuts ", "Peanuts"},
                {"Peanuts; Dust", "dust; Eggs", "Peanuts; Dust; Eggs"},
                {NONE_KNOWN, "Peanuts", "Peanuts"},
                {"Peanuts", NONE_KNOWN, "Peanuts"},
                {"Not recorded", "Peanuts", "Peanuts"},
                {"Peanuts", "unknown", "Peanuts"},
                {"?", "Peanuts", "Peanuts"},
                {NONE_KNOWN, "none known", NONE_KNOWN},
                {NONE_KNOWN, null, NONE_KNOWN},
                {null, null, null}};
        for (String[] c : cases) {
            // the same cases for the allergy and for the chronic text
            for (String field : new String[] {"allergy", "chronic"}) {
                JsonNode keep = newPatient(clinic, "allergy".equals(field) ? c[0] : null, "chronic".equals(field) ? c[0] : null);
                JsonNode dup = newPatient(clinic, "allergy".equals(field) ? c[1] : null, "chronic".equals(field) ? c[1] : null);
                JsonNode result = merge(clinic, keep.get("id").asText(), dup.get("id").asText());
                String got = result.has(field) && !result.get(field).isNull() ? result.get(field).asText() : null;
                assertThat(got).as(field + ": kept=" + c[0] + " merged=" + c[1]).isEqualTo(c[2]);
                // the saved record says the same as the answer
                JsonNode saved = ok(HttpMethod.GET, PATIENTS + "/" + keep.get("id").asText(), clinic.owner(), null, 200);
                String stored = saved.has(field) && !saved.get(field).isNull() ? saved.get(field).asText() : null;
                assertThat(stored).isEqualTo(c[2]);
            }
        }
    }

    @Test
    void aCombinedTextOver400CharactersIsCutAt400WithTheKeptRecordsItemsFirstAndTheCutCanFallInsideAnItem() throws Exception {
        Clinic clinic = hospital();
        List<String> keptItems = new ArrayList<>();
        List<String> mergedItems = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            keptItems.add(String.format("kept-aaaa-%02d", i));
            mergedItems.add(String.format("merg-bbbb-%02d", i));
        }
        String kept = String.join("; ", keptItems);
        String mergedText = String.join("; ", mergedItems);
        assertThat(kept.length()).isLessThanOrEqualTo(400);
        assertThat(kept.length() + 2 + mergedText.length()).isGreaterThan(400);
        JsonNode keep = newPatient(clinic, kept, null);
        JsonNode dup = newPatient(clinic, mergedText, null);
        String combined = merge(clinic, keep.get("id").asText(), dup.get("id").asText()).get("allergy").asText();
        assertThat(combined.length()).as("never over the 400 of the field").isLessThanOrEqualTo(400);
        assertThat(combined).as("the kept record's items come first and whole").startsWith(kept + "; merg-bbbb-01; merg-bbbb-02; merg-bbbb-03");
        assertThat(combined).doesNotContain("merg-bbbb-25").doesNotContain("merg-bbbb-10");
        // CURRENT BEHAVIOUR (reported to the owner): a plain character cut, so the last item is cut in the middle
        assertThat(combined).hasSize(400).endsWith("; merg-bbb");
        // the saved record holds exactly that text
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + keep.get("id").asText(), clinic.owner(), null, 200).get("allergy").asText())
                .isEqualTo(combined);
        // the merged-away record keeps its own text (soft merge, nothing is lost from it)
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + dup.get("id").asText(), clinic.owner(), null, 200).get("allergy").asText())
                .isEqualTo(mergedText);
    }

    @Test
    void afterAMergeTheEmrOfAnAdmissionOfTheMergedAwayRecordShowsTheKeptRecordsAllergyAndTheHardStopApplies() throws Exception {
        World w = world(NONE_KNOWN);                       // admission of the record that will be merged away
        Clinic clinic = w.clinic();
        String amox = orderIdOf(place(w.doctor().auth(), w.adm().id(), "amox"));   // allowed: "None known" at the time
        String cotri = orderIdOf(place(w.doctor().auth(), w.adm().id(), "cotri"));
        JsonNode keep = newPatient(clinic, "Penicillin " + MARKER, null);
        merge(clinic, keep.get("id").asText(), w.adm().patientId().toString());

        // the EMR of the admission that still points at the merged-away record
        JsonNode sheet = emr(w.doctor().auth(), w.adm().id());
        assertThat(sheet.get("patient").get("allergyText").asText()).isEqualTo("Penicillin " + MARKER);
        assertThat(sheet.get("patient").get("allergyStatus").asText()).isEqualTo("RECORDED");
        // a new penicillin order is a hard stop for the doctor
        JsonNode blocked = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "pip", "od", null, null)), 200);
        assertThat(blocked.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(firstHardStop(blocked).get("code").asText()).isEqualTo("ALLERGY_PENICILLIN");
        // the waiting penicillin order is locked in the queue and verify is order-blocked
        JsonNode q = queue(w.pharmacist().auth(), "limit=100");
        JsonNode amoxRow = queueRow(q, amox);
        assertThat(amoxRow.get("canVerify").asBoolean()).isFalse();
        assertThat(amoxRow.get("verifyLockReason").asText()).isEqualTo("HARD_STOP");
        assertThat(amoxRow.get("patient").get("allergyText").asText()).isEqualTo("Penicillin " + MARKER);
        assertThat(queueRow(q, cotri).get("canVerify").asBoolean()).as("the sulfa drug is not stopped").isTrue();
        assertThat(problem(verifyCall(w.pharmacist(), amox, w.pin(), amoxRow.get("check").get("checkToken").asText()), 409)
                .get("detail").asText()).isEqualTo(HARD_STOP_ON_ORDER);
        assertThat(state(clinic, amox).status()).isEqualTo("PENDING");
        // the allergy text is still not in the events, audit rows or stored warnings
        assertThat(String.join("\n", eventLines(clinic, w.adm().id()))).doesNotContain(MARKER);
        assertThat(allAuditText(clinic)).doesNotContain(MARKER);
    }

    @Test
    void aMergeInOneClinicNeverReachesAnotherClinicsPatients() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        JsonNode keepA = newPatient(a, "Peanuts", null);
        JsonNode dupA = newPatient(a, "Dust", null);
        JsonNode inB = newPatient(b, "Eggs", null);
        // a clinic cannot merge a patient it cannot see
        UUID id = UUID.fromString(inB.get("id").asText());
        assertType(problem(call(HttpMethod.POST, PATIENTS + "/merge", a.owner(),
                js(Map.of("keepId", keepA.get("id").asText(), "mergeId", id.toString()))), 404), "not-found");
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + id, b.owner(), null, 200).get("allergy").asText()).isEqualTo("Eggs");
        assertThat(merge(a, keepA.get("id").asText(), dupA.get("id").asText()).get("allergy").asText()).isEqualTo("Peanuts; Dust");
        assertThat(ok(HttpMethod.GET, PATIENTS + "/" + id, b.owner(), null, 200).get("allergy").asText()).isEqualTo("Eggs");
    }

    // ================= R3: the queue count is the true count =================

    @Test
    void waitingIsTheTrueCountNotThePageSizeAndOrdersOfADischargedAdmissionAreNotCounted() throws Exception {
        World w = world(NONE_KNOWN);
        Adm second = admitWithAllergy(w.clinic(), NONE_KNOWN);
        for (String drug : new String[] {"pcm", "panto", "ondan"}) {
            place(w.doctor().auth(), w.adm().id(), drug);
        }
        for (String drug : new String[] {"pcm", "panto"}) {
            place(w.doctor().auth(), second.id(), drug);
        }
        JsonNode one = queue(w.pharmacist().auth(), "limit=1");
        assertThat(one.get("items")).hasSize(1);
        assertThat(one.get("waiting").asInt()).isEqualTo(5);
        assertThat(queue(w.pharmacist().auth(), "limit=2").get("waiting").asInt()).isEqualTo(5);
        assertThat(queue(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(5);
        assertThat(summary(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(5);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order o join ipd.admission a on a.tenant_id = o.tenant_id "
                + "and a.id = o.admission_id where o.status = 'PENDING' and a.status = 'ADMITTED'")).isEqualTo(5);

        discharge(w.clinic(), w.adm().id());
        JsonNode after = queue(w.pharmacist().auth(), "limit=1");
        assertThat(after.get("waiting").asInt()).as("the three orders of the discharged admission are out").isEqualTo(2);
        assertThat(after.get("items")).hasSize(1);
        assertThat(after.get("items").get(0).get("admissionId").asText()).isEqualTo(second.id());
        assertThat(summary(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(2);
        // the discharged admission's orders keep their status
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order where admission_id = ?::uuid and status = 'PENDING'",
                w.adm().id())).isEqualTo(3);
        // the verify answer's count agrees too
        String id = orderIdOf(place(w.doctor().auth(), second.id(), "ondan"));
        assertThat(queue(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(3);
        JsonNode verified = verifyOk(w.pharmacist(), w.pin(), id);
        assertThat(verified.get("checks").get("waiting").asInt()).isEqualTo(2);
    }
}
