package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76 fix round part 2 (contract 0.4.3, ADR-027 A9, DEC-057) on the real {@code sahaj_test} database:
 * <ul>
 *   <li>a mismatch followed by a manual override within 15 minutes (409 override-after-mismatch, the scan-stop row and the
 *       IPD_DOSE_REFUSED audit stay committed; another nurse, another slot, or 15 minutes later is allowed);</li>
 *   <li>a PRN dose before its start or at or after its end (409 dose-not-due, no audit; PrnLine.lockReason);</li>
 *   <li>a replay of a client key by another user (409 client-key-reused, nothing written);</li>
 *   <li>an OWNER who changed an order cannot verify or query it (403 self-verification, SELF_CHANGED);</li>
 *   <li>a missing wristband with an override is a 400, never a 500; the override reasons and the ignored override;</li>
 *   <li>the scan-stop table: tenant isolation, append-only for the application.</li>
 * </ul>
 * Test plan: docs/test-plans/IPD-go-live.md (section "Fix round part 2", tests R-01 to R-12).
 */
@OnTestDatabase
@WithPinPepper
class Sah76ReviewRoundIT extends Sah76NursingSupport {

    private static final String AFTER_MISMATCH = "A scan for this dose did not match in the last 15 minutes. "
            + "Check the patient and the medicine, then scan the medicine code.";
    private static final String SELF_CHANGED_VERIFY = "The doctor who changed this order cannot verify it. Ask the pharmacist.";
    private static final String SELF_CHANGED_QUERY = "The doctor who changed this order cannot check it. Ask the pharmacist.";
    private static final String NOTE = "Scanner is down, read the label";

    // ---------------- helpers ----------------

    /** Rows of the scan-stop table of an order: kind, actor, IST slot time. */
    private List<List<String>> stops(Ward w, String order) throws SQLException {
        return table(w.clinic(), "select check_kind, actor::text, to_char(slot_at at time zone 'Asia/Kolkata', 'HH24:MI') "
                + "from ipd.dose_scan_stop where order_id = ?::uuid order by stopped_at, id", order);
    }

    /** Moves every scan-stop row of the order back in time (table owner, append-only trigger off inside the transaction). */
    private void ageStops(String order, long minutes) throws SQLException {
        asOwner(true, new String[] {"ipd.dose_scan_stop", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.dose_scan_stop disable trigger trg_dose_scan_stop_no_update_delete");
            DbAccess.execute(c, "update ipd.dose_scan_stop set stopped_at = stopped_at - (? * interval '1 minute') "
                    + "where order_id = ?::uuid", minutes, order);
            DbAccess.execute(c, "alter table ipd.dose_scan_stop enable trigger trg_dose_scan_stop_no_update_delete");
            return null;
        });
    }

    /** Runs one statement as the application with the clinic bound; returns its error (fails when it is accepted). */
    private SqlErr stmtErr(Clinic clinic, String sql, Object... params) throws SQLException {
        return inTenantErr(clinic, c -> {
            DbAccess.execute(c, sql, params);
            return null;
        });
    }

    private Map<String, Object> badBand(Ward w, Instant slot, String code) {
        Map<String, Object> body = scanGiven(w, slot, 1, code);
        body.put("wristband", "PT-00000");
        return body;
    }

    private static String refusalKey(String[] auditRow, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        return mapper.readTree(auditRow[3]).get("refusal").asText();
    }

    // ---------------- R-01 .. R-03 override after a mismatch ----------------

