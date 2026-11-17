package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76 WP1 (stories 76-1 and 76-2): placing with the new fields, change (a new version), hold, resume, stop with a reason,
 * the pharmacist query and the doctor's reply, verify with orderVersion, and the review-after-dose list. Contract:
 * docs/api/ipd.yaml 0.4.1; ADR-027 D1, D4 to D6. Test plan: docs/test-plans/SAH-76.md.
 */
@OnTestDatabase
@WithPinPepper
class Sah76OrdersIT extends Sah76Support {

    // ---------------- placing ----------------

    @Test
    void aScheduledOrderIsPlacedWithDoseRouteAndVersionOneAndWaitsForThePharmacist() throws Exception {
        Ward w = ward();
        JsonNode a = place(w.doctor().auth(), w.adm().id(), "pcm");
        String id = orderIdOf(a);
        JsonNode row = a.get("order");
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
        assertThat(row.get("orderVersion").asInt()).isEqualTo(1);
        JsonNode d = row.get("details");
        assertThat(d.get("kind").asText()).isEqualTo("SCHEDULED");
        assertThat(d.get("doseAmount").asDouble()).isEqualTo(1.0);
        assertThat(d.get("doseUnit").asText()).isEqualTo("TABLET");
        assertThat(d.get("routeKey").asText()).isEqualTo("ORAL");
        assertThat(d.get("priority").asText()).isEqualTo("ROUTINE");
        assertThat(versions(w.clinic(), id)).isOne();
        assertThat(one(w.clinic(), "select kind || '/' || verification from ipd.medicine_order_version where order_id = ?::uuid", id))
                .isEqualTo("SCHEDULED/REQUIRED");
        assertThat(one(w.clinic(), "select current_version::text from ipd.medicine_order where id = ?::uuid", id)).isEqualTo("1");
        assertThat(noticeRows(w.clinic(), id)).as("a routine order needs its pharmacist before the nurse is told").isZero();
    }

