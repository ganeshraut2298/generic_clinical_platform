package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76 two users at the same moment (ADR-027 D1: first writer wins through the admission row lock and the orderVersion check).
 * The forced-order technique of {@link MedicineSupport#ordered}: a second connection holds the admission row, the requests
 * are started one after the other and each is seen waiting, then the lock is released. No answer may be a server error.
 */
@OnTestDatabase
@WithPinPepper
class Sah76OrdersConcurrencyIT extends Sah76Support {

    private Req change(Ward w, org.springframework.test.web.servlet.request.RequestPostProcessor auth, String id, int version,
                       Object dose) throws Exception {
        return new Req(HttpMethod.POST, changeUrl(id), auth, js(changeBody(version, "Fever is not settling", dose)));
    }

    @Test
    void twoDoctorsChangingTheSameVersionOneWinsAndTheOtherIsToldItChanged() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        List<Resp> r = ordered(w.clinic(), w.adm().id(), change(w, w.doctor().auth(), id, 1, 2),
                change(w, w.clinic().owner(), id, 1, 3));
        assertThat(r.get(0).status()).isEqualTo(200);
        assertThat(r.get(1).status()).isEqualTo(409);
        assertThat(r.get(1).type(objectMapper)).isEqualTo("order-changed");
        assertThat(r.get(1).detail(objectMapper)).isEqualTo(ORDER_CHANGED_TEXT);
        assertThat(versions(w.clinic(), id)).isEqualTo(2);
        assertThat(one(w.clinic(), "select dose_amount::text from ipd.medicine_order_version where order_id = ?::uuid and version_no = 2",
                id)).isEqualTo("2.000");
        assertThat(noticeRows(w.clinic(), id, "CHANGED")).isOne();
    }

    @Test
    void twoChangesStartedTogetherNeverMakeTwoVersionTwoRowsOrAServerError() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        List<Resp> r = parallel(change(w, w.doctor().auth(), id, 1, 2), change(w, w.clinic().owner(), id, 1, 3));
        List<Integer> statuses = new ArrayList<>(List.of(r.get(0).status(), r.get(1).status()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(versions(w.clinic(), id)).isEqualTo(2);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_version where order_id = ?::uuid and superseded_at is null",
                id)).isOne();
    }

    @Test
    void aChangeAheadOfTheVerifyMakesTheVerifyTellTheVersionMoved() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        List<Resp> r = ordered(w.clinic(), w.adm().id(), change(w, w.doctor().auth(), id, 1, 2),
                verifyReq(w.pharmacist(), id, w.pharmacistPin(), token));
        assertThat(r.get(0).status()).isEqualTo(200);
        assertThat(r.get(1).status()).isEqualTo(409);
        assertThat(r.get(1).type(objectMapper)).isEqualTo("order-changed");
        assertThat(status(w.clinic(), id)).isEqualTo("PENDING");
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_version where order_id = ?::uuid and verified_by is not null",
                id)).isZero();
    }

    @Test
    void aVerifyAheadOfTheChangeLetsTheChangeThroughAndTheNewVersionWaitsForTheCheck() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        List<Resp> r = ordered(w.clinic(), w.adm().id(), verifyReq(w.pharmacist(), id, w.pharmacistPin(), token),
                change(w, w.doctor().auth(), id, 1, 2));
        assertThat(r.get(0).status()).isEqualTo(200);
        assertThat(r.get(1).status()).isEqualTo(200);
        assertThat(status(w.clinic(), id)).isEqualTo("PENDING");
        assertThat(versions(w.clinic(), id)).isEqualTo(2);
        assertThat(one(w.clinic(), "select (verified_by is not null)::text from ipd.medicine_order_version where order_id = ?::uuid "
                + "and version_no = 1", id)).isEqualTo("true");
    }

    @Test
    void holdAheadOfStopAndStopAheadOfHoldGiveTheRightAnswers() throws Exception {
        Ward w = ward();
        String a = active(w, "pcm");
        List<Resp> r = ordered(w.clinic(), w.adm().id(),
                new Req(HttpMethod.POST, holdUrl(a), w.doctor().auth(), js(actBody(1, "reason", "Patient is nil by mouth"))),
                new Req(HttpMethod.POST, stopUrl(a), w.clinic().owner(), js(actBody(1, "reason", "Course is complete"))));
        assertThat(r.get(0).status()).isEqualTo(200);
        assertThat(r.get(1).status()).isEqualTo(200);
        assertThat(status(w.clinic(), a)).isEqualTo("STOPPED");
        String b = active(w, "asp");
        List<Resp> s = ordered(w.clinic(), w.adm().id(),
                new Req(HttpMethod.POST, stopUrl(b), w.doctor().auth(), js(actBody(1, "reason", "Course is complete"))),
                new Req(HttpMethod.POST, holdUrl(b), w.clinic().owner(), js(actBody(1, "reason", "Patient is nil by mouth"))));
        assertThat(s.get(0).status()).isEqualTo(200);
        assertThat(s.get(1).status()).isEqualTo(409);
        assertThat(s.get(1).type(objectMapper)).isEqualTo("order-state");
        assertThat(count(w.clinic(), "select count(*) from ipd.order_hold where medicine_order_id = ?::uuid", b)).isZero();
    }

    @Test
    void twoQueriesAndTwoRepliesAtTheSameTimeAcceptOnlyTheFirst() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        List<Resp> q = ordered(w.clinic(), w.adm().id(),
                new Req(HttpMethod.POST, queryUrl(id), w.pharmacist().auth(), js(actBody(1, "question", "Is it right?"))),
                new Req(HttpMethod.POST, queryUrl(id), w.clinic().owner(), js(actBody(1, "question", "Is it really right?"))));
        assertThat(q.get(0).status()).isEqualTo(200);
        assertThat(q.get(1).status()).isEqualTo(409);
        assertThat(q.get(1).detail(objectMapper)).isEqualTo(NOT_WAITING_TEXT);
        assertThat(count(w.clinic(), "select count(*) from ipd.medicine_order_query where order_id = ?::uuid", id)).isOne();
        List<Resp> p = ordered(w.clinic(), w.adm().id(),
                new Req(HttpMethod.POST, replyUrl(id), w.doctor().auth(), js(actBody(1, "reply", "Yes it is."))),
                new Req(HttpMethod.POST, replyUrl(id), w.clinic().owner(), js(actBody(1, "reply", "Yes it really is."))));
        assertThat(p.get(0).status()).isEqualTo(200);
        assertThat(p.get(1).status()).isEqualTo(409);
        assertThat(p.get(1).detail(objectMapper)).isEqualTo(NO_OPEN_QUERY_TEXT);
        assertThat(one(w.clinic(), "select reply from ipd.medicine_order_query where order_id = ?::uuid", id)).isEqualTo("Yes it is.");
    }

    @Test
    void twoNursesAcknowledgingTheSameNoticeOnlyTheFirstIsRecorded() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        String notice = one(w.clinic(), "select id::text from ipd.order_notice where medicine_order_id = ?::uuid", id);
        List<Resp> r = ordered(w.clinic(), w.adm().id(), new Req(HttpMethod.POST, noticeAckUrl(notice), w.nurse().auth(), null),
                new Req(HttpMethod.POST, noticeAckUrl(notice), w.nurse2().auth(), null));
        assertThat(r.get(0).status()).isEqualTo(200);
        assertThat(r.get(1).status()).isEqualTo(409);
        assertThat(r.get(1).detail(objectMapper)).isEqualTo(displayName(w.nurse()) + " already acknowledged this.");
        assertThat(audit(w.clinic(), "IPD_ORDER_NOTICE_ACKNOWLEDGED")).hasSize(1);
        JsonNode list = noticesOf(w.nurse().auth(), w.adm().id());
        assertThat(list.get("open").get("count").asInt()).isZero();
    }
}
