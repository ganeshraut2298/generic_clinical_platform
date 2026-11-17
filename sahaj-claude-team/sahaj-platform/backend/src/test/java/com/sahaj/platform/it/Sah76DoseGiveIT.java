package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76-4 giving a dose under the 0.4.x contract ({@code POST /api/v1/ipd/medicine-orders/{id}/doses}) on the real
 * {@code sahaj_test} database: the medicine code step and its mismatch trace, the manual override and what it never skips,
 * NOT_GIVEN with VOMITED and OTHER, replay by client key, the order version, the slot rules, STAT before the pharmacist check,
 * PRN interval and daily maximum, who may give, another clinic, two nurses racing for one slot, and the device label of the
 * audit rows. Scheduled orders run on the fixed clock of {@link MarSupport}; STAT and PRN orders run on the real clock.
 * Test plan: docs/test-plans/SAH-76-dose-settings.md (tests D-01 to D-30).
 */
@OnTestDatabase
@WithPinPepper
class Sah76DoseGiveIT extends Sah76NursingSupport {

    private record Fx(Ward w, String order) {
    }

    /** A ward with one ACTIVE once-a-day order (8 AM) that is old enough for the fixed clock; the clock reads 8:05 AM IST. */
    private Fx pcm() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        clockAt(8, 5);
        return new Fx(w, order);
    }

    // ---------------- D-01 .. D-04 the medicine code ----------------

    @Test
    void aScanGivenDoseStoresTheVersionKindMethodAndTheNormalisedMedicineCode() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        String code = codeOf("pcm");
        Map<String, Object> body = scanGiven(w, ist(8, 0), 1, " " + code.toLowerCase() + " ");
        JsonNode a = doseOk(w.nurse().auth(), f.order(), body);

        JsonNode dose = a.get("dose");
        assertThat(dose.get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(dose.get("orderVersion").asInt()).isEqualTo(1);
        assertThat(dose.get("method").asText()).isEqualTo("SCAN");
        assertThat(dose.get("overrideReason").isNull()).isTrue();
        assertThat(dose.get("beforeVerification").asBoolean()).isFalse();
        List<Dose> rows = dosesOf(w.clinic(), f.order());
        assertThat(rows).hasSize(1);
        Dose d = rows.get(0);
        assertThat(d.attempt()).isEqualTo(1);
        assertThat(d.supersedes()).isEmpty();
        assertThat(d.orderVersion()).isEqualTo(1);
        assertThat(d.doseKind()).isEqualTo("SCHEDULED");
        assertThat(d.scanMethod()).isEqualTo("SCAN");
        assertThat(d.medicineScan()).as("stored upper case without spaces").isEqualTo(code);
        assertThat(d.identityScan()).isEqualTo(w.uhid());
        assertThat(d.undoSeconds()).as("undo_until is recorded_at plus the undo seconds (default 10)").isEqualTo(10.0);
        assertThat(d.beforeVerification()).isFalse();

        JsonNode after = auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 0);
        assertThat(after.get("method").asText()).isEqualTo("SCAN");
        assertThat(after.get("orderVersion").asInt()).isEqualTo(1);
        assertThat(after.get("doseKind").asText()).isEqualTo("SCHEDULED");
        assertThat(after.get("beforeVerification").asBoolean()).isFalse();
        assertThat(after.get("notePresent").asBoolean()).isFalse();
        assertThat(after.get("overrideReasonKey").isNull()).isTrue();
        assertThat(after.get("device").has("id")).isTrue();
        assertThat(after.get("device").has("agent")).isTrue();
        assertThat(eventLines(w.clinic(), w.adm().id()))
                .contains("give|Gave Tab. Paracetamol 650 mg (8 AM dose) · identity checked against UHID");
        assertThat(allEventText(w.clinic())).as("the scanned code is not written to the event log").doesNotContain(code);
        assertThat(allAuditTexts(w.clinic(), "IPD\\_DOSE\\_%").toString()).doesNotContain(code).doesNotContain(w.uhid());
    }

    @Test
    void theMedicineCodeIsRequiredWithoutAnOverrideAndNothingIsWritten() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        DSnap before = dsnap(w);
        invalid(doseCall(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, null)), "medicineCode", SCAN_MEDICINE);
        invalid(doseCall(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, "   ")), "medicineCode", SCAN_MEDICINE);
        Map<String, Object> noBand = without(scanGiven(w, ist(8, 0), 1, null), "wristband");
        invalid(doseCall(w.nurse().auth(), f.order(), noBand), "wristband", SCAN_FIRST);
        assertThat(dsnap(w)).as("the wristband is asked first and a refused request writes nothing").isEqualTo(before);
    }

    @Test
    void aCodeOfAnotherMedicineStopsTheDoseAndLeavesADenyEntryAndAnAuditRow() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        codeOf("pcm");
        String heparin = codeOf("hep");
        long deniesBefore = denyEvents(w.clinic(), w.adm().id());
        refused(doseCall(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, heparin)), 409, "medicine-mismatch",
                "Wrong medicine. This is Inj. Heparin 5,000 IU, not Tab. Paracetamol 650 mg.");

        assertThat(doseRowsOfOrder(w.clinic(), f.order())).as("no dose is stored").isZero();
        assertThat(denyEvents(w.clinic(), w.adm().id())).isEqualTo(deniesBefore + 1);
        assertThat(eventLines(w.clinic(), w.adm().id()))
                .contains("deny|Stopped at the medicine check: entered " + heparin + ", expected Tab. Paracetamol 650 mg");
        JsonNode after = auditAfter(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH", 0);
        assertThat(after.get("orderId").asText()).isEqualTo(f.order());
        assertThat(after.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(after.get("drugKey").asText()).isEqualTo("pcm");
        assertThat(after.get("device").has("agent")).isTrue();
        assertThat(giveEvents(w.clinic(), w.adm().id())).isZero();
        // the right code still works afterwards
        doseOk(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, codeOf("pcm")));
    }

    @Test
    void aCodeThatIsNotInTheDrugMasterIsAnItemNotOnThisOrderAndTheTypedTextIsNeverLogged() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        String typed = "ZZ99XX77AB";
        refused(doseCall(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, typed)), 409, "medicine-mismatch",
                "Wrong medicine. This is an item not on this order, not Tab. Paracetamol 650 mg.");
        assertThat(eventLines(w.clinic(), w.adm().id())).contains(
                "deny|Stopped at the medicine check: entered a code not in the medicine list, expected Tab. Paracetamol 650 mg");
        assertThat(allEventText(w.clinic())).doesNotContain(typed);
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).doesNotContain(typed);
        // a text over 100 characters never matches and never reaches the log
        String longText = "AB".repeat(60);
        refused(doseCall(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 1, longText)), 409, "medicine-mismatch", null);
        assertThat(allEventText(w.clinic())).doesNotContain(longText);
    }

    @Test
    void aWrongWristbandIsCheckedBeforeTheMedicineCode() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        Map<String, Object> body = scanGiven(w, ist(8, 0), 1, codeOf("hep"));
        body.put("wristband", "PT-00000");
        refused(doseCall(w.nurse().auth(), f.order(), body), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(audit(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH")).isEmpty();
        assertThat(audit(w.clinic(), "IPD_DOSE_WRISTBAND_MISMATCH")).hasSize(1);
    }

    // ---------------- D-05 .. D-08 the manual override ----------------

    @Test
    void anOverrideGivesWithoutAScanAndStoresTheReasonAndTheNoteButNeverLogsTheNote() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        String note = "Read the strip label against the chart";
        JsonNode a = doseOk(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "NO_BARCODE", note));

        JsonNode dose = a.get("dose");
        assertThat(dose.get("method").asText()).isEqualTo("OVERRIDE");
        assertThat(dose.get("overrideReason").get("key").asText()).isEqualTo("NO_BARCODE");
        assertThat(dose.get("overrideReason").get("label").asText()).isEqualTo("Medicine has no barcode");
        assertThat(a.toString()).as("the override note is never sent back in a list or a dose").doesNotContain("strip label");
        Dose d = dosesOf(w.clinic(), f.order()).get(0);
        assertThat(d.scanMethod()).isEqualTo("OVERRIDE");
        assertThat(d.overrideReason()).isEqualTo("NO_BARCODE");
        assertThat(d.overrideNote()).isEqualTo(note);
        assertThat(d.medicineScan()).isEmpty();
        assertThat(eventLines(w.clinic(), w.adm().id()))
                .contains("give|Gave Tab. Paracetamol 650 mg (8 AM dose) · identity checked against UHID · scan override: Medicine has no barcode");
        JsonNode after = auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 0);
        assertThat(after.get("method").asText()).isEqualTo("OVERRIDE");
        assertThat(after.get("overrideReasonKey").asText()).isEqualTo("NO_BARCODE");
        assertThat(after.get("notePresent").asBoolean()).isTrue();
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).doesNotContain("strip label");
        assertThat(allEventText(w.clinic())).doesNotContain("strip label");
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(mar.get("overrideReasons")).as("WRISTBAND_DAMAGED is no longer offered (contract 0.4.3)").hasSize(3);
        assertThat(mar.get("overrideReasons").get(0).get("key").asText()).isEqualTo("NO_BARCODE");
        assertThat(mar.get("overrideReasons").get(1).get("key").asText()).isEqualTo("SCANNER_DOWN");
        assertThat(mar.get("overrideReasons").get(2).get("key").asText()).isEqualTo("EMERGENCY");
        assertThat(mar.toString()).doesNotContain("strip label");
    }

    @Test
    void anOverrideWithABadReasonOrAShortNoteIsRefusedWithTheContractTexts() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        DSnap before = dsnap(w);
        invalid(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "BROKEN", "Checked the chart twice")),
                "override.reasonKey", CHOOSE_OVERRIDE);
        invalid(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, null, "Checked the chart twice")),
                "override.reasonKey", CHOOSE_OVERRIDE);
        invalid(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "EMERGENCY", "abc")), "override.note",
                OVERRIDE_NOTE);
        invalid(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "EMERGENCY", "  ab  ")), "override.note",
                OVERRIDE_NOTE);
        invalid(doseCall(w.nurse().auth(), f.order(), overrideGiven(w, ist(8, 0), 1, "EMERGENCY", null)), "override.note",
                OVERRIDE_NOTE);
        assertThat(dsnap(w)).isEqualTo(before);
    }

    @Test
    void anOverrideNeverSkipsTheWristbandMismatchTheMedicineMismatchOrTheSecondIdentifier() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        codeOf("pcm");
        // the statement is checked after the override-after-mismatch rule (contract 0.4.3 check order), so it is tried first,
        // before any mismatch of this order is on record
        Map<String, Object> unconfirmed = overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", "Scanner is down, read the label");
        unconfirmed.put("identityConfirmed", false);
        invalid(doseCall(w.nurse().auth(), f.order(), unconfirmed), "identityConfirmed", CONFIRM_TEXT);
        Map<String, Object> badBand = overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", "Scanner is down, read the band");
        badBand.put("wristband", "PT-00000");
        refused(doseCall(w.nurse().auth(), f.order(), badBand), 409, "wristband-mismatch", MISMATCH_TEXT);

        Map<String, Object> badCode = overrideGiven(w, ist(8, 0), 1, "SCANNER_DOWN", "Scanner is down, read the label");
        badCode.put("medicineCode", codeOf("hep"));
        refused(doseCall(w.nurse().auth(), f.order(), badCode), 409, "medicine-mismatch", null);

        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
    }

    @Test
    void anOverrideOnAHighAlertMedicineStillNeedsASecondNurseWhosePinIsChecked() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "hep", "od", "IU", "SC");
        clockAt(8, 5);
        Map<String, Object> noSecond = overrideGiven(w, ist(8, 0), 1, "EMERGENCY", "Emergency, read the label");
        JsonNode p = refused(doseCall(w.nurse().auth(), order, noSecond), 400, "validation", HIGH_ALERT_TEXT);
        assertThat(fieldNames(p)).contains("secondNurseId");

        Map<String, Object> self = overrideGiven(w, ist(8, 0), 1, "EMERGENCY", "Emergency, read the label");
        self.put("secondNurseId", w.nurse().membershipId().toString());
        self.put("secondNursePin", "4821");
        invalid(doseCall(w.nurse().auth(), order, self), "secondNurseId", DIFFERENT_NURSE);

        Map<String, Object> wrongPin = overrideGiven(w, ist(8, 0), 1, "EMERGENCY", "Emergency, read the label");
        wrongPin.put("secondNurseId", w.nurse2().membershipId().toString());
        wrongPin.put("secondNursePin", otherPin(w.nurse2(), w.nurse2Pin()));
        freshPinAllowance(w.nurse());
        refused(doseCall(w.nurse().auth(), order, wrongPin), 403, "wrong-pin", "That PIN is not right.");
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();

        Map<String, Object> good = overrideGiven(w, ist(8, 0), 1, "EMERGENCY", "Emergency, read the label");
        good.put("secondNurseId", w.nurse2().membershipId().toString());
        good.put("secondNursePin", w.nurse2Pin());
        freshPinAllowance(w.nurse());
        JsonNode a = doseOk(w.nurse().auth(), order, good);
        assertThat(a.get("dose").get("method").asText()).isEqualTo("OVERRIDE");
        assertThat(a.get("dose").get("secondCheckByName").asText()).isEqualTo(displayName(w.nurse2()));
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).doesNotContain(w.nurse2Pin());
    }

    // ---------------- D-09 .. D-10 not given ----------------

    @Test
    void notGivenVomitedIsStoredAsHeldAndOtherNeedsANoteThatIsStoredButNeverLogged() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "ceft", "q8", "MG", "IV");
        clockAt(22, 10);
        JsonNode vomited = doseOk(w.nurse().auth(), order, notGivenBody(ist(6, 0), 1, "VOMITED", null));
        assertThat(vomited.get("dose").get("outcome").asText()).isEqualTo("HELD");
        assertThat(vomited.get("dose").get("reason").get("key").asText()).isEqualTo("VOMITED");
        assertThat(vomited.get("dose").get("reason").get("label").asText()).isEqualTo("Vomited");
        assertThat(vomited.get("dose").get("method").isNull()).isTrue();

        DSnap before = dsnap(w);
        invalid(doseCall(w.nurse().auth(), order, notGivenBody(ist(14, 0), 1, "OTHER", null)), "note", WRITE_WHAT_HAPPENED);
        invalid(doseCall(w.nurse().auth(), order, notGivenBody(ist(14, 0), 1, "OTHER", "abc")), "note", WRITE_WHAT_HAPPENED);
        assertThat(dsnap(w)).isEqualTo(before);

        String note = "Patient asleep, retry later";
        JsonNode other = doseOk(w.nurse().auth(), order, notGivenBody(ist(14, 0), 1, "OTHER", note));
        assertThat(other.get("dose").get("reason").get("label").asText()).isEqualTo("Other");
        assertThat(other.toString()).doesNotContain("asleep");
        JsonNode refused = doseOk(w.nurse().auth(), order, notGivenBody(ist(22, 0), 1, "PATIENT_REFUSED", null));
        assertThat(refused.get("dose").get("outcome").asText()).isEqualTo("REFUSED");

        List<Dose> rows = dosesOf(w.clinic(), order);
        assertThat(rows).extracting(Dose::reason).containsExactly("VOMITED", "OTHER", "PATIENT_REFUSED");
        assertThat(rows).extracting(Dose::outcome).containsExactly("HELD", "HELD", "REFUSED");
        assertThat(rows.get(1).notGivenNote()).isEqualTo(note);
        assertThat(eventLines(w.clinic(), w.adm().id())).contains(
                "give|Did not give Inj. Ceftriaxone 1 g (6 AM dose) · Vomited",
                "give|Did not give Inj. Ceftriaxone 1 g (2 PM dose) · Other");
        assertThat(allEventText(w.clinic())).doesNotContain("asleep");
        List<String[]> audits = audit(w.clinic(), "IPD_DOSE_NOT_GIVEN");
        assertThat(audits).hasSize(3);
        assertThat(objectMapper.readTree(audits.get(1)[3]).get("notePresent").asBoolean()).isTrue();
        assertThat(objectMapper.readTree(audits.get(0)[3]).get("notePresent").asBoolean()).isFalse();
        assertThat(allAuditTexts(w.clinic(), "IPD\\_%").toString()).doesNotContain("asleep");
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(mar.get("notGivenReasons")).hasSize(7);
        assertThat(mar.get("notGivenReasons").get(5).get("key").asText()).isEqualTo("VOMITED");
        assertThat(mar.get("notGivenReasons").get(6).get("key").asText()).isEqualTo("OTHER");
        assertThat(mar.toString()).doesNotContain("asleep");
    }

    @Test
    void theNoteOfNotGivenIsIgnoredForAReasonThatNeedsNone() throws Exception {
        Fx f = pcm();
        doseOk(f.w().nurse().auth(), f.order(), notGivenBody(ist(8, 0), 1, "NIL_BY_MOUTH", "this text is ignored here"));
        assertThat(dosesOf(f.w().clinic(), f.order()).get(0).notGivenNote()).isEmpty();
    }

    // ---------------- D-11 replay ----------------

    @Test
    void aRepeatWithTheSameKeyAnswersTheFirstRecordAndAnyOtherValueIsRefused() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        Map<String, Object> body = scanGiven(w, ist(8, 0), 1, codeOf("pcm"));
        JsonNode first = doseOk(w.nurse().auth(), f.order(), body);
        DSnap before = dsnap(w);
        JsonNode again = ok(HttpMethod.POST, dosesUrl(f.order()), w.nurse().auth(), js(body), 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(again.get("dose").get("id").asText()).isEqualTo(first.get("dose").get("id").asText());
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(now.events()).isEqualTo(before.events());
        assertThat(now.audits()).isEqualTo(before.audits());
        assertThat(now.access()).as("a replay writes one record-access row").isEqualTo(before.access() + 1);

        Map<String, Object> other = notGivenBody(ist(8, 0), 1, "NIL_BY_MOUTH", null);
        other.put("clientKey", body.get("clientKey"));
        refused(doseCall(w.nurse().auth(), f.order(), other), 409, "client-key-reused", DOSE_KEY_REUSED);

        // the same key sent for another order of the clinic
        Adm secondAdm = admitWithAllergy(w.clinic(), "None known");
        String otherOrder = placeOrder(w.doctor().auth(), secondAdm.id(), sched("pcm", "od", "TABLET", "ORAL"));
        Map<String, Object> elsewhere = scanGiven(w, ist(8, 0), 1, codeOf("pcm"));
        elsewhere.put("clientKey", body.get("clientKey"));
        invalid(doseCall(w.nurse().auth(), otherOrder, elsewhere), "clientKey", RELOAD_TEXT);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
    }

    // ---------------- D-12 .. D-13 version and slot ----------------

    @Test
    void anOrderVersionThatIsNotTheCurrentOneIsRefusedAndTheRefusalIsAudited() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        refused(doseCall(w.nurse().auth(), f.order(), scanGiven(w, ist(8, 0), 2, codeOf("pcm"))), 409, "order-changed",
                ORDER_CHANGED);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
        List<String[]> audits = audit(w.clinic(), "IPD_DOSE_REFUSED");
        assertThat(audits).as("committed although the answer is 409").hasSize(1);
        JsonNode after = objectMapper.readTree(audits.get(0)[3]);
        assertThat(after.get("refusal").asText()).isEqualTo("order-changed");
        assertThat(after.get("orderId").asText()).isEqualTo(f.order());
        assertThat(after.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(after.get("device").has("agent")).isTrue();

        Map<String, Object> missing = without(scanGiven(w, ist(8, 0), 1, codeOf("pcm")), "orderVersion");
        JsonNode p = refused(doseCall(w.nurse().auth(), f.order(), missing), 400, "validation", RELOAD_TEXT);
        assertThat(fieldNames(p)).contains("orderVersion");
        refused(doseCall(w.nurse().auth(), f.order(), set(scanGiven(w, ist(8, 0), 1, codeOf("pcm")), "orderVersion", 0)), 400,
                "validation", RELOAD_TEXT);
    }

    @Test
    void theSlotRulesStillHoldWithTheOrderVersionAndTheWindowTextNamesTheMinutes() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "ceft", "q8", "MG", "IV");
        clockAt(8, 5);
        String code = codeOf("ceft");
        invalid(doseCall(w.nurse().auth(), order, scanGiven(w, istOn(day.minusDays(1), 6, 0), 1, code)), "slotAt", NOT_A_DOSE_TIME);
        invalid(doseCall(w.nurse().auth(), order, without(scanGiven(w, ist(6, 0), 1, code), "slotAt")), "slotAt", RELOAD_TEXT);
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, ist(14, 0), 1, code)), 409, "dose-not-due",
                "This dose is not due yet. It can be given from 60 min before its time.");
        doseOk(w.nurse().auth(), order, scanGiven(w, ist(6, 0), 1, code));
        refused(doseCall(w.nurse2().auth(), order, scanGiven(w, ist(6, 0), 1, code)), 409, "dose-recorded", DOSE_RECORDED);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    // ---------------- D-14 .. D-15 STAT and PRN lifecycle ----------------

    @Test
    void aWaitingScheduledOrderAndAWaitingPrnOrderAreRefusedWithAnAuditedTrace() throws Exception {
        Ward w = ward();
        String waiting = placeOrder(w.doctor().auth(), w.adm().id(), sched("pcm", "od", "TABLET", "ORAL"));
        refused(doseCall(w.nurse().auth(), waiting, scanGiven(w, ist(8, 0), 1, codeOf("pcm"))), 409, "order-not-verified",
                NOT_VERIFIED);
        String prn = placeOrder(w.doctor().auth(), w.adm().id(), prnOrder("panto", "MG", "IV", 60, 3));
        refused(doseCall(w.nurse().auth(), prn, scanGiven(w, null, 1, codeOf("panto"))), 409, "order-not-verified", NOT_VERIFIED);
        List<String[]> audits = audit(w.clinic(), "IPD_DOSE_REFUSED");
        assertThat(audits).hasSize(2);
        assertThat(objectMapper.readTree(audits.get(0)[3]).get("refusal").asText()).isEqualTo("order-not-verified");
        assertThat(doseRows(w.clinic(), w.adm().id())).isZero();
    }

    @Test
    void aStatDoseMayBeGivenOnceBeforeThePharmacistCheckAndOpensAReviewItem() throws Exception {
        Ward w = ward();
        realClock();
        String order = placeOrder(w.doctor().auth(), w.adm().id(), statOrder("panto", "MG", "IV"));
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        JsonNode slot = slot(mar, order, 0);
        assertThat(marOrder(mar, order).get("status").asText()).isEqualTo("PENDING");
        assertThat(slot.get("canGive").asBoolean()).as("a STAT slot of a PENDING order can be given").isTrue();
        assertThat(slot.get("beforeVerification").asBoolean()).isTrue();
        assertThat(slot.get("state").asText()).isIn("DUE", "LATE");

        JsonNode a = doseOk(w.nurse().auth(), order, scanGiven(w, Instant.parse(slot.get("slotAt").asText()), 1, codeOf("panto")));
        assertThat(a.get("dose").get("beforeVerification").asBoolean()).isTrue();
        Dose d = dosesOf(w.clinic(), order).get(0);
        assertThat(d.beforeVerification()).isTrue();
        assertThat(d.doseKind()).isEqualTo("ONCE");
        assertThat(count(w.clinic(), "select count(*) from ipd.pharmacist_review where dose_id = ?::uuid and closed_at is null",
                d.id())).as("one open review-after-dose item").isOne();
        assertThat(eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("give|Gave")).findFirst().orElseThrow())
                .endsWith(" · before pharmacist check");
        assertThat(auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 0).get("beforeVerification").asBoolean()).isTrue();

        // a second dose before the check is refused and the refusal is audited
        long refusals = audit(w.clinic(), "IPD_DOSE_REFUSED").size();
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, Instant.parse(slot.get("slotAt").asText()), 1, codeOf("panto"))),
                409, "order-not-verified", NOT_VERIFIED);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).hasSize((int) refusals + 1);
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
    }

    @Test
    void aPrnDoseHasNoSlotAndTheMinimumIntervalHoldsUntilTheDoseIsRecordedInError() throws Exception {
        Ward w = ward();
        realClock();
        String order = activePrn(w, "pcm", "TABLET", "ORAL", 60, 3);
        String code = codeOf("pcm");
        invalid(doseCall(w.nurse().auth(), order, scanGiven(w, Instant.now(), 1, code)), "slotAt", RELOAD_TEXT);

        JsonNode first = doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
        Instant recorded = Instant.parse(first.get("dose").get("recordedAt").asText());
        Dose d = dosesOf(w.clinic(), order).get(0);
        assertThat(d.doseKind()).isEqualTo("PRN");
        assertThat(d.attempt()).isEqualTo(1);

        refused(doseCall(w.nurse().auth(), order, scanGiven(w, null, 1, code)), 409, "prn-too-soon",
                "Too soon. Next dose allowed at " + hmm(recorded.plus(Duration.ofMinutes(60))) + ".");
        List<String[]> audits = audit(w.clinic(), "IPD_DOSE_REFUSED");
        assertThat(audits).hasSize(1);
        assertThat(objectMapper.readTree(audits.get(0)[3]).get("refusal").asText()).isEqualTo("prn-too-soon");
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();

        JsonNode prn = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(prn.get("lockReason").asText()).isEqualTo("TOO_SOON");
        assertThat(prn.get("canGive").asBoolean()).isFalse();
        assertThat(prn.get("givenLast24h").asInt()).isOne();
        assertThat(Duration.between(Instant.parse(prn.get("nextAllowedAt").asText()), recorded.plus(Duration.ofMinutes(60))).abs())
                .isLessThan(Duration.ofSeconds(2));

        errorOk(w.nurse().auth(), d.id(), "WRONG_PATIENT");
        JsonNode prnAfter = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(prnAfter.get("givenLast24h").asInt()).as("a dose recorded in error never counts").isZero();
        doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
    }

    @Test
    void thePrnDailyMaximumHoldsAndADoseRecordedInErrorDoesNotCountTowardsIt() throws Exception {
        Ward w = ward();
        realClock();
        String order = activePrn(w, "pcm", "TABLET", "ORAL", 15, 2);
        String code = codeOf("pcm");
        JsonNode d1 = doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
        String id1 = d1.get("dose").get("id").asText();
        ageDose(w.clinic(), id1, 20);
        JsonNode d2 = doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
        String id2 = d2.get("dose").get("id").asText();

        Instant first = instantOf(w.clinic(), "select recorded_at from ipd.dose_administration where id = ?::uuid", id1);
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, null, 1, code)), 409, "prn-max-reached",
                "Daily maximum reached. Next dose allowed at " + hmm(first.plus(Duration.ofHours(24))) + ".");
        assertThat(objectMapper.readTree(audit(w.clinic(), "IPD_DOSE_REFUSED").get(0)[3]).get("refusal").asText())
                .isEqualTo("prn-max-reached");
        JsonNode prn = marOrder(mar(w.nurse().auth(), w.adm().id()), order).get("prn");
        assertThat(prn.get("lockReason").asText()).isEqualTo("MAX_REACHED");
        assertThat(prn.get("givenLast24h").asInt()).isEqualTo(2);
        assertThat(prn.get("maxPer24h").asInt()).isEqualTo(2);

        errorOk(w.nurse().auth(), id2, "DUPLICATE");
        doseOk(w.nurse().auth(), order, scanGiven(w, null, 1, code));
        assertThat(doseRowsOfOrder(w.clinic(), order)).isEqualTo(3);
    }

    // ---------------- D-16 .. D-19 roles, tenant, race, device ----------------

    @Test
    void everyRoleThatIsNotANurseOrTheOwnerIsRefusedAndTheOwnerMayGive() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        Map<String, Object> body = scanGiven(w, ist(8, 0), 1, codeOf("pcm"));
        DSnap before = dsnap(w);
        for (String role : CORRECT_NON_ROLES) {
            refused(doseCall(staff(w.clinic(), role).auth(), f.order(), body), 403, "forbidden", ONLY_GIVERS);
        }
        assertThat(dsnap(w)).isEqualTo(before);
        JsonNode a = doseOk(w.clinic().owner(), f.order(), body);
        assertThat(a.get("dose").get("recordedByName").asText()).isEqualTo(ownerStaff(w.clinic()).name());
    }

    @Test
    void aNurseOfAnotherClinicCannotSeeOrGiveTheOrder() throws Exception {
        Fx f = pcm();
        Ward other = ward();
        DSnap before = dsnap(f.w());
        problem(doseCall(other.nurse().auth(), f.order(), scanGiven(f.w(), ist(8, 0), 1, codeOf("pcm"))), 404);
        assertThat(dsnap(f.w())).isEqualTo(before);
        assertThat(doseRowsOfOrder(other.clinic(), f.order())).isZero();
    }

    @Test
    void twoNursesGivingTheSameSlotAtTheSameMomentRecordItOnce() throws Exception {
        Fx f = pcm();
        Ward w = f.w();
        String code = codeOf("pcm");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                new Req(HttpMethod.POST, dosesUrl(f.order()), w.nurse().auth(), js(scanGiven(w, ist(8, 0), 1, code))),
                new Req(HttpMethod.POST, dosesUrl(f.order()), w.nurse2().auth(), js(scanGiven(w, ist(8, 0), 1, code))));
        assertThat(answers.get(0).status()).isEqualTo(201);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(answers.get(1).type(objectMapper)).isEqualTo("dose-recorded");
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        assertThat(dosesOf(w.clinic(), f.order()).get(0).recordedBy()).isEqualTo(w.nurse().userId().toString());
    }

    @Test
    void theAuditRowsCarryTheDeviceLabelAndAMalformedHeaderIsNeverRefused() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "ceft", "q8", "MG", "IV");
        clockAt(22, 10);
        String code = codeOf("ceft");
        String chrome = "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
        callWith(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(scanGiven(w, ist(6, 0), 1, code)), "qa76-device-0001", chrome)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        callWith(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(scanGiven(w, ist(14, 0), 1, code)), null, null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        callWith(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(scanGiven(w, ist(22, 0), 1, code)), "short", chrome)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        callWith(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(scanGiven(w, ist(22, 0), 9, code)), "qa76-device-0002", chrome)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());

        JsonNode first = auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 0).get("device");
        assertThat(first.get("id").asText()).isEqualTo("qa76-device-0001");
        assertThat(first.get("agent").asText()).isEqualTo("Chrome");
        JsonNode none = auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 1).get("device");
        assertThat(none.get("id").isNull()).isTrue();
        assertThat(none.get("agent").asText()).isEqualTo("Other");
        JsonNode malformed = auditAfter(w.clinic(), "IPD_DOSE_GIVEN", 2).get("device");
        assertThat(malformed.get("id").isNull()).as("an id of 5 characters is not a device id").isTrue();
        assertThat(malformed.get("agent").asText()).isEqualTo("Chrome");
        JsonNode refusal = auditAfter(w.clinic(), "IPD_DOSE_REFUSED", 0).get("device");
        assertThat(refusal.get("id").asText()).isEqualTo("qa76-device-0002");
        // the device label holds no IP address and no serial
        assertThat(first.size()).isEqualTo(2);
    }
}
