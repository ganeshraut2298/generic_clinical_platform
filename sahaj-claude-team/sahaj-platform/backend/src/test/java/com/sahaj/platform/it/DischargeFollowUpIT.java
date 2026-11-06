package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.api.FollowUpPort;
import com.sahaj.opd.api.FollowUpPort.DischargeFollowUp;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-27 (ADR-004) the follow-up a discharge adds, on the real {@code sahaj_test} database: it is listed and counted
 * like any follow-up with {@code sourceAdmissionId} set and {@code sourceVisitId} null, a token for the same patient and
 * doctor closes it, the OPD fee window starts at the discharge, and {@code FollowUpPort.createForDischarge} is
 * idempotent, validates its input and joins the caller's transaction. Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeFollowUpIT extends DischargeSupport {

    private static final long DOCTOR_FEE = 30_000L;      // Dr Asha Rao, ₹300

    @Autowired
    private FollowUpPort port;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** Admits, signs and discharges a patient of the clinic's doctor; returns {admissionId, patientId}. */
    private String[] discharged(Clinic clinic, String type) throws Exception {
        String id = quickAdmit(clinic).get("id").asText();
        sign(clinic, id, type, "general");
        dischargeNow(clinic, id, type, "general", "UPI");
        return new String[] {id, patientOf(clinic, id)};
    }

    private void backdateFollowUp(Clinic clinic, String admissionId, int days) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update opd.follow_up set created_at = created_at - make_interval(days => ?) "
                + "where source_admission_id = ?::uuid", days, admissionId);
    }

    // ---------------- the list ----------------

    @Test
    void theFollowUpAppearsInTheListWithTheAdmissionAsSourceAndNoVisit() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String[] a = discharged(clinic, "normal");
        JsonNode list = followUps(clinic, "UPCOMING");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        JsonNode item = followUpOf(list, a[0]);
        assertThat(item).isNotNull();
        assertThat(item.get("sourceVisitId").isNull()).isTrue();
        assertThat(item.get("sourceAdmissionId").asText()).isEqualTo(a[0]);
        assertThat(item.get("patientId").asText()).isEqualTo(a[1]);
        assertThat(item.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(item.get("doctorMembershipId").asText()).isEqualTo(clinic.doctorMembershipId().toString());
        assertThat(item.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(item.get("dueDate").asText()).isEqualTo(day.plusDays(7).toString());
        assertThat(item.get("reason").asText()).isEqualTo("Review after discharge · Fever");
        assertThat(item.get("status").asText()).isEqualTo("UPCOMING");
        assertThat(item.get("daysLate").asInt()).isZero();
        assertThat(item.hasNonNull("mobile")).isTrue();
        assertThat(list.get("counts").get("upcoming").asInt()).isOne();

        // it moves through the tabs like any other: due today, then missed
        commitAsTenant(clinic.tenantId(), "update opd.follow_up set due_date = ?::date where source_admission_id = ?::uuid",
                day.toString(), a[0]);
        assertThat(followUpOf(followUps(clinic, "DUE"), a[0]).get("status").asText()).isEqualTo("DUE");
        commitAsTenant(clinic.tenantId(), "update opd.follow_up set due_date = ?::date where source_admission_id = ?::uuid",
                day.minusDays(3).toString(), a[0]);
        JsonNode missed = followUpOf(followUps(clinic, "MISSED"), a[0]);
        assertThat(missed.get("status").asText()).isEqualTo("MISSED");
        assertThat(missed.get("daysLate").asInt()).isEqualTo(3);
    }

    @Test
    void aTokenForTheSamePatientAndDoctorClosesTheDischargeFollowUpAndOthersDoNot() throws Exception {
        Clinic clinic = hospital();
        String[] a = discharged(clinic, "normal");
        String[] other = discharged(clinic, "normal");
        Member secondDoctor = addMember(clinic, "DOCTOR");

        issueToken(clinic, a[1], secondDoctor.membershipId());                       // another doctor
        issueToken(clinic, newPatient(clinic).toString(), clinic.doctorMembershipId());                                         // another patient
        JsonNode open = followUps(clinic, "UPCOMING");
        assertThat(followUpOf(open, a[0]).get("returnedVisitId").isNull()).isTrue();
        assertThat(open.get("counts").get("returned").asInt()).isZero();

        JsonNode visit = issueToken(clinic, a[1], clinic.doctorMembershipId());      // the admitting doctor
        JsonNode returned = followUps(clinic, "RETURNED");
        JsonNode item = followUpOf(returned, a[0]);
        assertThat(item).isNotNull();
        assertThat(item.get("status").asText()).isEqualTo("RETURNED");
        assertThat(item.get("returnedVisitId").asText()).isEqualTo(visit.get("id").asText());
        assertThat(item.get("sourceVisitId").isNull()).isTrue();
        assertThat(returned.get("counts").get("returned").asInt()).isOne();
        assertThat(followUpOf(followUps(clinic, "UPCOMING"), other[0])).as("the other patient's stays open").isNotNull();
        assertThat(count(clinic, "select count(*) from opd.follow_up where returned_visit_id is not null")).isOne();
        // closing is audited like any return
        assertThat(audits(clinic, "FOLLOW_UP_RETURNED")).isOne();
    }

    // ---------------- the fee window ----------------

    @Test
    void aReturnWithinTheFollowUpWindowAfterDischargeIsAFollowUpWithTheFeeOfTheClinicsRule() throws Exception {
        Clinic clinic = hospital();
        String[][] cases = {{"free", "0"}, {"half", "15000"}, {"full", "30000"}};
        for (String[] c : cases) {
            setFees(clinic, 7, c[0]);
            String[] a = discharged(clinic, "normal");
            JsonNode visit = issueToken(clinic, a[1], clinic.doctorMembershipId());
            assertThat(visit.get("visitType").asText()).as(c[0]).isEqualTo("FOLLOW_UP");
            assertThat(visit.get("feePaise").asLong()).as(c[0]).isEqualTo(Long.parseLong(c[1]));
        }
    }

    @Test
    void theWindowStartsAtTheDischargeNotAtAnOlderVisitAndEndsAfterTheConfiguredDays() throws Exception {
        Clinic clinic = hospital();
        setFees(clinic, 3, "free");
        String[] inside = discharged(clinic, "normal");
        String[] outside = discharged(clinic, "normal");
        backdateFollowUp(clinic, inside[0], 2);
        backdateFollowUp(clinic, outside[0], 4);
        JsonNode in = issueToken(clinic, inside[1], clinic.doctorMembershipId());
        JsonNode out = issueToken(clinic, outside[1], clinic.doctorMembershipId());
        assertThat(in.get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(in.get("feePaise").asLong()).isZero();
        assertThat(out.get("visitType").asText()).isEqualTo("NEW");
        assertThat(out.get("feePaise").asLong()).isEqualTo(DOCTOR_FEE);
    }

    @Test
    void theAnchorIsTheLaterOfTheLastVisitAndTheLastDischarge() throws Exception {
        Clinic clinic = hospital();
        setFees(clinic, 3, "free");
        // an old visit, then a discharge today: the discharge is later
        String[] a = discharged(clinic, "normal");
        // a discharge long ago, then a visit yesterday: the visit is later
        String[] b = discharged(clinic, "normal");
        backdateFollowUp(clinic, b[0], 20);
        JsonNode first = issueToken(clinic, b[1], clinic.doctorMembershipId());       // outside the window: NEW
        assertThat(first.get("visitType").asText()).isEqualTo("NEW");
        BackdateSupport.setVisitCreatedAt(db(), clinic.tenantId(), first.get("id").asText(), Instant.now().minus(1, ChronoUnit.DAYS));
        assertThat(issueToken(clinic, b[1], clinic.doctorMembershipId()).get("visitType").asText())
                .as("the visit of yesterday is inside the 3 days").isEqualTo("FOLLOW_UP");

        JsonNode oldVisit = issueToken(clinic, a[1], clinic.doctorMembershipId());
        // the token above already counts the discharge as a visit; make it the old one and check the discharge alone
        assertThat(oldVisit.get("visitType").asText()).isEqualTo("FOLLOW_UP");
        BackdateSupport.setVisitCreatedAt(db(), clinic.tenantId(), oldVisit.get("id").asText(), Instant.now().minus(20, ChronoUnit.DAYS));
        assertThat(issueToken(clinic, a[1], clinic.doctorMembershipId()).get("visitType").asText())
                .as("the discharge of today is inside the window although the visit is 20 days old").isEqualTo("FOLLOW_UP");
    }

    @Test
    void anotherDoctorAndADeathDischargeHaveNoDischargeWindow() throws Exception {
        Clinic clinic = hospital();
        String[] a = discharged(clinic, "normal");
        String[] death = discharged(clinic, "death");
        Member secondDoctor = addMember(clinic, "DOCTOR");
        JsonNode other = issueToken(clinic, a[1], secondDoctor.membershipId());
        assertThat(other.get("visitType").asText()).isEqualTo("NEW");
        assertThat(other.get("feePaise").asLong()).isEqualTo(25_000L);               // the second doctor's fee, ₹250
        JsonNode afterDeath = issueToken(clinic, death[1], clinic.doctorMembershipId());
        assertThat(afterDeath.get("visitType").asText()).isEqualTo("NEW");
        assertThat(afterDeath.get("feePaise").asLong()).isEqualTo(DOCTOR_FEE);
    }

    // ---------------- the port ----------------

    @Test
    void createForDischargeIsIdempotentPerAdmissionAndAuditsOnceWithoutTheReason() throws Exception {
        Clinic clinic = hospital();
        String[] a = discharged(clinic, "normal");
        UUID fresh = UUID.randomUUID();
        UUID patient = UUID.fromString(a[1]);
        LocalDate due = istToday().plusDays(5);
        DischargeFollowUp request = new DischargeFollowUp(fresh, patient, clinic.doctorMembershipId(), due, "Review after discharge · Test");
        var auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");

        UUID first = runAs(auth, () -> tx().execute(s -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(), request)));
        UUID second = runAs(auth, () -> tx().execute(s -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(), request)));
        assertThat(second).isEqualTo(first);
        assertThat(count(clinic, "select count(*) from opd.follow_up where source_admission_id = ?::uuid", fresh)).isOne();
        assertThat(audits(clinic, "FOLLOW_UP_CREATED")).as("the discharge's own plus this one").isEqualTo(2);
        assertThat(values(clinic, "select id::text, due_date::text, reason from opd.follow_up where source_admission_id = ?::uuid",
                fresh)).containsExactly(first.toString(), due.toString(), "Review after discharge · Test");

        // a second call with other values for the same admission changes nothing and returns the first id
        DischargeFollowUp changed = new DischargeFollowUp(fresh, patient, clinic.doctorMembershipId(), due.plusDays(9), "Other");
        UUID third = runAs(auth, () -> tx().execute(s -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(), changed)));
        assertThat(third).isEqualTo(first);
        assertThat(values(clinic, "select due_date::text, reason from opd.follow_up where source_admission_id = ?::uuid", fresh))
                .containsExactly(due.toString(), "Review after discharge · Test");
        // the admission that was discharged through the API: the port returns its follow-up, writes nothing
        DischargeFollowUp again = new DischargeFollowUp(UUID.fromString(a[0]), patient, clinic.doctorMembershipId(), due, "x");
        UUID existing = runAs(auth, () -> tx().execute(s -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(), again)));
        assertThat(values(clinic, "select id::text from opd.follow_up where source_admission_id = ?::uuid", a[0]))
                .containsExactly(existing.toString());
        assertThat(audits(clinic, "FOLLOW_UP_CREATED")).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from platform.audit_event where action = 'FOLLOW_UP_CREATED' "
                + "and audit_event::text like '%Test%'")).isZero();
    }

    @Test
    void createForDischargeRefusesMissingFieldsAndBlankOrTooLongReasonsAndNeedsATransaction() throws Exception {
        Clinic clinic = hospital();
        UUID patient = UUID.fromString(discharged(clinic, "death")[1]);
        UUID doctor = clinic.doctorMembershipId();
        var auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");
        LocalDate due = istToday().plusDays(7);
        DischargeFollowUp[] bad = {
                new DischargeFollowUp(null, patient, doctor, due, "r"), new DischargeFollowUp(UUID.randomUUID(), null, doctor, due, "r"),
                new DischargeFollowUp(UUID.randomUUID(), patient, null, due, "r"),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, null, "r"),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, due, null),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, due, ""),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, due, "   "),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, due, "a".repeat(201)),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, due, "😀".repeat(201))};
        for (DischargeFollowUp b : bad) {
            assertThatThrownBy(() -> runAs(auth, () -> tx().execute(s -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(), b))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // exactly 200 code points (also as 200 surrogate pairs) is accepted
        for (String reason : new String[] {"a".repeat(200), "😀".repeat(200)}) {
            UUID source = UUID.randomUUID();
            runAs(auth, () -> tx().execute(s -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(),
                    new DischargeFollowUp(source, patient, doctor, due, reason))));
            assertThat(count(clinic, "select count(*) from opd.follow_up where source_admission_id = ? and char_length(reason) = 200",
                    source)).isOne();
        }
        // outside a transaction the port refuses: it never starts its own
        assertThatThrownBy(() -> runAs(auth, () -> port.createForDischarge(clinic.tenantId(), clinic.ownerUserId(),
                new DischargeFollowUp(UUID.randomUUID(), patient, doctor, due, "r")))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> runAs(auth, () -> port.feeRule(clinic.tenantId()))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theFeeRuleOfThePortIsTheOpdFeeSettingWithItsDefaultsWhenNeverSaved() throws Exception {
        Clinic fresh = hospital();
        var auth = authentication(fresh.tenantId(), fresh.ownerUserId(), "OWNER");
        FollowUpPort.FeeRule wizard = runAs(auth, () -> tx().execute(s -> port.feeRule(fresh.tenantId())));
        assertThat(wizard.days()).isEqualTo(7);
        assertThat(wizard.fee()).isEqualTo("free");
        setFees(fresh, 4, "full");
        FollowUpPort.FeeRule saved = runAs(auth, () -> tx().execute(s -> port.feeRule(fresh.tenantId())));
        assertThat(saved).isEqualTo(new FollowUpPort.FeeRule(4, "full"));
    }

    @Test
    void thePortCannotReachAnotherClinicsPatientOrDoctorAndWritesNothing() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        UUID patientA = UUID.fromString(discharged(a, "death")[1]);
        var asB = authentication(b.tenantId(), b.ownerUserId(), "OWNER");
        UUID source = UUID.randomUUID();
        // clinic B's context with clinic A's patient or doctor: the tenant-composite foreign keys (V2026_11_21_1000) refuse with 23503
        assertThatThrownBy(() -> runAs(asB, () -> tx().execute(s -> port.createForDischarge(b.tenantId(), b.ownerUserId(),
                new DischargeFollowUp(source, patientA, b.doctorMembershipId(), istToday().plusDays(7), "r")))))
                .satisfies(DischargeFollowUpIT::assertForeignKeyViolation);
        UUID patientB = UUID.fromString(patientOf(b, quickAdmit(b).get("id").asText()));
        assertThatThrownBy(() -> runAs(asB, () -> tx().execute(s -> port.createForDischarge(b.tenantId(), b.ownerUserId(),
                new DischargeFollowUp(source, patientB, a.doctorMembershipId(),
                        istToday().plusDays(7), "r"))))).satisfies(DischargeFollowUpIT::assertForeignKeyViolation);
        assertThat(count(a, "select count(*) from opd.follow_up where source_admission_id = ?", source)).isZero();
        assertThat(count(b, "select count(*) from opd.follow_up where source_admission_id = ?", source)).isZero();
    }

    /** The root cause is a PostgreSQL foreign key violation (SQLState 23503): the key is (tenant_id, id), not id alone. */
    private static void assertForeignKeyViolation(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("23503");
    }
}
