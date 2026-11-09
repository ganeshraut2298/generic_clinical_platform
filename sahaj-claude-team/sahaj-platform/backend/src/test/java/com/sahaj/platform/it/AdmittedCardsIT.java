package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-36 the Admitted patient cards: the one fixed read set behind {@code GET /api/v1/ipd/admissions} (ADR-013, docs/stories/
 * SAH-36.md) on the real {@code sahaj_test} database: the new row facts (uhid, ward id, room name), the {@code clinical}
 * object that is absent from the raw JSON for a front desk caller (not null, not empty), the role read again from the
 * membership on every call (a role change applies on the second read, an inactive membership or a role outside the four is
 * 403), another clinic's patients never listed, 300 rows in one request, the escalation flag and its minutes equal to the
 * Safety board's (OPEN, ACK, DONE and re-opened), vitals due at 240 and 241 minutes on the database clock, the three allergy
 * states (text only when recorded), the card abilities by role and by Read-only, no record-access row, the room name null
 * when rooms are off, and no patient data in a log. Test plan: docs/test-plans/SAH-36.md.
 */
@OnTestDatabase
@WithPinPepper
class AdmittedCardsIT extends RoundSupport {

    private static final String ROOM_LEVEL = IPD + "/room-level";
    private static final String[] FORBIDDEN_FOR_FRONT_DESK = {"\"clinical\"", "\"escalation\"", "\"latestEws\"", "\"vitalsDue\"",
        "\"allergy\"", "\"dosesLate\"", "\"dosesDue\"", "\"changeToken\"", "\"waitingMinutes\""};

