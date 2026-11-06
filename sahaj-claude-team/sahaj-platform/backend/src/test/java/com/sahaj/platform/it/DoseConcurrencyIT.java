package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-32 ADR-011 D6 on the real {@code sahaj_test} database: things that happen at the same moment. The orders are forced, not
 * hoped for (the technique of {@link MedicineConcurrencyIT}): a second connection holds the admission row, the requests are
 * started one after the other and each is seen waiting in pg_stat_activity, then the lock is released, so the first in the
 * queue runs first. Two nurses give the same slot with different keys, the same key twice, a dose against a Stop in both orders,
 * a dose against a discharge in both orders, a high-alert dose against a Stop (the PIN is checked before the lock), a wristband
 * stop that must stay committed beside a successful dose, and free-for-alls that must give exactly one record and no server
 * error. The database refusal on the insert is in {@link DoseGiveIT} and the table itself in {@link DoseTableIT}. Test plan:
 * docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class DoseConcurrencyIT extends MarSupport {

    private Req doseReq(RequestPostProcessor auth, String order, Map<String, Object> body) throws Exception {
        return new Req(HttpMethod.POST, dosesUrl(order), auth, js(body));
    }

    private static int[] statuses(List<Resp> answers) {
        return answers.stream().mapToInt(Resp::status).toArray();
    }

    private String type(Resp r) throws Exception {
        return r.type(objectMapper);
    }

    private record Fix(Ward w, String order) {
    }

    private Fix fix(String drug, String frequency) throws Exception {
        Ward w = ward();
        String order = activeOrder(w, drug, frequency);
        clockAt(8, 5);
        return new Fix(w, order);
    }

    // ---------------- two nurses, the same slot ----------------

    @Test
    void twoNursesGiveTheSameSlotWithDifferentKeysOneIs201AndTheOtherIs409DoseRecordedAndOnlyOneRecordIsWritten() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        DSnap before = dsnap(w);
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                doseReq(w.nurse().auth(), f.order(), given(w, ist(8, 0))),
                doseReq(w.nurse2().auth(), f.order(), given(w, ist(8, 0))));
        assertThat(statuses(answers)).containsExactly(201, 409);
        assertThat(type(answers.get(1))).isEqualTo("dose-recorded");
        assertThat(answers.get(1).detail(objectMapper)).isEqualTo(DOSE_RECORDED);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        assertThat(doseDb(w.clinic(), f.order(), ist(8, 0)).recordedBy()).as("the first in the queue won").isEqualTo(w.nurse().userId().toString());
        DSnap now = dsnap(w);
        assertThat(now.events()).isEqualTo(before.events() + 1);
        assertThat(now.audits()).isEqualTo(before.audits() + 1);
        assertThat(now.access()).isEqualTo(before.access());
        assertThat(giveEvents(w.clinic(), w.adm().id())).isOne();
    }

    @Test
    void aGivenAndANotGivenOfTheSameSlotAtTheSameMomentGiveOneRecordInEitherOrder() throws Exception {
        for (boolean givenFirst : new boolean[] {true, false}) {
            Fix f = fix("pcm", "od");
            Ward w = f.w();
            Req give = doseReq(w.nurse().auth(), f.order(), given(w, ist(8, 0)));
            Req notGiven = doseReq(w.nurse2().auth(), f.order(), notGiven(ist(8, 0), "NIL_BY_MOUTH"));
            List<Resp> answers = givenFirst ? ordered(w.clinic(), w.adm().id(), give, notGiven)
                    : ordered(w.clinic(), w.adm().id(), notGiven, give);
            assertThat(statuses(answers)).containsExactly(201, 409);
            assertThat(type(answers.get(1))).isEqualTo("dose-recorded");
            assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
            assertThat(doseDb(w.clinic(), f.order(), ist(8, 0)).outcome()).isEqualTo(givenFirst ? "GIVEN" : "HELD");
        }
    }

    @Test
    void aDoubleTapWithTheSameKeyIs201ThenA200ReplayWithOnlyOneRecordAndOneAccessRow() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        Map<String, Object> body = given(w, ist(8, 0));
        DSnap before = dsnap(w);
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), doseReq(w.nurse().auth(), f.order(), body),
                doseReq(w.nurse().auth(), f.order(), body));
        assertThat(statuses(answers)).containsExactly(201, 200);
        JsonNode first = answers.get(0).json(objectMapper);
        JsonNode second = answers.get(1).json(objectMapper);
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(second.get("dose").get("id").asText()).isEqualTo(first.get("dose").get("id").asText());
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        DSnap now = dsnap(w);
        assertThat(now.events()).isEqualTo(before.events() + 1);
        assertThat(now.audits()).isEqualTo(before.audits() + 1);
        assertThat(now.access()).as("the replay wrote one access row").isEqualTo(before.access() + 1);
    }

    @Test
    void sixRequestsForTheSameSlotStartedTogetherGiveExactlyOneRecordAndNoServerError() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        List<Req> reqs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RequestPostProcessor who = i % 3 == 0 ? w.nurse().auth() : i % 3 == 1 ? w.nurse2().auth() : w.clinic().owner();
            reqs.add(doseReq(who, f.order(), i % 2 == 0 ? given(w, ist(8, 0)) : notGiven(ist(8, 0), "HELD_DOCTOR")));
        }
        List<Resp> answers = parallel(reqs.toArray(new Req[0]));
        int created = 0;
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isLessThan(500);
            if (r.status() == 201) {
                created++;
            } else {
                assertThat(r.status()).isEqualTo(409);
                assertThat(type(r)).isEqualTo("dose-recorded");
            }
        }
        assertThat(created).isOne();
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        assertThat(giveEvents(w.clinic(), w.adm().id())).isOne();
        assertThat(doseAudits(w.clinic())).isOne();
    }

    @Test
    void fourTapsOfTheSameKeyStartedTogetherGiveOneCreatedAndThreeReplays() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        Map<String, Object> body = given(w, ist(8, 0));
        List<Req> reqs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            reqs.add(doseReq(w.nurse().auth(), f.order(), body));
        }
        DSnap before = dsnap(w);
        List<Resp> answers = parallel(reqs.toArray(new Req[0]));
        int created = 0;
        int replays = 0;
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isIn(200, 201);
            if (r.status() == 201) {
                created++;
            } else {
                assertThat(r.json(objectMapper).get("replayed").asBoolean()).isTrue();
                replays++;
            }
        }
        assertThat(created).isOne();
        assertThat(replays).isEqualTo(3);
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses() + 1);
        assertThat(now.access()).isEqualTo(before.access() + 3);
        assertThat(now.audits()).isEqualTo(before.audits() + 1);
    }

    @Test
    void differentSlotsOfOneOrderAndSlotsOfDifferentOrdersAtTheSameMomentAreAllRecorded() throws Exception {
        Ward w = ward();
        String tds = activeOrder(w, "ondan", "tds");
        String pcm = activeOrder(w, "pcm", "od");
        clockAt(13, 30);
        List<Resp> answers = parallel(
                doseReq(w.nurse().auth(), tds, given(w, ist(8, 0))),
                doseReq(w.nurse2().auth(), tds, given(w, ist(14, 0))),
                doseReq(w.clinic().owner(), pcm, given(w, ist(8, 0))));
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isEqualTo(201);
        }
        assertThat(doseRows(w.clinic(), w.adm().id())).isEqualTo(3);
    }

    // ---------------- give against Stop ----------------

    @Test
    void aGiveThatRunsBeforeAStopIsRecordedAndTheStopStillSucceedsAndTheDoseStays() throws Exception {
        Fix f = fix("pcm", "bd");
        Ward w = f.w();
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), doseReq(w.nurse().auth(), f.order(), given(w, ist(8, 0))),
                stopReq(w.doctor().auth(), f.order()));
        assertThat(statuses(answers)).containsExactly(201, 200);
        assertThat(state(w.clinic(), f.order()).status()).isEqualTo("STOPPED");
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        JsonNode last = sheetRow(emr(w.doctor().auth(), w.adm().id()), f.order()).get("lastDose");
        assertThat(last.get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(marOrder(mar(w.nurse().auth(), w.adm().id()), f.order())).as("a Stopped order leaves the MAR").isNull();
    }

    @Test
    void aStopThatRunsBeforeAGiveMakesTheGive409OrderStateAndNothingIsWrittenForTheGive() throws Exception {
        Fix f = fix("pcm", "bd");
        Ward w = f.w();
        DSnap before = dsnap(w);
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), stopReq(w.doctor().auth(), f.order()),
                doseReq(w.nurse().auth(), f.order(), given(w, ist(8, 0))));
        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(type(answers.get(1))).isEqualTo("order-state");
        assertThat(answers.get(1).detail(objectMapper)).isEqualTo(DOSE_STOPPED);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(giveEvents(w.clinic(), w.adm().id())).isZero();
        assertThat(doseAudits(w.clinic())).isZero();
    }

    @Test
    void aNotGivenRecordAgainstAStopFollowsTheSameRule() throws Exception {
        Fix f = fix("pcm", "bd");
        Ward w = f.w();
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), stopReq(w.doctor().auth(), f.order()),
                doseReq(w.nurse().auth(), f.order(), notGiven(ist(8, 0), "NIL_BY_MOUTH")));
        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(type(answers.get(1))).isEqualTo("order-state");
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
    }

    @Test
    void aHighAlertGiveAfterAStopIs409OrderStateAlthoughItsPinWasCheckedFirstAndTheRightPinCostsNothing() throws Exception {
        Fix f = fix("hep", "bd");
        Ward w = f.w();
        PinRow pin = pinRow(w.clinic(), w.nurse2().membershipId());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), stopReq(w.doctor().auth(), f.order()),
                doseReq(w.nurse().auth(), f.order(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())));
        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(type(answers.get(1))).isEqualTo("order-state");
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId())).isEqualTo(pin);
        assertThat(colleagueAudits(w.clinic())).isZero();
    }

    @Test
    void aHighAlertGiveBeforeAStopIsRecordedWithItsSecondCheckAndTheStopSucceeds() throws Exception {
        Fix f = fix("hep", "bd");
        Ward w = f.w();
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                doseReq(w.nurse().auth(), f.order(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())),
                stopReq(w.doctor().auth(), f.order()));
        assertThat(statuses(answers)).containsExactly(201, 200);
        assertThat(doseDb(w.clinic(), f.order(), ist(8, 0)).secondCheckBy()).isEqualTo(w.nurse2().userId().toString());
    }

    // ---------------- give against discharge ----------------

    @Test
    void aGiveThatRunsBeforeADischargeIsRecordedAndTheDischargeStillSucceeds() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        Req discharge = dischargeReq(w.clinic(), w.adm());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), doseReq(w.nurse().auth(), f.order(), given(w, ist(8, 0))), discharge);
        assertThat(statuses(answers)).containsExactly(201, 200);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isOne();
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", w.adm().id()).get(0)).isEqualTo("DISCHARGED");
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("orders").get(0).get("slots").get(0).get("state").asText()).isEqualTo("GIVEN");
    }

    @Test
    void aDischargeThatRunsBeforeAGiveMakesTheGive409NotAdmittedAndNothingIsWritten() throws Exception {
        Fix f = fix("pcm", "od");
        Ward w = f.w();
        Req discharge = dischargeReq(w.clinic(), w.adm());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge, doseReq(w.nurse().auth(), f.order(), given(w, ist(8, 0))));
        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1))).isEqualTo("not-admitted");
        assertThat(answers.get(1).detail(objectMapper)).isEqualTo(DOSE_NOT_ADMITTED);
        assertThat(doseRowsOfOrder(w.clinic(), f.order())).isZero();
        assertThat(giveEvents(w.clinic(), w.adm().id())).isZero();
        assertThat(doseAudits(w.clinic())).isZero();
    }

    @Test
    void aHighAlertGiveAfterADischargeIs409NotAdmittedAndTheRightPinCostsNothing() throws Exception {
        Fix f = fix("ins", "od");
        Ward w = f.w();
        Req discharge = dischargeReq(w.clinic(), w.adm());
        PinRow pin = pinRow(w.clinic(), w.nurse2().membershipId());
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), discharge,
                doseReq(w.nurse().auth(), f.order(), givenHighAlert(w, ist(8, 0), w.nurse2(), w.nurse2Pin())));
        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(type(answers.get(1))).isEqualTo("not-admitted");
        assertThat(pinRow(w.clinic(), w.nurse2().membershipId())).isEqualTo(pin);
    }

    // ---------------- the stop at the wristband stays committed ----------------

    @Test
    void aWristbandStopCommitsItsEntryAndAuditBesideADoseThatSucceedsAtTheSameMoment() throws Exception {
        Ward w = ward();
        String a = activeOrder(w, "pcm", "od");
        String b = activeOrder(w, "panto", "od");
        clockAt(8, 5);
        // the wristband stop is answered in the first transaction, which takes no lock, so this pair is started together
        List<Resp> answers = parallel(
                doseReq(w.nurse().auth(), a, given(ist(8, 0), "WRONG-00000")),
                doseReq(w.nurse2().auth(), b, given(w, ist(8, 0))));
        assertThat(statuses(answers)).containsExactly(409, 201);
        assertThat(type(answers.get(0))).isEqualTo("wristband-mismatch");
        assertThat(denyEvents(w.clinic(), w.adm().id())).isOne();
        assertThat(audits(w.clinic(), "IPD_DOSE_WRISTBAND_MISMATCH")).isOne();
        assertThat(doseRowsOfOrder(w.clinic(), a)).isZero();
        assertThat(doseRowsOfOrder(w.clinic(), b)).isOne();
    }
}
