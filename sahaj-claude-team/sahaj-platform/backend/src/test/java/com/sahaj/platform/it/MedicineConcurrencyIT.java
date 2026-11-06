package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-31 ADR-009 D10 on the real {@code sahaj_test} database: things that happen at the same moment. The orders are forced,
 * not hoped for (the technique of {@link SafetyConcurrencyIT}): a second connection holds the admission row, the requests
 * are started one after the other and each is seen waiting in pg_stat_activity, then the lock is released, so the first in
 * the queue runs first. Two orders of the same medicine, an order against a verify that an added interaction makes stale,
 * verify against stop, verify against reject, two verifies, an order and a verify against a discharge, two stops, and a
 * free-for-all. Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineConcurrencyIT extends MedicineSupport {

    private static final String NONE_KNOWN = "None known";

    private static int[] statuses(List<Resp> answers) {
        return answers.stream().mapToInt(Resp::status).toArray();
    }

    private String type(Resp r) throws Exception {
        return r.type(objectMapper);
    }

    private String hepToken(World w, String drugOnChart, String candidate) throws Exception {
        JsonNode ask = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), candidate, "od", null, null)), 200);
        assertThat(ask.get("outcome").asText()).isEqualTo("NEEDS_REASON");
        assertThat(drugOnChart).isNotEmpty();
        return ask.get("check").get("checkToken").asText();
    }

    // ---------------- order against order ----------------

    @Test
    void twoOrdersOfTheSameMedicineAtTheSameMomentGiveOnePlacedAndOneBlockedDuplicate() throws Exception {
        World w = world(NONE_KNOWN);
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                orderReq(w.doctor().auth(), w.adm().id(), newKey(), "pcm", null, null),
                orderReq(w.owner().auth(), w.adm().id(), newKey(), "pcm", null, null));
        assertThat(statuses(answers)).containsExactly(201, 200);
        assertThat(answers.get(0).json(objectMapper).get("outcome").asText()).isEqualTo("PLACED");
        JsonNode second = answers.get(1).json(objectMapper);
        assertThat(second.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(second.get("check").get("hardStops").get(0).get("code").asText()).isEqualTo("DUPLICATE");
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();
        assertThat(values(w.clinic(), "select ordered_by::text from ipd.medicine_order where admission_id = ?::uuid", w.adm().id()).get(0))
                .isEqualTo(w.doctor().userId().toString());
        assertThat(audit(w.clinic(), "IPD_MED_ORDERED")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_MED_ORDER_BLOCKED")).hasSize(1);
        assertThat(eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("order|")).count()).isOne();
        assertThat(eventLines(w.clinic(), w.adm().id()).stream().filter(l -> l.startsWith("deny|")).count()).isOne();
    }

    @Test
    void sixOrdersOfTheSameMedicineStartedTogetherGiveExactlyOneOrderAndNoServerError() throws Exception {
        World w = world(NONE_KNOWN);
        List<Req> reqs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reqs.add(orderReq(i % 2 == 0 ? w.doctor().auth() : w.owner().auth(), w.adm().id(), newKey(), "panto", null, null));
        }
        List<Resp> answers = parallel(reqs.toArray(new Req[0]));
        int created = 0;
        int blocked = 0;
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isLessThan(500);
            if (r.status() == 201) {
                created++;
            } else {
                assertThat(r.json(objectMapper).get("outcome").asText()).isEqualTo("BLOCKED");
                blocked++;
            }
        }
        assertThat(created).isOne();
        assertThat(blocked).isEqualTo(5);
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();
    }

    @Test
    void aDoubleTapWithTheSameKeyGivesOnePlacedOrderAndOneReplay() throws Exception {
        World w = world(NONE_KNOWN);
        String key = newKey();
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                orderReq(w.doctor().auth(), w.adm().id(), key, "pcm", null, null),
                orderReq(w.doctor().auth(), w.adm().id(), key, "pcm", null, null));
        assertThat(statuses(answers)).containsExactly(201, 200);
        assertThat(answers.get(1).json(objectMapper).get("replayed").asBoolean()).isTrue();
        assertThat(answers.get(1).json(objectMapper).get("outcome").asText()).isEqualTo("PLACED");
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();
        assertThat(audit(w.clinic(), "IPD_MED_ORDERED")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_MED_ORDER_BLOCKED")).as("the retry is no false 'already on the chart'").isEmpty();
    }

    // ---------------- order against verify ----------------

    @Test
    void anOrderThatAddsAnInteractionBeforeAVerifyMakesTheVerify409OrderChecksChanged() throws Exception {
        World w = world(NONE_KNOWN);
        String asp = orderIdOf(place(w.doctor().auth(), w.adm().id(), "asp"));
        String screen = tokenOfOrder(w.pharmacist().auth(), asp); // what the pharmacist's screen showed
        String hepToken = hepToken(w, "asp", "hep");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                orderReq(w.doctor().auth(), w.adm().id(), newKey(), "hep", "SPECIALIST_ADVISED", hepToken),
                verifyReq(w.pharmacist(), asp, w.pin(), screen));
        assertThat(answers.get(0).status()).isEqualTo(201);
        assertThat(answers.get(1).status()).isEqualTo(409);
        assertThat(type(answers.get(1))).isEqualTo("order-checks-changed");
        assertThat(answers.get(1).detail(objectMapper)).isEqualTo(CHECKS_CHANGED);
        assertThat(state(w.clinic(), asp).status()).isEqualTo("PENDING");
        assertThat(orderCount(w.clinic(), w.adm().id())).isEqualTo(2);
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).as("the right PIN was spent, not a wrong try").isZero();
        // the pharmacist reloads, sees the new warning and verifies with the new token
        JsonNode row = queueRow(queue(w.pharmacist().auth()), asp);
        assertThat(finding(row.get("check"), "warnings", "INTERACTION")).isNotNull();
        verifyOk(w.pharmacist(), w.pin(), asp);
        assertThat(state(w.clinic(), asp).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aVerifyThatRunsBeforeTheOrderIsAcceptedAndTheOrderStillGoesThroughWithItsReason() throws Exception {
        World w = world(NONE_KNOWN);
        String asp = orderIdOf(place(w.doctor().auth(), w.adm().id(), "asp"));
        String screen = tokenOfOrder(w.pharmacist().auth(), asp);
        String hepToken = hepToken(w, "asp", "hep");
        List<Resp> answers = ordered(w.clinic(), w.adm().id(),
                verifyReq(w.pharmacist(), asp, w.pin(), screen),
                orderReq(w.doctor().auth(), w.adm().id(), newKey(), "hep", "SPECIALIST_ADVISED", hepToken));
        assertThat(statuses(answers)).containsExactly(200, 201);
        assertThat(state(w.clinic(), asp).status()).isEqualTo("ACTIVE");
        assertThat(orderCount(w.clinic(), w.adm().id())).isEqualTo(2);
    }

    // ---------------- verify against stop / reject / verify ----------------

    @Test
    void aStopBeforeAVerifyMakesTheVerify409OrderStateAndAVerifyBeforeAStopLetsTheStopStopTheActiveOrder() throws Exception {
        World w = world(NONE_KNOWN);
        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String tokenA = tokenOfOrder(w.pharmacist().auth(), a);
        List<Resp> stopFirst = ordered(w.clinic(), w.adm().id(), stopReq(w.doctor().auth(), a),
                verifyReq(w.pharmacist(), a, w.pin(), tokenA));
        assertThat(stopFirst.get(0).status()).isEqualTo(200);
        assertThat(stopFirst.get(1).status()).isEqualTo(409);
        assertThat(type(stopFirst.get(1))).isEqualTo("order-state");
        assertThat(stopFirst.get(1).detail(objectMapper)).isEqualTo(STOPPED_BY_DOCTOR);
        assertThat(state(w.clinic(), a).status()).isEqualTo("STOPPED");
        assertThat(state(w.clinic(), a).verifiedBy()).isEmpty();

        String b = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String tokenB = tokenOfOrder(w.pharmacist().auth(), b);
        List<Resp> verifyFirst = ordered(w.clinic(), w.adm().id(), verifyReq(w.pharmacist(), b, w.pin(), tokenB),
                stopReq(w.doctor().auth(), b));
        assertThat(statuses(verifyFirst)).containsExactly(200, 200);
        OrderState s = state(w.clinic(), b);
        assertThat(s.status()).isEqualTo("STOPPED");
        assertThat(s.verifiedBy()).isEqualTo(w.pharmacist().userId().toString());
        assertThat(s.version()).isEqualTo(2);
        assertThat(audit(w.clinic(), "IPD_MED_VERIFIED")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_MED_STOPPED")).hasSize(2);
    }

    @Test
    void aRejectBeforeAVerifyMakesTheVerify409AndAVerifyBeforeARejectMakesTheReject409WithTheVerifiersName() throws Exception {
        World w = world(NONE_KNOWN);
        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String tokenA = tokenOfOrder(w.pharmacist().auth(), a);
        List<Resp> rejectFirst = ordered(w.clinic(), w.adm().id(), rejectReq(w.owner().auth(), a, "WRONG_DOSE"),
                verifyReq(w.pharmacist(), a, w.pin(), tokenA));
        assertThat(rejectFirst.get(0).status()).isEqualTo(200);
        assertThat(rejectFirst.get(1).status()).isEqualTo(409);
        assertThat(rejectFirst.get(1).detail(objectMapper)).isEqualTo(ALREADY_REJECTED);
        assertThat(state(w.clinic(), a).status()).isEqualTo("REJECTED");

        String b = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String tokenB = tokenOfOrder(w.pharmacist().auth(), b);
        List<Resp> verifyFirst = ordered(w.clinic(), w.adm().id(), verifyReq(w.pharmacist(), b, w.pin(), tokenB),
                rejectReq(w.owner().auth(), b, "WRONG_DOSE"));
        assertThat(verifyFirst.get(0).status()).isEqualTo(200);
        assertThat(verifyFirst.get(1).status()).isEqualTo(409);
        assertThat(type(verifyFirst.get(1))).isEqualTo("order-state");
        assertThat(verifyFirst.get(1).detail(objectMapper)).isEqualTo(nameOf(w.clinic(), w.pharmacist().userId()) + " already verified this order.");
        assertThat(state(w.clinic(), b).status()).isEqualTo("ACTIVE");
    }

    @Test
    void twoVerifiesByTwoPeopleGiveOneVerifiedAndOne409OrderStateAndNeitherSpendsAWrongTry() throws Exception {
        World w = world(NONE_KNOWN);
        Pn second = newPn(w.clinic(), "PHARMACIST");
        String secondPin = goodPin(second);
        setPin(second, secondPin);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        List<Resp> answers = ordered(w.clinic(), w.adm().id(), verifyReq(w.pharmacist(), id, w.pin(), token),
                verifyReq(second, id, secondPin, token));
        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(type(answers.get(1))).isEqualTo("order-state");
        assertThat(answers.get(1).detail(objectMapper)).isEqualTo(nameOf(w.clinic(), w.pharmacist().userId()) + " already verified this order.");
        assertThat(state(w.clinic(), id).verifiedBy()).isEqualTo(w.pharmacist().userId().toString());
        assertThat(state(w.clinic(), id).version()).isOne();
        assertThat(audit(w.clinic(), "IPD_MED_VERIFIED")).hasSize(1);
        assertThat(pinRow(w.clinic(), second.membershipId()).failed()).isZero();
        // the other way round
        String id2 = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String token2 = tokenOfOrder(w.pharmacist().auth(), id2);
        List<Resp> reversed = ordered(w.clinic(), w.adm().id(), verifyReq(second, id2, secondPin, token2),
                verifyReq(w.pharmacist(), id2, w.pin(), token2));
        assertThat(statuses(reversed)).containsExactly(200, 409);
        assertThat(state(w.clinic(), id2).verifiedBy()).isEqualTo(second.userId().toString());
    }

    @Test
    void twoStopsByTwoDoctorsGiveOneStopAndOne409AndTwoStopsByTheSamePersonAreBothOk() throws Exception {
        World w = world(NONE_KNOWN);
        Staff other = staff(w.clinic(), "DOCTOR");
        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        List<Resp> two = ordered(w.clinic(), w.adm().id(), stopReq(w.doctor().auth(), a), stopReq(other.auth(), a));
        assertThat(statuses(two)).containsExactly(200, 409);
        assertThat(two.get(1).detail(objectMapper)).isEqualTo(ALREADY_STOPPED);
        assertThat(state(w.clinic(), a).stoppedBy()).isEqualTo(w.doctor().userId().toString());
        String b = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        List<Resp> same = ordered(w.clinic(), w.adm().id(), stopReq(w.doctor().auth(), b), stopReq(w.doctor().auth(), b));
        assertThat(statuses(same)).containsExactly(200, 200);
        assertThat(audit(w.clinic(), "IPD_MED_STOPPED")).hasSize(2);
    }

    // ---------------- against a discharge ----------------

    @Test
    void anOrderBeforeADischargeIsPlacedAndKeepsItsStatusAndAnOrderAfterADischargeIs409NotAdmitted() throws Exception {
        World w = world(NONE_KNOWN);
        List<Resp> orderFirst = ordered(w.clinic(), w.adm().id(),
                orderReq(w.doctor().auth(), w.adm().id(), newKey(), "pcm", null, null), dischargeReq(w.clinic(), w.adm()));
        assertThat(statuses(orderFirst)).containsExactly(201, 200);
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", w.adm().id()).get(0)).isEqualTo("DISCHARGED");
        assertThat(values(w.clinic(), "select status from ipd.medicine_order where admission_id = ?::uuid", w.adm().id()).get(0))
                .isEqualTo("PENDING");
        assertThat(queue(w.pharmacist().auth()).get("waiting").asInt()).isZero();

        World v = world(NONE_KNOWN);
        List<Resp> dischargeFirst = ordered(v.clinic(), v.adm().id(), dischargeReq(v.clinic(), v.adm()),
                orderReq(v.doctor().auth(), v.adm().id(), newKey(), "pcm", null, null));
        assertThat(dischargeFirst.get(0).status()).isEqualTo(200);
        assertThat(dischargeFirst.get(1).status()).isEqualTo(409);
        assertThat(type(dischargeFirst.get(1))).isEqualTo("not-admitted");
        assertThat(dischargeFirst.get(1).detail(objectMapper)).isEqualTo(NOT_ADMITTED_TEXT);
        assertThat(orderCount(v.clinic(), v.adm().id())).isZero();
    }

    @Test
    void aVerifyAndAStopAgainstADischargeAreNeverHalfDone() throws Exception {
        World w = world(NONE_KNOWN);
        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String b = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String tokenA = tokenOfOrder(w.pharmacist().auth(), a);
        // discharge first: the verify and the stop find a patient who is not admitted
        List<Resp> late = ordered(w.clinic(), w.adm().id(), dischargeReq(w.clinic(), w.adm()),
                verifyReq(w.pharmacist(), a, w.pin(), tokenA), stopReq(w.doctor().auth(), b));
        assertThat(statuses(late)).containsExactly(200, 409, 409);
        assertThat(type(late.get(1))).isEqualTo("not-admitted");
        assertThat(type(late.get(2))).isEqualTo("not-admitted");
        assertThat(state(w.clinic(), a).status()).isEqualTo("PENDING");
        assertThat(state(w.clinic(), b).status()).isEqualTo("PENDING");

        World v = world(NONE_KNOWN);
        String c = orderIdOf(place(v.doctor().auth(), v.adm().id(), "pcm"));
        String tokenC = tokenOfOrder(v.pharmacist().auth(), c);
        // verify first: the discharge goes through and the order stays Active
        List<Resp> early = ordered(v.clinic(), v.adm().id(), verifyReq(v.pharmacist(), c, v.pin(), tokenC),
                dischargeReq(v.clinic(), v.adm()));
        assertThat(statuses(early)).containsExactly(200, 200);
        assertThat(state(v.clinic(), c).status()).isEqualTo("ACTIVE");
    }

    // ---------------- a free-for-all ----------------

    @Test
    void aFreeForAllOfOrdersStopsRejectsAndVerifiesEndsWithoutAServerErrorAndWithAtMostOneLiveOrderPerMedicine()
            throws Exception {
        World w = world(NONE_KNOWN);
        String pcm = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String panto = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String ondan = orderIdOf(place(w.doctor().auth(), w.adm().id(), "ondan"));
        String tokenPcm = tokenOfOrder(w.pharmacist().auth(), pcm);
        String tokenPanto = tokenOfOrder(w.pharmacist().auth(), panto);
        List<Resp> answers = parallel(
                orderReq(w.doctor().auth(), w.adm().id(), newKey(), "pcm", null, null),
                orderReq(w.owner().auth(), w.adm().id(), newKey(), "ns", null, null),
                orderReq(w.owner().auth(), w.adm().id(), newKey(), "ns", null, null),
                stopReq(w.doctor().auth(), pcm),
                verifyReq(w.pharmacist(), pcm, w.pin(), tokenPcm),
                verifyReq(w.pharmacist(), panto, w.pin(), tokenPanto),
                rejectReq(w.owner().auth(), panto, "WRONG_DOSE"),
                rejectReq(w.owner().auth(), ondan, "WRONG_DOSE"),
                stopReq(w.doctor().auth(), ondan));
        for (Resp r : answers) {
            assertThat(r.status()).as(r.body()).isLessThan(500);
        }
        assertThat(values(w.clinic(), "select count(*)::text from (select drug_key from ipd.medicine_order where admission_id = ?::uuid "
                + "and status in ('PENDING', 'ACTIVE') group by drug_key having count(*) > 1) x", w.adm().id()).get(0)).isEqualTo("0");
        List<String> statuses = column(w.clinic(), "select status from ipd.medicine_order where admission_id = ?::uuid", w.adm().id());
        Collections.sort(statuses);
        assertThat(statuses).isSubsetOf("PENDING", "ACTIVE", "REJECTED", "STOPPED");
        // each of the three first orders ended in exactly one final or live state with matching stamps
        for (String id : List.of(pcm, panto, ondan)) {
            OrderState s = state(w.clinic(), id);
            switch (s.status()) {
                case "ACTIVE" -> assertThat(s.verifiedBy()).isNotEmpty();
                case "REJECTED" -> assertThat(s.rejectedBy()).isNotEmpty();
                case "STOPPED" -> assertThat(s.stoppedBy()).isNotEmpty();
                default -> assertThat(s.status()).isEqualTo("PENDING");
            }
        }
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isZero();
    }
}
