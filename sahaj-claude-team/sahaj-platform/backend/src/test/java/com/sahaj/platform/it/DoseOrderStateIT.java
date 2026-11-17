package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * GO-LIVE QA-1 row "DoseOrderStateIT" (docs/reviews/GO-LIVE-ipd-test-gaps.md section 4; F4, F5 of section 0) on the real
 * {@code sahaj_test} database, no fakes: what the dose and the MAR do with an order that is on hold, queried, changed, replaced or
 * stopped. A dose on a held order is 409 and writes nothing; a resumed order is given again; a queried order cannot be given until
 * the doctor has replied and the pharmacist has verified; the old order version is refused and the refusal is audited; the new
 * version cannot be given until it is verified; the MAR shows the states ON_HOLD, QUERIED (as WAITING under a QUERIED order),
 * REPLACED and STOPPED and the counters; a PRN line says why it cannot be given. Test plan: docs/test-plans/IPD-go-live.md.
 *
 * <p>The clock seam of {@link MarSupport} is fixed two days back; every time the database stamps with its own clock (hold, resume,
 * change, stop) is moved to that clock after the write ({@link IpdJourneySupport}), so no test depends on the time of day.
 */
@OnTestDatabase
@WithPinPepper
class DoseOrderStateIT extends IpdJourneySupport {

    @BeforeEach
    void theFixedClockSitsInThePast() {
        day = day.minusDays(2);
    }

    private static final String WAITING_FOR_REPLY = "This order is waiting for the doctor's reply.";

    // ---------------- hold and resume ----------------

    @Test
    void aDoseOnAHeldOrderIs409AndWritesNothingAndAfterTheResumeTheSlotIsGiven() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String order = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        clockAt(8, 5);
        audited(c, "IPD_MED_HELD", 1, () -> holdOk(w.doctor(), order, 1, "Nil by mouth for the scan " + MARK));
        stampHold(c, order, ist(7, 50), null);

        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        JsonNode block = marOrder(mar, order);
        assertThat(block.get("status").asText()).isEqualTo("ON_HOLD");
        assertThat(states(mar, order)).as("both slots fall inside the open hold").containsExactly("ON_HOLD", "ON_HOLD");
        assertThat(block.get("hold").get("heldByName").asText()).isEqualTo(w.doctor().name());
        assertThat(block.get("hold").get("reason").isNull()).as("the reason of a hold is not on the MAR").isTrue();
        assertThat(slot(mar, order, 0).get("stateBy").asText()).isEqualTo(w.doctor().name());
        assertThat(slot(mar, order, 0).get("canGive").asBoolean()).isFalse();
        assertThat(mar.get("counters").get("dueNow").asInt()).as("a held slot is never due").isZero();
        assertThat(mar.get("counters").get("late").asInt()).isZero();
        assertThat(mar.get("doseCount").asInt()).isZero();

