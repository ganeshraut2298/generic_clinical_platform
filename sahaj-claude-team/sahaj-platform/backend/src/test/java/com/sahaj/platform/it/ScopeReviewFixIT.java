package com.sahaj.platform.it;

import static com.sahaj.platform.it.RealSql.EVENT_REPO;
import static com.sahaj.platform.it.RealSql.bind;
import static com.sahaj.platform.it.RealSql.constant;
import static com.sahaj.platform.it.RealSql.firstColumn;
import static com.sahaj.platform.it.RealSql.params;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-34 review-fix cases (security and code review, 10 Nov 2026) on the real {@code sahaj_test} database:
 * <ul>
 *   <li>AC 79: the OWNER's Safety change token carries one line {@code bg:<count>:<newest unreviewed grant id>} while a grant is
 *       unreviewed and none at 0; DOCTOR and NURSE tokens never carry it; the token is worked out here from the rule, not read
 *       from the application;</li>
 *   <li>AC 81 and design 1.7: a scoped nurse with no ward and no grant gets exactly her own entries, the rows the old
 *       statement gives, with the same paging;</li>
 *   <li>AC 14 (code review finding 3): for an order or an escalation of a discharged admission a nurse outside the scope gets
 *       403 {@code outside-scope}, never 409 or 404;</li>
 *   <li>AC 80: a discharged admission is read only by the nurses of the ward of its last bed, never through a grant.</li>
 * </ul>
 * The picker's audit row and the IPD Off order are in {@code EmergencyAccessIT}. Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class ScopeReviewFixIT extends ScopeSupport {

    // ---------------- AC 79: the owner's change token ----------------

    /** The token of the rule: escalation lines "id:version" of the not closed escalations of admitted patients, plus the bg line. */
    private String expectedOwnerToken(Clinic clinic) throws Exception {
        List<String> lines = new ArrayList<>(column(clinic, "select lower(e.id::text) || ':' || e.version::text "
                + "from ipd.ews_escalation e join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id "
                + "where e.status in ('OPEN', 'ACK') and a.status = 'ADMITTED'"));
        long waiting = count(clinic, "select count(*) from ipd.emergency_access_grant where reviewed_at is null");
        if (waiting > 0) {
            String newest = values(clinic, "select lower(id::text) from ipd.emergency_access_grant where reviewed_at is null "
                    + "order by opened_at desc, id desc limit 1").get(0);
            lines.add("bg:" + waiting + ":" + newest);
        }
        Collections.sort(lines);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
    }

    /** The token on the board, the summary and the banner of the admitted list: the three must agree. */
    private String tokenOf(RequestPostProcessor auth) throws Exception {
        List<String> all = new ArrayList<>();
        all.add(boardOf(auth).get("counts").get("changeToken").asText());
        all.add(summaryOf(auth).get("changeToken").asText());
        all.add(ok(HttpMethod.GET, ADMISSIONS, auth, null, 200).get("safety").get("counts").get("changeToken").asText());
        assertThat(all).as("board, summary and banner carry one token").containsOnly(all.get(0));
        assertThat(all.get(0)).matches("[A-Za-z0-9_-]{22}");
        return all.get(0);
    }

    private void review(Clinic clinic, String grantId) throws Exception {
        ok(HttpMethod.POST, EA + "/" + grantId + "/review", clinic.owner(), null, 200);
    }

    @Test
    void theOwnerTokenGainsOneBgLineWithTheCountAndTheNewestIdWhileAGrantIsUnreviewedAndHasNoneAtZero() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Adm second = admitInNamed(clinic, h.wardB(), "Chand Second-B");
        Staff doctor = staff(clinic, "DOCTOR");
        raise(clinic, h.inB(), high7());
        String plain = expectedChangeToken(clinic); // the rule without the bg line: the escalation of ward B

        // 0 unreviewed: no line, so the owner token equals the doctor token and the old rule
        assertThat(tokenOf(clinic.owner())).isEqualTo(plain).isEqualTo(expectedOwnerToken(clinic));
        assertThat(tokenOf(doctor.auth())).isEqualTo(plain);
        assertThat(boardOf(clinic.owner()).get("counts").get("emergencyAccessWaiting").asInt()).isZero();

        // one grant: count 1 and its id
        JsonNode g1 = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String one = tokenOf(clinic.owner());
        assertThat(one).isNotEqualTo(plain).isEqualTo(expectedOwnerToken(clinic));
        assertThat(boardOf(clinic.owner()).get("counts").get("emergencyAccessWaiting").asInt()).isOne();
        // DOCTOR and the NURSE who did not open it: unchanged; the grantee: her escalation set changed (she now sees ward B's
        // patient) but the line is not there, so her token is the plain rule over what she sees
        assertThat(tokenOf(doctor.auth())).isEqualTo(plain);
        assertThat(tokenOf(h.nurseB().auth())).isEqualTo(plain);
        assertThat(tokenOf(h.nurseA().auth())).isEqualTo(plain);
        assertThat(boardOf(doctor.auth()).get("counts").has("emergencyAccessWaiting")).isFalse();

        // a second grant: count 2 and the newest id; the token changes
        JsonNode g2 = openGrant(h.nurseA(), second.id(), "COVERING_BREAK");
        String two = tokenOf(clinic.owner());
        assertThat(two).isNotEqualTo(one).isEqualTo(expectedOwnerToken(clinic));
        assertThat(tokenOf(doctor.auth())).isEqualTo(plain);

        // a review of the newest: count 1, the other one is the newest; the token changes, and differs from the first state
        // only by being the same count and the same newest id as before: it is the token of g1 alone again
        review(clinic, g2.get("id").asText());
        assertThat(tokenOf(clinic.owner())).isEqualTo(one).isEqualTo(expectedOwnerToken(clinic));
        // the last one reviewed: 0 unreviewed, no line, back to the doctor token
        review(clinic, g1.get("id").asText());
        assertThat(tokenOf(clinic.owner())).isEqualTo(plain).isEqualTo(expectedOwnerToken(clinic));
        assertThat(boardOf(clinic.owner()).get("counts").get("emergencyAccessWaiting").asInt()).isZero();
    }

    @Test
    void aReviewTogetherWithANewGrantInOneIntervalChangesTheTokenBecauseTheNewestIdIsInIt() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        JsonNode g1 = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String before = tokenOf(clinic.owner());
        // between two polls: the owner reviews g1 and the other nurse opens a grant on the other ward's patient. Count is 1 both times.
        review(clinic, g1.get("id").asText());
        JsonNode g2 = openGrant(h.nurseB(), h.inA().id(), "DUTY_DOCTOR_ASKED");
        assertThat(boardOf(clinic.owner()).get("counts").get("emergencyAccessWaiting").asInt()).as("same count").isOne();
        String after = tokenOf(clinic.owner());
        assertThat(after).isNotEqualTo(before).isEqualTo(expectedOwnerToken(clinic));
        assertThat(g2.get("id").asText()).isNotEqualTo(g1.get("id").asText());
    }

    @Test
    void theTokenHoldsNoIdNameOrCountAsPlainText() throws Exception {
        Hosp h = hosp();
        JsonNode g = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String token = tokenOf(h.clinic().owner());
        assertThat(token).hasSize(22).doesNotContain(g.get("id").asText()).doesNotContain("bg").doesNotContain(h.inB().name());
    }

    @Test
    void theTokenOfAnotherClinicsGrantIsNeverInThisClinicsToken() throws Exception {
        Hosp h = hosp();
        Hosp other = hosp();
        String before = tokenOf(h.clinic().owner());
        openGrant(other.nurseA(), other.inB().id(), "DETERIORATING");
        assertThat(tokenOf(h.clinic().owner())).isEqualTo(before).isEqualTo(expectedOwnerToken(h.clinic()));
        assertThat(tokenOf(other.clinic().owner())).isNotEqualTo(before);
    }

    // ---------------- AC 81: clinical events, nothing in scope ----------------

    private List<String> pageIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get("id").asText()));
        return ids;
    }

    @Test
    void aScopedNurseWithNoWardAndNoGrantGetsExactlyHerOwnEntriesAndTheSameRowsAsTheOldStatementWithTheSamePaging() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Pn lone = newPn(clinic, "NURSE");
        Adm inC = admitInNamed(clinic, h.wardA(), "Gita Third-A");
        // her entries on an admitted patient of her old ward, on a discharged admission and on another ward's patient
        insertEvents(clinic, h.inA().id(), lone.userId(), Instant.now().minusSeconds(7200), 40);
        insertEvents(clinic, inC.id(), lone.userId(), Instant.now().minusSeconds(5400), 15);
        insertEvents(clinic, h.inB().id(), lone.userId(), Instant.now().minusSeconds(3600), 10);
        // everybody else's entries: a colleague of her old ward, the nurse of ward B, an emergency access entry of another nurse
        insertEvents(clinic, h.inA().id(), h.nurseA().userId(), Instant.now().minusSeconds(1800), 20);
        insertEvents(clinic, h.inB().id(), h.nurseB().userId(), Instant.now().minusSeconds(1700), 20);
        raise(clinic, h.inB(), high7());
        JsonNode grant = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        sign(clinic, inC.id(), "lama", "general");
        dischargeNow(clinic, inC.id(), "lama", "general", "UPI");
        assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", inC.id()).get(0)).isEqualTo("DISCHARGED");
        // she has no ward (never assigned) and no grant of her own
        assertThat(count(clinic, "select count(*) from ipd.ward_assignment where membership_id = ?::uuid", lone.membershipId().toString())).isZero();

        List<String> mine = column(clinic, "select id::text from ipd.clinical_event where actor = ?::uuid order by at desc, seq desc",
                lone.userId().toString());
        assertThat(mine).hasSize(65);
        // the old statement (SCOPED_NURSE with an empty ward list and an empty grant list), run as sahaj_app
        String old = constant(EVENT_REPO, "PAGE_HEAD") + constant(EVENT_REPO, "SCOPED_NURSE") + constant(EVENT_REPO, "PAGE_TAIL");
        List<String> oldRows = db().inTenant(clinic.tenantId(), c -> firstColumn(c, bind(old,
                params("tenantId", clinic.tenantId(), "nurse", lone.userId(), "wards", "{}", "grants", "{}", "limit", 1000))));
        assertThat(oldRows).as("the statement before the F-2 change gives her own entries only").containsExactlyElementsOf(mine);

        // the endpoint: the first page of 14, then 50 at a time, the same rows in the same order, no entry of anybody else
        JsonNode first = eventsOf(lone.auth(), null);
        assertThat(first.get("items")).hasSize(14);
        List<String> seen = new ArrayList<>(pageIds(first));
        JsonNode next = first;
        while (!next.get("nextCursor").isNull()) {
            next = eventsOf(lone.auth(), "limit=50&cursor=" + next.get("nextCursor").asText());
            seen.addAll(pageIds(next));
        }
        assertThat(seen).containsExactlyElementsOf(mine);
        assertThat(seen).doesNotContain(grant.get("id").asText());
        assertThat(values(clinic, "select count(*)::text from ipd.clinical_event where grant_id is not null and actor = ?::uuid",
                lone.userId().toString()).get(0)).isEqualTo("0");

        // with no entries of her own the page is empty
        Pn fresh = newPn(clinic, "NURSE");
        JsonNode empty = eventsOf(fresh.auth(), null);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("nextCursor").isNull()).isTrue();
        // and a nurse WITH a ward still gets her own entries plus those of her ward's admitted patients (AC 11 applies)
        List<String> wardNurse = pageIds(eventsOf(h.nurseA().auth(), "limit=50"));
        assertThat(wardNurse).containsAll(column(clinic, "select id::text from ipd.clinical_event where actor = ?::uuid "
                + "order by at desc, seq desc limit 5", h.nurseA().userId().toString()));
    }

    // ---------------- AC 14: the guard comes before the state check ----------------

    @Test
    void forAnOrderOrAnEscalationOfADischargedAdmissionANurseOutsideScopeGets403OutsideScopeNeverNotAdmittedOrNotFound() throws Exception {
        Full f = full();
        Hosp h = f.h();
        Clinic clinic = h.clinic();
        sign(clinic, h.inB().id(), "lama", "general");
        dischargeNow(clinic, h.inB().id(), "lama", "general", "UPI");
        assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", h.inB().id()).get(0)).isEqualTo("DISCHARGED");
        String dose = "{\"clientKey\":\"" + newKey() + "\",\"slotAt\":\"" + ist(8, 0) + "\",\"outcome\":\"NOT_GIVEN\",\"reasonKey\":\"NIL_BY_MOUTH\"}";
        String call = "{\"version\":0,\"seniorCalls\":0}";
        String written = written(clinic);
        // ward A's nurse: outside scope, on the discharged admission's order and escalation
        JsonNode d = outside(call(HttpMethod.POST, dosesUrl(f.orderId()), h.nurseA().auth(), dose));
        JsonNode c = outside(call(HttpMethod.POST, callUrl(f.escalationId()), h.nurseA().auth(), call));
        for (JsonNode p : new JsonNode[] {d, c}) {
            assertThat(p.fieldNames()).toIterable().containsOnly("type", "title", "status", "detail");
        }
        assertThat(written(clinic)).as("refused, nothing written").isEqualTo(written);
        // the nurse of the last bed's ward is in scope, so she gets the endpoint's own state answer (a 4xx that is not outside-scope)
        for (var r : List.of(call(HttpMethod.POST, dosesUrl(f.orderId()), h.nurseB().auth(), dose),
                call(HttpMethod.POST, callUrl(f.escalationId()), h.nurseB().auth(), call))) {
            var response = r.andReturn().getResponse();
            assertThat(response.getStatus()).isGreaterThanOrEqualTo(400).isNotEqualTo(403);
        }
        // an unknown or another clinic's order or escalation stays 404, never outside-scope
        problem(call(HttpMethod.POST, dosesUrl(java.util.UUID.randomUUID()), h.nurseA().auth(), dose), 404);
        problem(call(HttpMethod.POST, callUrl(java.util.UUID.randomUUID()), h.nurseA().auth(), call), 404);
    }

    // ---------------- AC 80: discharged admissions ----------------

    @Test
    void aDischargedAdmissionIsReadableOnlyByTheNursesOfTheWardOfItsLastBedAndNeverThroughAnEmergencyAccess() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Pn grantee = newPn(clinic, "NURSE");
        Pn wardBTwin = newPn(clinic, "NURSE");
        assign(clinic, h.wardA(), h.nurseA(), grantee);
        assign(clinic, h.wardB(), h.nurseB(), wardBTwin);
        // the grantee opens an emergency access on B's patient, then B's patient is discharged within the 30 minutes
        JsonNode grant = openGrant(grantee, h.inB().id(), "DETERIORATING");
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), grantee.auth(), null, 200);
        sign(clinic, h.inB().id(), "lama", "general");
        dischargeNow(clinic, h.inB().id(), "lama", "general", "UPI");
        String id = h.inB().id();
        String[][] reads = {{"detail", ADMISSIONS + "/" + id}, {"nursing", nursingUrl(id)}, {"emr", emrUrl(id)}, {"mar", marUrl(id)},
            {"discharge sheet", dischargeUrl(id)}};
        for (String[] r : reads) {
            int ownerStatus = call(HttpMethod.GET, r[1], clinic.owner(), null).andReturn().getResponse().getStatus();
            assertThat(ownerStatus).as(r[0] + " for the owner").isNotEqualTo(403);
            // every nurse whose live ward is the ward of the last bed reads it, exactly as the owner does
            for (Pn inWard : new Pn[] {h.nurseB(), wardBTwin}) {
                assertThat(call(HttpMethod.GET, r[1], inWard.auth(), null).andReturn().getResponse().getStatus())
                        .as(r[0] + " for a nurse of the last ward").isEqualTo(ownerStatus);
            }
            // a nurse of another ward, and the one who had the grant (30 minutes not over): outside scope
            outside(call(HttpMethod.GET, r[1], h.nurseA().auth(), null));
            outside(call(HttpMethod.GET, r[1], grantee.auth(), null));
        }
        for (String name : new String[] {"detail", "nursing", "emr", "mar"}) {
            for (String[] r : reads) {
                if (r[0].equals(name)) {
                    ok(HttpMethod.GET, r[1], h.nurseB().auth(), null, 200);
                }
            }
        }
        // the grant is still there and still waiting for the owner's review (AC 55 refined: the grant stays in the list)
        JsonNode list = ok(HttpMethod.GET, EA, clinic.owner(), null, 200);
        assertThat(list.toString()).contains(grant.get("id").asText());
        assertThat(grantOf(clinic, grantee, id)).isEqualTo(grant.get("id").asText());
        // it is not in the grantee's list any more either
        assertThat(idsOf(listOf(grantee.auth()))).doesNotContain(id);
        // a write to a discharged admission: the scope check first (403 outside-scope), then the endpoint's own state check
        outside(call(HttpMethod.POST, ADMISSIONS + "/" + id + "/charges", h.nurseA().auth(), "{\"serviceKey\":\"ecg\"}"));
        JsonNode state = problem(call(HttpMethod.POST, ADMISSIONS + "/" + id + "/charges", h.nurseB().auth(), "{\"serviceKey\":\"ecg\"}"), 409);
        assertType(state, "not-admitted");
    }
}
