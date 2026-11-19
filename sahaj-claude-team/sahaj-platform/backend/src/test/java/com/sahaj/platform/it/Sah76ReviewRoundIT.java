package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
        ageScanStops(order, 14);
        refused(doseCall(w.nurse().auth(), order, overrideGiven(w, ist(14, 0), 1, "NO_BARCODE", NOTE)), 409,
                "override-after-mismatch", AFTER_MISMATCH);
        ageScanStops(order, 2);
        JsonNode late = doseOk(w.nurse().auth(), order, overrideGiven(w, ist(14, 0), 1, "NO_BARCODE", NOTE));
        assertThat(late.get("dose").get("method").asText()).isEqualTo("OVERRIDE");
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).hasSize(3);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isEqualTo(3);
        // security L-3 (a): the check is repeated at the end, after the mismatch and every override audit (3 refusals, 3 gives)
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).as("no note and no medicine code in any audit row")
                .doesNotContain(NOTE).doesNotContain(code).doesNotContain(heparin).doesNotContain(codeOf("hep"));
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
        // security L-3 (b): the insert grant is limited to five columns; naming the tenant, the time or the id is refused (42501)
        String tenant = w.clinic().tenantId().toString();
        assertThat(stmtErr(w.clinic(), "insert into ipd.dose_scan_stop (tenant_id, admission_id, order_id, slot_at, check_kind, actor) "
                + "values (?::uuid, ?::uuid, ?::uuid, now(), 'WRISTBAND', ?::uuid)", tenant, w.adm().id(), order,
                w.nurse().userId().toString()).state()).as("tenant_id is not insertable").isEqualTo("42501");
        assertThat(stmtErr(w.clinic(), "insert into ipd.dose_scan_stop (stopped_at, admission_id, order_id, slot_at, check_kind, actor) "
                + "values (now() - interval '30 minutes', ?::uuid, ?::uuid, now(), 'WRISTBAND', ?::uuid)", w.adm().id(), order,
                w.nurse().userId().toString()).state()).as("stopped_at is not insertable").isEqualTo("42501");
        assertThat(stmtErr(w.clinic(), "insert into ipd.dose_scan_stop (id, admission_id, order_id, slot_at, check_kind, actor) "
                + "values (gen_random_uuid(), ?::uuid, ?::uuid, now(), 'WRISTBAND', ?::uuid)", w.adm().id(), order,
                w.nurse().userId().toString()).state()).as("id is not insertable").isEqualTo("42501");
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

    // ---------------- code review round 2 #4: the exact boundaries ----------------

    @Test
    void aStopThirtySecondsInsideTheWindowStillCountsAndFromFifteenMinutesOnItDoesNot() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "ceft", "q8", "MG", "IV");
        clockAt(22, 10);
        String code = codeOf("ceft");
        Instant s1 = ist(6, 0);
        Instant s2 = ist(14, 0);
        Instant s3 = ist(22, 0);
        // one wristband mismatch on each of three slots: three stop rows, stamped by the database at its real clock
        for (Instant s : List.of(s1, s2, s3)) {
            refused(doseCall(w.nurse().auth(), order, badBand(w, s, code)), 409, "wristband-mismatch", MISMATCH_TEXT);
        }
        assertThat(stops(w, order)).hasSize(3);

        // 14 min 30 s old (a 30 s margin, so a slow request cannot push it over the edge): inside the window
        setScanStopAge(order, s1, 870);
        refused(doseCall(w.nurse().auth(), order, overrideGiven(w, s1, 1, "NO_BARCODE", NOTE)), 409, "override-after-mismatch",
                AFTER_MISMATCH);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();

        // 15 min 00 s old: outside from 15:00 (the rule is "newer than 15 minutes"). The request runs a little after the age is set,
        // so the stop is at least 900 s old when read; whether the database compares with > or >= at exactly 900.000 s cannot be
        // told apart by a test and is not claimed here.
        setScanStopAge(order, s1, 900);
        assertThat(doseOk(w.nurse().auth(), order, overrideGiven(w, s1, 1, "NO_BARCODE", NOTE)).get("dose").get("method").asText())
                .isEqualTo("OVERRIDE");

        // 15 min 01 s old (another slot, its own stop row): outside as well
        setScanStopAge(order, s2, 901);
        assertThat(doseOk(w.nurse().auth(), order, overrideGiven(w, s2, 1, "NO_BARCODE", NOTE)).get("dose").get("method").asText())
                .isEqualTo("OVERRIDE");

        // the third slot was never aged: it is still refused (the ages set on the other slots did not leak)
        refused(doseCall(w.nurse().auth(), order, overrideGiven(w, s3, 1, "NO_BARCODE", NOTE)), 409, "override-after-mismatch",
                AFTER_MISMATCH);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isEqualTo(2);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).as("the 14:30 refusal and the third slot's").hasSize(2);
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).doesNotContain(NOTE).doesNotContain(code);
    }

    @Test
    void aPrnDoseIsRefusedOneSecondBeforeItsStartAndRecordedAtItsStart() throws Exception {
        Ward w = ward();
        String order = placeOrder(w.doctor().auth(), w.adm().id(), prnOrder("pcm", "TABLET", "ORAL", 60, 3));
        verifyNow(w, order);
        Instant start = ist(10, 0);
        backdate(w.clinic(), order, start, start.minusSeconds(7200), start.minusSeconds(3600));
        String code = codeOf("pcm");

        clockAt(start.minusSeconds(1));
        DSnap before = dsnap(w);
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, null, 1, code)), 409, "dose-not-due", CANNOT_NOW);
        refused(doseCall(w.nurse().auth(), order, notGivenBody(null, 1, "NIL_BY_MOUTH", null)), 409, "dose-not-due", CANNOT_NOW);
        assertThat(dsnap(w)).as("nothing is written, not even an audit row").isEqualTo(before);
        JsonNode locked = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(locked.get("lockReason").asText()).isEqualTo("NOT_STARTED");
        assertThat(locked.get("canGive").asBoolean()).isFalse();

        clockAt(start);
        JsonNode open = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(open.get("lockReason").isNull()).as("at the start the line is open").isTrue();
        assertThat(open.get("canGive").asBoolean()).isTrue();
        JsonNode given = doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
        assertThat(given.get("dose").get("method").asText()).isEqualTo("SCAN");
        assertThat(values(w.clinic(), "select to_char(slot_at at time zone 'Asia/Kolkata', 'HH24:MI') from ipd.dose_administration "
                + "where order_id = ?::uuid", order)).containsExactly("10:00");
    }

    @Test
    void aPrnDoseIsRecordedOneSecondBeforeItsEndAndRefusedAtItsEnd() throws Exception {
        Ward w = ward();
        Map<String, Object> body = prnOrder("pcm", "TABLET", "ORAL", 60, 3);
        body.put("durationDays", 1);
        String order = placeOrder(w.doctor().auth(), w.adm().id(), body);
        verifyNow(w, order);
        Instant start = istOn(day.minusDays(1), 10, 0);
        Instant end = istOn(day, 10, 0);
        backdate(w.clinic(), order, start, start.minusSeconds(7200), start.minusSeconds(3600));
        endAt(order, end);
        String code = codeOf("pcm");

        clockAt(end.minusSeconds(1));
        JsonNode open = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(open.get("lockReason").isNull()).as("one second before the end the line is open").isTrue();
        assertThat(open.get("canGive").asBoolean()).isTrue();
        assertThat(doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code)).get("dose").get("method").asText()).isEqualTo("SCAN");

        for (Instant at : List.of(end, end.plusSeconds(1))) {
            clockAt(at);
            DSnap before = dsnap(w);
            refused(doseCall(w.nurse().auth(), order, scanGiven(w, null, 1, code)), 409, "dose-not-due", CANNOT_NOW);
            refused(doseCall(w.nurse().auth(), order, notGivenBody(null, 1, "NIL_BY_MOUTH", null)), 409, "dose-not-due", CANNOT_NOW);
            assertThat(dsnap(w)).as("at or after the end nothing is written, not even an audit row").isEqualTo(before);
        }
        clockAt(end);
        JsonNode ended = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(ended.get("lockReason").asText()).isEqualTo("ENDED");
        assertThat(ended.get("canGive").asBoolean()).isFalse();
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    /** Sets the end of version 1 (table owner, committed, the version guard off inside the one transaction). */
    private void endAt(String order, Instant end) throws SQLException {
        asOwner(true, new String[] {"ipd.medicine_order_version", "ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order_version disable trigger trg_medicine_order_version_guard_update");
            DbAccess.execute(c, "update ipd.medicine_order_version set end_at = ? where order_id = ?::uuid and version_no = 1",
                    java.sql.Timestamp.from(end), order);
            DbAccess.execute(c, "alter table ipd.medicine_order_version enable trigger trg_medicine_order_version_guard_update");
            DbAccess.execute(c, "set constraints all immediate");
            return null;
        });
    }

    // ---------------- SELF_CHANGED and SELF on the pharmacist review rows ----------------

    @Test
    void aReviewRowOfAnOrderTheOwnerChangedOrPlacedCannotBeQueriedByThatOwnerButByThePharmacist() throws Exception {
        Ward w = ward();
        realClock();
        String adm = w.adm().id();
        // two STAT orders, each given before the pharmacist check: each leaves one review row
        String changed = placeOrder(w.doctor().auth(), adm, statOrder("pcm", "TABLET", "ORAL"));
        String own = placeOrder(w.clinic().owner(), adm, statOrder("ibu", "TABLET", "ORAL"));
        for (String o : List.of(changed, own)) {
            Instant slot = slotOf(mar(w.nurse().auth(), adm), o, 0);
            JsonNode a = doseOk(w.nurse().auth(), o, overrideGiven(w, slot, 1, "NO_BARCODE", NOTE));
            assertThat(a.get("dose").get("beforeVerification").asBoolean()).isTrue();
        }
        // the owner changes the first order (version 2 is the owner's text, the orderer is the doctor)
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("clientKey", newKey());
        change.put("orderVersion", 1);
        change.put("reason", "Dose raised");
        change.put("doseAmount", 2);
        change.put("doseUnit", "TABLET");
        change.put("routeKey", "ORAL");
        change.put("priority", "STAT");
        ok(HttpMethod.POST, Sah76Support.changeUrl(changed), w.clinic().owner(), js(change), 200);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_version where order_id = ?::uuid", changed)).isEqualTo(2);

        String reviews = IPD + "/pharmacist-reviews";
        JsonNode ownerList = ok(HttpMethod.GET, reviews, w.clinic().owner(), null, 200);
        JsonNode pharmacistList = ok(HttpMethod.GET, reviews, w.pharmacist().auth(), null, 200);
        JsonNode ownerChanged = reviewRow(ownerList, changed);
        JsonNode ownerOwn = reviewRow(ownerList, own);
        assertThat(ownerChanged).as("the review row of the changed order is listed").isNotNull();
        assertThat(ownerOwn).as("the review row of the owner's own order is listed").isNotNull();
        assertThat(ownerChanged.get("canQuery").asBoolean()).as("SELF_CHANGED: the owner wrote the current version").isFalse();
        assertThat(ownerOwn.get("canQuery").asBoolean()).as("SELF: the owner placed it").isFalse();
        assertThat(reviewRow(pharmacistList, changed).get("canQuery").asBoolean()).isTrue();
        assertThat(reviewRow(pharmacistList, own).get("canQuery").asBoolean()).isTrue();
    }

    private static JsonNode reviewRow(JsonNode page, String orderId) {
        for (JsonNode r : page.get("items")) {
            if (orderId.equals(r.get("orderId").asText())) {
                return r;
            }
        }
        return null;
    }

    // ---------------- read-only and not admitted: query, reply, acknowledge ----------------

    @Test
    void queryIsAllowedInReadOnlyReplyAndAcknowledgeAreRefusedAndAllThreeAreRefusedForADischargedPatient() throws Exception {
        Ward w = ward();
        String adm = w.adm().id();
        String toQuery = orderIdOf(place(w.doctor().auth(), adm, "pcm"));            // PENDING: the pharmacist will query it
        String queried = orderIdOf(place(w.doctor().auth(), adm, "ibu"));            // QUERIED: waits for the doctor's reply
        ok(HttpMethod.POST, Sah76Support.queryUrl(queried), w.pharmacist().auth(),
                js(Sah76Support.actBody(1, "question", "Is the dose right for this patient?")), 200);
        String active = activeScheduled(w, "ceft", "q8", "MG", "IV");                // ACTIVE with a NEW notice for the nurse
        String notice = null;
        for (JsonNode n : ok(HttpMethod.GET, Sah76Support.noticesUrl(adm), w.nurse().auth(), null, 200).get("items")) {
            if ("NEW".equals(n.get("kind").asText()) && n.get("acknowledgedAt").isNull()) {
                notice = n.get("id").asText();
            }
        }
        assertThat(notice).as("an open NEW notice of the active order " + active).isNotNull();
        String queryBody = js(Sah76Support.actBody(1, "question", "Check the dose and the route"));
        String replyBody = js(Sah76Support.actBody(1, "reply", "The dose is right, checked the chart"));

        // Read-only: the pharmacist's query is allowed (it is a check, not a change of the clinic's work); reply and acknowledge are not
        setIpdState(w.clinic(), "readonly");
        long audits = allAudits(w.clinic());
        refused(call(HttpMethod.POST, Sah76Support.replyUrl(queried), w.doctor().auth(), replyBody), 409, "ipd-readonly", READONLY_TEXT);
        refused(call(HttpMethod.POST, Sah76Support.noticeAckUrl(notice), w.nurse().auth(), null), 409, "ipd-readonly", READONLY_TEXT);
        assertThat(allAudits(w.clinic())).as("a refused reply or acknowledge writes nothing").isEqualTo(audits);
        assertThat(values(w.clinic(), "select status from ipd.medicine_order where id = ?::uuid", queried)).containsExactly("QUERIED");
        assertThat(count(w.clinic(), "select count(*) from ipd.order_notice where acknowledged_at is not null")).isZero();
        JsonNode q = ok(HttpMethod.POST, Sah76Support.queryUrl(toQuery), w.pharmacist().auth(), queryBody, 200);
        assertThat(q.get("result").get("outcome").asText()).isEqualTo("QUERIED");
        assertThat(values(w.clinic(), "select status from ipd.medicine_order where id = ?::uuid", toQuery)).containsExactly("QUERIED");
        assertThat(allAudits(w.clinic())).as("the query wrote its audit row").isEqualTo(audits + 1);
        setIpdState(w.clinic(), "on");

        // discharged: every one of the three is 409 not-admitted, nothing written
        discharge(w);
        audits = allAudits(w.clinic());
        long queries = count(w.clinic(), "select count(*) from ipd.medicine_order_query");
        refused(call(HttpMethod.POST, Sah76Support.queryUrl(active), w.pharmacist().auth(), queryBody), 409, "not-admitted", NOT_ADMITTED_TEXT);
        refused(call(HttpMethod.POST, Sah76Support.replyUrl(toQuery), w.doctor().auth(), replyBody), 409, "not-admitted", NOT_ADMITTED_TEXT);
        refused(call(HttpMethod.POST, Sah76Support.noticeAckUrl(notice), w.nurse().auth(), null), 409, "not-admitted", NOT_ADMITTED_TEXT);
        assertThat(allAudits(w.clinic())).isEqualTo(audits);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_query")).isEqualTo(queries);
        assertThat(count(w.clinic(), "select count(*) from ipd.order_notice where acknowledged_at is not null")).isZero();
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
