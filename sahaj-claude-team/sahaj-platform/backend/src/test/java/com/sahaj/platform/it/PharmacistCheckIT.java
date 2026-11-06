package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-31 the pharmacist check, stop and reject on the real {@code sahaj_test} database with the real staff PIN service
 * (docs/api/ipd.yaml {@code /pharmacist-checks}, {@code /medicine-orders/{id}/verify|reject|stop}; ADR-009 D1, D5, D8;
 * ADR-010 D9, D11): the queue and its paging, the counts-only summary and its change token, verify with the PIN (right,
 * wrong and counted, locked, not set, reset, rate limited), self-verification before any PIN try, a state conflict that
 * asks for the PIN again, changed findings, reject, stop, replays, discharged admissions, and what is written. Test plan:
 * docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class PharmacistCheckIT extends MedicineSupport {

    private static final String NONE_KNOWN = "None known";

    private static String wrongFor(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    private JsonNode problemOf(org.springframework.test.web.servlet.ResultActions a, int status, String type, String detail)
            throws Exception {
        JsonNode p = problem(a, status);
        assertType(p, type);
        if (detail != null) {
            assertThat(p.get("detail").asText()).isEqualTo(detail);
        }
        return p;
    }

    private String pharmacistName(World w) throws Exception {
        return nameOf(w.clinic(), w.pharmacist().userId());
    }

    private String token(World w, String orderId) throws Exception {
        return tokenOfOrder(w.pharmacist().auth(), orderId);
    }

    // ---------------- the queue ----------------

    @Test
    void theQueueListsWaitingOrdersOldestFirstWithPatientCheckAndFlagsAndWritesOneAccessRowPerPageAndNothingElse() throws Exception {
        World w = world("Penicillin (rash)");
        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        JsonNode bAnswer = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), "ceft", "bd", null, null)), 200);
        String b = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "ceft", "bd",
                "ALLERGY_HISTORY_CHECKED", bAnswer.get("check").get("checkToken").asText())), 201));
        String c = orderIdOf(place(w.owner().auth(), w.adm().id(), "panto"));
        MedSnap before = medSnap(w.clinic(), w.adm());

        JsonNode q = queue(w.pharmacist().auth());
        // review S3: a page that shows the patient is one record access, once per distinct patient (three orders, one patient)
        assertThat(medSnap(w.clinic(), w.adm()).access()).as("one row for one patient on the page").isEqualTo(before.access() + 1);
        assertThat(q.get("state").asText()).isEqualTo("trial");
        assertThat(q.get("waiting").asInt()).isEqualTo(3);
        assertThat(q.get("nextCursor").isNull()).isTrue();
        assertThat(q.get("asOf").isNull()).isFalse();
        assertThat(q.get("verifierName").asText()).isEqualTo(pharmacistName(w));
        List<String> reasons = new ArrayList<>();
        q.get("rejectReasons").forEach(r -> reasons.add(r.get("key").asText() + ":" + r.get("label").asText()));
        assertThat(reasons).containsExactly("ALLERGY_CONFLICT:Allergy conflict", "INTERACTION_RISK:Interaction risk too high",
                "WRONG_DOSE:Wrong dose or frequency", "DUPLICATE_THERAPY:Duplicate therapy");
        List<String> ids = new ArrayList<>();
        q.get("items").forEach(r -> ids.add(r.get("orderId").asText()));
        assertThat(ids).containsExactly(a, b, c);

        JsonNode rowB = queueRow(q, b);
        assertThat(rowB.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(rowB.get("drugKey").asText()).isEqualTo("ceft");
        assertThat(rowB.get("drugName").asText()).isEqualTo("Inj. Ceftriaxone 1 g");
        assertThat(rowB.get("frequencyKey").asText()).isEqualTo("bd");
        assertThat(rowB.get("frequencyLabel").asText()).isEqualTo("Twice daily");
        assertThat(rowB.get("orderedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(rowB.get("overrideReason").get("key").asText()).isEqualTo("ALLERGY_HISTORY_CHECKED");
        assertThat(rowB.get("patient").get("name").asText()).isEqualTo(w.adm().name());
        assertThat(rowB.get("patient").get("bedCode").asText()).isEqualTo(w.adm().bedCode());
        assertThat(rowB.get("patient").get("allergyText").asText()).isEqualTo("Penicillin (rash)");
        assertThat(rowB.get("patient").get("allergyStatus").asText()).isEqualTo("RECORDED");
        assertThat(finding(rowB.get("check"), "warnings", "ALLERGY_CROSS_CEPHALOSPORIN")).isNotNull();
        assertThat(rowB.get("check").get("checkToken").asText()).hasSize(22);
        assertThat(rowB.get("canVerify").asBoolean()).isTrue();
        assertThat(rowB.get("verifyLockReason").isNull()).isTrue();
        assertThat(rowB.get("canReject").asBoolean()).isTrue();
        assertThat(queueRow(q, a).get("highAlert").isNull()).isTrue();

        // the owner sees the same queue, and cannot verify the orders the owner placed
        JsonNode owner = queue(w.owner().auth());
        assertThat(owner.get("verifierName").asText()).isEqualTo(w.owner().name());
        JsonNode own = queueRow(owner, c);
        assertThat(own.get("canVerify").asBoolean()).isFalse();
        assertThat(own.get("verifyLockReason").asText()).isEqualTo("SELF");
        assertThat(own.get("canReject").asBoolean()).isTrue();
        assertThat(queueRow(owner, a).get("canVerify").asBoolean()).isTrue();
        MedSnap afterLists = medSnap(w.clinic(), w.adm());
        assertThat(afterLists.access()).as("the owner's page is a second access").isEqualTo(before.access() + 2);
        summary(w.pharmacist().auth());
        MedSnap afterSummary = medSnap(w.clinic(), w.adm());
        assertThat(afterSummary).as("the summary writes nothing").isEqualTo(afterLists);
        assertThat(afterSummary.events()).as("no event").isEqualTo(before.events());
        assertThat(afterSummary.audits()).as("no audit").isEqualTo(before.audits());
        assertThat(afterSummary.orders()).isEqualTo(before.orders());
        assertThat(afterSummary.pinRows()).isEqualTo(before.pinRows());
        // the row names the person who opened the list and the patient
        assertThat(values(w.clinic(), "select count(*) from patient.record_access where patient_id = ?::uuid and user_id = ?::uuid",
                w.adm().patientId().toString(), w.pharmacist().userId().toString()).get(0)).isEqualTo("1");
    }

    @Test
    void highAlertMedicinesCarryTheirFlagInTheQueue() throws Exception {
        World w = world(NONE_KNOWN);
        String hep = orderIdOf(place(w.doctor().auth(), w.adm().id(), "hep"));
        String ins = orderIdOf(place(w.doctor().auth(), w.adm().id(), "ins"));
        String kcl = orderIdOf(place(w.doctor().auth(), w.adm().id(), "kcl"));
        JsonNode q = queue(w.pharmacist().auth());
        assertThat(queueRow(q, hep).get("highAlert").asText()).isEqualTo("Anticoagulant");
        assertThat(queueRow(q, ins).get("highAlert").asText()).isEqualTo("Insulin");
        assertThat(queueRow(q, kcl).get("highAlert").asText()).isEqualTo("Concentrated electrolyte");
    }

    @Test
    void aHardStopThatAppearsAfterTheOrderMakesTheRowLockedAndVerifyIs409OrderBlockedAfterTheRightPinAndRejectStaysAllowed()
            throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "amox"));
        String tokenBefore = token(w, id);
        setAllergy(w.clinic(), w.adm(), "Penicillin");
        JsonNode row = queueRow(queue(w.pharmacist().auth()), id);
        assertThat(row.get("canVerify").asBoolean()).isFalse();
        assertThat(row.get("verifyLockReason").asText()).isEqualTo("HARD_STOP");
        assertThat(row.get("check").get("hardStops")).hasSize(1);
        assertThat(row.get("canReject").asBoolean()).isTrue();
        JsonNode p = problemOf(verifyCall(w.pharmacist(), id, w.pin(), row.get("check").get("checkToken").asText()), 409,
                "order-blocked", HARD_STOP_ON_ORDER);
        assertThat(p.toString()).doesNotContain("Penicillin");
        assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
        assertThat(tokenBefore).isNotEqualTo(row.get("check").get("checkToken").asText());
        rejectOk(w.pharmacist().auth(), id, "ALLERGY_CONFLICT");
        assertThat(state(w.clinic(), id).status()).isEqualTo("REJECTED");
    }

    // ---------------- verify ----------------

    @Test
    void theRightPinAndTheCurrentTokenVerifyTheOrderAndWriteTheStampTheEventTheAuditAndNoAccessRow() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        place(w.doctor().auth(), w.adm().id(), "panto");
        String token = token(w, id);
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode r;
        try (LogCapture logs = new LogCapture()) {
            freshPinAllowance(w.pharmacist());
            r = ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
            assertThat(logs.allText()).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile())
                    .doesNotContain(PEPPER).doesNotContain(pinRow(w.clinic(), w.pharmacist().membershipId()).hash());
            assertThat(java.util.regex.Pattern.compile("(?<![0-9A-Za-z.:\\-])" + w.pin() + "(?![0-9A-Za-z.:\\-])")
                    .matcher(logs.allText()).find()).isFalse();
        }
        JsonNode result = r.get("result");
        assertThat(result.get("orderId").asText()).isEqualTo(id);
        assertThat(result.get("drugName").asText()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(result.get("outcome").asText()).isEqualTo("VERIFIED");
        assertThat(result.get("replayed").asBoolean()).isFalse();
        assertThat(result.get("actorName").asText()).isEqualTo(pharmacistName(w));
        assertThat(result.get("rejectReason").isNull()).isTrue();
        assertThat(r.get("checks").get("waiting").asInt()).as("the queue after the action").isOne();
        assertThat(queueRow(r.get("checks"), id)).isNull();

        OrderState s = state(w.clinic(), id);
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.version()).isOne();
        assertThat(s.verifiedBy()).isEqualTo(w.pharmacist().userId().toString());
        assertThat(s.hasVerifiedAt()).isTrue();
        assertThat(s.hasRejectedAt() || s.hasStoppedAt()).isFalse();
        assertThat(values(w.clinic(), "select (verified_at >= ordered_at and verified_at <= clock_timestamp())::text from "
                + "ipd.medicine_order where id = ?::uuid", id).get(0)).isEqualTo("true");

        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.events()).isEqualTo(before.events() + 1);
        assertThat(after.audits()).isEqualTo(before.audits() + 1);
        assertThat(after.access()).as("the answer carries the next page (the other order): one access row for its patient")
                .isEqualTo(before.access() + 1);
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("verify|Verified Tab. Paracetamol 650 mg");
        List<String[]> audit = audit(w.clinic(), "IPD_MED_VERIFIED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_MEDICINE_ORDER");
        assertThat(audit.get(0)[1]).isEqualTo(id);
        assertThat(audit.get(0)[4]).isEqualTo(w.pharmacist().userId().toString());
        assertThat(audit.get(0)[3]).contains("ACTIVE").contains("pcm").doesNotContain(w.adm().name());
        assertThat(hasBarePin(audit.get(0)[3], w.pin())).isFalse();
        // the doctor sees it Active with the pharmacist's name, and can still stop it
        JsonNode row = sheetRow(emr(w.doctor().auth(), w.adm().id()), id);
        assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.get("verifiedByName").asText()).isEqualTo(pharmacistName(w));
        assertThat(row.get("canStop").asBoolean()).isTrue();
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isZero();
    }

    @Test
    void verifyingAnOrderWithTheDoctorsReasonSaysSoInTheEvent() throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "hep");
        String id = orderIdOf(placeWithReason(w.doctor().auth(), w.adm().id(), "asp", "CARDIOLOGY_STENT"));
        verifyOk(w.pharmacist(), w.pin(), id);
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("verify|Verified Tab. Aspirin 75 mg · accepted doctor's reason");
        assertThat(audit(w.clinic(), "IPD_MED_VERIFIED").get(0)[3]).contains("CARDIOLOGY_STENT");
    }

    @Test
    void theOwnerCanVerifyAnotherPersonsOrderWithTheOwnersOwnPin() throws Exception {
        World w = world(NONE_KNOWN);
        Pn owner = ownerPn(w.clinic());
        String ownerPin = otherPin(owner, w.pin());
        setPin(owner, ownerPin);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        JsonNode r = verifyOk(owner, ownerPin, id);
        assertThat(r.get("result").get("actorName").asText()).isEqualTo(w.owner().name());
        assertThat(state(w.clinic(), id).verifiedBy()).isEqualTo(w.clinic().ownerUserId().toString());
        // the pharmacist's PIN is not the owner's: it is checked against the person who is signed in
        String second = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String token = tokenOfOrder(owner.auth(), second);
        problemOf(verifyCall(owner, second, w.pin(), token), 403, "wrong-pin", null);
    }

    @Test
    void aRepeatByTheSameVerifierIsReplayedAndAnotherVerifierGets409OrderStateNamingTheFirstOne() throws Exception {
        World w = world(NONE_KNOWN);
        Pn owner = ownerPn(w.clinic());
        String ownerPin = goodPin(owner);
        setPin(owner, ownerPin);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = token(w, id);
        verifyOk(w.pharmacist(), w.pin(), id);
        MedSnap before = medSnap(w.clinic(), w.adm());
        freshPinAllowance(w.pharmacist());
        JsonNode again = ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
        assertThat(again.get("result").get("replayed").asBoolean()).isTrue();
        assertThat(again.get("result").get("outcome").asText()).isEqualTo("VERIFIED");
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.events()).isEqualTo(before.events());
        assertThat(after.audits()).isEqualTo(before.audits());
        assertThat(after.access()).isEqualTo(before.access());
        // another verifier with a right PIN
        problemOf(verifyCall(owner, id, ownerPin, token), 409, "order-state", pharmacistName(w) + " already verified this order.");
        assertThat(state(w.clinic(), id).verifiedBy()).isEqualTo(w.pharmacist().userId().toString());
    }

    // ---------------- the PIN ----------------

    @Test
    void aWrongPinIsCountedAndCommittedEvenThoughTheRequestFailsAndTheOrderStaysPending() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = token(w, id);
        MedSnap before = medSnap(w.clinic(), w.adm());
        String[] left = {"4 tries left.", "3 tries left.", "2 tries left.", "1 try left."};
        for (int i = 0; i < 4; i++) {
            problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 403, "wrong-pin", "That PIN is not right. " + left[i]);
            assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isEqualTo(i + 1);
        }
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.events()).isEqualTo(before.events());
        assertThat(after.audits()).isEqualTo(before.audits());
        // the right PIN works and clears the count
        ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isZero();
        assertThat(state(w.clinic(), id).status()).isEqualTo("ACTIVE");
    }

    @Test
    void theFifthWrongPinLocksAndEvenTheRightPinAnswersLockedAndCountsNothingUntilTheLockEnds() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = token(w, id);
        for (int i = 0; i < 4; i++) {
            problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 403, "wrong-pin", null);
        }
        JsonNode locked = problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 403, "pin-locked", null);
        assertThat(locked.get("detail").asText()).startsWith("Your PIN is locked after 5 wrong tries. Try again at ");
        assertThat(locked.get("retryAfterSeconds").asInt()).isBetween(800, 900);
        PinRow row = pinRow(w.clinic(), w.pharmacist().membershipId());
        assertThat(row.lockedNow()).isTrue();
        assertThat(row.lockCount()).isOne();
        assertThat(audit(w.clinic(), "PLATFORM_PIN_LOCKED")).hasSize(1);
        // the right PIN while locked
        JsonNode stillLocked = problemOf(verifyCall(w.pharmacist(), id, w.pin(), token), 403, "pin-locked", null);
        assertThat(stillLocked.get("retryAfterSeconds").asInt()).isPositive();
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId())).isEqualTo(row);
        assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
        assertThat(pinStatus(w.pharmacist()).get("state").asText()).isEqualTo("LOCKED");
        lockEnded(w.pharmacist());
        ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
        assertThat(state(w.clinic(), id).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aPharmacistWithoutAPinGets409PinNotSetAndNothingIsChanged() throws Exception {
        World w = world(NONE_KNOWN);
        Pn noPin = newPn(w.clinic(), "PHARMACIST");
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = tokenOfOrder(noPin.auth(), id); // reading the queue is an access (S3), so the snapshot is taken after it
        MedSnap before = medSnap(w.clinic(), w.adm());
        problemOf(verifyCall(noPin, id, "4827", token), 409, "pin-not-set", PIN_NEVER);
        assertThat(pinRow(w.clinic(), noPin.membershipId()).exists()).isFalse();
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
    }

    @Test
    void afterAnOwnerResetTheVerifyAsksForANewPinWithTheResetText() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = token(w, id);
        resetPin(w.clinic(), w.pharmacist().membershipId());
        problemOf(verifyCall(w.pharmacist(), id, w.pin(), token), 409, "pin-not-set", PIN_RESET_TEXT);
        assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
        String fresh = otherPin(w.pharmacist(), w.pin());
        setPin(w.pharmacist(), fresh);
        problemOf(verifyCall(w.pharmacist(), id, w.pin(), token), 403, "wrong-pin", null);
        ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(fresh, token), 200);
    }

    @Test
    void theThirdLockWithin24HoursSwitchesThePinOffAndTheFifthWrongTryOfThatLockAnswers409PinNotSetNeverPinLocked()
            throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = token(w, id);
        for (int lock = 1; lock <= 2; lock++) {
            for (int i = 0; i < 4; i++) {
                problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 403, "wrong-pin", null);
            }
            problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 403, "pin-locked", null);
            lockEnded(w.pharmacist());
        }
        for (int i = 0; i < 4; i++) {
            problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 403, "wrong-pin", null);
        }
        problemOf(verifyCall(w.pharmacist(), id, wrongFor(w.pin()), token), 409, "pin-not-set", PIN_SWITCHED_OFF);
        problemOf(verifyCall(w.pharmacist(), id, w.pin(), token), 409, "pin-not-set", PIN_SWITCHED_OFF);
        assertThat(audit(w.clinic(), "PLATFORM_PIN_CLEARED_AFTER_LOCKS")).hasSize(1);
        assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
    }

    @Test
    void tenFailedPinChecksInFiveMinutesAreAllowedAndTheEleventhVerifyEvenWithTheRightPinIs429RateLimitWithRetryAfter() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String token = token(w, id);
        freshPinAllowance(w.pharmacist());
        for (int i = 0; i < 10; i++) {
            org.springframework.test.web.servlet.ResultActions a = call(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(),
                    verifyBody(wrongFor(w.pin()), token));
            int status = a.andReturn().getResponse().getStatus();
            assertThat(status).as("try " + (i + 1)).isEqualTo(403);
        }
        JsonNode limited = problemOf(call(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pin(), token)), 429,
                "rate-limit", PIN_RATE);
        assertThat(limited.get("retryAfterSeconds").asInt()).isPositive();
        assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
    }

    // ---------------- self-verification ----------------

    @Test
    void theOwnerCannotVerifyAnOrderTheOwnerPlacedAndThatIsRefusedBeforeAnyPinTryEvenWithAWrongPinOrNoPinAtAll()
            throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.owner().auth(), w.adm().id(), "pcm"));
        Pn owner = ownerPn(w.clinic());
        MedSnap before = medSnap(w.clinic(), w.adm());
        // the owner has no PIN: still the self-verification text, not "set your PIN"
        problemOf(verifyCall(owner, id, "4827", "AAAAAAAAAAAAAAAAAAAAAA"), 403, "self-verification", SELF_VERIFY);
        String ownerPin = goodPin(owner);
        setPin(owner, ownerPin);
        for (String pin : new String[] {ownerPin, wrongFor(ownerPin), "0000"}) {
            problemOf(verifyCall(owner, id, pin, "AAAAAAAAAAAAAAAAAAAAAA"), 403, "self-verification", SELF_VERIFY);
        }
        PinRow row = pinRow(w.clinic(), owner.membershipId());
        assertThat(row.failed()).as("no PIN try was spent").isZero();
        assertThat(row.version()).isZero();
        assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
        assertThat(medSnap(w.clinic(), w.adm()).orders()).isEqualTo(before.orders());
        // but the owner may reject it
        rejectOk(owner.auth(), id, "WRONG_DOSE");
        assertThat(state(w.clinic(), id).status()).isEqualTo("REJECTED");
    }

    // ---------------- a state conflict asks for the PIN again ----------------

    @Test
    void aVerifyOfAnOrderThatIsNotWaitingAnyMoreChecksThePinFirstAndThenAnswers409OrderState() throws Exception {
        World w = world(NONE_KNOWN);
        String rejected = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String stopped = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String token = token(w, rejected);
        rejectOk(w.pharmacist().auth(), rejected, "WRONG_DOSE");
        stopOk(w.doctor().auth(), stopped);
        for (String[] c : new String[][] {{rejected, ALREADY_REJECTED}, {stopped, STOPPED_BY_DOCTOR}}) {
            // a wrong PIN is a wrong PIN (403, counted), not the state
            problemOf(verifyCall(w.pharmacist(), c[0], wrongFor(w.pin()), token), 403, "wrong-pin", null);
            assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isEqualTo(1);
            // the right PIN reaches the state answer and clears the count
            problemOf(verifyCall(w.pharmacist(), c[0], w.pin(), token), 409, "order-state", c[1]);
            assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isZero();
        }
        assertThat(state(w.clinic(), rejected).status()).isEqualTo("REJECTED");
        assertThat(state(w.clinic(), stopped).status()).isEqualTo("STOPPED");
    }

    @Test
    void whenTheFindingsChangedSinceThePharmacistLoadedTheScreenVerifyIs409OrderChecksChangedAndAFreshTokenWorks()
            throws Exception {
        World w = world(NONE_KNOWN);
        String hep = orderIdOf(place(w.doctor().auth(), w.adm().id(), "hep"));
        String asp = orderIdOf(placeWithReason(w.doctor().auth(), w.adm().id(), "asp", "SPECIALIST_ADVISED"));
        String old = token(w, asp);
        stopOk(w.doctor().auth(), hep); // the interaction is gone
        problemOf(verifyCall(w.pharmacist(), asp, w.pin(), old), 409, "order-checks-changed", CHECKS_CHANGED);
        assertThat(state(w.clinic(), asp).status()).isEqualTo("PENDING");
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).isZero();
        String fresh = token(w, asp);
        assertThat(fresh).isNotEqualTo(old);
        freshPinAllowance(w.pharmacist());
        ok(HttpMethod.POST, verifyUrl(asp), w.pharmacist().auth(), verifyBody(w.pin(), fresh), 200);
        // a made-up token never verifies
        String other = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        problemOf(verifyCall(w.pharmacist(), other, w.pin(), "AAAAAAAAAAAAAAAAAAAAAA"), 409, "order-checks-changed", CHECKS_CHANGED);
    }

    // ---------------- reject ----------------

    @Test
    void rejectWritesTheReasonTheEventAndTheAuditNeedsNoPinAndTheOwnerMayRejectAnOrderTheOwnerPlaced() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode r = rejectOk(w.pharmacist().auth(), id, "ALLERGY_CONFLICT");
        assertThat(r.get("result").get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(r.get("result").get("replayed").asBoolean()).isFalse();
        assertThat(r.get("result").get("orderId").asText()).isEqualTo(id);
        assertThat(r.get("result").get("drugName").asText()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(r.get("result").get("actorName").asText()).isEqualTo(pharmacistName(w));
        assertThat(r.get("result").get("rejectReason").get("key").asText()).isEqualTo("ALLERGY_CONFLICT");
        assertThat(r.get("result").get("rejectReason").get("label").asText()).isEqualTo("Allergy conflict");
        assertThat(r.get("checks").get("waiting").asInt()).isZero();
        OrderState s = state(w.clinic(), id);
        assertThat(s.status()).isEqualTo("REJECTED");
        assertThat(s.version()).isOne();
        assertThat(s.rejectedBy()).isEqualTo(w.pharmacist().userId().toString());
        assertThat(s.rejectReason()).isEqualTo("ALLERGY_CONFLICT");
        assertThat(s.hasRejectedAt()).isTrue();
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.events()).isEqualTo(before.events() + 1);
        assertThat(after.audits()).isEqualTo(before.audits() + 1);
        assertThat(after.access()).isEqualTo(before.access());
        assertThat(after.pinRows()).as("no PIN is involved in a reject").isEqualTo(before.pinRows());
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("verify|Rejected Tab. Paracetamol 650 mg · Allergy conflict");
        List<String[]> audit = audit(w.clinic(), "IPD_MED_REJECTED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[3]).contains("REJECTED").contains("ALLERGY_CONFLICT").contains("pcm");
        // the doctor sees the reason and who rejected it
        JsonNode row = sheetRow(emr(w.doctor().auth(), w.adm().id()), id);
        assertThat(row.get("status").asText()).isEqualTo("REJECTED");
        assertThat(row.get("rejectReason").get("label").asText()).isEqualTo("Allergy conflict");
        assertThat(row.get("rejectedByName").asText()).isEqualTo(pharmacistName(w));
        assertThat(row.get("canStop").asBoolean()).isFalse();
        for (String[] reason : REJECT_REASONS) {
            String next = orderIdOf(place(w.owner().auth(), w.adm().id(), "pcm"));
            JsonNode each = rejectOk(w.owner().auth(), next, reason[0]);
            assertThat(each.get("result").get("rejectReason").get("label").asText()).isEqualTo(reason[1]);
        }
    }

    @Test
    void rejectIsAllowedWhateverTheFindingsAndARepeatIsReplayedOnlyForTheSamePersonAndReason() throws Exception {
        World w = world("Penicillin");
        String ok = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        rejectOk(w.pharmacist().auth(), ok, "DUPLICATE_THERAPY");
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode again = rejectOk(w.pharmacist().auth(), ok, "DUPLICATE_THERAPY");
        assertThat(again.get("result").get("replayed").asBoolean()).isTrue();
        assertThat(medSnap(w.clinic(), w.adm())).as("a replay writes nothing").isEqualTo(before);
        problemOf(rejectCall(w.pharmacist().auth(), ok, "WRONG_DOSE"), 409, "order-state", ALREADY_REJECTED);
        problemOf(rejectCall(w.owner().auth(), ok, "DUPLICATE_THERAPY"), 409, "order-state", ALREADY_REJECTED);
        assertThat(state(w.clinic(), ok).rejectReason()).isEqualTo("DUPLICATE_THERAPY");
    }

    @Test
    void rejectOfAnActiveOrStoppedOrderIs409OrderStateWithTheContractTexts() throws Exception {
        World w = world(NONE_KNOWN);
        String active = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String stopped = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        verifyOk(w.pharmacist(), w.pin(), active);
        stopOk(w.doctor().auth(), stopped);
        MedSnap before = medSnap(w.clinic(), w.adm());
        problemOf(rejectCall(w.owner().auth(), active, "WRONG_DOSE"), 409, "order-state", pharmacistName(w) + " already verified this order.");
        problemOf(rejectCall(w.pharmacist().auth(), stopped, "WRONG_DOSE"), 409, "order-state", STOPPED_BY_DOCTOR);
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
    }

    // ---------------- stop ----------------

    @Test
    void stopMovesAPendingOrAnActiveOrderToStoppedWithTheEventAndTheAuditAndTheEarlierStampsStay() throws Exception {
        World w = world(NONE_KNOWN);
        String pending = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String active = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        verifyOk(w.pharmacist(), w.pin(), active);
        MedSnap before = medSnap(w.clinic(), w.adm());

        JsonNode sheet = stopOk(w.doctor().auth(), pending);
        assertThat(sheetRow(sheet, pending).get("status").asText()).isEqualTo("STOPPED");
        assertThat(sheetRow(sheet, pending).get("stoppedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(sheetRow(sheet, pending).get("canStop").asBoolean()).isFalse();
        OrderState s = state(w.clinic(), pending);
        assertThat(s.status()).isEqualTo("STOPPED");
        assertThat(s.version()).isOne();
        assertThat(s.stoppedBy()).isEqualTo(w.doctor().userId().toString());
        assertThat(s.verifiedBy()).isEmpty();
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("stop|Stopped Tab. Paracetamol 650 mg");

        stopOk(w.owner().auth(), active); // any doctor, not only the one who ordered
        OrderState a = state(w.clinic(), active);
        assertThat(a.status()).isEqualTo("STOPPED");
        assertThat(a.version()).isEqualTo(2);
        assertThat(a.verifiedBy()).as("the verification stays on record").isEqualTo(w.pharmacist().userId().toString());
        assertThat(a.hasVerifiedAt() && a.hasStoppedAt()).isTrue();
        assertThat(values(w.clinic(), "select (stopped_at >= verified_at)::text from ipd.medicine_order where id = ?::uuid", active).get(0))
                .isEqualTo("true");

        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.events()).isEqualTo(before.events() + 2);
        assertThat(after.audits()).isEqualTo(before.audits() + 2);
        assertThat(after.access()).isEqualTo(before.access());
        List<String[]> audit = audit(w.clinic(), "IPD_MED_STOPPED");
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)[3]).contains("\"statusBefore\"").contains("PENDING").contains("STOPPED");
        assertThat(audit.get(1)[3]).contains("ACTIVE").contains("STOPPED");
        // a stopped order leaves the pharmacist queue
        assertThat(queue(w.pharmacist().auth()).get("waiting").asInt()).isZero();
        // and can be ordered again
        place(w.doctor().auth(), w.adm().id(), "pcm");
    }

    @Test
    void aRepeatedStopByTheSamePersonIs200WithOneAccessRowAndOtherStopsAndRejectedOrdersAre409() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String rejected = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        rejectOk(w.pharmacist().auth(), rejected, "WRONG_DOSE");
        stopOk(w.doctor().auth(), id);
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode again = stopOk(w.doctor().auth(), id);
        assertThat(sheetRow(again, id).get("status").asText()).isEqualTo("STOPPED");
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.events()).isEqualTo(before.events());
        assertThat(after.audits()).isEqualTo(before.audits());
        assertThat(after.access()).isEqualTo(before.access() + 1);
        problemOf(call(HttpMethod.POST, stopUrl(id), w.owner().auth(), null), 409, "order-state", ALREADY_STOPPED);
        problemOf(call(HttpMethod.POST, stopUrl(rejected), w.doctor().auth(), null), 409, "order-state", REJECTED_CANNOT_STOP);
        assertThat(medSnap(w.clinic(), w.adm()).orders()).isEqualTo(before.orders());
    }

    // ---------------- discharged ----------------

    @Test
    void aDischargedAdmissionKeepsItsOrderStatusesLeavesTheQueueAndTheCountAtOnceAndEveryActionIs409NotAdmitted()
            throws Exception {
        World w = world(NONE_KNOWN);
        Adm other = admitWithAllergy(w.clinic(), NONE_KNOWN);
        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String b = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String kept = orderIdOf(place(w.doctor().auth(), other.id(), "pcm"));
        verifyOk(w.pharmacist(), w.pin(), b);
        String token = token(w, a);
        String tokenBefore = summary(w.pharmacist().auth()).get("changeToken").asText();
        assertThat(summary(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(2);

        discharge(w.clinic(), w.adm().id());
        JsonNode q = queue(w.pharmacist().auth());
        assertThat(q.get("waiting").asInt()).isOne();
        assertThat(queueRow(q, a)).isNull();
        assertThat(queueRow(q, kept)).isNotNull();
        JsonNode s = summary(w.pharmacist().auth());
        assertThat(s.get("waiting").asInt()).isOne();
        assertThat(s.get("changeToken").asText()).isNotEqualTo(tokenBefore);
        MedSnap before = medSnap(w.clinic(), w.adm());
        problemOf(call(HttpMethod.POST, stopUrl(a), w.doctor().auth(), null), 409, "not-admitted", NOT_ADMITTED_TEXT);
        problemOf(call(HttpMethod.POST, stopUrl(b), w.doctor().auth(), null), 409, "not-admitted", NOT_ADMITTED_TEXT);
        problemOf(rejectCall(w.pharmacist().auth(), a, "WRONG_DOSE"), 409, "not-admitted", NOT_ADMITTED_TEXT);
        problemOf(verifyCall(w.pharmacist(), a, w.pin(), token), 409, "not-admitted", NOT_ADMITTED_TEXT);
        problemOf(orderCall(w.doctor().auth(), w.adm().id(), "ondan"), 409, "not-admitted", NOT_ADMITTED_TEXT);
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
        assertThat(state(w.clinic(), a).status()).isEqualTo("PENDING");
        assertThat(state(w.clinic(), b).status()).isEqualTo("ACTIVE");
    }

    // ---------------- paging ----------------

    /** Inserts 14 PENDING orders (every medicine) into each of the admissions as the table owner, in groups of three with the same time. */
    private void insertPending(World w, List<Adm> adms) throws Exception {
        Instant base = Instant.now().minusSeconds(3 * 3600);
        asOwner(true, new String[] {"ipd.medicine_order", "ipd.admission"}, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into ipd.medicine_order (tenant_id, admission_id, client_key, "
                    + "drug_key, frequency_key, ordered_by, ordered_at) values (?::uuid, ?::uuid, ?::uuid, ?, 'od', ?::uuid, ?)")) {
                int n = 0;
                for (Adm adm : adms) {
                    for (String[] drug : DRUGS) {
                        ps.setString(1, w.clinic().tenantId().toString());
                        ps.setString(2, adm.id());
                        ps.setString(3, UUID.randomUUID().toString());
                        ps.setString(4, drug[0]);
                        ps.setString(5, w.doctor().userId().toString());
                        ps.setTimestamp(6, Timestamp.from(base.plusSeconds(n++ / 3)));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private List<String> walk(World w, String limit) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            String query = "limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = queue(w.pharmacist().auth(), query);
            page.get("items").forEach(r -> ids.add(r.get("orderId").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            assertThat(guard++).isLessThan(200);
        } while (cursor != null);
        return ids;
    }

    @Test
    void theQueuePagesByKeysetOldestFirstWith50ByDefaultUpTo100NoRowSkippedOrRepeatedAcrossTiesAndNewOrdersJoinTheEnd()
            throws Exception {
        World w = world(NONE_KNOWN);
        List<Adm> adms = new ArrayList<>();
        adms.add(w.adm());
        for (int i = 0; i < 7; i++) {
            adms.add(admitWithAllergy(w.clinic(), NONE_KNOWN));
        }
        insertPending(w, adms);
        List<String> expected = column(w.clinic(), "select o.id::text from ipd.medicine_order o join ipd.admission a on a.tenant_id = "
                + "o.tenant_id and a.id = o.admission_id where o.status = 'PENDING' and a.status = 'ADMITTED' "
                + "order by o.ordered_at, o.id");
        assertThat(expected).hasSize(8 * 14);

        JsonNode first = queue(w.pharmacist().auth());
        assertThat(first.get("items")).as("default limit 50").hasSize(50);
        assertThat(first.get("waiting").asInt()).isEqualTo(112);
        assertThat(first.get("nextCursor").isNull()).isFalse();
        assertThat(first.get("nextCursor").asText()).matches("[A-Za-z0-9_-]{32}");
        assertThat(first.get("items").get(0).get("orderId").asText()).isEqualTo(expected.get(0));

        JsonNode p100 = queue(w.pharmacist().auth(), "limit=100");
        assertThat(p100.get("items")).hasSize(100);
        assertThat(p100.get("waiting").asInt()).isEqualTo(112);
        JsonNode rest = queue(w.pharmacist().auth(), "limit=100&cursor=" + p100.get("nextCursor").asText());
        assertThat(rest.get("items")).hasSize(12);
        assertThat(rest.get("nextCursor").isNull()).isTrue();
        assertThat(rest.get("waiting").asInt()).as("waiting is always the full count").isEqualTo(112);

        assertThat(walk(w, "100")).isEqualTo(expected);
        assertThat(walk(w, "50")).isEqualTo(expected);
        assertThat(walk(w, "7")).as("a page size that cuts the groups of equal times").isEqualTo(expected);
        assertThat(walk(w, "1")).isEqualTo(expected);

        // a new order arrives between two pages: it is on a later page, the pages do not shift
        JsonNode page1 = queue(w.pharmacist().auth(), "limit=100");
        Adm fresh = admitWithAllergy(w.clinic(), NONE_KNOWN);
        String newOrder = orderIdOf(place(w.doctor().auth(), fresh.id(), "pcm"));
        JsonNode page2 = queue(w.pharmacist().auth(), "limit=100&cursor=" + page1.get("nextCursor").asText());
        List<String> second = new ArrayList<>();
        page2.get("items").forEach(r -> second.add(r.get("orderId").asText()));
        assertThat(second).hasSize(13);
        assertThat(second.subList(0, 12)).isEqualTo(expected.subList(100, 112));
        assertThat(second.get(12)).isEqualTo(newOrder);
        assertThat(page2.get("waiting").asInt()).isEqualTo(113);
    }

    @Test
    void aBadLimitOrCursorIs400OnItsOwnFieldWithTheReloadText() throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "pcm");
        for (String limit : new String[] {"0", "101", "abc", "-1", "", "1.5", "1e2", "1000", "%EF%BC%91%EF%BC%90", "5%20"}) {
            JsonNode p = problem(call(HttpMethod.GET, CHECKS + "?limit=" + limit, w.pharmacist().auth(), null), 400);
            assertType(p, "validation");
            assertThat(fieldNames(p)).as("limit=" + limit).containsExactly("limit");
            assertThat(fieldMessage(p, "limit")).isEqualTo(RELOAD_TEXT);
        }
        String negative = "_".repeat(32); // base64url of 24 bytes of 0xFF: a negative time
        for (String cursor : new String[] {"abc", "A".repeat(31), "A".repeat(33), "A".repeat(31) + "+", "A".repeat(31) + "=", negative,
                "%20" + "A".repeat(32)}) {
            JsonNode p = problem(call(HttpMethod.GET, CHECKS + "?cursor=" + cursor, w.pharmacist().auth(), null), 400);
            assertType(p, "validation");
            assertThat(fieldNames(p)).as("cursor=" + cursor).containsExactly("cursor");
            assertThat(fieldMessage(p, "cursor")).isEqualTo(RELOAD_TEXT);
        }
        // limit 1 and 100 are the bounds that work
        assertThat(queue(w.pharmacist().auth(), "limit=1").get("items")).hasSize(1);
        assertThat(queue(w.pharmacist().auth(), "limit=100").get("items")).hasSize(1);
        // the role comes before the paging check
        problemOf(call(HttpMethod.GET, CHECKS + "?limit=0", staff(w.clinic(), "NURSE").auth(), null), 403, "forbidden", ONLY_PHARMACIST);
    }

    // ---------------- the summary and its change token ----------------

    private String expectedToken(Clinic clinic) throws Exception {
        List<String> lines = new ArrayList<>(column(clinic, "select lower(o.id::text) || ':' || o.version::text from ipd.medicine_order o "
                + "join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id where o.status = 'PENDING' "
                + "and a.status = 'ADMITTED'"));
        Collections.sort(lines);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
    }

    @Test
    void theSummaryIsCountsOnlyAndItsChangeTokenFollowsTheWaitingOrdersExactly() throws Exception {
        World w = world(NONE_KNOWN);
        JsonNode empty = summary(w.pharmacist().auth());
        assertThat(empty.fieldNames()).toIterable().containsExactlyInAnyOrder("waiting", "changeToken", "asOf");
        assertThat(empty.get("waiting").asInt()).isZero();
        assertThat(empty.get("changeToken").asText()).isEqualTo(EMPTY_TOKEN).isEqualTo(expectedToken(w.clinic()));

        String a = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        JsonNode one = summary(w.pharmacist().auth());
        assertThat(one.get("waiting").asInt()).isOne();
        assertThat(one.get("changeToken").asText()).isEqualTo(expectedToken(w.clinic())).hasSize(22);
        assertThat(summary(w.pharmacist().auth()).get("changeToken").asText()).as("stable while nothing changes")
                .isEqualTo(one.get("changeToken").asText());
        assertThat(one.toString()).doesNotContain(w.adm().name()).doesNotContain("Paracetamol").doesNotContain(w.adm().id());

        String b = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        JsonNode two = summary(w.owner().auth());
        assertThat(two.get("waiting").asInt()).isEqualTo(2);
        assertThat(two.get("changeToken").asText()).isEqualTo(expectedToken(w.clinic())).isNotEqualTo(one.get("changeToken").asText());

        verifyOk(w.pharmacist(), w.pin(), a);
        JsonNode afterVerify = summary(w.pharmacist().auth());
        assertThat(afterVerify.get("waiting").asInt()).isOne();
        assertThat(afterVerify.get("changeToken").asText()).isEqualTo(expectedToken(w.clinic()))
                .isNotEqualTo(two.get("changeToken").asText());

        rejectOk(w.pharmacist().auth(), b, "WRONG_DOSE");
        JsonNode none = summary(w.pharmacist().auth());
        assertThat(none.get("waiting").asInt()).isZero();
        assertThat(none.get("changeToken").asText()).isEqualTo(EMPTY_TOKEN);

        String c = orderIdOf(place(w.doctor().auth(), w.adm().id(), "ondan"));
        String withC = summary(w.pharmacist().auth()).get("changeToken").asText();
        stopOk(w.doctor().auth(), c);
        assertThat(summary(w.pharmacist().auth()).get("changeToken").asText()).isNotEqualTo(withC).isEqualTo(EMPTY_TOKEN);
    }
}