        DSnap before = dsnap(w);
        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "order-on-hold", ORDER_ON_HOLD);
        DSnap after = dsnap(w);
        assertThat(after.doses()).as("a refused dose writes no dose").isEqualTo(before.doses());
        assertThat(after.events()).as("and no give entry").isEqualTo(before.events());
        assertThat(after.pins()).as("and checks no PIN").isEqualTo(before.pins());
        assertThat(giveEvents(c, w.adm().id())).isZero();
        assertThat(audit(c, "IPD_DOSE_REFUSED")).as("the refusal itself is audited once").hasSize((int) refused + 1);
        assertThat(after.audits()).isEqualTo(before.audits() + 1);
        assertThat(doseRowsOfOrder(c, order)).isZero();

        // resume (the hold is moved to end before the 8 AM dose), then the slot is due and can be given
        audited(c, "IPD_MED_RESUMED", 1, () -> resumeOk(w.doctor(), order, 1));
        stampHold(c, order, ist(7, 50), ist(7, 55));
        JsonNode resumed = mar(w.nurse().auth(), w.adm().id());
        assertThat(marOrder(resumed, order).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(marOrder(resumed, order).get("hold").isNull()).isTrue();
        assertThat(states(resumed, order)).containsExactly("DUE", "LATER");
        JsonNode given = doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        assertThat(states(given.get("mar"), order)).containsExactly("GIVEN", "LATER");
        assertThat(doseRowsOfOrder(c, order)).isOne();
    }

    @Test
    void aSlotThatFellInsideTheHoldStaysOnHoldAfterTheResumeAndIsNotCreatedAgain() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String order = activeScheduled(w, "panto", "bd", "MG", "IV");
        clockAt(8, 45);
        holdOk(w.doctor(), order, 1, "Hold for the scan " + MARK);
        resumeOk(w.doctor(), order, 1);
        stampHold(c, order, ist(7, 50), ist(8, 30)); // held from 7:50 to 8:30: the 8 AM slot fell inside it
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(mar, order)).containsExactly("ON_HOLD", "LATER");
        assertThat(slot(mar, order, 0).get("canGive").asBoolean()).isFalse();
        assertThat(mar.get("counters").get("dueNow").asInt()).isZero();
        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "dose-not-due", CANNOT_NOW);
        assertThat(doseRowsOfOrder(c, order)).isZero();
        assertThat(audit(c, "IPD_DOSE_REFUSED")).as("dose-not-due is not one of the audited refusals").hasSize((int) refused);
    }

    // ---------------- query ----------------

    @Test
    void aQueriedOrderCannotBeGivenUntilTheDoctorRepliesAndThePharmacistVerifies() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        String order = placeOrder(w.doctor().auth(), adm, sched("pcm", "bd", "TABLET", "ORAL"));
        backdate(c, order, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0));
        clockAt(8, 5);

        JsonNode asked = audited(c, "IPD_MED_QUERIED", 1, () -> queryOk(w.pharmacist(), order, 1, "Is one tablet right? " + MARK));
        assertThat(asked.get("result").get("outcome").asText()).isEqualTo("QUERIED");
        JsonNode mar = mar(w.nurse().auth(), adm);
        assertThat(marOrder(mar, order).get("status").asText()).isEqualTo("QUERIED");
        assertThat(states(mar, order)).as("a queried order waits: its slots are not due").containsExactly("WAITING", "WAITING");
        assertThat(mar.get("counters").get("dueNow").asInt()).isZero();
        assertThat(mar.get("doseCount").asInt()).isZero();
        JsonNode emrRow = sheetRow(emr(w.doctor().auth(), adm), order);
        assertThat(emrRow.get("canReply").asBoolean()).isTrue();
        assertThat(emrRow.get("status").asText()).isEqualTo("QUERIED");

        // not givable
        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "order-not-verified", NOT_VERIFIED);
        assertThat(doseRowsOfOrder(c, order)).isZero();
        assertThat(audit(c, "IPD_DOSE_REFUSED")).hasSize((int) refused + 1);
        // not verifiable: the PIN is right, the order waits for the doctor
        freshPinAllowance(w.pharmacist());
        JsonNode wait = problem(call(HttpMethod.POST, verifyUrl(order), w.pharmacist().auth(), "{\"pin\":\"" + w.pharmacistPin()
                + "\",\"checkToken\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"orderVersion\":1}"), 409);
        assertType(wait, "order-state");
        assertThat(wait.get("detail").asText()).isEqualTo(WAITING_FOR_REPLY);

        // the doctor replies: back to the pharmacist, still not givable
        audited(c, "IPD_MED_QUERY_REPLIED", 1, () -> replyOk(w.doctor(), order, 1, "Yes, one tablet is the dose " + MARK));
        assertThat(marOrder(mar(w.nurse().auth(), adm), order).get("status").asText()).isEqualTo("PENDING");
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "order-not-verified", NOT_VERIFIED);
        // the pharmacist verifies: now it can be given
        verifyVersionOk(w.pharmacist(), w.pharmacistPin(), order, 1);
        JsonNode ready = mar(w.nurse().auth(), adm);
        assertThat(marOrder(ready, order).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(states(ready, order)).containsExactly("DUE", "LATER");
        doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        assertThat(doseRowsOfOrder(c, order)).isOne();
    }

    // ---------------- versions ----------------

    @Test
    void theOldVersionIsRefusedAndAuditedAndTheNewVersionIsNotGivableUntilVerified() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        String order = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        clockAt(8, 5);
        audited(c, "IPD_MED_CHANGED", 1, () -> changeOk(w.doctor(), order, changeBodyFor(1, "Dose raised " + MARK, 2, "TABLET", "ORAL", "bd")));
        assertThat(values(c, "select status || '/' || current_version from ipd.medicine_order where id = ?::uuid", order)).containsExactly("PENDING/2");

        // the new version waits for the pharmacist: nothing can be given, whichever version the nurse names
        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        refused(doseCall(w.nurse().auth(), order, versioned(given(w, ist(8, 0)), 2)), 409, "order-not-verified", NOT_VERIFIED);
        refused(doseCall(w.nurse().auth(), order, given(w, ist(8, 0))), 409, "order-not-verified", NOT_VERIFIED);
        assertThat(audit(c, "IPD_DOSE_REFUSED")).as("each refusal is audited").hasSize((int) refused + 2);
        assertThat(doseRowsOfOrder(c, order)).isZero();

        // verified: the old version is refused (order-changed, audited), nothing written
        verifyVersionOk(w.pharmacist(), w.pharmacistPin(), order, 2);
        stampVersion(c, order, 1, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0), ist(8, 5));
        stampVersion(c, order, 2, ist(8, 5), ist(8, 5), ist(8, 5), null);
        clockAt(20, 5);
        DSnap before = dsnap(w);
        long refusedNow = audit(c, "IPD_DOSE_REFUSED").size();
        JsonNode stale = refused(doseCall(w.nurse().auth(), order, given(w, ist(20, 0))), 409, "order-changed", ORDER_CHANGED);
        assertThat(stale.toString()).doesNotContain(w.adm().name());
        assertThat(dsnap(w).doses()).isEqualTo(before.doses());
        assertThat(audit(c, "IPD_DOSE_REFUSED")).hasSize((int) refusedNow + 1);
        JsonNode refusalAudit = objectMapper.readTree(audit(c, "IPD_DOSE_REFUSED").get((int) refusedNow)[3]);
        assertThat(refusalAudit.get("orderId").asText()).isEqualTo(order);
        assertThat(refusalAudit.toString()).contains("order-changed");

        // the MAR: the 8 AM slot belongs to version 1 (late, not givable), the 8 PM slot to version 2 (due)
        JsonNode mar = mar(w.nurse().auth(), adm);
        assertThat(marOrder(mar, order).get("orderVersion").asInt()).isEqualTo(2);
        assertThat(marOrder(mar, order).get("changed").asBoolean()).isTrue();
        assertThat(slot(mar, order, 0).get("orderVersion").asInt()).isEqualTo(1);
        assertThat(slot(mar, order, 0).get("canGive").asBoolean()).as("a slot of an older version cannot be given").isFalse();
        assertThat(slot(mar, order, 1).get("orderVersion").asInt()).isEqualTo(2);
        assertThat(states(mar, order).get(1)).isEqualTo("DUE");
        // version 2 is given
        JsonNode given = doseOk(w.nurse().auth(), order, versioned(given(w, ist(20, 0)), 2));
        assertThat(given.get("dose").get("orderVersion").asInt()).isEqualTo(2);
        assertThat(dosesOf(c, order).get(0).orderVersion()).isEqualTo(2);
    }

    @Test
    void anOrderChangedToOnceADayShowsTheEveningSlotOfTheOldVersionAsReplaced() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String order = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        clockAt(8, 5);
        changeOk(w.doctor(), order, changeBodyFor(1, "Once a day is enough " + MARK, 1, "TABLET", "ORAL", "od"));
        stampVersion(c, order, 1, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0), ist(8, 5));
        stampVersion(c, order, 2, ist(8, 5), ist(8, 5), null, null);
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(mar, order)).as("8 AM was before the change, 8 PM is replaced").containsExactly("DUE", "REPLACED");
        JsonNode replaced = slot(mar, order, 1);
        assertThat(replaced.get("stateBy").asText()).isEqualTo(w.doctor().name());
        assertThat(Instant.parse(replaced.get("stateAt").asText())).isEqualTo(ist(8, 5));
        assertThat(replaced.get("canGive").asBoolean()).isFalse();
        assertThat(mar.get("counters").get("dueNow").asInt()).as("the order waits for the pharmacist: nothing is due").isZero();
    }

    // ---------------- stop and the counters ----------------

    @Test
    void theMarShowsStoppedAndCountsLateDueGivenAndHeldCorrectly() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        String o1 = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        String o2 = activeScheduled(w, "panto", "od", "MG", "IV");
        String o3 = activeScheduled(w, "asp", "od", "TABLET", "ORAL");
        String o4 = activeScheduled(w, "ceft", "bd", "MG", "IV");
        clockAt(8, 30);
        JsonNode mar = mar(w.nurse().auth(), adm);
        assertThat(mar.get("counters").get("dueNow").asInt()).isEqualTo(4);
        assertThat(mar.get("counters").get("late").asInt()).isZero();
        assertThat(mar.get("counters").get("given").asInt()).isZero();
        assertThat(mar.get("doseCount").asInt()).isEqualTo(4);
        doseOk(w.nurse().auth(), o1, given(w, ist(8, 0)));
        mar = mar(w.nurse().auth(), adm);
        assertThat(mar.get("counters").get("given").asInt()).isOne();
        assertThat(mar.get("counters").get("dueNow").asInt()).isEqualTo(3);

        // a held order is never due and never counted
        holdOk(w.doctor(), o2, 1, "Hold " + MARK);
        stampHold(c, o2, ist(7, 50), null);
        mar = mar(w.nurse().auth(), adm);
        assertThat(states(mar, o2)).containsExactly("ON_HOLD");
        assertThat(mar.get("counters").get("dueNow").asInt()).isEqualTo(2);
        assertThat(mar.get("doseCount").asInt()).isEqualTo(2);

        // two hours later what was due is late
        clockAt(10, 30);
        mar = mar(w.nurse().auth(), adm);
        assertThat(states(mar, o3)).containsExactly("LATE");
        assertThat(slot(mar, o3, 0).get("lateHours").asInt()).isEqualTo(2);
        assertThat(mar.get("counters").get("late").asInt()).isEqualTo(2);
        assertThat(mar.get("counters").get("dueNow").asInt()).isZero();
        assertThat(mar.get("counters").get("given").asInt()).isOne();
        assertThat(mar.get("doseCount").asInt()).as("late doses count on the tab").isEqualTo(2);

        // the doctor stops the evening order after its morning dose: the evening slot shows Stopped
        doseOk(w.nurse().auth(), o4, given(w, ist(8, 0)));
        audited(c, "IPD_MED_STOPPED", 1, () -> stopOk(w.doctor().auth(), o4));
        stampStop(c, o4, ist(10, 30));
        mar = mar(w.nurse().auth(), adm);
        JsonNode stopped = marOrder(mar, o4);
        assertThat(stopped).as("an order stopped today is listed").isNotNull();
        assertThat(stopped.get("status").asText()).isEqualTo("STOPPED");
        assertThat(states(mar, o4)).containsExactly("GIVEN", "STOPPED");
        assertThat(slot(mar, o4, 1).get("stateBy").asText()).isEqualTo(w.doctor().name());
        assertThat(Instant.parse(slot(mar, o4, 1).get("stateAt").asText())).isEqualTo(ist(10, 30));
        assertThat(mar.get("counters").get("given").asInt()).isEqualTo(2);
        assertThat(mar.get("counters").get("late").asInt()).isEqualTo(1);
        refused(doseCall(w.nurse().auth(), o4, given(w, ist(20, 0))), 409, "order-state", DOSE_STOPPED);
        assertThat(doseRowsOfOrder(c, o4)).isOne();
        // the doctor's EMR line agrees with the MAR for each of them
        for (String order : new String[] {o1, o3, o4}) {
            assertEmrEqualsMar(w, () -> mar(w.nurse().auth(), adm), order, "order " + order);
        }
    }

    // ---------------- PRN ----------------

    @Test
    void aPrnLineSaysWhyItCannotBeGivenWhileWaitingOnHoldAndStopped() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        realClock();
        String order = placeOrder(w.doctor().auth(), adm, prnOrder("pcm", "TABLET", "ORAL", 360, 4));
        JsonNode line = marOrder(mar(w.nurse().auth(), adm), order).get("prn");
        assertThat(line.get("lockReason").asText()).isEqualTo("NOT_VERIFIED");
        assertThat(line.get("canGive").asBoolean()).isFalse();
        verifyNow(w, order);
        line = marOrder(mar(w.nurse().auth(), adm), order).get("prn");
        assertThat(line.get("lockReason").isNull()).isTrue();
        assertThat(line.get("canGive").asBoolean()).isTrue();

        holdOk(w.doctor(), order, 1, "Hold " + MARK);
        line = marOrder(mar(w.nurse().auth(), adm), order).get("prn");
        assertThat(line.get("lockReason").asText()).isEqualTo("ON_HOLD");
        assertThat(line.get("canGive").asBoolean()).isFalse();
        Instant now = Instant.now();
        refused(doseCall(w.nurse().auth(), order, without(given(w, now), "slotAt")), 409, "order-on-hold", ORDER_ON_HOLD);
        assertThat(doseRowsOfOrder(c, order)).isZero();

        resumeOk(w.doctor(), order, 1);
        line = marOrder(mar(w.nurse().auth(), adm), order).get("prn");
        assertThat(line.get("lockReason").isNull()).isTrue();
        doseOk(w.nurse().auth(), order, without(given(w, now), "slotAt"));
        assertThat(marOrder(mar(w.nurse().auth(), adm), order).get("prn").get("lockReason").asText()).isEqualTo("TOO_SOON");

        stopOk(w.doctor().auth(), order);
        line = marOrder(mar(w.nurse().auth(), adm), order).get("prn");
        assertThat(line.get("lockReason").asText()).isEqualTo("STOPPED");
        assertThat(line.get("canGive").asBoolean()).isFalse();
        refused(doseCall(w.nurse().auth(), order, without(given(w, now), "slotAt")), 409, "order-state", DOSE_STOPPED);
        assertThat(doseRowsOfOrder(c, order)).isOne();
    }
}
