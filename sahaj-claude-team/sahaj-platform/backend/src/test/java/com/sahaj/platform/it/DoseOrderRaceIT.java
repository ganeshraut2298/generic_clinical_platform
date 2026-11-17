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
 * GO-LIVE QA-1 row "DoseOrderRaceIT" (docs/reviews/GO-LIVE-ipd-test-gaps.md F5): two requests that reach the same admission at the
 * same moment, in each of the two orders in which the database can serve them, on the real {@code sahaj_test} database. The order
 * is forced the way {@link SafetyConcurrencyIT} does it (a second connection holds the admission row, the requests are started one
 * after the other and each is seen waiting, then the row is released), so a test never passes by luck; a free-running pair
 * ({@link #parallel}) follows each forced pair and must end in one of the two forced results. The pairs: give against hold, give
 * against change, undo against give again, undo against recorded in error, give against a transfer out of a scoped nurse's ward.
 * Never a double dose, never a dose of a held, replaced or moved patient, never a server error. Test plan:
 * docs/test-plans/IPD-go-live.md.
 */
@OnTestDatabase
@WithPinPepper
class DoseOrderRaceIT extends IpdJourneySupport {

    private Req give(RequestPostProcessor auth, String order, Ward w, int version) throws Exception {
        return new Req(HttpMethod.POST, dosesUrl(order), auth, js(versioned(given(w, ist(8, 0)), version)));
    }

    private Req post(RequestPostProcessor auth, String url, String body) {
        return new Req(HttpMethod.POST, url, auth, body);
    }

    private static void assertNoServerError(List<Resp> answers) {
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isLessThan(500);
        }
    }

    private String type(Resp r) throws Exception {
        return r.type(objectMapper);
    }

    private long live(Clinic c, String order) throws Exception {
        return count(c, "select count(*) from ipd.dose_administration d where d.order_id = ?::uuid and d.outcome = 'GIVEN' "
                + "and not exists (select 1 from ipd.dose_correction x where x.dose_id = d.id)", order);
    }

    // ---------------- give against hold ----------------

    @Test
    void aGiveAndAHoldAtTheSameMomentNeverGiveAHeldOrderAndNeverLoseTheDoseThatWonTheLock() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        clockAt(8, 5);
        String hold = js(Sah76Support.actBody(1, "reason", "Nil by mouth " + MARK));

        // the give gets the row first: it is recorded, then the order goes on hold
        String first = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        List<Resp> r1 = ordered(c, adm, give(w.nurse().auth(), first, w, 1), post(w.doctor().auth(), Sah76Support.holdUrl(first), hold));
        assertThat(r1.get(0).status()).as(r1.get(0).body()).isEqualTo(201);
        assertThat(r1.get(1).status()).as(r1.get(1).body()).isEqualTo(200);
        assertThat(doseRowsOfOrder(c, first)).isOne();
        assertThat(values(c, "select status from ipd.medicine_order where id = ?::uuid", first)).containsExactly("ON_HOLD");

        // the hold gets the row first: the give is refused, nothing is recorded, the refusal is audited
        String second = activeScheduled(w, "panto", "bd", "MG", "IV");
        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        List<Resp> r2 = ordered(c, adm, post(w.doctor().auth(), Sah76Support.holdUrl(second), hold), give(w.nurse().auth(), second, w, 1));
        assertThat(r2.get(0).status()).as(r2.get(0).body()).isEqualTo(200);
        assertThat(r2.get(1).status()).as(r2.get(1).body()).isEqualTo(409);
        assertThat(type(r2.get(1))).isEqualTo("order-on-hold");
        assertThat(doseRowsOfOrder(c, second)).as("no dose of a held order").isZero();
        assertThat(audit(c, "IPD_DOSE_REFUSED")).hasSize((int) refused + 1);

        // free running: one of the two results above, never a dose on a held order and never an error
        String third = activeScheduled(w, "asp", "bd", "TABLET", "ORAL");
        List<Resp> r3 = parallel(give(w.nurse().auth(), third, w, 1), post(w.doctor().auth(), Sah76Support.holdUrl(third), hold));
        assertNoServerError(r3);
        assertThat(r3.get(1).status()).as(r3.get(1).body()).isEqualTo(200);
        if (r3.get(0).status() == 201) {
            assertThat(doseRowsOfOrder(c, third)).isOne();
        } else {
            assertThat(r3.get(0).status()).isEqualTo(409);
            assertThat(type(r3.get(0))).isEqualTo("order-on-hold");
            assertThat(doseRowsOfOrder(c, third)).isZero();
        }
        assertThat(values(c, "select status from ipd.medicine_order where id = ?::uuid", third)).containsExactly("ON_HOLD");
    }

    // ---------------- give against change ----------------

    @Test
    void aGiveAndAChangeAtTheSameMomentNeverRecordADoseOfAReplacedVersion() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        // no pharmacist check: a change is Active at once, so the old version is refused with order-changed
        ok(HttpMethod.PUT, SETTINGS, c.owner(), "{\"medVerificationRequired\":false,\"doseWindowBeforeMinutes\":60,"
                + "\"doseWindowAfterMinutes\":60,\"statWindowMinutes\":15,\"unackFlagMinutes\":30,\"unackFlagStatMinutes\":5,"
                + "\"undoSeconds\":10}", 200);
        String first = placeOrder(w.doctor().auth(), adm, sched("pcm", "bd", "TABLET", "ORAL"));
        String second = placeOrder(w.doctor().auth(), adm, sched("panto", "bd", "MG", "IV"));
        for (String id : new String[] {first, second}) {
            backdate(c, id, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0));
        }
        clockAt(8, 5);
        assertThat(values(c, "select status from ipd.medicine_order where id = ?::uuid", first)).containsExactly("ACTIVE");

        List<Resp> r1 = ordered(c, adm, give(w.nurse().auth(), first, w, 1),
                post(w.doctor().auth(), Sah76Support.changeUrl(first), js(changeBodyFor(1, "Dose raised " + MARK, 2, "TABLET", "ORAL", "bd"))));
        assertThat(r1.get(0).status()).as(r1.get(0).body()).isEqualTo(201);
        assertThat(r1.get(1).status()).as(r1.get(1).body()).isEqualTo(200);
        assertThat(dosesOf(c, first)).hasSize(1);
        assertThat(dosesOf(c, first).get(0).orderVersion()).as("the dose stays on the version it was given under").isEqualTo(1);

        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        List<Resp> r2 = ordered(c, adm,
                post(w.doctor().auth(), Sah76Support.changeUrl(second), js(changeBodyFor(1, "Dose raised " + MARK, 2, "MG", "IV", "bd"))),
                give(w.nurse().auth(), second, w, 1));
        assertThat(r2.get(0).status()).as(r2.get(0).body()).isEqualTo(200);
        assertThat(r2.get(1).status()).as(r2.get(1).body()).isEqualTo(409);
        assertThat(type(r2.get(1))).isEqualTo("order-changed");
        assertThat(doseRowsOfOrder(c, second)).as("no dose of the replaced version").isZero();
        assertThat(audit(c, "IPD_DOSE_REFUSED")).hasSize((int) refused + 1);
    }

    @Test
    void whenThePharmacistChecksChangesAGiveThatLosesTheLockIsRefusedAsNotVerified() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        String order = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        clockAt(8, 5);
        long refused = audit(c, "IPD_DOSE_REFUSED").size();
        List<Resp> r = ordered(c, adm,
                post(w.doctor().auth(), Sah76Support.changeUrl(order), js(changeBodyFor(1, "Dose raised " + MARK, 2, "TABLET", "ORAL", "bd"))),
                give(w.nurse().auth(), order, w, 1));
        assertThat(r.get(0).status()).isEqualTo(200);
        assertThat(r.get(1).status()).as(r.get(1).body()).isEqualTo(409);
        assertThat(type(r.get(1))).as("version 2 waits for the pharmacist").isEqualTo("order-not-verified");
        assertThat(doseRowsOfOrder(c, order)).isZero();
        assertThat(audit(c, "IPD_DOSE_REFUSED")).hasSize((int) refused + 1);
    }

    // ---------------- undo against give again, undo against recorded in error ----------------

    private String givenAndUndoable(Ward w, String order) throws Exception {
        String id = doseOk(w.nurse().auth(), order, given(w, ist(8, 0))).get("dose").get("id").asText();
        extendUndo(w.clinic(), id); // the database guard reads the real clock: the window stays open for an hour
        return id;
    }

    @Test
    void anUndoAndAGiveOfTheSameSlotAreServedOneAfterTheOtherAndNeverLeaveTwoLiveDoses() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        clockAt(8, 5);

        // a give of a recorded slot never reaches the lock: the live record is seen first (nothing to queue behind the undo)
        String a = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        String doseA = givenAndUndoable(w, a);
        refused(doseCall(w.nurse2().auth(), a, given(w, ist(8, 0))), 409, "dose-recorded", DOSE_RECORDED);
        assertThat(dosesOf(c, a)).hasSize(1);
        // the undo is committed: the slot is free again and the new give is attempt 2
        ok(HttpMethod.POST, undoUrl(doseA), w.nurse().auth(), corrBody(newKey(), null), 200);
        doseOk(w.nurse2().auth(), a, given(w, ist(8, 0)));
        assertThat(dosesOf(c, a)).hasSize(2);
        assertThat(dosesOf(c, a).get(1).attempt()).isEqualTo(2);
        assertThat(dosesOf(c, a).get(1).supersedes()).isEqualTo(doseA);
        assertThat(live(c, a)).isOne();

        // free running: the undo always succeeds; the give is refused when it saw the record, and is attempt 2 when it came after
        for (String[] drug : new String[][] {{"panto", "MG", "IV"}, {"asp", "TABLET", "ORAL"}, {"ceft", "MG", "IV"}}) {
            String order = activeScheduled(w, drug[0], "bd", drug[1], drug[2]);
            String dose = givenAndUndoable(w, order);
            List<Resp> r = parallel(post(w.nurse().auth(), undoUrl(dose), corrBody(newKey(), null)), give(w.nurse2().auth(), order, w, 1));
            assertNoServerError(r);
            assertThat(r.get(0).status()).as(r.get(0).body()).isEqualTo(200);
            assertThat(r.get(1).status()).isIn(201, 409);
            if (r.get(1).status() == 409) {
                assertThat(type(r.get(1))).isEqualTo("dose-recorded");
                assertThat(dosesOf(c, order)).hasSize(1);
                assertThat(live(c, order)).isZero();
            } else {
                assertThat(dosesOf(c, order)).hasSize(2);
                assertThat(live(c, order)).isOne();
            }
            assertThat(live(c, order)).as("never two live doses of one slot").isLessThanOrEqualTo(1);
        }
        assertThat(slot(mar(w.nurse().auth(), adm), a, 0).get("state").asText()).isEqualTo("GIVEN");
    }

    @Test
    void anUndoAndARecordedInErrorOfTheSameDoseAtTheSameMomentCorrectItOnce() throws Exception {
        Ward w = ward();
        Clinic c = w.clinic();
        String adm = w.adm().id();
        clockAt(8, 5);

        String a = activeScheduled(w, "pcm", "bd", "TABLET", "ORAL");
        String doseA = givenAndUndoable(w, a);
        List<Resp> r1 = ordered(c, adm, post(w.nurse().auth(), undoUrl(doseA), corrBody(newKey(), null)),
                post(w.nurse2().auth(), errorUrl(doseA), corrBody(newKey(), "WRONG_TIME")));
        assertThat(r1.get(0).status()).as(r1.get(0).body()).isEqualTo(200);
        assertThat(r1.get(1).status()).as(r1.get(1).body()).isEqualTo(409);
        assertThat(type(r1.get(1))).isEqualTo("dose-state");
        assertThat(r1.get(1).detail(objectMapper)).isEqualTo(CANNOT_MARK_ERROR);
        assertThat(correctionRows(c, doseA)).isOne();
        assertThat(values(c, "select kind from ipd.dose_correction where dose_id = ?::uuid", doseA)).containsExactly("UNDO");

        String b = activeScheduled(w, "panto", "bd", "MG", "IV");
        String doseB = givenAndUndoable(w, b);
        List<Resp> r2 = ordered(c, adm, post(w.nurse2().auth(), errorUrl(doseB), corrBody(newKey(), "WRONG_TIME")),
                post(w.nurse().auth(), undoUrl(doseB), corrBody(newKey(), null)));
        assertThat(r2.get(0).status()).as(r2.get(0).body()).isEqualTo(200);
        assertThat(r2.get(1).status()).as(r2.get(1).body()).isEqualTo(409);
        assertThat(type(r2.get(1))).isEqualTo("dose-state");
        assertThat(r2.get(1).detail(objectMapper)).isEqualTo(CANNOT_UNDO);
        assertThat(correctionRows(c, doseB)).isOne();
        assertThat(values(c, "select kind from ipd.dose_correction where dose_id = ?::uuid", doseB)).containsExactly("IN_ERROR");

        String d = activeScheduled(w, "asp", "bd", "TABLET", "ORAL");
        String doseD = givenAndUndoable(w, d);
        List<Resp> r3 = parallel(post(w.nurse().auth(), undoUrl(doseD), corrBody(newKey(), null)),
                post(w.nurse2().auth(), errorUrl(doseD), corrBody(newKey(), "DUPLICATE")));
        assertNoServerError(r3);
        List<Integer> codes = new ArrayList<>();
        r3.forEach(r -> codes.add(r.status()));
        assertThat(codes).as("one correction wins, the other is told").containsExactlyInAnyOrder(200, 409);
        assertThat(correctionRows(c, doseD)).isOne();
    }

    // ---------------- give against a transfer out of the nurse's ward ----------------

    @Test
    void aGiveAndATransferOutOfTheNursesWardAtTheSameMomentAreServedInTheOrderOfTheLockAndNeverLeakADose() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        setRules(clinic, false, "same");
        Staff doctor = staff(clinic, "DOCTOR");
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        String pin = goodPin(pharmacist);
        setPin(pharmacist, pin);
        String uhidA = values(clinic, "select uhid from patient.patient where id = ?::uuid", h.inA().patientId()).get(0);
        Ward w = new Ward(clinic, doctor, h.nurseA(), h.nurseB(), "0000", pharmacist, pin, h.inA(), uhidA);
        String first = activeOrder(w, "pcm", "bd");
        String second = activeOrder(w, "panto", "bd");
        String third = activeOrder(w, "asp", "bd");
        clockAt(8, 5);
        String adm = h.inA().id();

        // the give gets the row first: recorded under the nurse's own ward, then the patient is moved
        UUID bed1 = bedIn(clinic, h.wardB());
        List<Resp> r1 = ordered(clinic, adm, give(h.nurseA().auth(), first, w, 1),
                post(clinic.owner(), transferUrl(adm), moveBody(bed1, null)));
        assertThat(r1.get(0).status()).as(r1.get(0).body()).isEqualTo(201);
        assertThat(r1.get(1).status()).as(r1.get(1).body()).isEqualTo(200);
        assertThat(doseRowsOfOrder(clinic, first)).isOne();
        assertThat(bedOf(clinic, adm)).isEqualTo(bed1);

        // the patient is now in ward B: the nurse of ward A is refused, and the nurse of ward B can give
        String written = written(clinic);
        outside(doseCall(h.nurseA().auth(), second, given(w, ist(8, 0))));
        assertThat(written(clinic)).isEqualTo(written);

        // the transfer gets the row first (back to ward A): the nurse of ward B who queued behind it is refused, nothing written
        UUID bedBack = bedIn(clinic, h.wardA());
        long doses = doseRowsOfOrder(clinic, second);
        List<Resp> r2 = ordered(clinic, adm, post(clinic.owner(), transferUrl(adm), moveBody(bedBack, null)),
                give(h.nurseB().auth(), second, w, 1));
        assertThat(r2.get(0).status()).as(r2.get(0).body()).isEqualTo(200);
        assertThat(r2.get(1).status()).as(r2.get(1).body()).isEqualTo(403);
        assertThat(type(r2.get(1))).isEqualTo("outside-scope");
        assertThat(r2.get(1).detail(objectMapper)).isEqualTo(OUTSIDE);
        assertThat(doseRowsOfOrder(clinic, second)).as("no dose of a patient who left the nurse's ward").isEqualTo(doses);

        // free running: the nurse of ward A gives while the patient moves to ward B; either order is valid, never a server error
        UUID bed3 = bedIn(clinic, h.wardB());
        List<Resp> r3 = parallel(give(h.nurseA().auth(), third, w, 1), post(clinic.owner(), transferUrl(adm), moveBody(bed3, null)));
        assertNoServerError(r3);
        assertThat(r3.get(1).status()).as(r3.get(1).body()).isEqualTo(200);
        if (r3.get(0).status() == 201) {
            assertThat(doseRowsOfOrder(clinic, third)).isOne();
        } else {
            assertThat(r3.get(0).status()).isEqualTo(403);
            assertThat(type(r3.get(0))).isEqualTo("outside-scope");
            assertThat(doseRowsOfOrder(clinic, third)).isZero();
        }
        JsonNode mar = mar(h.nurseB().auth(), adm);
        assertThat(marOrder(mar, third)).isNotNull();
    }
}
