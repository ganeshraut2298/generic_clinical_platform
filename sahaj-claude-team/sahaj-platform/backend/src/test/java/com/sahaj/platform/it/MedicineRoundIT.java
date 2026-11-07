package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-35 the Medicine round on the Safety board on the real {@code sahaj_test} database (ADR-012, docs/stories/SAH-35.md,
 * docs/database/SAH-35.md): the first execution of the two set-based reads (Q1 orders of admitted patients with the
 * {@code cast(:admissionIds as uuid[])} binding of Q2) under row level security, the database clock moved with the
 * {@link RoundSupport} seam to the 60 and 61 minute edges, the rows (order, fields, tie-break, the 200-row cap with the
 * true counts), the tile figures and the invariant dosesDue + dosesLate = sum of the MAR tab counts, the change token
 * (with and without dose lines), two nurses, another clinic, the roles, module Off, Read-only, the three places that must
 * write no record-access row, and no patient or medicine in a log. Test plan: docs/test-plans/SAH-35.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineRoundIT extends RoundSupport {

    private static final String[] ROW_KEYS = {"admissionId", "orderId", "slotAt", "state", "lateHours", "drugName", "highAlert",
        "patientName", "bedCode"};

    private JsonNode counts(JsonNode board) {
        return board.get("counts");
    }

    // ---------------- AC1 to AC3, AC9: the panel, the row, the tile ----------------

    @Test
    void theBoardHoldsThePanelWithTheRowsTheFieldsAndTheTileFiguresForTheThreeBoardRoles() throws Exception {
        Ward w = ward();
        String pcm = activeOrder(w, "pcm", "od");
        String hep = activeOrder(w, "hep", "bd");
        clockAt(10, 5);
        for (Object[] who : new Object[][] {{"OWNER", w.clinic().owner()}, {"DOCTOR", w.doctor().auth()}, {"NURSE", w.nurse().auth()}}) {
            JsonNode b = boardOf((org.springframework.test.web.servlet.request.RequestPostProcessor) who[1]);
            JsonNode round = b.get("medicineRound");
            assertThat(keys(round)).as(who[0] + "").containsExactlyInAnyOrder("canGive", "giveLockReason", "truncated", "rows");
            assertThat(round.get("truncated").asBoolean()).isFalse();
            assertThat(round.get("rows")).hasSize(2);
            JsonNode first = round.get("rows").get(0);
            assertThat(keys(first)).containsExactlyInAnyOrder(ROW_KEYS);
            assertThat(first.get("orderId").asText()).as("same slot time: tie-break by bed, then medicine name").isEqualTo(hep);
            assertThat(first.get("drugName").asText()).isEqualTo("Inj. Heparin 5,000 IU");
            assertThat(first.get("highAlert").asText()).isEqualTo("Anticoagulant");
            assertThat(first.get("state").asText()).isEqualTo("LATE");
            assertThat(first.get("lateHours").asInt()).as("125 minutes late is 2 whole hours").isEqualTo(2);
            assertThat(Instant.parse(first.get("slotAt").asText())).isEqualTo(ist(8, 0));
            assertThat(first.get("patientName").asText()).isEqualTo(w.adm().name());
            assertThat(first.get("bedCode").asText()).isEqualTo(w.adm().bedCode());
            assertThat(first.get("admissionId").asText()).isEqualTo(w.adm().id());
            JsonNode second = round.get("rows").get(1);
            assertThat(second.get("orderId").asText()).isEqualTo(pcm);
            assertThat(second.get("highAlert").isNull()).isTrue();
            assertThat(counts(b).get("dosesLate").asInt()).isEqualTo(2);
            assertThat(counts(b).get("dosesDue").asInt()).isZero();
            assertThat(Instant.parse(b.get("asOf").asText())).as("the board's clock is the database clock").isEqualTo(ist(10, 5));
            boolean give = !"DOCTOR".equals(who[0]);
            assertThat(round.get("canGive").asBoolean()).as(who[0] + "").isEqualTo(give);
            assertThat(round.get("giveLockReason").isNull()).isEqualTo(give);
            if (!give) {
                assertThat(round.get("giveLockReason").asText()).isEqualTo("ROLE");
            }
        }
        // the summary carries the same two figures and no name, medicine or bed
        JsonNode summary = summaryOf(w.nurse().auth());
        assertThat(summary.get("dosesLate").asInt()).isEqualTo(2);
        assertThat(summary.get("dosesDue").asInt()).isZero();
        assertThat(summary.toString()).doesNotContain(w.adm().name()).doesNotContain("Heparin").doesNotContain(w.adm().bedCode());
    }

    @Test
    void whenNoDoseIsDueTheRoundIsEmptyAndTheFiguresAreZero() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(3, 0);
        JsonNode b = boardOf(w.nurse().auth());
        assertThat(b.get("medicineRound").get("rows")).isEmpty();
        assertThat(b.get("medicineRound").get("canGive").asBoolean()).isTrue();
        assertThat(counts(b).get("dosesDue").asInt()).isZero();
        assertThat(counts(b).get("dosesLate").asInt()).isZero();
        // a clinic that has no order at all
        Ward bare = ward();
        clockAt(10, 5);
        assertThat(boardOf(bare.nurse().auth()).get("medicineRound").get("rows")).isEmpty();
    }

    // ---------------- AC2: the 60 and 61 minute edges on the database clock ----------------

    @Test
    void aSlotIsListedFromSixtyMinutesAheadToSixtyWholeMinutesAfterAndLateFromSixtyOneWithTheMarAgreeing() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        Object[][] cases = {
            {ist(6, 58, 59), null, 0},    // 61 min 1 s ahead: LATER
            {ist(6, 59, 1), "DUE", 0},    // 60 min 59 s ahead: whole minutes, still DUE
            {ist(7, 0, 0), "DUE", 0},
            {ist(8, 0, 0), "DUE", 0},
            {ist(9, 0, 0), "DUE", 0},     // exactly 60 minutes after
            {ist(9, 0, 59), "DUE", 0},    // 60 min 59 s after: still DUE (rounded down)
            {ist(9, 1, 0), "LATE", 1},    // 61 minutes
            {ist(9, 59, 59), "LATE", 1},  // 119 min 59 s: 1 whole hour
            {ist(10, 0, 0), "LATE", 2},
            {ist(23, 59, 0), "LATE", 15},
        };
        for (Object[] c : cases) {
            Instant at = (Instant) c[0];
            clockAt(at);
            JsonNode b = boardOf(w.nurse().auth());
            JsonNode row = roundRow(b, order, ist(8, 0));
            if (c[1] == null) {
                assertThat(row).as("at " + at).isNull();
                assertThat(counts(b).get("dosesDue").asInt() + counts(b).get("dosesLate").asInt()).isZero();
            } else {
                assertThat(row).as("at " + at).isNotNull();
                assertThat(row.get("state").asText()).as("at " + at).isEqualTo(c[1]);
                if ("LATE".equals(c[1])) {
                    assertThat(row.get("lateHours").asInt()).as("at " + at).isEqualTo(c[2]);
                } else {
                    assertThat(row.get("lateHours").isNull()).isTrue();
                }
            }
            JsonNode mar = mar(w.nurse().auth(), w.adm().id());
            assertThat(counts(b).get("dosesDue").asInt() + counts(b).get("dosesLate").asInt()).as("the MAR tab count at " + at)
                    .isEqualTo(mar.get("doseCount").asInt());
            JsonNode s = summaryOf(w.nurse().auth());
            assertThat(s.get("dosesDue").asInt()).isEqualTo(counts(b).get("dosesDue").asInt());
            assertThat(s.get("dosesLate").asInt()).isEqualTo(counts(b).get("dosesLate").asInt());
        }
    }

    // ---------------- AC2: what is not listed ----------------

    @Test
    void pendingRejectedAndStoppedOrdersDischargedPatientsRecordedSlotsAndSlotsBeforeTheOrderStartedAreNotListed() throws Exception {
        Ward w = ward();
        String listed = activeOrder(w, "pcm", "tds");                  // 8 AM, 2 PM, 8 PM
        String pending = pendingOrder(w, "panto", "od");
        String rejected = pendingOrder(w, "ondan", "od");
        rejectOk(w.pharmacist().auth(), rejected, "WRONG_DOSE");
        String stopped = activeOrder(w, "ns", "od");
        stopOk(w.doctor().auth(), stopped);
        String late = activeOrderVerifiedAt(w, "ceft", "od", 10, 0);   // checked at 10:00: the 8 AM slot is before it started
        String given = activeOrder(w, "amox", "od");
        String held = activeOrder(w, "cotri", "od");
        String refused = activeOrder(w, "asp", "od");
        clockAt(8, 5);
        doseOk(w.nurse().auth(), given, given(w, ist(8, 0)));
        doseOk(w.nurse().auth(), held, notGiven(ist(8, 0), "HELD_DOCTOR"));
        doseOk(w.nurse().auth(), refused, notGiven(ist(8, 0), "PATIENT_REFUSED"));
        clockAt(10, 5);
        // a discharged patient with a due order
        Ward gone = anotherPatient(w);
        String goneOrder = activeOrder(gone, "pcm", "od");
        discharge(gone);
        clockAt(10, 5);
        JsonNode b = boardOf(w.nurse().auth());
        List<String> keys = roundKeys(b);
        assertThat(keys).containsExactly(listed + "@" + ist(8, 0).getEpochSecond());
        for (String not : new String[] {pending, rejected, stopped, late, given, held, refused, goneOrder}) {
            assertThat(keys.stream().anyMatch(k -> k.startsWith(not))).as("order " + not + " is not listed").isFalse();
        }
        assertThat(counts(b).get("dosesLate").asInt()).isOne();
        assertThat(counts(b).get("dosesDue").asInt()).isZero();
        // the slot 2 PM is more than 60 minutes ahead; at 1:30 PM it is listed as DUE
        clockAt(13, 30);
        assertThat(roundRow(boardOf(w.nurse().auth()), listed, ist(14, 0)).get("state").asText()).isEqualTo("DUE");
    }

    // ---------------- AC4: order and tie-break ----------------

    @Test
    void lateRowsComeFirstThenTheEarlierSlotThenBedThenMedicineNameThenOrderId() throws Exception {
        Ward a = ward();
        Ward b = anotherPatient(a);
        Ward c = anotherPatient(a);
        activeOrder(a, "pcm", "tds");
        activeOrder(a, "panto", "bd");
        activeOrder(b, "pcm", "tds");
        activeOrder(b, "ns", "od");
        activeOrder(c, "ondan", "tds");
        activeOrder(c, "pcm", "od");
        clockAt(14, 20);
        JsonNode board = boardOf(a.nurse().auth());
        List<JsonNode> rows = new ArrayList<>();
        board.get("medicineRound").get("rows").forEach(rows::add);
        assertThat(rows).hasSize(9);
        for (int i = 1; i < rows.size(); i++) {
            JsonNode p = rows.get(i - 1);
            JsonNode q = rows.get(i);
            int stateP = "LATE".equals(p.get("state").asText()) ? 0 : 1;
            int stateQ = "LATE".equals(q.get("state").asText()) ? 0 : 1;
            int byState = Integer.compare(stateP, stateQ);
            int bySlot = Instant.parse(p.get("slotAt").asText()).compareTo(Instant.parse(q.get("slotAt").asText()));
            int byBed = p.get("bedCode").asText().compareTo(q.get("bedCode").asText());
            int byDrug = p.get("drugName").asText().compareTo(q.get("drugName").asText());
            int byId = java.util.UUID.fromString(p.get("orderId").asText()).compareTo(java.util.UUID.fromString(q.get("orderId").asText()));
            int cmp = byState != 0 ? byState : bySlot != 0 ? bySlot : byBed != 0 ? byBed : byDrug != 0 ? byDrug : byId;
            assertThat(cmp).as("row " + i + " must not sort before row " + (i - 1)).isLessThanOrEqualTo(0);
        }
        // and the order is the same on a second read (it does not jump between refreshes)
        assertThat(roundKeys(boardOf(a.nurse().auth()))).isEqualTo(roundKeys(board));
        // LATE group first
        assertThat(rows.get(0).get("state").asText()).isEqualTo("LATE");
        assertThat(rows.get(rows.size() - 1).get("state").asText()).isEqualTo("DUE");
    }

    // ---------------- the invariant, two patients ----------------

    @Test
    void duePlusLateEqualsTheSumOfTheMarTabCountsOverTheAdmittedPatientsAtOneClock() throws Exception {
        Ward a = ward();
        Ward b = anotherPatient(a);
        String a1 = activeOrder(a, "pcm", "tds");
        activeOrder(a, "hep", "bd");
        activeOrder(b, "pcm", "q8");
        activeOrder(b, "ns", "od");
        clockAt(14, 10);
        doseOk(a.nurse().auth(), a1, given(a, ist(8, 0)));
        for (Instant at : new Instant[] {ist(5, 30), ist(8, 30), ist(13, 5), ist(14, 10), ist(19, 0), ist(23, 30)}) {
            clockAt(at);
            JsonNode board = boardOf(a.clinic().owner());
            int sum = mar(a.clinic().owner(), a.adm().id()).get("doseCount").asInt()
                    + mar(a.clinic().owner(), b.adm().id()).get("doseCount").asInt();
            assertThat(counts(board).get("dosesDue").asInt() + counts(board).get("dosesLate").asInt()).as("at " + at)
                    .isEqualTo(sum);
            assertThat(board.get("medicineRound").get("rows").size()).isEqualTo(sum);
        }
    }

    // ---------------- the change token ----------------

    @Test
    void theChangeTokenHoldsTheDoseLinesAndIsTheSah33TokenWhenNoDoseIsInTheWindow() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(3, 0);
        JsonNode none = boardOf(w.nurse().auth());
        assertThat(counts(none).get("changeToken").asText()).isEqualTo(EMPTY_TOKEN).isEqualTo(expectedChangeToken(w.clinic()));
        assertThat(summaryOf(w.nurse().auth()).get("changeToken").asText()).isEqualTo(EMPTY_TOKEN);

        clockAt(ist(7, 30));
        String dueToken = counts(boardOf(w.nurse().auth())).get("changeToken").asText();
        List<String> lines = new ArrayList<>(escalationLines(w.clinic()));
        lines.add(doseLine(order, ist(8, 0), "DUE"));
        assertThat(dueToken).isEqualTo(tokenOfLines(lines));
        assertThat(summaryOf(w.nurse().auth()).get("changeToken").asText()).as("board and summary agree").isEqualTo(dueToken);

        // DUE turns LATE: the token changes
        clockAt(ist(9, 30));
        String lateToken = counts(boardOf(w.nurse().auth())).get("changeToken").asText();
        assertThat(lateToken).isNotEqualTo(dueToken);
        assertThat(lateToken).isEqualTo(tokenOfLines(List.of(doseLine(order, ist(8, 0), "LATE"))));
        // only lateHours grows: the token does not change
        clockAt(ist(11, 45));
        assertThat(counts(boardOf(w.nurse().auth())).get("changeToken").asText()).isEqualTo(lateToken);
        // a dose recorded by anyone: the slot leaves and the token goes back to the empty token
        clockAt(ist(11, 50));
        doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        assertThat(counts(boardOf(w.nurse().auth())).get("changeToken").asText()).isEqualTo(EMPTY_TOKEN);
    }

    @Test
    void oneDoseRecordedWhileAnotherEntersTheWindowKeepsTheCountsButChangesTheToken() throws Exception {
        Ward w = ward();
        String leaving = activeOrder(w, "pcm", "q8");   // 6:00, 2 PM, 10 PM
        String entering = activeOrder(w, "ns", "od");   // 8:00
        clockAt(ist(6, 59, 0));                          // 6:00 is DUE (59 min after); 8:00 is 61 min ahead: not yet
        JsonNode first = boardOf(w.nurse().auth());
        assertThat(counts(first).get("dosesDue").asInt()).isOne();
        assertThat(counts(first).get("dosesLate").asInt()).isZero();
        String t1 = counts(first).get("changeToken").asText();
        assertThat(t1).isEqualTo(tokenOfLines(List.of(doseLine(leaving, ist(6, 0), "DUE"))));
        doseOk(w.nurse().auth(), leaving, given(w, ist(6, 0)));
        clockAt(ist(6, 59, 1));                          // 8:00 is 60 min 59 s ahead: whole minutes, DUE
        JsonNode second = boardOf(w.nurse().auth());
        assertThat(counts(second).get("dosesDue").asInt()).as("the counts are equal").isEqualTo(counts(first).get("dosesDue").asInt());
        assertThat(counts(second).get("dosesLate").asInt()).isEqualTo(counts(first).get("dosesLate").asInt());
        assertThat(counts(second).get("changeToken").asText()).as("the token is not").isNotEqualTo(t1)
                .isEqualTo(tokenOfLines(List.of(doseLine(entering, ist(8, 0), "DUE"))));
    }

    @Test
    void theTokenOfTheAdmittedListEqualsTheTokenOfTheBoardAndTheSummaryAtOneSnapshot() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "bd");
        clockAt(9, 30);
        String board = counts(boardOf(w.nurse().auth())).get("changeToken").asText();
        JsonNode list = ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200);
        JsonNode safety = list.get("safety").get("counts");
        assertThat(safety.get("changeToken").asText()).isEqualTo(board).isEqualTo(summaryOf(w.nurse().auth()).get("changeToken").asText());
        assertThat(safety.get("dosesLate").asInt()).isEqualTo(summaryOf(w.nurse().auth()).get("dosesLate").asInt());
        assertThat(safety.get("dosesDue").asInt()).isEqualTo(summaryOf(w.nurse().auth()).get("dosesDue").asInt());
    }

    // ---------------- two nurses, the same dose ----------------

    @Test
    void whenOneNurseRecordsADoseItLeavesTheOthersListAtOnceAndTheSecondTryIsA409() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        assertThat(roundRow(boardOf(w.nurse().auth()), order, ist(8, 0))).isNotNull();
        assertThat(roundRow(boardOf(w.nurse2().auth()), order, ist(8, 0))).isNotNull();
        doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        assertThat(roundRow(boardOf(w.nurse2().auth()), order, ist(8, 0))).as("gone for the other nurse").isNull();
        assertThat(counts(boardOf(w.nurse2().auth())).get("dosesLate").asInt()).isZero();
        refused(doseCall(w.nurse2().auth(), order, given(w, ist(8, 0))), 409, "dose-recorded", DOSE_RECORDED);
    }

    @Test
    void twoNursesGivingTheSameRowAtTheSameMomentRecordExactlyOneDose() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        List<Resp> answers = parallel(
                new Req(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(given(w, ist(8, 0)))),
                new Req(HttpMethod.POST, dosesUrl(order), w.nurse2().auth(), js(given(w, ist(8, 0)))));
        assertThat(answers.stream().map(Resp::status).sorted().toList()).containsExactly(201, 409);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
        assertThat(roundRow(boardOf(w.nurse().auth()), order, ist(8, 0))).isNull();
    }

    // ---------------- tenants ----------------

    @Test
    void anotherClinicsOrdersPatientsAndDosesAreNeverInTheListOrTheCounts() throws Exception {
        Ward mine = ward();
        Ward theirs = ward();
        String myOrder = activeOrder(mine, "pcm", "od");
        String theirOrder = activeOrder(theirs, "hep", "bd");
        activeOrder(theirs, "pcm", "od");
        clockAt(10, 5);
        JsonNode mineBoard = boardOf(mine.nurse().auth());
        JsonNode theirBoard = boardOf(theirs.nurse().auth());
        assertThat(roundKeys(mineBoard)).containsExactly(myOrder + "@" + ist(8, 0).getEpochSecond());
        assertThat(roundKeys(theirBoard)).hasSize(2);
        assertThat(mineBoard.toString()).doesNotContain(theirs.adm().name()).doesNotContain(theirOrder);
        assertThat(theirBoard.toString()).doesNotContain(mine.adm().name()).doesNotContain(myOrder);
        assertThat(counts(mineBoard).get("dosesLate").asInt()).isOne();
        assertThat(counts(theirBoard).get("dosesLate").asInt()).isEqualTo(2);
        // the dose endpoint and the MAR of the other clinic are 404 (row level security)
        refused(doseCall(mine.nurse().auth(), theirOrder, given(mine, ist(8, 0))), 404, "not-found", null);
        problem(call(HttpMethod.GET, marUrl(theirs.adm().id()), mine.nurse().auth(), null), 404);
    }

    // ---------------- roles, module Off, Read-only ----------------

    @Test
    void everyRoleOutsideTheBoardRolesIs403OnTheBoardAndTheSummaryAndTheBoardRolesAreReadAgainFromTheMembership() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        for (String role : NON_BOARD_ROLES) {
            Staff s = staff(w.clinic(), role);
            JsonNode board = problem(call(HttpMethod.GET, SAFETY, s.auth(), null), 403);
            assertType(board, "forbidden");
            JsonNode summary = problem(call(HttpMethod.GET, SUMMARY, s.auth(), null), 403);
            assertType(summary, "forbidden");
            assertThat(board.toString()).doesNotContain(w.adm().name());
        }
        // a nurse whose membership role changed to FRONT_DESK: the token still says NURSE, the membership decides
        Staff moved = staff(w.clinic(), "NURSE");
        assertThat(boardOf(moved.auth())).isNotNull();
        setMembershipRole(w.clinic(), moved.userId(), "FRONT_DESK");
        assertType(problem(call(HttpMethod.GET, SAFETY, moved.auth(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.GET, SUMMARY, moved.auth(), null), 403), "forbidden");
        // an unauthenticated call
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(SAFETY))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void withTheModuleOffEveryCallAnswers403ModuleOff() throws Exception {
        Clinic off = newClinic();
        for (String role : new String[] {"OWNER", "NURSE", "DOCTOR"}) {
            for (String url : new String[] {SAFETY, SUMMARY, ADMISSIONS}) {
                JsonNode p = problem(call(HttpMethod.GET, url, off.as(role), null), 403);
                if ("OWNER".equals(role)) {
                    assertType(p, "module-off");
                }
            }
        }
    }

    @Test
    void inReadOnlyTheListStaysReadableNoGiveIsOfferedAndTheDoseCallIs409() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        expireTrial(w.clinic());
        {
            JsonNode b = boardOf(w.nurse().auth());
            assertThat(roundRow(b, order, ist(8, 0))).isNotNull();
            assertThat(b.get("medicineRound").get("canGive").asBoolean()).isFalse();
            assertThat(b.get("medicineRound").get("giveLockReason").asText()).isEqualTo("IPD_READONLY");
            assertThat(boardOf(w.clinic().owner()).get("medicineRound").get("giveLockReason").asText()).isEqualTo("IPD_READONLY");
            // the doctor's reason is the role first
            assertThat(boardOf(w.doctor().auth()).get("medicineRound").get("giveLockReason").asText()).isEqualTo("ROLE");
            refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "ipd-readonly", null);
        }
    }

    // ---------------- AC20, AC22: the dose endpoint keeps its own role rule ----------------

    @Test
    void aDoctorSeesTheRowsButCannotGiveAndThePostIs403() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        assertThat(roundRow(boardOf(w.doctor().auth()), order, ist(8, 0))).isNotNull();
        refused(doseCall(w.doctor().auth(), order, given(w, ist(8, 0))), 403, "forbidden", ONLY_GIVERS);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();
    }

    // ---------------- AC23: record access ----------------

    @Test
    void theBoardTheSummaryAndTheAdmittedListWriteNoRecordAccessRowAndTheMarOpenWritesOne() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        long before = count(w.clinic(), "select count(*) from patient.record_access");
        for (int i = 0; i < 3; i++) {
            boardOf(w.nurse().auth());
            boardOf(w.clinic().owner());
            summaryOf(w.nurse().auth());
            summaryOf(w.doctor().auth());
            ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200);
            ok(HttpMethod.GET, ADMISSIONS, w.clinic().owner(), null, 200);
        }
        assertThat(count(w.clinic(), "select count(*) from patient.record_access")).as("lists write no access row").isEqualTo(before);
        mar(w.nurse().auth(), w.adm().id());
        assertThat(count(w.clinic(), "select count(*) from patient.record_access")).as("the Give sheet's MAR read writes one")
                .isEqualTo(before + 1);
    }

    // ---------------- AC7: privacy in logs and audit ----------------

    @Test
    void noPatientNameBedOrMedicineReachesTheLogsOrTheAuditWhenTheBoardIsRead() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(10, 5);
        long audits = allAudits(w.clinic());
        try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
            boardOf(w.nurse().auth());
            summaryOf(w.nurse().auth());
            ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200);
            String text = logs.allText();
            assertThat(text).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain("Paracetamol")
                    .doesNotContain(w.uhid());
        }
        assertThat(allAudits(w.clinic())).as("reads write no audit row").isEqualTo(audits);
    }

    // ---------------- code review R35-1: one damaged order must not blank the clinic's board ----------------

    /**
     * The database cannot hold an order whose drug is not in the catalogue (foreign key) or whose frequency is unknown (check),
     * so the damage is made on the other side: the application's cached catalogue is swapped, through its own private field,
     * for one that lacks {@code hep}, and put back in {@code finally}. The order of {@code hep} is real, ACTIVE and due.
     */
    @Test
    void anOrderWhoseDrugIsNotInTheCatalogueIsLeftOutWithOneWarningAndTheBoardTheSummaryAndTheAdmittedListStillAnswer()
            throws Exception {
        Ward w = ward();
        String pcm = activeOrder(w, "pcm", "od");
        String hep = activeOrder(w, "hep", "od");
        clockAt(10, 5);
        assertThat(roundKeys(boardOf(w.nurse().auth()))).as("both orders are listed with the full catalogue").hasSize(2);

        Class<?> catalogueType = Class.forName("com.sahaj.ipd.application.MedicineCatalogue");
        Object bean = applicationContext.getBean(catalogueType);
        java.lang.reflect.Field loadedField = catalogueType.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        java.lang.reflect.Method catalogueMethod = catalogueType.getDeclaredMethod("catalogue");
        catalogueMethod.setAccessible(true);
        Object original = loadedField.get(bean);
        com.sahaj.ipd.domain.DrugCatalogue full = (com.sahaj.ipd.domain.DrugCatalogue) catalogueMethod.invoke(bean);
        List<com.sahaj.ipd.domain.Drug> without = new ArrayList<>();
        for (com.sahaj.ipd.domain.Drug d : full.drugs()) {
            if (!"hep".equals(d.key())) {
                without.add(d);
            }
        }
        assertThat(without).hasSize(full.drugs().size() - 1);
        java.lang.reflect.Constructor<?> fixedConstructor = catalogueType.getDeclaredConstructor(
                com.sahaj.ipd.domain.DrugCatalogue.class);
        fixedConstructor.setAccessible(true);
        Object damaged = loadedField.get(fixedConstructor.newInstance(new com.sahaj.ipd.domain.DrugCatalogue(without,
                full.interactions())));
        JsonNode board;
        JsonNode summary;
        JsonNode list;
        String logged;
        try {
            loadedField.set(bean, damaged);
            try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
                board = boardOf(w.nurse().auth());
                summary = summaryOf(w.nurse().auth());
                list = ok(HttpMethod.GET, ADMISSIONS, w.nurse().auth(), null, 200);
                logged = logs.allText();
            }
        } finally {
            loadedField.set(bean, original);
        }

        assertThat(roundKeys(board)).as("only the healthy order is on the board").hasSize(1);
        assertThat(board.get("medicineRound").get("rows").get(0).get("orderId").asText()).isEqualTo(pcm);
        assertThat(counts(board).get("dosesLate").asInt()).isOne();
        assertThat(summary.get("dosesLate").asInt()).isOne();
        assertThat(list.get("safety").get("counts").get("dosesLate").asInt()).isOne();
        assertThat(logged).contains("medicine-round order-skipped tenantId=" + w.clinic().tenantId() + " orderId=" + hep
                + " cause=DRUG_NOT_IN_CATALOGUE");
        assertThat(logged).as("no patient, bed, drug text or key in the log").doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile())
                .doesNotContain(w.uhid()).doesNotContain("Heparin").doesNotContain("Paracetamol");
        assertThat(logged.split("order-skipped", -1).length - 1).as("a warning for every answer that read the round")
                .isGreaterThanOrEqualTo(3);
        // put back: the catalogue is whole again and the damaged order shows
        assertThat(roundKeys(boardOf(w.nurse().auth()))).hasSize(2);
    }

    // ---------------- AC6: the cap ----------------

    @Test
    void twoHundredAndOneQualifyingSlotsGiveTwoHundredRowsTruncatedAndTheFullCountsAndToken() throws Exception {
        Ward base = ward();
        List<Ward> patients = new ArrayList<>();
        patients.add(base);
        for (int i = 0; i < 4; i++) {
            patients.add(anotherPatient(base));
        }
        // 67 tds orders: 5 admissions x up to 14 drugs; at 8:05 PM each tds order has three listed slots (8 AM and 2 PM late, 8 PM due)
        List<String> orders = new ArrayList<>();
        int need = 67;
        for (Ward p : patients) {
            int n = Math.min(14, need - orders.size());
            if (n > 0) {
                orders.addAll(bulkActiveOrders(p, n, "tds"));
            }
        }
        assertThat(orders).hasSize(201 / 3);
        clockAt(20, 5);
        JsonNode b = boardOf(base.nurse().auth());
        JsonNode round = b.get("medicineRound");
        assertThat(round.get("truncated").asBoolean()).isTrue();
        assertThat(round.get("rows")).hasSize(200);
        assertThat(counts(b).get("dosesLate").asInt()).isEqualTo(134);
        assertThat(counts(b).get("dosesDue").asInt()).isEqualTo(67);
        assertThat(summaryOf(base.nurse().auth()).get("dosesLate").asInt()).isEqualTo(134);
        assertThat(summaryOf(base.nurse().auth()).get("dosesDue").asInt()).isEqualTo(67);
        // the rows kept are the most overdue: every dropped slot is DUE (a LATE slot never loses its place to a DUE one)
        long dueInRows = 0;
        for (JsonNode r : round.get("rows")) {
            if ("DUE".equals(r.get("state").asText())) {
                dueInRows++;
            }
        }
        assertThat(dueInRows).as("200 rows hold all 134 late rows and 66 of the 67 due ones").isEqualTo(66);
        // the token covers all 201 slots, not only the rows
        List<String> lines = new ArrayList<>();
        for (String id : orders) {
            lines.add(doseLine(id, ist(8, 0), "LATE"));
            lines.add(doseLine(id, ist(14, 0), "LATE"));
            lines.add(doseLine(id, ist(20, 0), "DUE"));
        }
        assertThat(counts(b).get("changeToken").asText()).isEqualTo(tokenOfLines(lines));
        // the summary and the admitted list run the same function
        assertThat(ok(HttpMethod.GET, ADMISSIONS, base.nurse().auth(), null, 200).get("safety").get("counts").get("changeToken")
                .asText()).isEqualTo(tokenOfLines(lines));
        // and the MAR tab counts add up to the same total
        int sum = 0;
        for (Ward p : patients) {
            sum += mar(base.clinic().owner(), p.adm().id()).get("doseCount").asInt();
        }
        assertThat(sum).isEqualTo(201);
    }

    // ---------------- AC10: the tab number inputs ----------------

    @Test
    void theTabNumberInputsAreOpenPlusLateDosesWithDueDosesNotAddedAndThePharmacistWaitingForTheOwner() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");               // late at 10:05
        pendingOrder(w, "panto", "od");            // waiting for the pharmacist
        String dueSoon = activeOrder(w, "ns", "bd");   // 8 AM late, 8 PM later
        clockAt(10, 5);
        JsonNode b = boardOf(w.clinic().owner());
        assertThat(counts(b).get("open").asInt()).isZero();
        assertThat(counts(b).get("dosesLate").asInt()).isEqualTo(2);
        // a DUE-only moment adds nothing to the late figure
        clockAt(7, 30);
        JsonNode due = boardOf(w.clinic().owner());
        assertThat(counts(due).get("dosesLate").asInt()).isZero();
        assertThat(counts(due).get("dosesDue").asInt()).isEqualTo(2);
        assertThat(dueSoon).isNotBlank();
        // the owner's pharmacist figure is unchanged by the round
        assertThat(summary(w.clinic().owner()).get("waiting").asInt()).isOne();
    }
}
