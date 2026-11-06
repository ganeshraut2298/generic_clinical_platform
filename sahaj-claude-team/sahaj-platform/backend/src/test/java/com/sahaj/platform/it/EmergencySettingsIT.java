package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-28 Clinic setup → Emergency and triage on the real {@code sahaj_test} database: the defaults, the two kept target
 * sets, the owner-only whole-body save with its exact messages, the no-op when nothing differs, the audit rows, the
 * mapping of open cases when the scale changes (3 to 5 and 5 to 3, with SCALE_CHANGED events), targets that apply at
 * once, and bays that are never taken from a case. Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencySettingsIT extends EmergencySupport {

    private static final String TARGETS = "Choose a target from now to 240 minutes, in steps of 5";
    private static final String TEAM = "/api/v1/platform/team";

    private long configRows(Clinic clinic) throws Exception {
        return rows(clinic, "config");
    }

    // ---------------- defaults and reading ----------------

    @Test
    void aClinicThatNeverSavedHasTheDefaultsAndReadingCreatesNoRow() throws Exception {
        Clinic clinic = hospital();
        long before = configRows(clinic);
        JsonNode s = settings(clinic);
        assertThat(s.get("scale").asInt()).isEqualTo(3);
        assertThat(ints(s.get("targets3"))).containsExactly(5, 30, 120);
        assertThat(ints(s.get("targets5"))).containsExactly(0, 10, 30, 60, 120);
        assertThat(s.get("bays").asInt()).isEqualTo(2);
        assertThat(s.get("unknownPatientRegistration").asBoolean()).isTrue();
        assertThat(s.get("canEdit").asBoolean()).isTrue();
        JsonNode levels = s.get("levels");
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0).get("name").asText()).isEqualTo("Red");
        assertThat(levels.get(0).get("description").asText()).isEqualTo("Immediate");
        assertThat(levels.get(0).get("targetMinutes").asInt()).isEqualTo(5);
        assertThat(levels.get(2).get("targetMinutes").asInt()).isEqualTo(120);
        for (String role : ER_ROLES) {
            ok(HttpMethod.GET, ER_SETTINGS, clinic.as(role), null, 200);
            assertThat(ok(HttpMethod.GET, ER_SETTINGS, clinic.as(role), null, 200).get("canEdit").asBoolean())
                    .as(role).isEqualTo("OWNER".equals(role));
        }
        queue(clinic);
        assertThat(configRows(clinic)).as("reading creates no row").isEqualTo(before);
        assertThat(erAudits(clinic)).isZero();
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    @Test
    void theTwoTargetSetsAreKeptSeparatelyWhenTheScaleIsSwitched() throws Exception {
        Clinic clinic = hospital();
        JsonNode five = putSetup(clinic, 5, "[10,40,130]", "[0,15,35,65,125]", 3, true);
        assertThat(five.get("scale").asInt()).isEqualTo(5);
        assertThat(five.get("levels")).hasSize(5);
        assertThat(five.get("levels").get(1).get("targetMinutes").asInt()).isEqualTo(15);
        assertThat(ints(five.get("targets3"))).containsExactly(10, 40, 130);
        JsonNode three = putSetup(clinic, 3, "[10,40,130]", "[0,15,35,65,125]", 3, true);
        assertThat(three.get("levels")).hasSize(3);
        assertThat(three.get("levels").get(1).get("targetMinutes").asInt()).isEqualTo(40);
        JsonNode stored = settings(clinic);
        assertThat(ints(stored.get("targets3"))).containsExactly(10, 40, 130);
        assertThat(ints(stored.get("targets5"))).containsExactly(0, 15, 35, 65, 125);
        assertThat(values(clinic, "select triage_targets_3::text || ' ' || triage_targets_5::text || ' ' || triage_scale || ' ' "
                + "|| er_bays || ' ' || unknown_patient_registration from ipd.config"))
                .containsExactly("{10,40,130} {0,15,35,65,125} 3 3 true");
        // a new clinic still has the defaults
        assertThat(ints(settings(hospital()).get("targets3"))).containsExactly(5, 30, 120);
    }

    @Test
    void boundaryValuesAreAcceptedZeroAndTwoHundredFortyMinutesAndZeroAndTenBays() throws Exception {
        Clinic clinic = hospital();
        JsonNode s = putSetup(clinic, 5, "[0,0,240]", "[0,5,10,15,240]", 0, false);
        assertThat(ints(s.get("targets3"))).containsExactly(0, 0, 240);
        assertThat(ints(s.get("targets5"))).containsExactly(0, 5, 10, 15, 240);
        assertThat(s.get("bays").asInt()).isZero();
        assertThat(s.get("unknownPatientRegistration").asBoolean()).isFalse();
        assertThat(putSetup(clinic, 3, T3, T5, 10, true).get("bays").asInt()).isEqualTo(10);
        assertThat(queue(clinic).get("baysTotal").asInt()).isEqualTo(10);
    }

    // ---------------- validation ----------------

    private JsonNode refused(Clinic clinic, String json) throws Exception {
        long audits = erAudits(clinic);
        long config = configRows(clinic);
        JsonNode p = problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), json), 400);
        assertType(p, "validation");
        assertThat(erAudits(clinic)).isEqualTo(audits);
        assertThat(configRows(clinic)).isEqualTo(config);
        return p;
    }

    @Test
    void everyWrongValueIsRefusedWithItsExactMessageAndNothingIsSaved() throws Exception {
        Clinic clinic = hospital();
        String[][] bad = {
                {"scale", "4", "Choose 3 colours or 5 levels"}, {"scale", "0", "Choose 3 colours or 5 levels"},
                {"scale", "3.5", "Choose 3 colours or 5 levels"}, {"scale", "null", "Choose 3 colours or 5 levels"},
                {"targets3", "[5,30]", TARGETS}, {"targets3", "[5,30,120,200]", TARGETS}, {"targets3", "[5,30,7]", TARGETS},
                {"targets3", "[5,30,-5]", TARGETS}, {"targets3", "[5,30,245]", TARGETS}, {"targets3", "[5,30,null]", TARGETS},
                {"targets3", "[5,30,12.5]", TARGETS}, {"targets3", "null", TARGETS}, {"targets3", "[]", TARGETS},
                {"targets5", "[0,10,30,60]", TARGETS}, {"targets5", "[0,10,30,60,120,240]", TARGETS},
                {"targets5", "[0,10,30,60,121]", TARGETS}, {"targets5", "[0,10,30,60,245]", TARGETS},
                {"targets5", "[0,10,30,60,-5]", TARGETS}, {"targets5", "null", TARGETS},
                {"bays", "-1", "Choose 0 to 10 bays"}, {"bays", "11", "Choose 0 to 10 bays"}, {"bays", "2.5", "Choose 0 to 10 bays"},
                {"bays", "null", "Choose 0 to 10 bays"},
                {"unknownPatientRegistration", "null", "Choose on or off"}};
        for (String[] b : bad) {
            String json = settingsJson(5, T3, T5, 2, true).replaceFirst("\"" + b[0] + "\":(\\[[^\\]]*\\]|[^,}]*)", "\"" + b[0] + "\":" + b[1]);
            JsonNode p = refused(clinic, json);
            assertThat(fieldsOf(p)).as(b[0] + " " + b[1]).containsExactly(b[0]);
            assertThat(messageOf(p, b[0])).as(b[0] + " " + b[1]).isEqualTo(b[2]);
        }
        // everything wrong at once: every field, in the order of the contract
        JsonNode all = refused(clinic, "{}");
        assertThat(fieldsOf(all)).containsExactly("scale", "targets3", "targets5", "bays", "unknownPatientRegistration");
        JsonNode some = refused(clinic, settingsJson(7, "[1]", T5, 11, true));
        assertThat(fieldsOf(some)).containsExactly("scale", "targets3", "bays");
    }

    // ---------------- who may change it, check order ----------------

    @Test
    void onlyTheOwnerChangesTheSettingsAndTheOtherRolesAreRefusedWithAnUnchangedSetup() throws Exception {
        Clinic clinic = hospital();
        for (String role : ALL_ROLES) {
            if ("OWNER".equals(role)) {
                continue;
            }
            assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.as(role), settingsJson(5, T3, T5, 5, false)), 403), "forbidden");
        }
        assertThat(configRows(clinic)).isZero();
        assertThat(erAudits(clinic)).isZero();
        JsonNode s = settings(clinic);
        assertThat(s.get("scale").asInt()).isEqualTo(3);
        assertThat(s.get("canEdit").asBoolean()).isTrue();
    }

    @Test
    void aDemotedOwnersOldTokenCannotChangeTheSetup() throws Exception {
        Clinic clinic = hospital();
        Member second = addMember(clinic, "OWNER");
        UUID firstId = UUID.fromString(ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200).get("membershipId").asText());
        RequestPostProcessor secondOwner = bearer(login(second.mobile()).accessToken());
        ok(HttpMethod.PATCH, TEAM + "/" + firstId, secondOwner, "{\"role\":\"NURSE\"}", 200);
        problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), settingsJson(5, T3, T5, 5, true)), 403);
        assertThat(configRows(clinic)).isZero();
        assertThat(erAudits(clinic)).isZero();
        ok(HttpMethod.PUT, ER_SETTINGS, secondOwner, settingsJson(5, T3, T5, 5, true), 200);
        assertThat(settings(clinic).get("scale").asInt()).isEqualTo(5);
    }

    @Test
    void theOrderIsRoleThenModuleOffThenReadOnlyThenTheBody() throws Exception {
        Clinic off = newClinic();
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, off.owner(), "{}"), 403), "module-off");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, off.as("NURSE"), "{}"), 403), "forbidden");
        Clinic clinic = hospital();
        putSetup(clinic, 3, T3, T5, 3, true);
        expireTrial(clinic);
        long audits = erAudits(clinic);
        JsonNode ro = problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), "{}"), 409);
        assertType(ro, "ipd-readonly");
        assertThat(ro.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), settingsJson(5, T3, T5, 9, true)), 409), "ipd-readonly");
        assertType(problem(call(HttpMethod.PUT, ER_SETTINGS, clinic.as("FRONT_DESK"), "{}"), 403), "forbidden");
        assertThat(erAudits(clinic)).isEqualTo(audits);
        JsonNode read = settings(clinic);
        assertThat(read.get("bays").asInt()).isEqualTo(3);
        assertThat(read.get("canEdit").asBoolean()).as("read-only: nobody edits").isFalse();
    }

    // ---------------- no-op and audit ----------------

    @Test
    void savingTheSameValuesWritesNothingAndAChangeIsAuditedWithBeforeAndAfterAndTheRemappedCount() throws Exception {
        Clinic clinic = hospital();
        long config = configRows(clinic);
        putSetup(clinic, 3, T3, T5, 2, true);                     // the defaults, with no row yet
        assertThat(audits(clinic, "IPD_ER_SETTINGS_UPDATED")).isZero();
        assertThat(configRows(clinic)).as("nothing differs, no row").isEqualTo(config);

        putSetup(clinic, 3, T3, T5, 4, true);
        List<String[]> audit = audit(clinic, "IPD_ER_SETTINGS_UPDATED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_CONFIG");
        assertThat(audit.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        JsonNode before = objectMapper.readTree(audit.get(0)[2]);
        JsonNode after = objectMapper.readTree(audit.get(0)[3]);
        assertThat(before.get("bays").asInt()).isEqualTo(2);
        assertThat(before.get("scale").asInt()).isEqualTo(3);
        assertThat(ints(before.get("targets3"))).containsExactly(5, 30, 120);
        assertThat(ints(before.get("targets5"))).containsExactly(0, 10, 30, 60, 120);
        assertThat(before.get("unknownPatientRegistration").asBoolean()).isTrue();
        assertThat(before.has("remappedCases")).isFalse();
        assertThat(after.get("bays").asInt()).isEqualTo(4);
        assertThat(after.get("remappedCases").asInt()).isZero();
        assertThat(after.fieldNames()).toIterable().containsExactlyInAnyOrder("scale", "targets3", "targets5", "bays",
                "unknownPatientRegistration", "remappedCases");

        Written same = written(clinic);
        putSetup(clinic, 3, T3, T5, 4, true);
        assertThat(written(clinic)).as("the same values again").isEqualTo(same);
        assertThat(audits(clinic, "IPD_ER_SETTINGS_UPDATED")).isOne();
        putSetup(clinic, 3, "[5,35,120]", T5, 4, true);          // one target of the set in use
        putSetup(clinic, 3, "[5,35,120]", "[0,10,30,60,125]", 4, true);   // only the set not in use
        assertThat(audits(clinic, "IPD_ER_SETTINGS_UPDATED")).isEqualTo(3);
        putSetup(clinic, 3, "[5,35,120]", "[0,10,30,60,125]", 4, false);
        assertThat(audits(clinic, "IPD_ER_SETTINGS_UPDATED")).isEqualTo(4);
    }

    // ---------------- the scale changes ----------------

    @Test
    void threeColoursToFiveLevelsMapsEveryOpenCaseRedToLevel2YellowToLevel3GreenToLevel5AndLogsIt() throws Exception {
        Clinic clinic = hospital();
        String red = regWaited(clinic, "Red case", 1, 12).get("id").asText();
        String yellow = regWaited(clinic, "Yellow case", 2, 20).get("id").asText();
        String green = regWaited(clinic, "Green case", 3, 60).get("id").asText();
        String seenYellow = reg(clinic, "Seen yellow", 2).get("id").asText();
        seen(clinic, seenYellow);
        String closedGreen = reg(clinic, "Closed green", 3).get("id").asText();
        release(clinic, closedGreen);
        String bay = col(clinic, red, "bay");
        String arrived = col(clinic, red, "arrived_at");
        long redVersion = Long.parseLong(col(clinic, red, "version"));
        Written before = written(clinic);

        JsonNode s = setScale(clinic, 5);
        assertThat(s.get("scale").asInt()).isEqualTo(5);
        JsonNode q = queue(clinic);
        assertThat(q.get("scale").asInt()).isEqualTo(5);
        assertThat(caseIn(q, red).get("level").asInt()).isEqualTo(2);
        assertThat(caseIn(q, red).get("levelName").asText()).isEqualTo("Level 2");
        assertThat(caseIn(q, yellow).get("level").asInt()).isEqualTo(3);
        assertThat(caseIn(q, green).get("level").asInt()).isEqualTo(5);
        assertThat(caseIn(q, seenYellow).get("level").asInt()).as("a seen case is mapped too").isEqualTo(3);
        assertThat(caseIn(q, seenYellow).get("status").asText()).isEqualTo("SEEN");
        assertThat(col(clinic, closedGreen, "level")).as("a closed case is not mapped").isEqualTo("3");
        assertThat(events(clinic, closedGreen)).containsExactly("REGISTERED ->3 s3");
        // the arrival time, the status and the bay are kept; the wait goes on
        assertThat(col(clinic, red, "bay")).isEqualTo(bay);
        assertThat(col(clinic, red, "arrived_at")).isEqualTo(arrived);
        assertThat(caseIn(q, red).get("waitingMinutes").asInt()).isEqualTo(12);
        assertThat(Long.parseLong(col(clinic, red, "version"))).isEqualTo(redVersion + 1);
        assertThat(events(clinic, red)).containsExactly("REGISTERED ->1 s3", "SCALE_CHANGED 1>2 s5");
        assertThat(events(clinic, yellow)).containsExactly("REGISTERED ->2 s3", "SCALE_CHANGED 2>3 s5");
        assertThat(events(clinic, green)).containsExactly("REGISTERED ->3 s3", "SCALE_CHANGED 3>5 s5");
        assertThat(events(clinic, seenYellow)).containsExactly("REGISTERED ->2 s3", "SCALE_CHANGED 2>3 s5");
        assertThat(values(clinic, "select distinct recorded_by::text from ipd.emergency_triage_event where reason = 'SCALE_CHANGED'"))
                .containsExactly(clinic.ownerUserId().toString());
        assertThat(eventRows(clinic)).isEqualTo(before.events() + 4);
        JsonNode after = objectMapper.readTree(audit(clinic, "IPD_ER_SETTINGS_UPDATED").get(0)[3]);
        assertThat(after.get("remappedCases").asInt()).isEqualTo(4);
        assertThat(after.get("scale").asInt()).isEqualTo(5);

        // the targets of the new scale apply at once: Level 2 is 10 minutes, so a 12-minute wait is over
        assertThat(caseIn(q, red).get("targetMinutes").asInt()).isEqualTo(10);
        assertThat(caseIn(q, red).get("overTarget").asBoolean()).isTrue();
        assertThat(caseIn(q, green).get("targetMinutes").asInt()).isEqualTo(120);
        assertThat(caseIn(q, green).get("overTarget").asBoolean()).isFalse();
    }

    @Test
    void fiveLevelsToThreeColoursMapsLevels1And2ToRed3ToYellow4And5ToGreenAndLogsEveryOpenCase() throws Exception {
        Clinic clinic = hospital();
        setScale(clinic, 5);
        String[] ids = new String[6];
        for (int level = 1; level <= 5; level++) {
            ids[level] = reg(clinic, "Level " + level, level).get("id").asText();
        }
        String closed = reg(clinic, "Closed four", 4).get("id").asText();
        refer(clinic, closed);
        String bay = col(clinic, ids[1], "bay");
        assertThat(bay).isEqualTo("ER-1");

        setScale(clinic, 3);
        JsonNode q = queue(clinic);
        int[] want = {0, 1, 1, 2, 3, 3};
        String[] wantName = {"", "Red", "Red", "Yellow", "Green", "Green"};
        for (int level = 1; level <= 5; level++) {
            assertThat(caseIn(q, ids[level]).get("level").asInt()).as("level " + level).isEqualTo(want[level]);
            assertThat(caseIn(q, ids[level]).get("levelName").asText()).isEqualTo(wantName[level]);
            // every open case gets an event, also the one whose level number stays 1
            assertThat(events(clinic, ids[level]).get(events(clinic, ids[level]).size() - 1))
                    .isEqualTo("SCALE_CHANGED " + level + ">" + want[level] + " s3");
        }
        assertThat(col(clinic, ids[1], "bay")).as("the bay stays").isEqualTo(bay);
        assertThat(col(clinic, closed, "level")).isEqualTo("4");
        assertThat(events(clinic, closed)).hasSize(1);
        assertThat(objectMapper.readTree(audit(clinic, "IPD_ER_SETTINGS_UPDATED").get(1)[3]).get("remappedCases").asInt()).isEqualTo(5);
        assertThat(q.get("levels")).hasSize(3);
        // the old levels still validate: level 4 is no longer a choice
        assertFieldError(problem(retriageCall(clinic.owner(), ids[3], "{\"level\":4}"), 400), "level");
    }

    @Test
    void aSwitchWithNoOpenCaseMapsNothingAndOtherChangesNeverTouchCases() throws Exception {
        Clinic clinic = hospital();
        String id = reg(clinic, "Stays as is", 2).get("id").asText();
        long events = eventRows(clinic);
        putSetup(clinic, 3, T3, T5, 5, false);
        putSetup(clinic, 3, "[5,10,120]", "[0,10,30,60,125]", 5, false);
        assertThat(eventRows(clinic)).as("no scale change, no event").isEqualTo(events);
        assertThat(col(clinic, id, "level")).isEqualTo("2");
        assertThat(col(clinic, id, "version")).isEqualTo("0");
        release(clinic, id);
        long after = eventRows(clinic);
        setScale(clinic, 5);
        assertThat(eventRows(clinic)).as("no open case, no event").isEqualTo(after);
        assertThat(objectMapper.readTree(audit(clinic, "IPD_ER_SETTINGS_UPDATED").get(2)[3]).get("remappedCases").asInt()).isZero();
    }

    // ---------------- targets and bays apply at once ----------------

    @Test
    void aNewTargetAppliesAtOnceToTheCasesInTheQueue() throws Exception {
        Clinic clinic = hospital();
        String id = regWaited(clinic, "Yellow 20 min", 2, 20).get("id").asText();
        assertThat(caseIn(queue(clinic), id).get("overTarget").asBoolean()).isFalse();
        putSetup(clinic, 3, "[5,15,120]", T5, 2, true);
        JsonNode q = queue(clinic);
        assertThat(caseIn(q, id).get("targetMinutes").asInt()).isEqualTo(15);
        assertThat(caseIn(q, id).get("overTarget").asBoolean()).isTrue();
        assertThat(q.get("counts").get("overTarget").asInt()).isOne();
        // the set that is not in use changes nothing on the cards
        putSetup(clinic, 3, "[5,15,120]", "[0,5,10,15,20]", 2, true);
        assertThat(caseIn(queue(clinic), id).get("targetMinutes").asInt()).isEqualTo(15);
        // a longer target takes the case out of "over"
        putSetup(clinic, 3, "[5,60,120]", T5, 2, true);
        assertThat(caseIn(queue(clinic), id).get("overTarget").asBoolean()).isFalse();
    }

    @Test
    void loweringTheBaysNeverTakesABayFromACaseAndTheFreeCountOnlyCountsTheConfiguredBays() throws Exception {
        Clinic clinic = hospital();
        setBays(clinic, 3);
        String r1 = reg(clinic, "Bay one", 1).get("id").asText();
        String r2 = reg(clinic, "Bay two", 1).get("id").asText();
        String r3 = reg(clinic, "Bay three", 1).get("id").asText();
        assertThat(List.of(col(clinic, r1, "bay"), col(clinic, r2, "bay"), col(clinic, r3, "bay"))).containsExactly("ER-1", "ER-2", "ER-3");

        JsonNode lowered = setBays(clinic, 1);
        assertThat(lowered.get("bays").asInt()).isOne();
        JsonNode q = queue(clinic);
        assertThat(q.get("baysTotal").asInt()).isOne();
        assertThat(q.get("baysFree").asInt()).as("ER-1 is held").isZero();
        assertThat(caseIn(q, r2).get("bay").asText()).as("the case keeps ER-2").isEqualTo("ER-2");
        assertThat(caseIn(q, r3).get("bay").asText()).isEqualTo("ER-3");
        assertThat(col(clinic, r2, "bay")).isEqualTo("ER-2");
        assertThat(reg(clinic, "No bay now", 1).get("bay").isNull()).isTrue();

        // freeing ER-2 or ER-3 gives no bay, because only ER-1 is configured; freeing ER-1 does
        release(clinic, r2);
        assertThat(queue(clinic).get("baysFree").asInt()).isZero();
        assertThat(reg(clinic, "Still none", 1).get("bay").isNull()).isTrue();
        release(clinic, r1);
        assertThat(queue(clinic).get("baysFree").asInt()).isOne();
        assertThat(reg(clinic, "Gets ER-1", 1).get("bay").asText()).isEqualTo("ER-1");
        // raising the bays again: ER-2 is free (its case left), ER-3 is still held
        setBays(clinic, 3);
        assertThat(queue(clinic).get("baysFree").asInt()).isOne();
        assertThat(reg(clinic, "Gets ER-2", 1).get("bay").asText()).isEqualTo("ER-2");
        assertThat(reg(clinic, "ER-3 is held", 1).get("bay").isNull()).isTrue();
    }

    @Test
    void theSettingsOfOneClinicNeverChangeAnotherAndTheSetupIsReadableByTheOtherReaders() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        putSetup(a, 5, "[15,45,135]", "[0,5,10,15,20]", 7, false);
        JsonNode sb = settings(b);
        assertThat(sb.get("scale").asInt()).isEqualTo(3);
        assertThat(ints(sb.get("targets3"))).containsExactly(5, 30, 120);
        assertThat(sb.get("bays").asInt()).isEqualTo(2);
        for (String role : ER_ROLES) {
            JsonNode s = ok(HttpMethod.GET, ER_SETTINGS, a.as(role), null, 200);
            assertThat(s.get("scale").asInt()).isEqualTo(5);
            assertThat(s.get("bays").asInt()).isEqualTo(7);
        }
        assertThat(audits(b, "IPD_ER_SETTINGS_UPDATED")).isZero();
        assertThat(audits(a, "IPD_ER_SETTINGS_UPDATED")).isOne();
        assertThat(configRows(b)).isZero();
    }
}
