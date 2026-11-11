package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-34 story 34F (the owner's review of emergency access) on the real {@code sahaj_test} database: the owner's tile, count
 * and first page come with the Safety board and only for the owner; "Show 25 more" pages by keyset in a stable order over
 * ties; a grant is reviewed once (a second review is 409 already-reviewed), never by the person who opened it, and by exactly
 * one of two owners who press at the same time; expired, discharged and inactive grants stay in the list until reviewed;
 * the review writes a bg entry and an audit row with ids only. Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class EmergencyAccessReviewIT extends ScopeSupport {

    private JsonNode page(RequestPostProcessor auth, String query) throws Exception {
        return ok(HttpMethod.GET, EA + (query == null ? "" : "?" + query), auth, null, 200);
    }

    /** {@code n} unreviewed, already expired grants written by the table owner: groups of five share one opened_at (ties). */
    private void bulkGrants(Hosp h, int n) throws Exception {
        String bed = bedOf(h.clinic(), h.inB().id()).toString();
        asOwner(true, GRANT_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.emergency_access_grant disable trigger trg_emergency_access_grant_stamp");
            DbAccess.execute(c, "insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, membership_id, role, "
                    + "reason_key, opened_at, expires_at) with t as (select date_trunc('second', clock_timestamp()) - interval '3 hours' as t0) "
                    + "select ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'NURSE', "
                    + "(array['DETERIORATING','COVERING_BREAK','DUTY_DOCTOR_ASKED'])[1 + g % 3], "
                    + "t0 + ((g / 5) * interval '1 second'), t0 + ((g / 5) * interval '1 second') + interval '30 minutes' "
                    + "from t cross join generate_series(1, ?) g",
                    h.clinic().tenantId().toString(), h.inB().id(), bed, h.nurseA().userId().toString(),
                    h.nurseA().membershipId().toString(), n);
            DbAccess.execute(c, "alter table ipd.emergency_access_grant enable trigger trg_emergency_access_grant_stamp");
            return null;
        });
    }

    @Test
    void theOwnersTileCountAndFirstPageComeWithTheSafetyBoardAndNoOtherRoleGetsThemNorTheCount() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        JsonNode empty = boardOf(clinic.owner());
        assertThat(empty.get("emergencyAccess").get("waiting").asInt()).isZero();
        assertThat(empty.get("emergencyAccess").get("items")).isEmpty();
        assertThat(empty.get("emergencyAccess").get("nextCursor").isNull()).isTrue();
        assertThat(empty.get("counts").get("emergencyAccessWaiting").asInt()).isZero();
        Staff doctor = staff(clinic, "DOCTOR");
        JsonNode g = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String nurseName = nameOf(clinic, h.nurseA().userId());

        JsonNode board = boardOf(clinic.owner());
        JsonNode ea = board.get("emergencyAccess");
        assertThat(ea.get("waiting").asInt()).isOne();
        assertThat(board.get("counts").get("emergencyAccessWaiting").asInt()).isOne();
        assertThat(ea.get("asOf").asText()).isNotBlank();
        JsonNode row = ea.get("items").get(0);
        assertThat(row.get("grantId").asText()).isEqualTo(g.get("id").asText());
        assertThat(row.get("admissionId").asText()).isEqualTo(h.inB().id());
        assertThat(row.get("staffName").asText()).isEqualTo(nurseName);
        assertThat(row.get("patientName").asText()).isEqualTo(h.inB().name());
        assertThat(row.get("bedCode").asText()).isEqualTo(h.inB().bedCode());
        assertThat(row.get("reasonKey").asText()).isEqualTo("DETERIORATING");
        assertThat(row.get("reasonLabel").asText()).isEqualTo("Patient deteriorating, rapid response");
        assertThat(instant(row.get("openedAt"))).isEqualTo(instant(g.get("openedAt")));
        assertThat(instant(row.get("expiresAt"))).isEqualTo(instant(g.get("expiresAt")));
        assertThat(row.get("admissionStatus").asText()).isEqualTo("ADMITTED");
        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder("grantId", "admissionId", "staffName", "patientName",
                "bedCode", "reasonKey", "reasonLabel", "openedAt", "expiresAt", "admissionStatus");
        // the tab number of the owner counts it (open + dosesLate + waiting + emergencyAccessWaiting; here only the grant)
        assertThat(summaryOf(clinic.owner()).get("emergencyAccessWaiting").asInt()).isOne();
        assertThat(listOf(clinic.owner()).get("safety").get("counts").get("emergencyAccessWaiting").asInt()).isOne();
        // the doctor and the nurse never get the tile, the panel or the count
        for (Staff s : new Staff[] {doctor}) {
            JsonNode b = boardOf(s.auth());
            assertThat(b.has("emergencyAccess")).isFalse();
            assertThat(b.get("counts").has("emergencyAccessWaiting")).isFalse();
            assertThat(summaryOf(s.auth()).has("emergencyAccessWaiting")).isFalse();
            assertThat(listOf(s.auth()).get("safety").get("counts").has("emergencyAccessWaiting")).isFalse();
        }
        JsonNode nurse = boardOf(h.nurseA().auth());
        assertThat(nurse.has("emergencyAccess")).isFalse();
        assertThat(nurse.get("counts").has("emergencyAccessWaiting")).isFalse();
        assertThat(call(HttpMethod.GET, SAFETY, h.nurseA().auth(), null).andReturn().getResponse().getContentAsString())
                .doesNotContain("staffName");
        // a new grant and a review each change the board's change token
        String t1 = board.get("counts").get("changeToken").asText();
        ok(HttpMethod.POST, EA + "/" + g.get("id").asText() + "/review", clinic.owner(), null, 200);
        assertThat(boardOf(clinic.owner()).get("counts").get("changeToken").asText()).isNotEqualTo(t1);
    }

    @Test
    void onlyTheOwnerListsAndReviewsEveryoneElseGets403AndNothingIsWritten() throws Exception {
        Hosp h = hosp();
        JsonNode g = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String id = g.get("id").asText();
        String written = written(h.clinic());
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"}) {
            forbidden(call(HttpMethod.GET, EA, h.clinic().as(role), null));
            forbidden(call(HttpMethod.POST, EA + "/" + id + "/review", h.clinic().as(role), null));
        }
        forbidden(call(HttpMethod.GET, EA, h.nurseA().auth(), null));
        forbidden(call(HttpMethod.POST, EA + "/" + id + "/review", h.nurseA().auth(), null));
        // an OWNER token whose membership is not an owner any more
        Pn demoted = newPn(h.clinic(), "DOCTOR");
        RequestPostProcessor ownerClaim = tokenOf(h.clinic(), demoted.userId(), "OWNER");
        forbidden(call(HttpMethod.GET, EA, ownerClaim, null));
        forbidden(call(HttpMethod.POST, EA + "/" + id + "/review", ownerClaim, null));
        assertThat(written(h.clinic())).isEqualTo(written);
    }

    @Test
    void theListPagesBy25WithAKeysetOverTiesWithNoGapAndNoRepeatAndTheCountIsTheFullNumber() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        bulkGrants(h, 61);
        List<String> expected = column(clinic, "select id::text from ipd.emergency_access_grant where reviewed_at is null order by opened_at, id");
        assertThat(expected).hasSize(61);
        JsonNode board = boardOf(clinic.owner());
        assertThat(board.get("emergencyAccess").get("items")).as("the board holds the first page only").hasSize(25);
        assertThat(board.get("emergencyAccess").get("waiting").asInt()).isEqualTo(61);
        assertThat(board.get("counts").get("emergencyAccessWaiting").asInt()).isEqualTo(61);
        List<String> seen = new ArrayList<>();
        board.get("emergencyAccess").get("items").forEach(r -> seen.add(r.get("grantId").asText()));
        String cursor = board.get("emergencyAccess").get("nextCursor").asText();
        int pages = 1;
        while (cursor != null) {
            JsonNode next = page(clinic.owner(), "cursor=" + cursor);
            assertThat(next.get("waiting").asInt()).as("the full count on every page").isEqualTo(61);
            assertThat(next.get("items").size()).isLessThanOrEqualTo(25);
            next.get("items").forEach(r -> seen.add(r.get("grantId").asText()));
            cursor = next.get("nextCursor").isNull() ? null : next.get("nextCursor").asText();
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).as("oldest first, no gap, no repeat, ties by id").isEqualTo(expected);
        assertThat(new LinkedHashSet<>(seen)).hasSize(61);
        // limit
        JsonNode ten = page(clinic.owner(), "limit=10");
        assertThat(ten.get("items")).hasSize(10);
        assertThat(ten.get("nextCursor").isNull()).isFalse();
        assertThat(page(clinic.owner(), "limit=25").get("items")).hasSize(25);
        assertThat(page(clinic.owner(), "limit=1").get("items")).hasSize(1);
        // an expired grant stays in the list until it is reviewed
        assertThat(page(clinic.owner(), null).get("items")).hasSize(25);
        // reviewing one of the first page changes the next page by one (the cursor is a position, not an offset)
        String firstId = seen.get(0);
        ok(HttpMethod.POST, EA + "/" + firstId + "/review", clinic.owner(), null, 200);
        JsonNode after = page(clinic.owner(), null);
        assertThat(after.get("waiting").asInt()).isEqualTo(60);
        assertThat(after.get("items").get(0).get("grantId").asText()).isEqualTo(seen.get(1));
    }

    @Test
    void aBadLimitOrCursorIsA400WithTheReloadTextAndNothingIsRead() throws Exception {
        Hosp h = hosp();
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        for (String q : new String[] {"limit=0", "limit=26", "limit=-1", "limit=abc", "limit=", "limit=1.5", "limit=1000"}) {
            JsonNode p = problem(call(HttpMethod.GET, EA + "?" + q, h.clinic().owner(), null), 400);
            assertThat(fieldMessage(p, "limit")).as(q).isEqualTo("Reload the page and try again.");
        }
        for (String q : new String[] {"cursor=!!!", "cursor=abc", "cursor=" + "A".repeat(65), "cursor=AAAAAAAAAAAAAAAAAAAAAA"}) {
            JsonNode p = problem(call(HttpMethod.GET, EA + "?" + q, h.clinic().owner(), null), 400);
            assertThat(fieldMessage(p, "cursor")).as(q).isEqualTo("Reload the page and try again.");
        }
    }

    @Test
    void reviewingOnceWritesTheFieldsTheBgEntryAndAnAuditRowOfIdsAndASecondReviewIs409AlreadyReviewed() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String id = openGrant(h.nurseA(), h.inB().id(), "COVERING_BREAK").get("id").asText();
        openGrant(h.nurseB(), h.inA().id(), "DUTY_DOCTOR_ASKED");
        assertThat(boardOf(clinic.owner()).get("emergencyAccess").get("waiting").asInt()).isEqualTo(2);

        JsonNode done = ok(HttpMethod.POST, EA + "/" + id + "/review", clinic.owner(), null, 200);
        assertThat(done.get("grantId").asText()).isEqualTo(id);
        assertThat(done.get("waiting").asInt()).isOne();
        assertThat(done.get("reviewedAt").asText()).isNotBlank();
        List<String> row = values(clinic, "select reviewed_by::text, (reviewed_at between clock_timestamp() - interval '2 minutes' and clock_timestamp())::text, "
                + "version::text from ipd.emergency_access_grant where id = ?::uuid", id);
        assertThat(row).containsExactly(clinic.ownerUserId().toString(), "true", "1");
        List<String> bg = values(clinic, "select kind, text, actor::text from ipd.clinical_event where grant_id = ?::uuid and text like 'Reviewed%'", id);
        assertThat(bg).containsExactly("bg", "Reviewed emergency access", clinic.ownerUserId().toString());
        List<String[]> audit = audit(clinic, "IPD_EMERGENCY_ACCESS_REVIEWED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_EMERGENCY_ACCESS");
        assertThat(audit.get(0)[1]).isEqualTo(id);
        assertThat(audit.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        assertThat(String.valueOf(audit.get(0)[3])).doesNotContain(h.inB().name()).doesNotContain(nameOf(clinic, h.nurseA().userId()));
        // the reviewed grant is gone from the list; the other stays
        JsonNode now = boardOf(clinic.owner());
        assertThat(now.get("emergencyAccess").get("waiting").asInt()).isOne();
        assertThat(now.get("emergencyAccess").get("items")).hasSize(1);
        assertThat(now.get("counts").get("emergencyAccessWaiting").asInt()).isOne();
        // the trail of the patient shows the named entry "Reviewed emergency access by <grantee>"
        JsonNode events = eventsOf(clinic.owner(), "limit=50");
        boolean named = false;
        for (JsonNode e : events.get("items")) {
            if (e.has("grantStaffName") && !e.get("grantStaffName").isNull()
                    && nameOf(clinic, h.nurseA().userId()).equals(e.get("grantStaffName").asText())
                    && e.get("text").asText().startsWith("Reviewed emergency access")) {
                named = true;
            }
        }
        assertThat(named).as("ClinicalEvent.grantStaffName").isTrue();

        // second review: 409, nothing written
        String written = written(clinic);
        JsonNode p = problem(call(HttpMethod.POST, EA + "/" + id + "/review", clinic.owner(), null), 409);
        assertType(p, "already-reviewed");
        assertThat(p.get("detail").asText()).isEqualTo(ALREADY_REVIEWED_TEXT);
        assertThat(written(clinic)).isEqualTo(written);
        assertThat(audits(clinic, "IPD_EMERGENCY_ACCESS_REVIEWED")).isOne();
    }

    @Test
    void theGranteeHerselfIs403OwnReviewEvenWhenSheIsAnOwnerNowAndNothingIsWritten() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String id = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
        setMembershipRole(clinic, h.nurseA().userId(), "OWNER");
        RequestPostProcessor herAsOwner = tokenOf(clinic, h.nurseA().userId(), "OWNER");
        String written = written(clinic);
        JsonNode p = problem(call(HttpMethod.POST, EA + "/" + id + "/review", herAsOwner, null), 403);
        assertType(p, "forbidden");
        assertThat(p.get("detail").asText()).isEqualTo(OWN_REVIEW_TEXT);
        assertThat(written(clinic)).isEqualTo(written);
        // another owner can
        Pn other = newPn(clinic, "OWNER");
        ok(HttpMethod.POST, EA + "/" + id + "/review", other.auth(), null, 200);
    }

    @Test
    void unknownAnotherClinicsAndBadIdsAreAnswered404And400() throws Exception {
        Hosp h = hosp();
        Hosp other = hosp();
        String theirs = openGrant(other.nurseA(), other.inB().id(), "DETERIORATING").get("id").asText();
        String written = written(h.clinic());
        problem(call(HttpMethod.POST, EA + "/" + UUID.randomUUID() + "/review", h.clinic().owner(), null), 404);
        problem(call(HttpMethod.POST, EA + "/" + theirs + "/review", h.clinic().owner(), null), 404);
        assertType(problem(call(HttpMethod.POST, EA + "/not-a-uuid/review", h.clinic().owner(), null), 400), "bad-request");
        assertThat(written(h.clinic())).isEqualTo(written);
        // the other clinic's grant is still unreviewed, and is invisible to this owner's list
        assertThat(count(other.clinic(), "select count(*) from ipd.emergency_access_grant where reviewed_at is null")).isOne();
        assertThat(boardOf(h.clinic().owner()).get("emergencyAccess").get("waiting").asInt()).isZero();
        assertThat(page(h.clinic().owner(), null).get("items")).isEmpty();
        assertThat(count(h.clinic(), "select count(*) from ipd.emergency_access_grant")).isZero();
    }

    @Test
    void twoOwnersWhoPressAtTheSameMomentGetOne200AndOne409AndOneReview() throws Exception {
        for (int round = 0; round < 3; round++) {
            Hosp h = hosp();
            Clinic clinic = h.clinic();
            Pn second = newPn(clinic, "OWNER");
            String id = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
            List<Resp> answers = parallel(new Req(HttpMethod.POST, EA + "/" + id + "/review", clinic.owner(), null),
                    new Req(HttpMethod.POST, EA + "/" + id + "/review", second.auth(), null));
            List<Integer> statuses = new ArrayList<>();
            answers.forEach(a -> statuses.add(a.status()));
            assertThat(statuses).as("round " + round + " " + answers).containsExactlyInAnyOrder(200, 409);
            for (Resp a : answers) {
                if (a.status() == 409) {
                    assertThat(a.type(objectMapper)).isEqualTo("already-reviewed");
                }
            }
            assertThat(audits(clinic, "IPD_EMERGENCY_ACCESS_REVIEWED")).isOne();
            assertThat(count(clinic, "select count(*) from ipd.clinical_event where grant_id = ?::uuid and text like 'Reviewed%'", id)).isOne();
            assertThat(count(clinic, "select count(*) from ipd.emergency_access_grant where reviewed_at is not null")).isOne();
        }
    }

    @Test
    void expiredDischargedAndInactiveGranteeGrantsStayInTheListAndAreReviewableTheStaffNameIsNeverBlank() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Adm gone = admitInNamed(clinic, h.wardB(), "Jai Leaves");
        String expired = insertExpiredGrant(clinic, h.nurseA(), h.inB().id());
        String discharged = openGrant(h.nurseA(), gone.id(), "COVERING_BREAK").get("id").asText();
        signAndDischarge(clinic, gone.id());
        String inactive = openGrant(h.nurseA(), h.inB().id(), "DUTY_DOCTOR_ASKED").get("id").asText();
        setMembershipStatus(clinic.tenantId(), h.nurseA().userId(), "INACTIVE");
        JsonNode ea = boardOf(clinic.owner()).get("emergencyAccess");
        assertThat(ea.get("waiting").asInt()).isEqualTo(3);
        List<String> ids = new ArrayList<>();
        for (JsonNode r : ea.get("items")) {
            ids.add(r.get("grantId").asText());
            assertThat(r.get("staffName").asText()).isNotBlank();
            assertThat(r.get("patientName").asText()).isNotBlank();
            if (r.get("grantId").asText().equals(discharged)) {
                assertThat(r.get("admissionStatus").asText()).isEqualTo("DISCHARGED");
            }
        }
        assertThat(ids).containsExactlyInAnyOrder(expired, discharged, inactive);
        for (String id : ids) {
            ok(HttpMethod.POST, EA + "/" + id + "/review", clinic.owner(), null, 200);
        }
        assertThat(boardOf(clinic.owner()).get("emergencyAccess").get("waiting").asInt()).isZero();
    }

    @Test
    void reviewingIsAllowedWhenIpdIsReadOnlyAndTheListIsToo() throws Exception {
        Hosp h = hosp();
        String id = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
        expireTrial(h.clinic());
        assertThat(page(h.clinic().owner(), null).get("items")).hasSize(1);
        ok(HttpMethod.POST, EA + "/" + id + "/review", h.clinic().owner(), null, 200);
        assertThat(page(h.clinic().owner(), null).get("items")).isEmpty();
    }

    @Test
    void thePanelAndTheListHoldNoDiagnosisNoteNoBillAndTheReviewLogsNoName() throws Exception {
        Hosp h = hosp();
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        String raw = call(HttpMethod.GET, EA, h.clinic().owner(), null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("diagnosis").doesNotContain("allerg").doesNotContain("Paise").doesNotContain(h.inB().mobile());
        Set<String> keys = new LinkedHashSet<>();
        objectMapper.readTree(raw).fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("asOf", "waiting", "items", "nextCursor");
    }
}