    private JsonNode list(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, ADMISSIONS, auth, null, 200);
    }

    private static JsonNode rowOf(JsonNode list, String admissionId) {
        for (JsonNode r : list.get("items")) {
            if (admissionId.equals(r.get("id").asText())) {
                return r;
            }
        }
        return null;
    }

    private String raw(RequestPostProcessor auth) throws Exception {
        return call(HttpMethod.GET, ADMISSIONS, auth, null).andReturn().getResponse().getContentAsString();
    }

    private Instant recordedAt(Clinic clinic, String admissionId) throws Exception {
        return instantOf(clinic, "select max(recorded_at) from ipd.vitals where admission_id = '" + admissionId + "'::uuid");
    }

    /**
     * SAH-37: a front desk answer holds no clinical key and no clinical value. The answer now also carries the published
     * setup ({@code cardSetup}: section and field keys such as {@code "clinical"}, which are setup names and not facts) and
     * {@code card.visibleGroups}. The key check therefore runs on the rows ({@code items}) and on everything except
     * {@code cardSetup}; the groups the front desk may see must not name the clinical or the vitals group.
     */
    private void assertNoClinicalForFrontDesk(JsonNode answer) {
        com.fasterxml.jackson.databind.node.ObjectNode withoutSetup =
                ((com.fasterxml.jackson.databind.node.ObjectNode) answer).deepCopy();
        withoutSetup.remove("cardSetup");
        String items = answer.get("items").toString();
        String rest = withoutSetup.toString();
        for (String key : FORBIDDEN_FOR_FRONT_DESK) {
            assertThat(items).as("front desk rows must not hold " + key).doesNotContain(key);
            assertThat(rest).as("front desk answer (without the setup) must not hold " + key).doesNotContain(key);
        }
        List<String> groups = new ArrayList<>();
        answer.get("card").get("visibleGroups").forEach(g -> groups.add(g.asText()));
        assertThat(groups).as("front desk groups").containsExactly("patient", "contact", "billing");
        for (JsonNode row : answer.get("items")) {
            assertThat(row.has("clinical")).isFalse();
        }
    }

    // ---------------- the row facts, the clinical object and the abilities ----------------

    @Test
    void everyRowCarriesUhidWardIdAndRoomNameAndTheListCarriesAsOfWardsAndCardForTheFourRoles() throws Exception {
        Ward w = ward();
        Instant fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(3);
        clockAt(fixed);
        String uhid = w.uhid();
        String wardId = layout(w.clinic()).get("wards").get(0).get("id").asText();
        ok(HttpMethod.POST, WARDS, w.clinic().owner(), "{\"name\":\"Second ward\"}", 201);
        for (Object[] who : new Object[][] {{"OWNER", w.clinic().owner()}, {"DOCTOR", w.doctor().auth()}, {"NURSE", w.nurse().auth()},
            {"FRONT_DESK", staff(w.clinic(), "FRONT_DESK").auth()}}) {
            JsonNode l = list((RequestPostProcessor) who[1]);
            assertThat(Instant.parse(l.get("asOf").asText())).as(who[0] + " asOf is the database clock").isEqualTo(fixed);
            JsonNode row = rowOf(l, w.adm().id());
            assertThat(row).as(who[0] + "").isNotNull();
            assertThat(row.get("uhid").asText()).isEqualTo(uhid);
            assertThat(row.get("wardId").asText()).isEqualTo(wardId);
            assertThat(row.get("roomName").isNull()).as("a bed with a room shows its room").isFalse();
            assertThat(l.get("wards")).as("every ward, also the empty one, in setup order").hasSize(2);
            assertThat(l.get("wards").get(0).get("id").asText()).isEqualTo(wardId);
            assertThat(l.get("wards").get(1).get("name").asText()).isEqualTo("Second ward");
            assertThat(keys(l.get("card"))).containsExactlyInAnyOrder("nursing", "emr", "transfer", "discharge", "deposit",
                    "depositLowFlag", "visibleGroups");
        }
    }

    @Test
    void theCardAbilitiesFollowTheRoleAndReadOnlyRemovesTransferAndDischarge() throws Exception {
        Ward w = ward();
        boolean[][] expected = {
            // nursing, emr, transfer, discharge, depositLowFlag
            {true, true, true, true, true},      // OWNER
            {true, true, false, true, false},    // DOCTOR
            {true, true, true, false, false},    // NURSE
            {false, false, true, false, true}};  // FRONT_DESK
        String[] roles = {"OWNER", "DOCTOR", "NURSE", "FRONT_DESK"};
        RequestPostProcessor[] auths = {w.clinic().owner(), w.doctor().auth(), w.nurse().auth(), staff(w.clinic(), "FRONT_DESK").auth()};
        String[] names = {"nursing", "emr", "transfer", "discharge", "depositLowFlag"};
        for (int i = 0; i < roles.length; i++) {
            JsonNode card = list(auths[i]).get("card");
            for (int j = 0; j < names.length; j++) {
                assertThat(card.get(names[j]).asBoolean()).as(roles[i] + " " + names[j]).isEqualTo(expected[i][j]);
            }
        }
        expireTrial(w.clinic());
        for (int i = 0; i < roles.length; i++) {
            JsonNode card = list(auths[i]).get("card");
            assertThat(card.get("transfer").asBoolean()).as(roles[i] + " read-only transfer").isFalse();
            assertThat(card.get("discharge").asBoolean()).as(roles[i] + " read-only discharge").isFalse();
            assertThat(card.get("nursing").asBoolean()).as(roles[i] + " read-only nursing is reading").isEqualTo(expected[i][0]);
            assertThat(card.get("emr").asBoolean()).isEqualTo(expected[i][1]);
        }
    }

    @Test
    void aFrontDeskAnswerHasNoClinicalKeyAtAllInTheRawJsonAndNoSafetyAndNoClinicalValueAnywhere() throws Exception {
        Ward w = ward();
        setAllergy(w.clinic(), w.adm(), "Penicillin rash");
        String escalation = raise(w.clinic(), w.adm(), high7());
        assertThat(escalation).isNotBlank();
        activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        Staff frontDesk = staff(w.clinic(), "FRONT_DESK");
        String json = raw(frontDesk.auth());
        JsonNode l = objectMapper.readTree(json);
        assertNoClinicalForFrontDesk(l);
        assertThat(json).doesNotContain("Penicillin").doesNotContain("HIGH").doesNotContain(escalation);
        assertThat(l.get("safety").isNull()).isTrue();
        for (JsonNode row : l.get("items")) {
            assertThat(row.has("clinical")).isFalse();
            assertThat(row.has("uhid")).isTrue();
        }
        // the same data is there for the three clinical roles
        for (RequestPostProcessor auth : new RequestPostProcessor[] {w.clinic().owner(), w.doctor().auth(), w.nurse().auth()}) {
            JsonNode row = rowOf(list(auth), w.adm().id());
            assertThat(row.has("clinical")).isTrue();
            assertThat(keys(row.get("clinical"))).containsExactlyInAnyOrder("escalation", "latestEws", "vitalsDue", "allergy");
        }
    }

    /**
     * R36-5 / ADR-013 D15: the front desk card comes from PatientLookupPort.findAllCurrent (no clinical port), which follows a
     * merge. A patient merged away after the admission shows the kept record's name and UHID, for all four roles alike.
     */
    @Test
    void aPatientMergedAwayAfterTheAdmissionShowsTheSameNameAndUhidOnTheFrontDeskCardAsOnTheClinicalCards() throws Exception {
        Ward w = ward();
        String keepName = "Kept Record " + uniqueMobile().substring(5);
        JsonNode keep = ok(HttpMethod.POST, "/api/v1/patient/patients", w.clinic().owner(),
                "{\"name\":\"" + keepName + "\",\"age\":41,\"sex\":\"F\",\"mobile\":\"" + uniqueMobile() + "\"}", 201);
        String keepId = keep.get("id").asText();
        String keepUhid = values(w.clinic(), "select uhid from patient.patient where id = ?::uuid", keepId).get(0);
        assertThat(keepUhid).isNotEqualTo(w.uhid());
        ok(HttpMethod.POST, "/api/v1/patient/patients/merge", w.clinic().owner(),
                "{\"keepId\":\"" + keepId + "\",\"mergeId\":\"" + w.adm().patientId() + "\"}", 200);

        Staff frontDesk = staff(w.clinic(), "FRONT_DESK");
        String firstName = null;
        for (Object[] who : new Object[][] {{"OWNER", w.clinic().owner()}, {"DOCTOR", w.doctor().auth()}, {"NURSE", w.nurse().auth()},
            {"FRONT_DESK", frontDesk.auth()}}) {
            JsonNode row = rowOf(list((RequestPostProcessor) who[1]), w.adm().id());
            assertThat(row).as(who[0] + " still lists the admission").isNotNull();
            assertThat(row.get("uhid").asText()).as(who[0] + " UHID is the kept record's").isEqualTo(keepUhid);
            if (firstName == null) {
                firstName = row.get("patientName").asText();
            }
            assertThat(row.get("patientName").asText()).as(who[0] + " name equals the clinical roles' name").isEqualTo(firstName);
            assertThat(row.get("patientName").asText()).as(who[0] + " shows a real name, never a placeholder")
                    .isIn(keepName, w.adm().name());
        }
        // the raw-JSON front desk check still holds after a merge: no clinical key at all
        JsonNode afterMerge = objectMapper.readTree(raw(frontDesk.auth()));
        assertNoClinicalForFrontDesk(afterMerge);
        assertThat(afterMerge.get("safety").isNull()).isTrue();
    }

    // ---------------- the role is read again ----------------

    @Test
    void aRoleChangedBetweenTwoReadsAppliesOnTheSecondOneAndInactiveOrOtherRolesAre403() throws Exception {
        Ward w = ward();
        Staff moved = staff(w.clinic(), "NURSE");
        JsonNode first = list(moved.auth());
        assertThat(rowOf(first, w.adm().id()).has("clinical")).isTrue();
        assertThat(first.get("safety").isNull()).isFalse();
        setRole(w, moved, "FRONT_DESK");
        JsonNode second = list(moved.auth());
        assertThat(rowOf(second, w.adm().id()).has("clinical")).as("the token still says NURSE, the membership says FRONT_DESK").isFalse();
        assertThat(second.get("safety").isNull()).isTrue();
        assertThat(second.get("card").get("nursing").asBoolean()).isFalse();
        setRole(w, moved, "BILLING");
        assertType(problem(call(HttpMethod.GET, ADMISSIONS, moved.auth(), null), 403), "forbidden");
        setRole(w, moved, "DOCTOR");
        assertThat(rowOf(list(moved.auth()), w.adm().id()).has("clinical")).isTrue();
        // inactive membership with a valid token
        Staff gone = staff(w.clinic(), "NURSE");
        assertThat(list(gone.auth())).isNotNull();
        setMembershipStatus(w.clinic().tenantId(), gone.userId(), "INACTIVE");
        assertType(problem(call(HttpMethod.GET, ADMISSIONS, gone.auth(), null), 403), "forbidden");
        // the token roles outside the four
        for (String role : new String[] {"BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"}) {
            assertType(problem(call(HttpMethod.GET, ADMISSIONS, staff(w.clinic(), role).auth(), null), 403), "forbidden");
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ADMISSIONS))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private void setRole(Ward w, Staff s, String role) throws Exception {
        setMembershipRole(w.clinic(), s.userId(), role);
    }

    // ---------------- tenants ----------------

    @Test
    void anotherClinicsPatientsNeverAppearAndEachClinicSeesOnlyItsOwnWards() throws Exception {
        Ward mine = ward();
        Ward theirs = ward();
        JsonNode a = list(mine.clinic().owner());
        JsonNode b = list(theirs.clinic().owner());
        assertThat(a.get("items")).hasSize(1);
        assertThat(b.get("items")).hasSize(1);
        assertThat(rowOf(a, theirs.adm().id())).isNull();
        assertThat(rowOf(b, mine.adm().id())).isNull();
        assertThat(a.toString()).doesNotContain(theirs.adm().name()).doesNotContain(theirs.adm().id());
        // UHIDs are numbered per clinic (both clinics have a PT-00001), so a UHID cannot tell the two apart
        assertThat(a.get("wards").get(0).get("id").asText()).isNotEqualTo(b.get("wards").get(0).get("id").asText());
    }

    // ---------------- 300 rows ----------------

    @Test
    void threeHundredAdmittedPatientsComeInOneRequestWithTheirClinicalObjects() throws Exception {
        Ward w = ward();
        commitAsTenant(w.clinic().tenantId(), "update platform.module_entitlement set bed_limit = 300");
        for (int i = 0; i < 299; i++) {
            admitNamed(w.clinic(), "Cardtest Patient " + i + " " + uniqueMobile().substring(6));
        }
        long started = System.nanoTime();
        JsonNode l = list(w.nurse().auth());
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(l.get("items")).hasSize(300);
        for (JsonNode row : l.get("items")) {
            assertThat(row.has("clinical")).isTrue();
            assertThat(row.get("uhid").isNull()).isFalse();
        }
        assertThat(l.get("bedsOccupied").asInt()).isEqualTo(300);
        assertThat(millis).as("one answer for 300 patients (ms)").isLessThan(5000);
        assertThat(list(staff(w.clinic(), "FRONT_DESK").auth()).get("items")).hasSize(300);
        // HTTP compression is on for JSON above 2 KB (MockMvc does not run the server connector, so the setting is what can be checked)
        assertThat(env.getProperty("server.compression.enabled")).isEqualTo("true");
        assertThat(env.getProperty("server.compression.mime-types")).isEqualTo("application/json");
        assertThat(env.getProperty("server.compression.min-response-size")).isEqualTo("2KB");
        var res = call(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null).andReturn().getResponse();
        assertThat(res.getContentType()).startsWith("application/json");
        assertThat(res.getContentAsString().length()).as("a 300 row answer is far above the compression threshold").isGreaterThan(100_000);
    }

    // ---------------- the escalation flag ----------------

    @Test
    void theEscalationGoesOpenToAcknowledgedToNoneAndTheMinutesEqualTheBoardsAlsoWhenReopened() throws Exception {
        Ward w = ward();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(5);   // whole seconds: no microsecond rounding of the stored times
        clockAt(now);
        String id = insertEscalation(w.clinic(), w.adm().id(), 7, "HIGH", now.minusSeconds(125), null, null);
        JsonNode card = rowOf(list(w.nurse().auth()), w.adm().id()).get("clinical").get("escalation");
        assertThat(keys(card)).containsExactlyInAnyOrder("status", "waitingMinutes", "targetMinutes", "pastTarget");
        assertThat(card.get("status").asText()).isEqualTo("OPEN");
        assertThat(card.get("waitingMinutes").asInt()).isEqualTo(2);
        assertThat(card.get("targetMinutes").asInt()).isEqualTo(15);
        assertThat(card.get("pastTarget").asBoolean()).isFalse();
        JsonNode onBoard = escRow(boardOf(w.nurse().auth()), id);
        assertThat(onBoard.get("waitingMinutes").asInt()).isEqualTo(card.get("waitingMinutes").asInt());
        assertThat(onBoard.get("pastTarget").asBoolean()).isEqualTo(card.get("pastTarget").asBoolean());
        // past the 15 minute target
        clockAt(now.plusSeconds(60 * 20));
        JsonNode late = rowOf(list(w.nurse().auth()), w.adm().id()).get("clinical").get("escalation");
        assertThat(late.get("waitingMinutes").asInt()).isEqualTo(22);
        assertThat(late.get("pastTarget").asBoolean()).isTrue();
        assertThat(escRow(boardOf(w.nurse().auth()), id).get("waitingMinutes").asInt()).isEqualTo(22);
        // acknowledged by a doctor
        clockAt(now);
        ackOk(w.doctor().auth(), id, esc(w.clinic(), id).version());
        JsonNode acknowledged = rowOf(list(w.nurse().auth()), w.adm().id()).get("clinical").get("escalation");
        assertThat(acknowledged.get("status").asText()).isEqualTo("ACK");
        assertThat(acknowledged.get("pastTarget").asBoolean()).isFalse();
        // closed: no flag
        respondOk(w.doctor().auth(), id, esc(w.clinic(), id).version(), "BEDSIDE_REVIEW", "Plan: vitals again in one hour.");
        assertThat(rowOf(list(w.nurse().auth()), w.adm().id()).get("clinical").get("escalation").isNull()).isTrue();
        // re-opened 20 minutes ago after being raised five hours ago: the clock starts at the re-open, as on the board
        String again = insertEscalation(w.clinic(), w.adm().id(), 9, "HIGH", now.minusSeconds(5 * 3600), now.minusSeconds(20 * 60), null);
        JsonNode reopened = rowOf(list(w.doctor().auth()), w.adm().id()).get("clinical").get("escalation");
        assertThat(reopened.get("waitingMinutes").asInt()).isEqualTo(20);
        assertThat(escRow(boardOf(w.doctor().auth()), again).get("waitingMinutes").asInt()).isEqualTo(20);
        assertThat(reopened.get("pastTarget").asBoolean()).isEqualTo(escRow(boardOf(w.doctor().auth()), again).get("pastTarget").asBoolean());
    }

    // ---------------- vitals due and the score ----------------

    @Test
    void vitalsAreDueOnlyWhenMoreThan240WholeMinutesOldOnTheDatabaseClock() throws Exception {
        Ward w = ward();
        save(w.clinic(), w.adm().id(), medium5());
        Instant recorded = recordedAt(w.clinic(), w.adm().id());
        Object[][] cases = {{recorded.plusSeconds(10), false}, {recorded.plusSeconds(240 * 60), false},
            {recorded.plusSeconds(240 * 60 + 59), false}, {recorded.plusSeconds(241 * 60), true},
            {recorded.plusSeconds(600 * 60), true}};
        for (Object[] c : cases) {
            clockAt((Instant) c[0]);
            JsonNode clinical = rowOf(list(w.nurse().auth()), w.adm().id()).get("clinical");
            assertThat(clinical.get("vitalsDue").asBoolean()).as("at " + c[0]).isEqualTo(c[1]);
        }
        // the score and risk are those of the latest record
        clockAt(recorded.plusSeconds(60));
        JsonNode ews = rowOf(list(w.doctor().auth()), w.adm().id()).get("clinical").get("latestEws");
        assertThat(ews.get("score").asInt()).isEqualTo(5);
        assertThat(ews.get("risk").asText()).isEqualTo("MEDIUM");
        // no vitals at all: due, and no score
        Ward fresh = anotherPatient(w);
        JsonNode none = rowOf(list(w.nurse().auth()), fresh.adm().id()).get("clinical");
        assertThat(none.get("vitalsDue").asBoolean()).isTrue();
        assertThat(none.get("latestEws").isNull()).isTrue();
        assertThat(none.get("escalation").isNull()).isTrue();
    }

    // ---------------- the allergy ----------------

    @Test
    void theAllergyStatusIsNoneKnownNotRecordedOrRecordedAndTheTextIsSentOnlyWhenRecorded() throws Exception {
        Ward none = ward();
        Ward notRecorded = new Ward(none.clinic(), none.doctor(), none.nurse(), none.nurse2(), none.nurse2Pin(), none.pharmacist(),
                none.pharmacistPin(), admitWithAllergy(none.clinic(), null), "x");
        Ward recorded = new Ward(none.clinic(), none.doctor(), none.nurse(), none.nurse2(), none.nurse2Pin(), none.pharmacist(),
                none.pharmacistPin(), admitWithAllergy(none.clinic(), "Penicillin rash"), "x");
        for (RequestPostProcessor auth : new RequestPostProcessor[] {none.clinic().owner(), none.doctor().auth(), none.nurse().auth()}) {
            JsonNode l = list(auth);
            JsonNode a = rowOf(l, none.adm().id()).get("clinical").get("allergy");
            assertThat(a.get("status").asText()).isEqualTo("NONE_KNOWN");
            assertThat(a.has("text")).as("no text for none known").isFalse();
            JsonNode b = rowOf(l, notRecorded.adm().id()).get("clinical").get("allergy");
            assertThat(b.get("status").asText()).isEqualTo("NOT_RECORDED");
            assertThat(b.has("text")).as("no text for not recorded").isFalse();
            JsonNode c = rowOf(l, recorded.adm().id()).get("clinical").get("allergy");
            assertThat(c.get("status").asText()).isEqualTo("RECORDED");
            assertThat(c.get("text").asText()).isEqualTo("Penicillin rash");
            assertThat(keys(c)).containsExactlyInAnyOrder("status", "text");
        }
        // a changed allergy shows on the next read
        setAllergy(none.clinic(), recorded.adm(), "None known");
        assertThat(rowOf(list(none.nurse().auth()), recorded.adm().id()).get("clinical").get("allergy").get("status").asText())
                .isEqualTo("NONE_KNOWN");
    }

    // ---------------- the room ----------------

    @Test
    void theRoomNameIsNullWhenRoomsAreOffAndTheRoomNameWhenTheyAreOn() throws Exception {
        Ward w = ward();
        JsonNode on = rowOf(list(w.nurse().auth()), w.adm().id());
        assertThat(on.get("roomName").isNull()).isFalse();
        assertThat(on.get("roomName").asText()).isNotBlank();
        ok(HttpMethod.PUT, ROOM_LEVEL, w.clinic().owner(), "{\"on\":false}", 200);
        JsonNode off = rowOf(list(w.nurse().auth()), w.adm().id());
        assertThat(off.has("roomName")).as("present as null, not absent").isTrue();
        assertThat(off.get("roomName").isNull()).isTrue();
        ok(HttpMethod.PUT, ROOM_LEVEL, w.clinic().owner(), "{\"on\":true}", 200);
        assertThat(rowOf(list(w.nurse().auth()), w.adm().id()).get("roomName").asText()).isEqualTo(on.get("roomName").asText());
    }

    // ---------------- privacy ----------------

    @Test
    void theListWritesNoRecordAccessRowOrAuditAndNoPatientDataReachesALog() throws Exception {
        Ward w = ward();
        setAllergy(w.clinic(), w.adm(), "Penicillin rash");
        raise(w.clinic(), w.adm(), high7());
        Staff frontDesk = staff(w.clinic(), "FRONT_DESK");
        long access = count(w.clinic(), "select count(*) from patient.record_access");
        long audits = allAudits(w.clinic());
        try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
            for (int i = 0; i < 3; i++) {
                list(w.clinic().owner());
                list(w.doctor().auth());
                list(w.nurse().auth());
                list(frontDesk.auth());
            }
            String text = logs.allText();
            assertThat(text).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain(w.uhid())
                    .doesNotContain("Penicillin");
        }
        assertThat(count(w.clinic(), "select count(*) from patient.record_access")).isEqualTo(access);
        assertThat(allAudits(w.clinic())).isEqualTo(audits);
    }

    @Test
    void theDepositLowAndGoingHomeFlagsStayOnTheRowForAllFourRolesAndTheSafetyObjectKeepsItsShape() throws Exception {
        Ward w = ward();
        for (RequestPostProcessor auth : new RequestPostProcessor[] {w.clinic().owner(), w.doctor().auth(), w.nurse().auth(),
            staff(w.clinic(), "FRONT_DESK").auth()}) {
            JsonNode row = rowOf(list(auth), w.adm().id());
            assertThat(row.has("depositLow")).isTrue();
            assertThat(row.has("goingHomeToday")).isTrue();
            assertThat(row.has("runningBillPaise")).isTrue();
        }
        List<String> safetyKeys = new ArrayList<>(keys(list(w.nurse().auth()).get("safety")));
        assertThat(safetyKeys).contains("counts");
    }
}