    @Test
    void anOverrideWithin15MinutesOfAMismatchIsRefusedAndTheStopRowAndTheAuditStayCommitted() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "ceft", "q8", "MG", "IV");
        clockAt(22, 10);
        String code = codeOf("ceft");
        String heparin = codeOf("hep");

        // a wristband mismatch at the 6 AM slot writes the stop row in its own committed step
        refused(doseCall(w.nurse().auth(), order, badBand(w, ist(6, 0), code)), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(stops(w, order)).containsExactly(List.of("WRISTBAND", w.nurse().userId().toString(), "06:00"));
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).isEmpty();

        // the same nurse, same order and slot, now tries the manual override: refused, nothing else written
        DSnap before = dsnap(w);
        JsonNode p = refused(doseCall(w.nurse().auth(), order, overrideGiven(w, ist(6, 0), 1, "SCANNER_DOWN", NOTE)), 409,
                "override-after-mismatch", AFTER_MISMATCH);
        assertThat(p.get("title").asText()).isEqualTo("Override not allowed");
        DSnap after = dsnap(w);
        assertThat(after.doses()).isEqualTo(before.doses());
        assertThat(after.events()).as("no clinical event for this refusal").isEqualTo(before.events());
        assertThat(after.audits()).as("one audit row").isEqualTo(before.audits() + 1);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();
        assertThat(stops(w, order)).as("the stop row is committed and the refusal adds none").hasSize(1);
        List<String[]> refusals = audit(w.clinic(), "IPD_DOSE_REFUSED");
        assertThat(refusals).hasSize(1);
        assertThat(refusalKey(refusals.get(0), objectMapper)).isEqualTo("override-after-mismatch");
        JsonNode audited = auditAfter(w.clinic(), "IPD_DOSE_REFUSED", 0);
        assertThat(audited.get("orderId").asText()).isEqualTo(order);
        assertThat(audited.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(audited.has("device")).isTrue();
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).doesNotContain(NOTE).doesNotContain(code);

        // another slot of the same order is not blocked (the rule is per slot)
        JsonNode otherSlot = doseOk(w.nurse().auth(), order, overrideGiven(w, ist(22, 0), 1, "SCANNER_DOWN", NOTE));
        assertThat(otherSlot.get("dose").get("method").asText()).isEqualTo("OVERRIDE");
        // another nurse is not blocked by this nurse's mismatch
        JsonNode otherNurse = doseOk(w.nurse2().auth(), order, overrideGiven(w, ist(6, 0), 1, "SCANNER_DOWN", NOTE));
        assertThat(otherNurse.get("dose").get("method").asText()).isEqualTo("OVERRIDE");
        assertThat(dosesOf(w.clinic(), order)).extracting(Dose::recordedBy)
                .containsExactlyInAnyOrder(w.nurse().userId().toString(), w.nurse2().userId().toString());

        // a medicine mismatch counts as well; 14 minutes later it still counts, after 16 minutes it does not
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, ist(14, 0), 1, heparin)), 409, "medicine-mismatch", null);
        assertThat(stops(w, order)).extracting(r -> r.get(0)).containsExactly("WRISTBAND", "MEDICINE");
        refused(doseCall(w.nurse().auth(), order, overrideGiven(w, ist(14, 0), 1, "NO_BARCODE", NOTE)), 409,
                "override-after-mismatch", AFTER_MISMATCH);
        ageStops(order, 14);
        refused(doseCall(w.nurse().auth(), order, overrideGiven(w, ist(14, 0), 1, "NO_BARCODE", NOTE)), 409,
                "override-after-mismatch", AFTER_MISMATCH);
        ageStops(order, 2);
        JsonNode late = doseOk(w.nurse().auth(), order, overrideGiven(w, ist(14, 0), 1, "NO_BARCODE", NOTE));
        assertThat(late.get("dose").get("method").asText()).isEqualTo("OVERRIDE");
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).hasSize(3);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isEqualTo(3);
    }

    @Test
    void forAPrnOrderAnyMismatchOfTheOrderBlocksTheOverrideButAMatchingScanIsNeverRefused() throws Exception {
        Ward w = ward();
        realClock();
        String order = activePrn(w, "pcm", "TABLET", "ORAL", 60, 3);
        String code = codeOf("pcm");
        refused(doseCall(w.nurse().auth(), order, badBand(w, null, code)), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(stops(w, order)).hasSize(1);
        assertThat(stops(w, order).get(0).get(0)).isEqualTo("WRISTBAND");

        refused(doseCall(w.nurse().auth(), order, overrideGiven(w, null, 1, "EMERGENCY", NOTE)), 409, "override-after-mismatch",
                AFTER_MISMATCH);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).hasSize(1);

        JsonNode scanned = doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
        assertThat(scanned.get("dose").get("method").asText()).as("a code that matches is never refused by this rule")
                .isEqualTo("SCAN");
    }

    @Test
    void aMismatchOfAnotherClinicOrAnotherOrderNeverBlocksAndTheStopRowIsInvisibleToAnotherClinic() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "ceft", "q8", "MG", "IV");
        String second = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        clockAt(22, 10);
        refused(doseCall(w.nurse().auth(), order, badBand(w, ist(6, 0), codeOf("ceft"))), 409, "wristband-mismatch", MISMATCH_TEXT);
        // the same nurse, the same time, another order of the patient: not blocked (the rule is per order)
        JsonNode other = doseOk(w.nurse().auth(), second, overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", NOTE));
        assertThat(other.get("dose").get("method").asText()).isEqualTo("OVERRIDE");

        Ward b = ward();
        assertThat(count(b.clinic(), "select count(*) from ipd.dose_scan_stop where order_id = ?::uuid", order))
                .as("another clinic sees no stop row").isZero();
        assertThat(count(w.clinic(), "select count(*) from ipd.dose_scan_stop where order_id = ?::uuid", order)).isOne();
        // the application can read and insert, never update, delete or truncate
        assertThat(stmtErr(w.clinic(), "update ipd.dose_scan_stop set check_kind = 'MEDICINE'").state()).isEqualTo("42501");
        assertThat(stmtErr(w.clinic(), "delete from ipd.dose_scan_stop").state()).isEqualTo("42501");
        assertThat(stmtErr(w.clinic(), "truncate ipd.dose_scan_stop").state()).isEqualTo("42501");
        assertThat(stmtErr(w.clinic(), "insert into ipd.dose_scan_stop (admission_id, order_id, slot_at, check_kind, actor) "
                + "values (?::uuid, ?::uuid, now(), 'OTHER', ?::uuid)", w.adm().id(), order, w.nurse().userId().toString()).state())
                .as("check_kind is WRISTBAND or MEDICINE").isEqualTo("23514");
        assertThat(stops(w, order)).hasSize(1);
    }

    // ---------------- R-04 .. R-06 PRN start and end ----------------

    @Test
    void aPrnDoseBeforeItsStartIsRefusedWithDoseNotDueAndNoAuditAndTheLineIsLockedNotStarted() throws Exception {
        Ward w = ward();
        realClock();
        Map<String, Object> body = prnOrder("pcm", "TABLET", "ORAL", 60, 3);
        body.put("startAt", Instant.now().plus(Duration.ofHours(3)).toString());
        String order = placeOrder(w.doctor().auth(), w.adm().id(), body);
        verifyNow(w, order);
        String code = codeOf("pcm");

        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, null, 1, code)), 409, "dose-not-due", CANNOT_NOW);
        refused(doseCall(w.nurse().auth(), order, notGivenBody(null, 1, "NIL_BY_MOUTH", null)), 409, "dose-not-due", CANNOT_NOW);
        assertThat(dsnap(w)).as("nothing is written, not even an audit row").isEqualTo(before);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).isEmpty();
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();

        JsonNode prn = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(prn.get("lockReason").asText()).isEqualTo("NOT_STARTED");
        assertThat(prn.get("canGive").asBoolean()).isFalse();
    }

    @Test
    void aPrnDoseAtOrAfterItsEndIsRefusedWithDoseNotDueAndNoAuditAndTheLineIsLockedEnded() throws Exception {
        Ward w = ward();
        realClock();
        Map<String, Object> body = prnOrder("pcm", "TABLET", "ORAL", 60, 3);
        body.put("durationDays", 1);
        String order = placeOrder(w.doctor().auth(), w.adm().id(), body);
        verifyNow(w, order);
        Instant start = istOn(day.minusDays(3), 6, 0);
        backdate(w.clinic(), order, start, start, istOn(day.minusDays(3), 7, 0));
        // the order ran for one day and ended the day before yesterday
        asOwner(true, new String[] {"ipd.medicine_order_version", "ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order_version disable trigger trg_medicine_order_version_guard_update");
            DbAccess.execute(c, "update ipd.medicine_order_version set end_at = ? where order_id = ?::uuid and version_no = 1",
                    java.sql.Timestamp.from(istOn(day.minusDays(2), 6, 0)), order);
            DbAccess.execute(c, "alter table ipd.medicine_order_version enable trigger trg_medicine_order_version_guard_update");
            DbAccess.execute(c, "set constraints all immediate");
            return null;
        });
        String code = codeOf("pcm");

        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, null, 1, code)), 409, "dose-not-due", CANNOT_NOW);
        refused(doseCall(w.nurse().auth(), order, notGivenBody(null, 1, "NIL_BY_MOUTH", null)), 409, "dose-not-due", CANNOT_NOW);
        assertThat(dsnap(w)).as("nothing is written, not even an audit row").isEqualTo(before);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).isEmpty();

        JsonNode prn = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(prn.get("lockReason").asText()).isEqualTo("ENDED");
        assertThat(prn.get("canGive").asBoolean()).isFalse();
    }

    @Test
    void aPrnDoseInsideItsWindowIsGivenAndTheLineHasNoLockReason() throws Exception {
        Ward w = ward();
        realClock();
        String order = activePrn(w, "pcm", "TABLET", "ORAL", 60, 3);
        JsonNode prn = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(prn.get("lockReason").isNull()).isTrue();
        assertThat(prn.get("canGive").asBoolean()).isTrue();
        doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, codeOf("pcm")));
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    // ---------------- R-07 replay by another user ----------------

    @Test
    void aReplayOfAClientKeyByAnotherNurseOrTheOwnerIsRefusedAndWritesNothingNotEvenARecordAccessRow() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        clockAt(8, 5);
        Map<String, Object> body = scanGiven(w, ist(8, 0), 1, codeOf("pcm"));
        doseOk(w.nurse().auth(), order, body);

        DSnap before = dsnap(w);
        refused(doseCall(w.nurse2().auth(), order, body), 409, "client-key-reused", DOSE_KEY_REUSED);
        refused(doseCall(w.clinic().owner(), order, body), 409, "client-key-reused", DOSE_KEY_REUSED);
        assertThat(dsnap(w)).as("no dose, event, audit or record-access row; the PIN state is untouched").isEqualTo(before);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();

        // the nurse who recorded it still gets the replay answer
        JsonNode again = ok(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(body), 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
    }

    // ---------------- R-08 .. R-09 the OWNER who changed an order ----------------

    @Test
    void anOwnerWhoChangedAnOrderCannotVerifyOrQueryItAndTheQueueRowSaysSelfChanged() throws Exception {
        Ward w = ward();
        String order = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm")); // placed by the doctor, version 1, PENDING
        ok(HttpMethod.POST, Sah76Support.changeUrl(order), w.clinic().owner(), js(Sah76Support.changeBody(1, "Dose raised", 2)), 200);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_version where order_id = ?::uuid", order)).isEqualTo(2);

        JsonNode ownerQueue = queue(w.clinic().owner(), "limit=100");
        JsonNode row = queueRow(ownerQueue, order);
        assertThat(row).isNotNull();
        assertThat(row.get("orderVersion").asInt()).isEqualTo(2);
        assertThat(row.get("verifyLockReason").asText()).isEqualTo("SELF_CHANGED");
        assertThat(row.get("canVerify").asBoolean()).isFalse();
        assertThat(row.get("canQuery").asBoolean()).isFalse();
        String token = row.get("check").get("checkToken").asText();

        MedSnap before = medSnap(w.clinic(), w.adm());
        String verifyBody = "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + token + "\",\"orderVersion\":2}";
        refused(call(HttpMethod.POST, verifyUrl(order), w.clinic().owner(), verifyBody), 403, "self-verification", SELF_CHANGED_VERIFY);
        refused(call(HttpMethod.POST, Sah76Support.queryUrl(order), w.clinic().owner(), js(Sah76Support.actBody(2, "question", "Is this dose right?"))), 403,
                "self-verification", SELF_CHANGED_QUERY);
        assertThat(medSnap(w.clinic(), w.adm()).orders()).as("the order is untouched").isEqualTo(before.orders());
        assertThat(state(w.clinic(), order).status()).isEqualTo("PENDING");
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_query where order_id = ?::uuid", order)).isZero();

        // the pharmacist sees it as verifiable, and verifies version 2
        JsonNode pharmacistRow = queueRow(queue(w.pharmacist().auth(), "limit=100"), order);
        assertThat(pharmacistRow.get("canVerify").asBoolean()).isTrue();
        assertThat(pharmacistRow.get("canQuery").asBoolean()).isTrue();
        assertThat(pharmacistRow.get("verifyLockReason").isNull()).isTrue();
        verifyNow(w, order);
        assertThat(state(w.clinic(), order).status()).isEqualTo("ACTIVE");
    }

    @Test
    void anOwnerWhoPlacedAndThenChangedHisOwnOrderGetsTheOrderedTextNotTheChangedText() throws Exception {
        Ward w = ward();
        String own = orderIdOf(place(w.clinic().owner(), w.adm().id(), "ibu"));
        ok(HttpMethod.POST, Sah76Support.changeUrl(own), w.clinic().owner(), js(changeBodyIbu(1)), 200);
        JsonNode row = queueRow(queue(w.clinic().owner(), "limit=100"), own);
        assertThat(row.get("canVerify").asBoolean()).isFalse();
        assertThat(row.get("canQuery").asBoolean()).isFalse();
        assertThat(row.get("verifyLockReason").asText()).isEqualTo("SELF");
        refused(call(HttpMethod.POST, Sah76Support.queryUrl(own), w.clinic().owner(), js(Sah76Support.actBody(2, "question", "Is this right?"))), 403,
                "self-verification", Sah76Support.SELF_QUERY_TEXT);
    }

    private static Map<String, Object> changeBodyIbu(int version) {
        Map<String, Object> m = Sah76Support.changeBody(version, "Dose raised", 2);
        return m;
    }

    // ---------------- R-10 .. R-12 the wristband and the override input ----------------

    @Test
    void aMissingWristbandWithAnOverrideIsA400AndNeverA500AndWritesNothing() throws Exception {
        Fx f = fixture();
        Ward w = f.w();
        DSnap before = dsnap(w);
        Map<String, Object> noBand = without(overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", NOTE), "wristband");
        invalid(doseCall(w.nurse().auth(), f.order(), noBand), "wristband", SCAN_FIRST);
        Map<String, Object> blank = overrideGiven(w, ist(8, 0), 1, "EMERGENCY", NOTE);
        blank.put("wristband", "   ");
        invalid(doseCall(w.nurse().auth(), f.order(), blank), "wristband", SCAN_FIRST);
        Map<String, Object> nul = overrideGiven(w, ist(8, 0), 1, "NO_BARCODE", NOTE);
        nul.put("wristband", null);
        invalid(doseCall(w.nurse().auth(), f.order(), nul), "wristband", SCAN_FIRST);
        // also with a code typed, and with nothing at all
        invalid(doseCall(w.nurse().auth(), f.order(), without(scanGiven(w, ist(8, 0), 1, codeOf("pcm")), "wristband")), "wristband",
                SCAN_FIRST);
        invalid(doseCall(w.nurse().auth(), f.order(), without(scanGiven(w, ist(8, 0), 1, null), "wristband")), "wristband", SCAN_FIRST);
        assertThat(dsnap(w)).as("a refused request writes nothing").isEqualTo(before);
        assertThat(stops(w, f.order())).isEmpty();
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
    }

    @Test
    void aMissingWristbandOnAHighAlertOverrideWithASecondNurseIsA400BeforeTheDatabaseBackstop() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "hep", "od", "IU", "SC");
        clockAt(8, 5);
        Map<String, Object> body = without(overrideGiven(w, ist(8, 0), 1, "EMERGENCY", NOTE), "wristband");
        body.put("secondNurseId", w.nurse2().membershipId().toString());
        body.put("secondNursePin", w.nurse2Pin());
        DSnap before = dsnap(w);
        freshPinAllowance(w.nurse());
        invalid(doseCall(w.nurse().auth(), order, body), "wristband", SCAN_FIRST);
        assertThat(dsnap(w)).as("the second nurse's PIN allowance is untouched too").isEqualTo(before);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();
    }

    @Test
    void theDamagedWristbandReasonIsRefusedOnInputAndAMatchingCodeIgnoresAnyOverrideSent() throws Exception {
        Fx f = fixture();
        Ward w = f.w();
        DSnap before = dsnap(w);
        invalid(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "WRISTBAND_DAMAGED", NOTE)),
                "override.reasonKey", CHOOSE_OVERRIDE);
        assertThat(dsnap(w)).isEqualTo(before);

        // a code that matches: the override (even an invalid one) is neither checked nor stored
        Map<String, Object> body = scanGiven(w, ist(8, 0), 1, codeOf("pcm"));
        body.put("override", Map.of("reasonKey", "BROKEN", "note", "x"));
        JsonNode a = doseOk(w.nurse().auth(), f.order(), body);
        assertThat(a.get("dose").get("method").asText()).isEqualTo("SCAN");
        assertThat(a.get("dose").get("overrideReason").isNull()).isTrue();
        Dose d = dosesOf(w.clinic(), f.order()).get(0);
        assertThat(d.scanMethod()).isEqualTo("SCAN");
        assertThat(d.overrideReason()).isEmpty();
        assertThat(d.overrideNote()).isEmpty();
        assertThat(d.medicineScan()).isEqualTo(codeOf("pcm"));
    }

    @Test
    void aTypedCodeThatDoesNotMatchStopsEvenWithAnOverrideAndTheNextOverrideIsRefused() throws Exception {
        Fx f = fixture();
        Ward w = f.w();
        Map<String, Object> body = overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", NOTE);
        body.put("medicineCode", codeOf("hep"));
        refused(doseCall(w.nurse().auth(), f.order(), body), 409, "medicine-mismatch", null);
        assertThat(stops(w, f.order())).hasSize(1);
        assertThat(stops(w, f.order()).get(0).get(0)).isEqualTo("MEDICINE");
        // the wrong code is not a way round the check: the override without a code is refused for 15 minutes
        refused(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", NOTE)), 409,
                "override-after-mismatch", AFTER_MISMATCH);
        // the right code is still accepted
        doseOk(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, codeOf("pcm")));
    }

    // ---------------- fixture ----------------

    private record Fx(Ward w, String order) {
    }

    /** A ward with one ACTIVE once-a-day order (8 AM); the clock reads 8:05 AM IST. */
    private Fx fixture() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        clockAt(8, 5);
        return new Fx(w, order);
    }
}
