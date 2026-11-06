package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-33 review fixes R2 and R3 (ADR-007, security review M1 and M2) on the real {@code sahaj_test} database.
 *
 * <p>R2: for Death, LAMA and Referred the doctor's latest sign-off must be strictly later than the start of the response
 * clock of the open escalation (re-open time, else raise time), otherwise the discharge is refused 409 escalation-open with
 * the new text; a new sign-off after the escalation lets the same discharge complete and close it as "Closed at
 * discharge"; Normal keeps its own refusal; the Discharge sheet says {@code signoffBeforeEscalation}.
 *
 * <p>R3: the discharge reads the membership first: a deactivated, removed or demoted member with a token that is still valid
 * is 403 and nothing is written; the refund rule uses the membership role. The people are real front desk members who
 * signed in by OTP. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class SafetyDischargeFixIT extends SafetyTestSupport {

    private static final String AFTER_SIGNED = "This patient has an early warning escalation that started after the doctor "
            + "signed the summary. A doctor must record the response or sign the summary again.";
    private static final String[] CLOSED_AT_DISCHARGE_TYPES = {"death", "lama", "referred"};

    private record Ward(Clinic clinic, Staff doctor, Person front) {
    }

    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        // A genuine session token of a deactivated user is answered 401 by the sign-in filter once its 60-second cache of
        // "is this membership active" has run out (SAH-3). A token that the filter accepts is what the service has to
        // judge (ADR-007 R3), so the front desk member here carries a signed token for the real user and role, which
        // reaches the service whatever the cache says.
        Person p = realPerson(clinic, "FRONT_DESK");
        return new Ward(clinic, staff(clinic, "DOCTOR"), new Person(p.userId(), p.membershipId(), tokenOf(clinic, p.userId(), "FRONT_DESK")));
    }

    /** A General stay of 7 bill days: the bill is above the deposit, so a front desk member needs no refund. */
    private String admitted(Clinic clinic) throws Exception {
        return admitGeneral(clinic, 7).get("id").asText();
    }

    private JsonNode sheetOf(Person p, String admission) throws Exception {
        return ok(HttpMethod.GET, dischargeUrl(admission), p.auth(), null, 200);
    }

    private String body(Person p, String admission, String type) throws Exception {
        JsonNode sheet = sheetOf(p, admission);
        return dischargeBody(type, "general", totalOf(sheet), depositsOf(sheet), "UPI");
    }

    // ---------------- R2 ----------------

    @Test
    void anEscalationRaisedAfterTheSignOffRefusesDeathLamaAndReferredUntilTheDoctorSignsAgainThenItIsClosedAtDischarge()
            throws Exception {
        Ward w = ward();
        for (String type : CLOSED_AT_DISCHARGE_TYPES) {
            String adm = admitted(w.clinic());
            sign(w.clinic(), adm, type, "general");                      // T0
            String id = raise(w.clinic(), new Adm(adm, null, null, null, null), medium3()); // T1 > T0
            assertThat(sheetOf(w.front(), adm).get("signoffBeforeEscalation").asBoolean()).as(type).isTrue();
            assertThat(sheetOf(w.front(), adm).get("openEscalation").asBoolean()).isTrue();

            String request = body(w.front(), adm, type); // reading the sheet writes a record access row: before the snapshot
            Written written = written(w.clinic(), adm);
            Side side = side(w.clinic());
            JsonNode p = problem(postDischarge(w.front().auth(), adm, request), 409);
            assertType(p, "escalation-open");
            assertThat(p.get("detail").asText()).as(type).isEqualTo(AFTER_SIGNED);
            assertThat(written(w.clinic(), adm)).as("nothing written " + type).isEqualTo(written);
            assertThat(side(w.clinic())).isEqualTo(side);
            assertThat(esc(w.clinic(), id).status()).isEqualTo("OPEN");
            assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("ADMITTED");

            // sign again: a new sign-off row is appended and audited
            long signoffs = rows(w.clinic(), "discharge_signoff");
            long signed = audits(w.clinic(), "IPD_DISCHARGE_SIGNED");
            sign(w.clinic(), adm, type, "general");
            assertThat(rows(w.clinic(), "discharge_signoff")).as("a new sign-off row, " + type).isEqualTo(signoffs + 1);
            assertThat(audits(w.clinic(), "IPD_DISCHARGE_SIGNED")).isEqualTo(signed + 1);
            assertThat(sheetOf(w.front(), adm).get("signoffBeforeEscalation").asBoolean()).as("covered now").isFalse();
            // and a third tap is the old double-tap no-op again: nothing more is written
            sign(w.clinic(), adm, type, "general");
            assertThat(rows(w.clinic(), "discharge_signoff")).isEqualTo(signoffs + 1);
            assertThat(audits(w.clinic(), "IPD_DISCHARGE_SIGNED")).isEqualTo(signed + 1);

            ok(HttpMethod.POST, dischargeUrl(adm), w.front().auth(), body(w.front(), adm, type), 200);
            Esc e = esc(w.clinic(), id);
            assertThat(e.status()).as(type).isEqualTo("DONE");
            assertThat(e.action()).isEqualTo("CLOSED_AT_DISCHARGE");
            assertThat(e.closedBy()).isEqualTo(w.front().userId().toString());
            assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("DISCHARGED");
            assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED_AT_DISCHARGE")).isNotEmpty();
        }
    }

    @Test
    void aNormalDischargeKeepsItsOwnRefusalWhateverTheSignOffTimeAndSigningAgainDoesNotLiftIt() throws Exception {
        Ward w = ward();
        String adm = admitted(w.clinic());
        sign(w.clinic(), adm, "normal", "general");
        String id = raise(w.clinic(), new Adm(adm, null, null, null, null), medium3());
        assertThat(sheetOf(w.front(), adm).get("signoffBeforeEscalation").asBoolean()).as("true whatever the type").isTrue();
        JsonNode first = problem(postDischarge(w.front().auth(), adm, body(w.front(), adm, "normal")), 409);
        assertType(first, "escalation-open");
        assertThat(first.get("detail").asText()).isEqualTo(ESCALATION_OPEN);

        sign(w.clinic(), adm, "normal", "general");                        // signed again, later than the escalation
        assertThat(sheetOf(w.front(), adm).get("signoffBeforeEscalation").asBoolean()).isFalse();
        JsonNode second = problem(postDischarge(w.front().auth(), adm, body(w.front(), adm, "normal")), 409);
        assertType(second, "escalation-open");
        assertThat(second.get("detail").asText()).as("Normal keeps its own text").isEqualTo(ESCALATION_OPEN);
        assertThat(esc(w.clinic(), id).status()).isEqualTo("OPEN");
    }

    @Test
    void aSignOffLaterThanTheEscalationCoversItAndTheSheetFlagIsFalseWhenThereIsNothingToCover() throws Exception {
        Ward w = ward();
        // no escalation: false
        String none = admitted(w.clinic());
        sign(w.clinic(), none, "lama", "general");
        JsonNode noEscalation = sheetOf(w.front(), none);
        assertThat(noEscalation.get("openEscalation").asBoolean()).isFalse();
        assertThat(noEscalation.get("signoffBeforeEscalation").asBoolean()).isFalse();

        // an open escalation and no signature: false (there is nothing that is "before")
        String unsigned = admitted(w.clinic());
        raise(w.clinic(), new Adm(unsigned, null, null, null, null), medium3());
        JsonNode noSignature = sheetOf(w.front(), unsigned);
        assertThat(noSignature.get("openEscalation").asBoolean()).isTrue();
        assertThat(noSignature.get("signature").isNull()).isTrue();
        assertThat(noSignature.get("signoffBeforeEscalation").asBoolean()).isFalse();

        // the escalation first, the sign-off later: covered, the discharge completes at once
        String later = admitted(w.clinic());
        String id = raise(w.clinic(), new Adm(later, null, null, null, null), medium3());
        sign(w.clinic(), later, "referred", "general");
        JsonNode covered = sheetOf(w.front(), later);
        assertThat(covered.get("openEscalation").asBoolean()).isTrue();
        assertThat(covered.get("signoffBeforeEscalation").asBoolean()).isFalse();
        ok(HttpMethod.POST, dischargeUrl(later), w.front().auth(), body(w.front(), later, "referred"), 200);
        assertThat(esc(w.clinic(), id).action()).isEqualTo("CLOSED_AT_DISCHARGE");

        // a closed escalation: false
        String closed = admitted(w.clinic());
        String closedId = raise(w.clinic(), new Adm(closed, null, null, null, null), medium3());
        sign(w.clinic(), closed, "lama", "general");
        respondOk(w.doctor().auth(), closedId, 0, "NO_CHANGE", "Reviewed, repeat vitals in 1 hour");
        JsonNode done = sheetOf(w.front(), closed);
        assertThat(done.get("openEscalation").asBoolean()).isFalse();
        assertThat(done.get("signoffBeforeEscalation").asBoolean()).isFalse();
    }

    @Test
    void aReopenedEscalationMeasuresFromTheReopenSoASignOffBetweenRaiseAndReopenDoesNotCoverIt() throws Exception {
        Ward w = ward();
        String adm = admitted(w.clinic());
        Adm a = new Adm(adm, null, null, null, null);
        String id = raise(w.clinic(), a, medium3());                       // raised
        sign(w.clinic(), adm, "lama", "general");                          // signed after the raise: covers it
        assertThat(sheetOf(w.front(), adm).get("signoffBeforeEscalation").asBoolean()).isFalse();
        ackOk(w.doctor().auth(), id, 0);
        save(w.clinic(), adm, high7());                                    // Medium to High: re-opened after the sign-off
        assertThat(esc(w.clinic(), id).hasReopenedAt()).isTrue();
        assertThat(sheetOf(w.front(), adm).get("signoffBeforeEscalation").asBoolean()).isTrue();
        JsonNode p = problem(postDischarge(w.front().auth(), adm, body(w.front(), adm, "lama")), 409);
        assertType(p, "escalation-open");
        assertThat(p.get("detail").asText()).isEqualTo(AFTER_SIGNED);
        sign(w.clinic(), adm, "lama", "general");
        ok(HttpMethod.POST, dischargeUrl(adm), w.front().auth(), body(w.front(), adm, "lama"), 200);
        assertThat(esc(w.clinic(), id).action()).isEqualTo("CLOSED_AT_DISCHARGE");
    }

    @Test
    void theSignOffMustBeStrictlyLaterThanTheStartOfTheClockEqualInstantsAreNotCovered() throws Exception {
        Ward w = ward();
        for (String type : CLOSED_AT_DISCHARGE_TYPES) {
            // equal instants: the escalation (written by the table owner) starts at the very microsecond of the sign-off
            String equal = admitted(w.clinic());
            sign(w.clinic(), equal, type, "general");
            Instant signedAt = instantOf(w.clinic(), "select max(signed_at) from ipd.discharge_signoff where admission_id = ?::uuid", equal);
            String eqId = insertEscalation(w.clinic(), equal, 3, "MEDIUM", signedAt, null, null);
            assertThat(sheetOf(w.front(), equal).get("signoffBeforeEscalation").asBoolean()).as(type + " equal").isTrue();
            JsonNode p = problem(postDischarge(w.front().auth(), equal, body(w.front(), equal, type)), 409);
            assertType(p, "escalation-open");
            assertThat(p.get("detail").asText()).isEqualTo(AFTER_SIGNED);
            assertThat(esc(w.clinic(), eqId).status()).isEqualTo("OPEN");

            // one millisecond earlier than the sign-off: covered
            String earlier = admitted(w.clinic());
            sign(w.clinic(), earlier, type, "general");
            Instant at = instantOf(w.clinic(), "select max(signed_at) from ipd.discharge_signoff where admission_id = ?::uuid", earlier);
            String earlierId = insertEscalation(w.clinic(), earlier, 3, "MEDIUM", at.minusMillis(1), null, null);
            assertThat(sheetOf(w.front(), earlier).get("signoffBeforeEscalation").asBoolean()).as(type + " earlier").isFalse();
            ok(HttpMethod.POST, dischargeUrl(earlier), w.front().auth(), body(w.front(), earlier, type), 200);
            assertThat(esc(w.clinic(), earlierId).action()).isEqualTo("CLOSED_AT_DISCHARGE");
        }
    }

    @Test
    void theOwnerIsHeldToTheSameRule() throws Exception {
        Ward w = ward();
        String adm = admitted(w.clinic());
        sign(w.clinic(), adm, "death", "general");
        String id = raise(w.clinic(), new Adm(adm, null, null, null, null), high7());
        JsonNode sheet = sheet(w.clinic(), adm);
        JsonNode p = problem(postDischarge(w.clinic().owner(), adm,
                dischargeBody("death", "general", totalOf(sheet), depositsOf(sheet), "UPI")), 409);
        assertType(p, "escalation-open");
        assertThat(p.get("detail").asText()).isEqualTo(AFTER_SIGNED);
        assertThat(esc(w.clinic(), id).status()).isEqualTo("OPEN");
    }

    // ---------------- R3 ----------------

    @Test
    void aDeactivatedRemovedOrDemotedFrontDeskMemberWithAValidTokenIs403AndNothingIsWritten() throws Exception {
        Ward w = ward();
        String adm = admitted(w.clinic());
        sign(w.clinic(), adm, "normal", "general");
        Person ghost = w.front();
        String body = body(ghost, adm, "normal");
        Written written = written(w.clinic(), adm);
        Side side = side(w.clinic());

        for (String status : new String[] {"INACTIVE", "REMOVED"}) {
            setMembershipStatus(w.clinic().tenantId(), ghost.userId(), status);
            JsonNode p = problem(postDischarge(ghost.auth(), adm, body), 403);
            assertType(p, "forbidden");
            assertThat(written(w.clinic(), adm)).as(status).isEqualTo(written);
            assertThat(side(w.clinic())).isEqualTo(side);
        }
        setMembershipStatus(w.clinic().tenantId(), ghost.userId(), "ACTIVE");
        for (String demoted : new String[] {"NURSE", "BILLING", "DOCTOR", "PHARMACIST"}) {
            setMembershipRole(w.clinic(), ghost.userId(), demoted);
            assertType(problem(postDischarge(ghost.auth(), adm, body), 403), "forbidden");
            assertThat(written(w.clinic(), adm)).as("demoted to " + demoted).isEqualTo(written);
            assertThat(side(w.clinic())).isEqualTo(side);
        }
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("ADMITTED");

        // positive control: the member is a front desk member again and the same request now completes
        setMembershipRole(w.clinic(), ghost.userId(), "FRONT_DESK");
        ok(HttpMethod.POST, dischargeUrl(adm), ghost.auth(), body, 200);
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("DISCHARGED");
    }

    @Test
    void aDeactivatedFrontDeskMemberCannotCloseAnEscalationAtDischarge() throws Exception {
        Ward w = ward();
        String adm = admitted(w.clinic());
        String id = raise(w.clinic(), new Adm(adm, null, null, null, null), high7());
        sign(w.clinic(), adm, "lama", "general");                         // covers the escalation
        String body = body(w.front(), adm, "lama");
        setMembershipStatus(w.clinic().tenantId(), w.front().userId(), "INACTIVE");
        Side side = side(w.clinic());
        assertType(problem(postDischarge(w.front().auth(), adm, body), 403), "forbidden");
        assertThat(esc(w.clinic(), id).status()).isEqualTo("OPEN");
        assertThat(side(w.clinic())).isEqualTo(side);
        assertThat(audit(w.clinic(), "IPD_ESCALATION_CLOSED_AT_DISCHARGE")).isEmpty();
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("ADMITTED");
    }

    @Test
    void theMembershipIsReadBeforeAnythingElseSoARemovedMemberDoesNotLearnWhetherThePatientExists() throws Exception {
        Ward w = ward();
        setMembershipStatus(w.clinic().tenantId(), w.front().userId(), "INACTIVE");
        // an unknown admission and a missing sign-off would be 404 and 409 for an active member; the removed one gets 403
        var unknown = postDischarge(w.front().auth(), java.util.UUID.randomUUID(), dischargeBody("normal", "general", 1L, null))
                .andReturn().getResponse();
        assertThat(unknown.getStatus()).as(unknown.getContentAsString()).isEqualTo(403);
        String adm = admitted(w.clinic());
        var unsigned = postDischarge(w.front().auth(), adm, dischargeBody("normal", "general", 1L, "UPI")).andReturn().getResponse();
        assertThat(unsigned.getStatus()).as(unsigned.getContentAsString()).isEqualTo(403);
    }

    @Test
    void theRefundRuleReadsTheRoleFromTheMembershipNotFromTheToken() throws Exception {
        Ward w = ward();
        // a refund is needed: total 1,600, deposit 10,000
        String adm = quickAdmit(w.clinic()).get("id").asText();
        signNormal(w.clinic(), adm);
        JsonNode sheet = sheet(w.clinic(), adm);
        String body = dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "UPI");
        Written before = written(w.clinic(), adm);

        // a front desk member whose token says OWNER: the membership decides, the refund is refused
        JsonNode p = problem(postDischarge(tokenOf(w.clinic(), w.front().userId(), "OWNER"), adm, body), 403);
        assertType(p, "refund-needs-owner");
        assertThat(p.get("detail").asText()).isEqualTo(ONLY_OWNER_REFUNDS);
        assertThat(written(w.clinic(), adm)).isEqualTo(before);
        // the real front desk session is refused the same way
        assertType(problem(postDischarge(w.front().auth(), adm, body), 403), "refund-needs-owner");

        // the clinic owner's user with a token that says FRONT_DESK is an OWNER membership: the refund is allowed
        ok(HttpMethod.POST, dischargeUrl(adm), w.clinic().as("FRONT_DESK"), body, 200);
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", adm)).containsExactly("DISCHARGED");
    }
}