    @Test
    void aPrnOrderIsPlacedWithItsIntervalAndMaximum() throws Exception {
        Ward w = ward();
        Map<String, Object> b = newOrderBody("pcm");
        b.put("frequencyKey", "prn");
        b.put("indication", "Fever above 38.5 C");
        b.put("prnMinIntervalMinutes", 360);
        b.put("prnMaxPer24h", 4);
        JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(b), 201);
        JsonNode d = a.get("order").get("details");
        assertThat(d.get("kind").asText()).isEqualTo("PRN");
        assertThat(d.get("frequencyKey").asText()).isEqualTo("prn");
        assertThat(d.get("prnMinIntervalMinutes").asInt()).isEqualTo(360);
        assertThat(d.get("prnMaxPer24h").asInt()).isEqualTo(4);
        assertThat(one(w.clinic(), "select kind from ipd.medicine_order_version where order_id = ?::uuid", orderIdOf(a))).isEqualTo("PRN");
    }

    @Test
    void aStatOrderIsOneDoseDueNowAndTheNurseIsToldAtOnce() throws Exception {
        Ward w = ward();
        JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201);
        String id = orderIdOf(a);
        JsonNode d = a.get("order").get("details");
        assertThat(d.get("kind").asText()).isEqualTo("ONCE");
        assertThat(d.get("priority").asText()).isEqualTo("STAT");
        assertThat(d.get("frequencyKey").isNull()).isTrue();
        assertThat(a.get("order").get("status").asText()).isEqualTo("PENDING");
        assertThat(noticeRows(w.clinic(), id, "NEW")).as("a STAT order notice is written at signing").isOne();
        assertThat(one(w.clinic(), "select priority from ipd.order_notice where medicine_order_id = ?::uuid", id)).isEqualTo("STAT");
    }

    @Test
    void whenTheWardDoesNotNeedAPharmacistTheOrderIsActiveAtOnceAndTheNoticeIsWritten() throws Exception {
        Ward w = ward();
        verificationRequired(w.clinic(), false);
        JsonNode a = place(w.doctor().auth(), w.adm().id(), "pcm");
        String id = orderIdOf(a);
        assertThat(a.get("order").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(a.get("order").get("details").get("pharmacistChecked").asBoolean()).isFalse();
        assertThat(one(w.clinic(), "select verification from ipd.medicine_order_version where order_id = ?::uuid", id))
                .isEqualTo("NOT_REQUIRED");
        assertThat(noticeRows(w.clinic(), id, "NEW")).isOne();
    }

    @Test
    void everyFieldErrorOfTheOrderFormIsAnsweredTogetherWithTheContractTextAndNothingIsSaved() throws Exception {
        Ward w = ward();
        long before = orderCount(w.clinic(), w.adm().id());
        record Case(String field, String message, Consumer<Map<String, Object>> mutate) {
        }
        List<Case> cases = new ArrayList<>(List.of(
                new Case("doseAmount", "Enter the dose.", m -> m.remove("doseAmount")),
                new Case("doseAmount", "Enter the dose.", m -> m.put("doseAmount", 0)),
                new Case("doseUnit", "Choose the unit.", m -> m.put("doseUnit", "G")),
                new Case("doseUnit", "Choose the unit.", m -> m.put("doseUnit", "LITRE")),
                new Case("routeKey", "Choose the route.", m -> m.put("routeKey", "MOUTH")),
                new Case("startAt", "Start cannot be in the past.", m -> m.put("startAt", Instant.now().minusSeconds(3600).toString())),
                new Case("durationDays", "Enter whole days, or choose Until stopped.", m -> m.put("durationDays", 0)),
                new Case("durationDays", "Enter whole days, or choose Until stopped.", m -> m.put("durationDays", 366)),
                new Case("indication", "Keep it to 200 characters.", m -> m.put("indication", "x".repeat(201))),
                new Case("indication", TEXT_CHARACTERS_TEXT, m -> m.put("indication", "Fever\u0007 high")),
                new Case("indication", "Enter what it is for.", m -> {
                    m.put("frequencyKey", "prn");
                    m.put("prnMinIntervalMinutes", 360);
                    m.put("prnMaxPer24h", 4);
                }),
                new Case("prnMinIntervalMinutes", "Enter the minimum time between doses.", m -> {
                    m.put("frequencyKey", "prn");
                    m.put("indication", "Fever");
                    m.put("prnMinIntervalMinutes", 5);
                    m.put("prnMaxPer24h", 4);
                }),
                new Case("prnMaxPer24h", "Enter the most doses in 24 hours.", m -> {
                    m.put("frequencyKey", "prn");
                    m.put("indication", "Fever");
                    m.put("prnMinIntervalMinutes", 360);
                    m.put("prnMaxPer24h", 25);
                })));
        for (Case c : cases) {
            Map<String, Object> b = newOrderBody("pcm");
            c.mutate().accept(b);
            JsonNode p = problem(post(w.doctor().auth(), ordersUrl(w.adm().id()), b), 400);
            assertType(p, "validation");
            assertThat(msg(p, c.field())).as(c.field() + " in " + p).isEqualTo(c.message());
        }
        Map<String, Object> stat = statBody("ondan");
        stat.put("frequencyKey", "od");
        JsonNode p = problem(post(w.doctor().auth(), ordersUrl(w.adm().id()), stat), 400);
        assertThat(p.get("fieldErrors").toString()).contains("A STAT order is one dose, due now.");
        Map<String, Object> many = newOrderBody("pcm");
        many.remove("doseAmount");
        many.put("routeKey", "MOUTH");
        JsonNode both = problem(post(w.doctor().auth(), ordersUrl(w.adm().id()), many), 400);
        assertThat(fieldNames(both)).contains("doseAmount", "routeKey");
        assertThat(orderCount(w.clinic(), w.adm().id())).isEqualTo(before);
    }

    @Test
    void aRepeatOfTheSameKeyAnswersTheFirstOrderAndADifferentDoseUnderTheSameKeyIsRefused() throws Exception {
        Ward w = ward();
        Map<String, Object> b = newOrderBody("pcm");
        JsonNode first = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(b), 201);
        JsonNode again = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(b), 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(orderIdOf(again)).isEqualTo(orderIdOf(first));
        b.put("doseAmount", 2);
        JsonNode p = problem(post(w.doctor().auth(), ordersUrl(w.adm().id()), b), 409);
        assertProblem(p, "client-key-reused", CLIENT_KEY_REUSED);
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();
        assertThat(versions(w.clinic(), orderIdOf(first))).isOne();
    }

    // ---------------- roles ----------------

    @Test
    void onlyADoctorOrTheOwnerMayChangeHoldResumeStopOrReplyAndOnlyThePharmacistOrOwnerMayQuery() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        String writes = writes(w.clinic(), w.adm().id());
        for (var auth : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {w.nurse().auth(),
                w.pharmacist().auth()}) {
            refused(post(auth, changeUrl(id), changeBody(1, "Higher dose is needed", 2)), 403, "forbidden", ONLY_DOCTOR);
            refused(post(auth, holdUrl(id), actBody(1, "reason", "Patient is nil by mouth")), 403, "forbidden", ONLY_DOCTOR);
            refused(post(auth, resumeUrl(id), actBody(1, null, null)), 403, "forbidden", ONLY_DOCTOR);
            refused(post(auth, stopUrl(id), actBody(1, "reason", "No longer needed")), 403, "forbidden", ONLY_DOCTOR);
            refused(post(auth, replyUrl(id), actBody(1, "reply", "Yes it is intended")), 403, "forbidden", ONLY_DOCTOR);
        }
        for (var auth : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {w.doctor().auth(), w.nurse().auth()}) {
            refused(post(auth, queryUrl(id), actBody(1, "question", "Is this dose right?")), 403, "forbidden", ONLY_PHARMACIST);
        }
        refused(call(HttpMethod.GET, REVIEWS, w.doctor().auth(), null), 403, "forbidden", null);
        refused(call(HttpMethod.GET, REVIEWS, w.nurse().auth(), null), 403, "forbidden", null);
        refused(post(w.nurse().auth(), markReviewedUrl(uuid()), Map.of()), 403, "forbidden", null);
        assertThat(writes(w.clinic(), w.adm().id())).as("a refused call writes nothing").isEqualTo(writes);
    }

    // ---------------- change ----------------

    @Test
    void aChangeWritesVersionTwoGoesBackToThePharmacistAndWritesAChangedNotice() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        assertThat(noticeRows(w.clinic(), id, "NEW")).isOne();
        JsonNode sheet = ok(HttpMethod.POST, changeUrl(id), w.doctor().auth(),
                js(changeBody(1, "Fever is not settling, raise the dose", 2)), 200);
        JsonNode row = sheetRow(sheet, id);
        assertThat(row.get("orderVersion").asInt()).isEqualTo(2);
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
        assertThat(row.get("details").get("doseAmount").asDouble()).isEqualTo(2.0);
        assertThat(row.get("changes").toString()).contains("\"field\":\"dose\"");
        assertThat(versions(w.clinic(), id)).isEqualTo(2);
        assertThat(one(w.clinic(), "select (superseded_at is not null)::text from ipd.medicine_order_version where order_id = ?::uuid "
                + "and version_no = 1", id)).isEqualTo("true");
        assertThat(one(w.clinic(), "select (superseded_at is null)::text from ipd.medicine_order_version where order_id = ?::uuid "
                + "and version_no = 2", id)).isEqualTo("true");
        assertThat(noticeRows(w.clinic(), id, "CHANGED")).isOne();
        assertThat(audit(w.clinic(), "IPD_MED_CHANGED")).hasSize(1);
    }

    @Test
    void aChangeNeedsTheVersionTheDoctorSawAChangeAndAReasonAndRefusesAnUnchangedOrder() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        refused(post(w.doctor().auth(), changeUrl(id), changeBody(3, "Fever is not settling", 2)), 409, "order-changed",
                ORDER_CHANGED_TEXT);
        JsonNode p = problem(post(w.doctor().auth(), changeUrl(id), changeBody(1, "no", 2)), 400);
        assertThat(msg(p, "reason")).isEqualTo("Write why you are changing this order.");
        JsonNode same = problem(post(w.doctor().auth(), changeUrl(id), changeBody(1, "Looks the same to me", 1)), 400);
        assertThat(same.get("detail").asText()).isEqualTo(NOTHING_CHANGED_TEXT);
        Map<String, Object> badUnit = changeBody(1, "Change the unit please", 2);
        badUnit.put("doseUnit", "G");
        assertThat(msg(problem(post(w.doctor().auth(), changeUrl(id), badUnit), 400), "doseUnit")).isEqualTo("Choose the unit.");
        Map<String, Object> control = changeBody(1, "Reason with\u0007 a bell", 2);
        assertThat(msg(problem(post(w.doctor().auth(), changeUrl(id), control), 400), "reason")).isEqualTo(TEXT_CHARACTERS_TEXT);
        assertThat(versions(w.clinic(), id)).as("nothing was saved").isOne();
    }

    @Test
    void aRepeatedChangeIsReadFromTheStateAndNeverAnswersClientKeyReused() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        Map<String, Object> b = changeBody(1, "Fever is not settling", 2);
        ok(HttpMethod.POST, changeUrl(id), w.doctor().auth(), js(b), 200);
        String writes = writes(w.clinic(), w.adm().id());
        ok(HttpMethod.POST, changeUrl(id), w.doctor().auth(), js(b), 200);
        assertThat(versions(w.clinic(), id)).isEqualTo(2);
        assertThat(noticeRows(w.clinic(), id, "CHANGED")).isOne();
        assertThat(writes(w.clinic(), w.adm().id())).as("a repeat writes only the record-access row").isEqualTo(writes);
        // the same key with other values is not a repeat: the version moved on, so it is order-changed
        Map<String, Object> other = changeBody(1, "A different story entirely", 3);
        other.put("clientKey", b.get("clientKey"));
        JsonNode p = problem(post(w.doctor().auth(), changeUrl(id), other), 409);
        assertThat(p.get("type").asText()).doesNotContain("client-key-reused");
        assertProblem(p, "order-changed", ORDER_CHANGED_TEXT);
    }

    @Test
    void anOrderOnHoldMustBeResumedBeforeAChangeAndAStoppedOrderCannotBeChanged() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(), js(actBody(1, "reason", "Patient is nil by mouth")), 200);
        refused(post(w.doctor().auth(), changeUrl(id), changeBody(1, "Raise the dose now", 2)), 409, "order-state", RESUME_FIRST_TEXT);
        String stopped = placed(w, "asp");
        ok(HttpMethod.POST, stopUrl(stopped), w.doctor().auth(), js(actBody(1, "reason", "Not needed any more")), 200);
        Map<String, Object> b = changeBody(1, "Raise the dose now", 2);
        b.put("doseUnit", "TABLET");
        refused(post(w.doctor().auth(), changeUrl(stopped), b), 409, "order-state", STOPPED_TEXT);
    }

    @Test
    void aChangeOfAnUnverifiedOrderKeepsItWaitingAndTheVerifyMustNameTheNewVersion() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        ok(HttpMethod.POST, changeUrl(id), w.doctor().auth(), js(changeBody(1, "Dose was entered wrongly", 2)), 200);
        assertThat(status(w.clinic(), id)).isEqualTo("PENDING");
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        freshPinAllowance(w.pharmacist());
        refused(call(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pharmacistPin(), token)), 409, "order-changed",
                ORDER_CHANGED_TEXT);
        freshPinAllowance(w.pharmacist());
        ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(),
                "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + token + "\",\"orderVersion\":2}", 200);
        assertThat(status(w.clinic(), id)).isEqualTo("ACTIVE");
        assertThat(one(w.clinic(), "select (verified_by is not null)::text from ipd.medicine_order_version where order_id = ?::uuid "
                + "and version_no = 2", id)).isEqualTo("true");
        assertThat(one(w.clinic(), "select (verified_by is null)::text from ipd.medicine_order_version where order_id = ?::uuid "
                + "and version_no = 1", id)).as("version 1 was never verified").isEqualTo("true");
    }

    // ---------------- hold and resume ----------------

    @Test
    void holdPutsAnActiveOrderOnHoldAndResumeBringsItBackWithoutANewPharmacistCheck() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        JsonNode held = sheetRow(ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(),
                js(actBody(1, "reason", "Patient is nil by mouth")), 200), id);
        assertThat(held.get("status").asText()).isEqualTo("ON_HOLD");
        assertThat(held.get("hold").get("reason").asText()).isEqualTo("Patient is nil by mouth");
        assertThat(held.get("canResume").asBoolean()).isTrue();
        assertThat(held.get("canHold").asBoolean()).isFalse();
        assertThat(count(w.clinic(), "select count(*) from ipd.order_hold where medicine_order_id = ?::uuid and resumed_at is null", id))
                .isOne();
        assertThat(noticeRows(w.clinic(), id, "HELD")).isOne();
        JsonNode resumed = sheetRow(ok(HttpMethod.POST, resumeUrl(id), w.doctor().auth(), js(actBody(1, null, null)), 200), id);
        assertThat(resumed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(resumed.get("hold").isNull()).isTrue();
        assertThat(count(w.clinic(), "select count(*) from ipd.order_hold where medicine_order_id = ?::uuid and resumed_at is not null",
                id)).isOne();
        assertThat(noticeRows(w.clinic(), id, "RESUMED")).isOne();
        assertThat(versions(w.clinic(), id)).as("hold and resume make no new version").isOne();
    }

    @Test
    void holdAndResumeRefuseTheWrongStateTheWrongVersionAndAShortReason() throws Exception {
        Ward w = ward();
        String pending = placed(w, "pcm");
        refused(post(w.doctor().auth(), holdUrl(pending), actBody(1, "reason", "Patient is nil by mouth")), 409, "order-state",
                NOT_ACTIVE_TEXT);
        String id = active(w, "asp");
        refused(post(w.doctor().auth(), resumeUrl(id), actBody(1, null, null)), 409, "order-state", NOT_ON_HOLD_TEXT);
        refused(post(w.doctor().auth(), holdUrl(id), actBody(2, "reason", "Patient is nil by mouth")), 409, "order-changed",
                ORDER_CHANGED_TEXT);
        JsonNode p = problem(post(w.doctor().auth(), holdUrl(id), actBody(1, "reason", "no")), 400);
        assertThat(msg(p, "reason")).isEqualTo("Write why you are holding this medicine.");
        assertThat(status(w.clinic(), id)).isEqualTo("ACTIVE");
    }

    @Test
    void aRepeatedHoldAndAResumeRepeatWriteNothingNew() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        Map<String, Object> hold = actBody(1, "reason", "Patient is nil by mouth");
        ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(), js(hold), 200);
        String afterHold = writes(w.clinic(), w.adm().id());
        ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(), js(hold), 200);
        assertThat(writes(w.clinic(), w.adm().id())).isEqualTo(afterHold);
        Map<String, Object> resume = actBody(1, null, null);
        ok(HttpMethod.POST, resumeUrl(id), w.doctor().auth(), js(resume), 200);
        String afterResume = writes(w.clinic(), w.adm().id());
        ok(HttpMethod.POST, resumeUrl(id), w.doctor().auth(), js(resume), 200);
        assertThat(writes(w.clinic(), w.adm().id())).isEqualTo(afterResume);
        assertThat(count(w.clinic(), "select count(*) from ipd.order_hold where medicine_order_id = ?::uuid", id)).isOne();
    }

    // ---------------- stop ----------------

    @Test
    void aStopNeedsTheVersionAndAReasonAndStoresTheReasonWithoutLoggingIt() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        assertThat(msg(problem(post(w.doctor().auth(), stopUrl(id), actBody(1, "reason", "no")), 400), "reason"))
                .isEqualTo("Write why you are stopping this medicine.");
        JsonNode noBody = problem(call(HttpMethod.POST, stopUrl(id), w.doctor().auth(), null), 400);
        assertThat(noBody.get("status").asInt()).isEqualTo(400);
        refused(post(w.doctor().auth(), stopUrl(id), actBody(2, "reason", "Course is complete")), 409, "order-changed", ORDER_CHANGED_TEXT);
        JsonNode row = sheetRow(ok(HttpMethod.POST, stopUrl(id), w.doctor().auth(),
                js(actBody(1, "reason", "Course is complete: zebra-quartz")), 200), id);
        assertThat(row.get("status").asText()).isEqualTo("STOPPED");
        assertThat(row.get("stopReason").asText()).isEqualTo("Course is complete: zebra-quartz");
        assertThat(one(w.clinic(), "select stop_reason from ipd.medicine_order where id = ?::uuid", id)).isEqualTo("Course is complete: zebra-quartz");
        assertThat(noticeRows(w.clinic(), id, "STOPPED")).isOne();
        assertThat(allAuditText(w.clinic())).doesNotContain("zebra-quartz");
        assertThat(allEventText(w.clinic())).doesNotContain("zebra-quartz");
        List<String[]> rows = audit(w.clinic(), "IPD_MED_STOPPED");
        assertThat(rows.get(0)[3]).contains("reasonPresent");
    }

    @Test
    void anOrderOnHoldCanBeStoppedAndTheOpenHoldStaysAsHistory() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(), js(actBody(1, "reason", "Patient is nil by mouth")), 200);
        ok(HttpMethod.POST, stopUrl(id), w.doctor().auth(), js(actBody(1, "reason", "Switch to injection")), 200);
        assertThat(status(w.clinic(), id)).isEqualTo("STOPPED");
        ok(HttpMethod.POST, stopUrl(id), w.doctor().auth(), js(actBody(1, "reason", "Stop it once more")), 200); // the same user: a repeat
        assertThat(noticeRows(w.clinic(), id, "STOPPED")).isOne();
    }

    // ---------------- query and reply ----------------

    @Test
    void aPharmacistQueryMakesTheOrderQueriedNotVerifiableAndTheDoctorsReplySendsItBack() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        JsonNode r = ok(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(),
                js(actBody(1, "question", "Is 650 mg intended for this patient?")), 200);
        assertThat(r.get("result").get("replayed").asBoolean()).isFalse();
        assertThat(r.get("result").get("outcome").asText()).isEqualTo("QUERIED");
        assertThat(status(w.clinic(), id)).isEqualTo("QUERIED");
        JsonNode q = queue(w.pharmacist().auth());
        assertThat(q.get("queried").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode qrow = queueRow(q, id);
        assertThat(qrow.get("status").asText()).isEqualTo("QUERIED");
        assertThat(qrow.get("canVerify").asBoolean()).isFalse();
        assertThat(qrow.get("query").get("question").asText()).isEqualTo("Is 650 mg intended for this patient?");
        String token = qrow.get("check").get("checkToken").asText();
        freshPinAllowance(w.pharmacist());
        refused(call(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pharmacistPin(), token)), 409, "order-state",
                WAITING_FOR_REPLY_TEXT);
        JsonNode docRow = rowOf(w, id);
        assertThat(docRow.get("status").asText()).isEqualTo("QUERIED");
        assertThat(docRow.get("canReply").asBoolean()).isTrue();
        assertThat(docRow.get("query").get("question").asText()).isEqualTo("Is 650 mg intended for this patient?");
        JsonNode replied = sheetRow(ok(HttpMethod.POST, replyUrl(id), w.doctor().auth(),
                js(actBody(1, "reply", "Yes, fever of 39 C.")), 200), id);
        assertThat(replied.get("status").asText()).isEqualTo("PENDING");
        assertThat(replied.get("query").get("reply").asText()).isEqualTo("Yes, fever of 39 C.");
        assertThat(one(w.clinic(), "select closed_by from ipd.medicine_order_query where order_id = ?::uuid", id)).isEqualTo("REPLY");
        verifyOk(w.pharmacist(), w.pharmacistPin(), id);
        assertThat(status(w.clinic(), id)).isEqualTo("ACTIVE");
        assertThat(allAuditText(w.clinic())).doesNotContain("Is 650 mg intended").doesNotContain("fever of 39");
        assertThat(allEventText(w.clinic())).doesNotContain("Is 650 mg intended").doesNotContain("fever of 39");
    }

    @Test
    void theQuestionAndReplyAreValidatedAndTheOrdererCannotQueryHisOwnOrder() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        assertThat(msg(problem(post(w.pharmacist().auth(), queryUrl(id), actBody(1, "question", "   ")), 400), "question"))
                .isEqualTo("Write your question for the doctor.");
        assertThat(msg(problem(post(w.pharmacist().auth(), queryUrl(id), actBody(1, "question", "q".repeat(301))), 400), "question"))
                .isEqualTo("Keep the question to 300 characters.");
        assertThat(msg(problem(post(w.pharmacist().auth(), queryUrl(id), actBody(1, "question", "Bell\u0007 here")), 400), "question"))
                .isEqualTo(TEXT_CHARACTERS_TEXT);
        refused(post(w.pharmacist().auth(), queryUrl(id), actBody(2, "question", "Is it right?")), 409, "order-changed", ORDER_CHANGED_TEXT);
        refused(post(w.doctor().auth(), replyUrl(id), actBody(1, "reply", "A reply to nothing")), 409, "order-state", NO_OPEN_QUERY_TEXT);
        // the owner places an order and may not query it himself
        String owners = orderIdOf(place(w.clinic().owner(), w.adm().id(), "asp"));
        refused(post(w.clinic().owner(), queryUrl(owners), actBody(1, "question", "Is it right?")), 403, "self-verification",
                SELF_QUERY_TEXT);
        ok(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(), js(actBody(1, "question", "Is it right?")), 200);
        assertThat(msg(problem(post(w.doctor().auth(), replyUrl(id), actBody(1, "reply", "")), 400), "reply"))
                .isEqualTo("Write your reply.");
        assertThat(msg(problem(post(w.doctor().auth(), replyUrl(id), actBody(1, "reply", "r".repeat(301))), 400), "reply"))
                .isEqualTo("Keep the reply to 300 characters.");
        refused(post(w.pharmacist().auth(), queryUrl(id), actBody(1, "question", "Asking twice")), 409, "order-state", NOT_WAITING_TEXT);
    }

    @Test
    void repeatsOfQueryAndReplyAreReadFromTheStateAndWriteNothing() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        Map<String, Object> q = actBody(1, "question", "Is it right?");
        ok(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(), js(q), 200);
        String afterQuery = writes(w.clinic(), w.adm().id());
        JsonNode again = ok(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(), js(q), 200);
        assertThat(again.get("result").get("replayed").asBoolean()).isTrue();
        assertThat(writes(w.clinic(), w.adm().id())).isEqualTo(afterQuery);
        Map<String, Object> r = actBody(1, "reply", "Yes, intended.");
        ok(HttpMethod.POST, replyUrl(id), w.doctor().auth(), js(r), 200);
        String afterReply = writes(w.clinic(), w.adm().id());
        ok(HttpMethod.POST, replyUrl(id), w.doctor().auth(), js(r), 200);
        assertThat(writes(w.clinic(), w.adm().id())).isEqualTo(afterReply);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_query where order_id = ?::uuid", id)).isOne();
    }

    @Test
    void aChangeAStopAndARejectEachCloseTheOpenQuery() throws Exception {
        Ward w = ward();
        String a = placed(w, "pcm");
        String b = placed(w, "asp");
        String c = placed(w, "met");
        for (String id : new String[] {a, b, c}) {
            ok(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(), js(actBody(1, "question", "Is it right?")), 200);
        }
        ok(HttpMethod.POST, changeUrl(a), w.doctor().auth(), js(changeBody(1, "Dose was entered wrongly", 2)), 200);
        ok(HttpMethod.POST, stopUrl(b), w.doctor().auth(), js(actBody(1, "reason", "Not needed any more")), 200);
        rejectOk(w.pharmacist().auth(), c, "WRONG_DOSE");
        assertThat(one(w.clinic(), "select closed_by from ipd.medicine_order_query where order_id = ?::uuid", a)).isEqualTo("CHANGE");
        assertThat(one(w.clinic(), "select closed_by from ipd.medicine_order_query where order_id = ?::uuid", b)).isEqualTo("STOP");
        assertThat(one(w.clinic(), "select closed_by from ipd.medicine_order_query where order_id = ?::uuid", c)).isEqualTo("REJECT");
        assertThat(status(w.clinic(), c)).isEqualTo("REJECTED");
        assertThat(status(w.clinic(), a)).isEqualTo("PENDING");
    }

    // ---------------- verify and reject with the version ----------------

    @Test
    void verifyNeedsOrderVersionAndWritesTheNewNoticeOnlyThen() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        freshPinAllowance(w.pharmacist());
        JsonNode p = problem(call(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(),
                "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + token + "\"}"), 400);
        assertThat(msg(p, "orderVersion")).isEqualTo(RELOAD_TEXT);
        freshPinAllowance(w.pharmacist());
        refused(call(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(),
                "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + token + "\",\"orderVersion\":2}"), 409, "order-changed",
                ORDER_CHANGED_TEXT);
        assertThat(status(w.clinic(), id)).isEqualTo("PENDING");
        assertThat(noticeRows(w.clinic(), id)).isZero();
        verifyOk(w.pharmacist(), w.pharmacistPin(), id);
        assertThat(noticeRows(w.clinic(), id, "NEW")).isOne();
        JsonNode docRow = rowOf(w, id);
        assertThat(docRow.get("status").asText()).isEqualTo("ACTIVE");
    }

    // ---------------- paging ----------------

    @Test
    void thePharmacistQueueIsPagedByKeysetAndRefusesABadLimitOrCursor() throws Exception {
        Ward w = ward();
        String a = placed(w, "pcm");
        String b = placed(w, "asp");
        String c = placed(w, "met");
        JsonNode p1 = queue(w.pharmacist().auth(), "limit=2");
        assertThat(p1.get("items")).hasSize(2);
        assertThat(p1.get("nextCursor").isNull()).isFalse();
        assertThat(p1.get("waiting").asInt()).isEqualTo(3);
        JsonNode p2 = queue(w.pharmacist().auth(), "limit=2&cursor=" + p1.get("nextCursor").asText());
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.get("nextCursor").isNull()).isTrue();
        List<String> seen = new ArrayList<>();
        p1.get("items").forEach(i -> seen.add(i.get("orderId").asText()));
        p2.get("items").forEach(i -> seen.add(i.get("orderId").asText()));
        assertThat(seen).containsExactly(a, b, c);
        for (String bad : new String[] {"limit=0", "limit=101", "cursor=%25%25", "cursor=" + "a".repeat(65)}) {
            problem(call(HttpMethod.GET, CHECKS + "?" + bad, w.pharmacist().auth(), null), 400);
            problem(call(HttpMethod.GET, REVIEWS + "?" + bad, w.pharmacist().auth(), null), 400);
        }
    }

    // ---------------- review after dose ----------------

    @Test
    void aStatDoseGivenBeforeTheCheckIsListedForReviewAndMarkReviewedClosesIt() throws Exception {
        Ward w = ward();
        String id = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201));
        Instant slot = firstSlot(w, id);
        JsonNode dose = doseOk(w.nurse().auth(), id, given(slot, w.uhid()));
        assertThat(dose.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(count(w.clinic(), "select count(*) from ipd.pharmacist_review where order_id = ?::uuid and closed_at is null", id))
                .isOne();
        JsonNode page = ok(HttpMethod.GET, REVIEWS, w.pharmacist().auth(), null, 200);
        assertThat(page.get("open").asInt()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("orderId").asText()).isEqualTo(id);
        assertThat(item.get("orderStatus").asText()).isEqualTo("PENDING");
        assertThat(item.get("canMarkReviewed").asBoolean()).isTrue();
        assertThat(queue(w.pharmacist().auth()).get("reviewsOpen").asInt()).isEqualTo(1);
        JsonNode after = ok(HttpMethod.POST, markReviewedUrl(item.get("id").asText()), w.pharmacist().auth(), null, 200);
        assertThat(after.get("open").asInt()).isZero();
        assertThat(one(w.clinic(), "select outcome from ipd.pharmacist_review where order_id = ?::uuid", id)).isEqualTo("REVIEWED");
        // a repeat by the same user writes nothing; another user is told it is closed
        ok(HttpMethod.POST, markReviewedUrl(item.get("id").asText()), w.pharmacist().auth(), null, 200);
        refused(call(HttpMethod.POST, markReviewedUrl(item.get("id").asText()), w.clinic().owner(), null), 409, "order-state", null);
        assertThat(audit(w.clinic(), "IPD_REVIEW_CLOSED")).hasSize(1);
    }

    @Test
    void verifyingTheOrderClosesItsOpenReviewAsVerified() throws Exception {
        Ward w = ward();
        String id = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201));
        doseOk(w.nurse().auth(), id, given(firstSlot(w, id), w.uhid()));
        assertThat(ok(HttpMethod.GET, REVIEWS, w.pharmacist().auth(), null, 200).get("open").asInt()).isEqualTo(1);
        verifyOk(w.pharmacist(), w.pharmacistPin(), id);
        assertThat(one(w.clinic(), "select outcome from ipd.pharmacist_review where order_id = ?::uuid", id)).isEqualTo("VERIFIED");
        assertThat(ok(HttpMethod.GET, REVIEWS, w.pharmacist().auth(), null, 200).get("open").asInt()).isZero();
    }

    @Test
    void rejectingTheOrderClosesItsOpenReviewAsRejected() throws Exception {
        Ward w = ward();
        String id = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201));
        doseOk(w.nurse().auth(), id, given(firstSlot(w, id), w.uhid()));
        rejectOk(w.pharmacist().auth(), id, "WRONG_DOSE");
        assertThat(one(w.clinic(), "select outcome from ipd.pharmacist_review where order_id = ?::uuid", id)).isEqualTo("REJECTED");
    }

    // ---------------- tenant isolation ----------------

    @Test
    void anotherClinicsOrdersNoticesAndReviewsAreInvisibleAndAnswerNotFound() throws Exception {
        Ward a = ward();
        Ward b = ward();
        String id = active(a, "pcm");
        String statId = orderIdOf(ok(HttpMethod.POST, ordersUrl(a.adm().id()), a.doctor().auth(), js(statBody("ondan")), 201));
        doseOk(a.nurse().auth(), statId, given(firstSlot(a, statId), a.uhid()));
        String noticeId = one(a.clinic(), "select id::text from ipd.order_notice where medicine_order_id = ?::uuid", id);
        String reviewId = one(a.clinic(), "select id::text from ipd.pharmacist_review where order_id = ?::uuid", statId);
        String writes = writes(a.clinic(), a.adm().id());
        refused(post(b.doctor().auth(), changeUrl(id), changeBody(1, "Raise the dose now", 2)), 404, "not-found", null);
        refused(post(b.doctor().auth(), holdUrl(id), actBody(1, "reason", "Patient is nil by mouth")), 404, "not-found", null);
        refused(post(b.doctor().auth(), resumeUrl(id), actBody(1, null, null)), 404, "not-found", null);
        refused(post(b.doctor().auth(), stopUrl(id), actBody(1, "reason", "Not needed any more")), 404, "not-found", null);
        refused(post(b.doctor().auth(), replyUrl(id), actBody(1, "reply", "A reply to nothing")), 404, "not-found", null);
        refused(post(b.pharmacist().auth(), queryUrl(id), actBody(1, "question", "Is it right?")), 404, "not-found", null);
        refused(call(HttpMethod.POST, markReviewedUrl(reviewId), b.pharmacist().auth(), null), 404, "not-found", null);
        refused(call(HttpMethod.POST, noticeAckUrl(noticeId), b.nurse().auth(), null), 404, "not-found", null);
        refused(call(HttpMethod.GET, noticesUrl(a.adm().id()), b.nurse().auth(), null), 404, "not-found", null);
        assertThat(ok(HttpMethod.GET, REVIEWS, b.pharmacist().auth(), null, 200).get("open").asInt()).isZero();
        assertThat(queue(b.pharmacist().auth()).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, NOTICES_ALL, b.nurse().auth(), null, 200).get("items")).isEmpty();
        assertThat(writes(a.clinic(), a.adm().id())).isEqualTo(writes);
    }

    // ---------------- privacy ----------------

    @Test
    void noClinicalTextOfTheNewActionsReachesTheAuditTrailOrTheEventLog() throws Exception {
        Ward w = ward();
        Map<String, Object> b = newOrderBody("pcm");
        b.put("indication", "walrus-indication");
        String id = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(b), 201));
        Map<String, Object> ch = changeBody(1, "walrus-change-reason", 2);
        ch.put("indication", "walrus-indication-two");
        ok(HttpMethod.POST, changeUrl(id), w.doctor().auth(), js(ch), 200);
        ok(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(), js(actBody(2, "question", "walrus-question")), 200);
        ok(HttpMethod.POST, replyUrl(id), w.doctor().auth(), js(actBody(2, "reply", "walrus-reply")), 200);
        verifyOkVersion(w, id, 2);
        ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(), js(actBody(2, "reason", "walrus-hold")), 200);
        ok(HttpMethod.POST, stopUrl(id), w.doctor().auth(), js(actBody(2, "reason", "walrus-stop")), 200);
        String audit = allAuditText(w.clinic());
        String events = allEventText(w.clinic());
        for (String text : new String[] {"walrus-indication", "walrus-change-reason", "walrus-question", "walrus-reply", "walrus-hold",
                "walrus-stop"}) {
            assertThat(audit).as("audit has " + text).doesNotContain(text);
            assertThat(events).as("events have " + text).doesNotContain(text);
        }
        assertThat(audit).contains("IPD_MED_CHANGED", "IPD_MED_QUERIED", "IPD_MED_QUERY_REPLIED", "IPD_MED_HELD", "IPD_MED_STOPPED");
        // the clinical text is stored and shown to clinical roles only
        assertThat(one(w.clinic(), "select question from ipd.medicine_order_query where order_id = ?::uuid", id)).isEqualTo("walrus-question");
    }

    private void verifyOkVersion(Ward w, String id, int version) throws Exception {
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        freshPinAllowance(w.pharmacist());
        ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + token
                + "\",\"orderVersion\":" + version + "}", 200);
    }
}
