package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76 WP2 (story 76-3): order notices, the Orders tab list of one patient, the ward-wide list, the counts-only summary and
 * acknowledge. Contract: docs/api/ipd.yaml 0.4.1 (/admissions/{id}/order-notices, /order-notices, /order-notices/summary,
 * /order-notices/{id}/acknowledge); ADR-027 D5. Test plan: docs/test-plans/SAH-76.md.
 */
@OnTestDatabase
@WithPinPepper
class Sah76NoticesIT extends Sah76Support {

    private String noticeIdOf(Ward w, String orderId, String kind) throws Exception {
        return one(w.clinic(), "select id::text from ipd.order_notice where medicine_order_id = ?::uuid and kind = ? "
                + "order by created_at desc limit 1", orderId, kind);
    }

    @Test
    void theOrdersTabListsAVerifiedOrderAsNewWithItsDetailsAndTheCounts() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        JsonNode list = noticesOf(w.nurse().auth(), w.adm().id());
        assertThat(list.get("open").get("count").asInt()).isOne();
        assertThat(list.get("open").get("stat").asInt()).isZero();
        assertThat(list.get("canAcknowledge").asBoolean()).isTrue();
        JsonNode n = noticesOfKind(list, "NEW").get(0);
        assertThat(n.get("orderId").asText()).isEqualTo(id);
        assertThat(n.get("orderVersion").asInt()).isOne();
        assertThat(n.get("drugName").asText()).isEqualTo(drugName("pcm"));
        assertThat(n.get("details").get("doseUnit").asText()).isEqualTo("TABLET");
        assertThat(n.get("acknowledgedAt").isNull()).isTrue();
        assertThat(n.get("canAcknowledge").asBoolean()).isTrue();
        assertThat(n.get("overdue").asBoolean()).isFalse();
        assertThat(n.get("stat").isNull()).isTrue();
        assertThat(accessRows(w.clinic(), w.adm())).as("opening the list is recorded").isGreaterThanOrEqualTo(1);
    }

    @Test
    void aStatNoticeShowsTheStatLineAndSortsBeforeOtherNotices() throws Exception {
        Ward w = ward();
        active(w, "pcm");
        String stat = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201));
        JsonNode list = noticesOf(w.nurse().auth(), w.adm().id());
        assertThat(list.get("open").get("count").asInt()).isEqualTo(2);
        assertThat(list.get("open").get("stat").asInt()).isOne();
        JsonNode first = list.get("items").get(0);
        assertThat(first.get("orderId").asText()).as("STAT first").isEqualTo(stat);
        assertThat(first.get("priority").asText()).isEqualTo("STAT");
        assertThat(first.get("stat").get("windowMinutes").asInt()).isEqualTo(15);
        assertThat(first.get("stat").get("dueBy").isNull()).isFalse();
    }

    @Test
    void changeHoldResumeAndStopEachWriteTheirOwnNotice() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        ok(HttpMethod.POST, changeUrl(id), w.doctor().auth(), js(changeBody(1, "Fever is not settling", 2)), 200);
        verifyOkVersion(w, id, 2);
        ok(HttpMethod.POST, holdUrl(id), w.doctor().auth(), js(actBody(2, "reason", "Patient is nil by mouth")), 200);
        ok(HttpMethod.POST, resumeUrl(id), w.doctor().auth(), js(actBody(2, null, null)), 200);
        ok(HttpMethod.POST, stopUrl(id), w.doctor().auth(), js(actBody(2, "reason", "Course is complete")), 200);
        JsonNode list = noticesOf(w.nurse().auth(), w.adm().id());
        for (String kind : new String[] {"NEW", "CHANGED", "HELD", "RESUMED", "STOPPED"}) {
            assertThat(noticesOfKind(list, kind)).as(kind).hasSizeGreaterThanOrEqualTo(1);
        }
        JsonNode changed = noticesOfKind(list, "CHANGED").get(0);
        assertThat(changed.get("orderVersion").asInt()).isEqualTo(2);
        assertThat(changed.get("changes").toString()).contains("\"field\":\"dose\"");
        assertThat(changed.get("reason").asText()).isEqualTo("Fever is not settling");
        // version 2 verified: its own NEW notice is not written again (a change already has CHANGED)
        assertThat(noticeRows(w.clinic(), id, "CHANGED")).isOne();
    }

    @Test
    void aNurseAcknowledgesOnceARepeatIsQuietAndAnotherNurseOrADoctorIsRefused() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        String noticeId = noticeIdOf(w, id, "NEW");
        refused(call(HttpMethod.POST, noticeAckUrl(noticeId), w.doctor().auth(), null), 403, "forbidden", ACK_ONLY_TEXT);
        refused(call(HttpMethod.POST, noticeAckUrl(noticeId), w.pharmacist().auth(), null), 403, "forbidden", ACK_ONLY_TEXT);
        JsonNode after = ok(HttpMethod.POST, noticeAckUrl(noticeId), w.nurse().auth(), null, 200);
        assertThat(after.get("open").get("count").asInt()).isZero();
        JsonNode done = noticesOfKind(after, "NEW").get(0);
        assertThat(done.get("acknowledgedAt").isNull()).isFalse();
        assertThat(done.get("acknowledgedByName").asText()).isEqualTo(displayName(w.nurse()));
        assertThat(one(w.clinic(), "select acknowledged_via from ipd.order_notice where id = ?::uuid", noticeId)).isEqualTo("TAP");
        String writes = writes(w.clinic(), w.adm().id());
        ok(HttpMethod.POST, noticeAckUrl(noticeId), w.nurse().auth(), null, 200);
        assertThat(writes(w.clinic(), w.adm().id())).as("a repeat writes nothing").isEqualTo(writes);
        JsonNode p = problem(call(HttpMethod.POST, noticeAckUrl(noticeId), w.nurse2().auth(), null), 409);
        assertType(p, "order-state");
        assertThat(p.get("detail").asText()).isEqualTo(displayName(w.nurse()) + " already acknowledged this.");
        assertThat(audit(w.clinic(), "IPD_ORDER_NOTICE_ACKNOWLEDGED")).hasSize(1);
        assertThat(allAuditText(w.clinic())).doesNotContain(displayName(w.nurse()));
    }

    @Test
    void theOwnerMayAcknowledgeAndTheDoctorSeesTheListButCannotAcknowledge() throws Exception {
        Ward w = ward();
        String id = active(w, "pcm");
        JsonNode doctorView = noticesOf(w.doctor().auth(), w.adm().id());
        assertThat(doctorView.get("canAcknowledge").asBoolean()).isFalse();
        assertThat(noticesOfKind(doctorView, "NEW").get(0).get("canAcknowledge").asBoolean()).isFalse();
        ok(HttpMethod.POST, noticeAckUrl(noticeIdOf(w, id, "NEW")), w.clinic().owner(), null, 200);
        assertThat(noticesOf(w.clinic().owner(), w.adm().id()).get("open").get("count").asInt()).isZero();
    }

    @Test
    void theOrderListIsForNursesOwnerAndDoctorsOnly() throws Exception {
        Ward w = ward();
        active(w, "pcm");
        for (var auth : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {w.nurse().auth(),
                w.doctor().auth(), w.clinic().owner()}) {
            ok(HttpMethod.GET, noticesUrl(w.adm().id()), auth, null, 200);
            ok(HttpMethod.GET, NOTICES_SUMMARY, auth, null, 200);
        }
        Staff front = staff(w.clinic(), "FRONT_DESK");
        Staff billing = staff(w.clinic(), "BILLING");
        for (var auth : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {w.pharmacist().auth(), front.auth(),
                billing.auth()}) {
            refused(call(HttpMethod.GET, noticesUrl(w.adm().id()), auth, null), 403, "forbidden", LIST_HIDDEN_TEXT);
            refused(call(HttpMethod.GET, NOTICES_SUMMARY, auth, null), 403, "forbidden", null);
            refused(call(HttpMethod.GET, NOTICES_ALL, auth, null), 403, "forbidden", null);
        }
        refused(call(HttpMethod.GET, NOTICES_ALL, w.doctor().auth(), null), 403, "forbidden", null);
        ok(HttpMethod.GET, NOTICES_ALL, w.nurse().auth(), null, 200);
        ok(HttpMethod.GET, NOTICES_ALL, w.clinic().owner(), null, 200);
    }

    @Test
    void theSummaryHoldsCountsOnlyAndItsChangeTokenMovesWithEachNotice() throws Exception {
        Ward w = ward();
        JsonNode empty = ok(HttpMethod.GET, NOTICES_SUMMARY, w.nurse().auth(), null, 200);
        assertThat(empty.get("total").get("count").asInt()).isZero();
        String t0 = empty.get("changeToken").asText();
        String id = active(w, "pcm");
        JsonNode one = ok(HttpMethod.GET, NOTICES_SUMMARY, w.nurse().auth(), null, 200);
        assertThat(one.get("total").get("count").asInt()).isOne();
        assertThat(one.get("byAdmission")).hasSize(1);
        assertThat(one.get("byAdmission").get(0).get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(one.get("changeToken").asText()).isNotEqualTo(t0);
        Set<String> keys = new TreeSet<>(keys(one));
        assertThat(keys).containsExactlyInAnyOrder("asOf", "total", "byAdmission", "changeToken");
        String text = one.toString();
        assertThat(text).doesNotContain(w.adm().name()).doesNotContain("Paracetamol").doesNotContain(id.substring(0, 8));
        ok(HttpMethod.POST, noticeAckUrl(noticeIdOf(w, id, "NEW")), w.nurse().auth(), null, 200);
        assertThat(ok(HttpMethod.GET, NOTICES_SUMMARY, w.nurse().auth(), null, 200).get("total").get("count").asInt()).isZero();
        assertThat(writesNothing(w)).isTrue();
    }

    private boolean writesNothing(Ward w) throws Exception {
        long before = recordAccess(w.clinic(), w.adm().patientId());
        ok(HttpMethod.GET, NOTICES_SUMMARY, w.nurse().auth(), null, 200);
        return recordAccess(w.clinic(), w.adm().patientId()) == before;
    }

    @Test
    void theWardWideListIsPagedStatFirstAndWritesOneAccessRowPerPatientOfThePage() throws Exception {
        Ward w = ward();
        verificationRequired(w.clinic(), false);
        List<String> orders = new ArrayList<>();
        for (String d : new String[] {"pcm", "asp", "met"}) {
            orders.add(orderIdOf(place(w.doctor().auth(), w.adm().id(), d)));
        }
        String stat = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201));
        long access = recordAccess(w.clinic(), w.adm().patientId());
        JsonNode p1 = ok(HttpMethod.GET, NOTICES_ALL + "?limit=3", w.nurse().auth(), null, 200);
        assertThat(p1.get("items")).hasSize(3);
        assertThat(p1.get("items").get(0).get("notice").get("orderId").asText()).isEqualTo(stat);
        assertThat(p1.get("nextCursor").isNull()).isFalse();
        assertThat(p1.get("open").get("count").asInt()).isEqualTo(4);
        assertThat(recordAccess(w.clinic(), w.adm().patientId())).isEqualTo(access + 1);
        JsonNode p2 = ok(HttpMethod.GET, NOTICES_ALL + "?limit=3&cursor=" + p1.get("nextCursor").asText(), w.nurse().auth(), null, 200);
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.get("nextCursor").isNull()).isTrue();
        List<String> seen = new ArrayList<>();
        p1.get("items").forEach(i -> seen.add(i.get("notice").get("orderId").asText()));
        p2.get("items").forEach(i -> seen.add(i.get("notice").get("orderId").asText()));
        assertThat(seen).containsExactlyInAnyOrder(orders.get(0), orders.get(1), orders.get(2), stat);
        assertThat(p1.get("items").get(0).get("patientName").asText()).isEqualTo(w.adm().name());
        for (String bad : new String[] {"limit=0", "limit=101", "limit=abc", "cursor=%25%25", "cursor=" + "a".repeat(65)}) {
            problem(call(HttpMethod.GET, NOTICES_ALL + "?" + bad, w.nurse().auth(), null), 400);
        }
    }

    @Test
    void aDoseForTheOrderAcknowledgesItsOpenNoticesWithViaDose() throws Exception {
        Ward w = ward();
        String stat = orderIdOf(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(statBody("ondan")), 201));
        assertThat(noticesOf(w.nurse().auth(), w.adm().id()).get("open").get("count").asInt()).isOne();
        doseOk(w.nurse().auth(), stat, given(firstSlot(w, stat), w.uhid()));
        assertThat(noticesOf(w.nurse().auth(), w.adm().id()).get("open").get("count").asInt()).isZero();
        assertThat(one(w.clinic(), "select acknowledged_via from ipd.order_notice where medicine_order_id = ?::uuid", stat))
                .isEqualTo("DOSE");
    }

    @Test
    void theAcknowledgeRefusesBadIdsAndUnknownNotices() throws Exception {
        Ward w = ward();
        problem(call(HttpMethod.POST, NOTICES_ALL + "/not-a-uuid/acknowledge", w.nurse().auth(), null), 400);
        refused(call(HttpMethod.POST, noticeAckUrl(uuid()), w.nurse().auth(), null), 404, "not-found", null);
        problem(call(HttpMethod.GET, ADMISSIONS + "/not-a-uuid/order-notices", w.nurse().auth(), null), 400);
        refused(call(HttpMethod.GET, noticesUrl(uuid()), w.nurse().auth(), null), 404, "not-found", null);
    }

    // ---------------- scope guard (SAH-34) ----------------

    @Test
    void aWardScopedNurseSeesAndAcknowledgesOnlyTheNoticesOfHerOwnWard() throws Exception {
        Hosp h = hosp();
        String inA = orderIdOf(place(h.clinic().owner(), h.inA().id(), "pcm"));
        String inB = orderIdOf(place(h.clinic().owner(), h.inB().id(), "pcm"));
        Pn ph = newPn(h.clinic(), "PHARMACIST");
        String pin = goodPin(ph);
        setPin(ph, pin);
        // the owner placed the orders, so the pharmacist (not the orderer) verifies both
        verifyOk(ph, pin, inA);
        verifyOk(ph, pin, inB);
        String noticeA = one(h.clinic(), "select id::text from ipd.order_notice where medicine_order_id = ?::uuid", inA);
        String noticeB = one(h.clinic(), "select id::text from ipd.order_notice where medicine_order_id = ?::uuid", inB);
        JsonNode list = ok(HttpMethod.GET, NOTICES_ALL, h.nurseA().auth(), null, 200);
        List<String> orderIds = new ArrayList<>();
        list.get("items").forEach(i -> orderIds.add(i.get("notice").get("orderId").asText()));
        assertThat(orderIds).containsExactly(inA);
        assertThat(ok(HttpMethod.GET, NOTICES_SUMMARY, h.nurseA().auth(), null, 200).get("total").get("count").asInt()).isOne();
        outside(call(HttpMethod.GET, noticesUrl(h.inB().id()), h.nurseA().auth(), null));
        outside(call(HttpMethod.POST, noticeAckUrl(noticeB), h.nurseA().auth(), null));
        assertThat(one(h.clinic(), "select (acknowledged_at is null)::text from ipd.order_notice where id = ?::uuid", noticeB))
                .isEqualTo("true");
        ok(HttpMethod.GET, noticesUrl(h.inA().id()), h.nurseA().auth(), null, 200);
        ok(HttpMethod.POST, noticeAckUrl(noticeA), h.nurseA().auth(), null, 200);
        // the owner and the doctor are never narrowed
        ok(HttpMethod.GET, noticesUrl(h.inB().id()), h.clinic().owner(), null, 200);
        assertThat(ok(HttpMethod.GET, NOTICES_ALL, h.clinic().owner(), null, 200).get("items")).hasSize(1);
    }

    private void verifyOkVersion(Ward w, String id, int version) throws Exception {
        String token = tokenOfOrder(w.pharmacist().auth(), id);
        freshPinAllowance(w.pharmacist());
        ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + token
                + "\",\"orderVersion\":" + version + "}", 200);
    }
}
