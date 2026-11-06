package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.ClinicalEventRepository.Position;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.ResponseAction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-33: the statements of the Safety board repositories. The escalation updates are exactly those the database guard
 * trigger accepts (docs/database/SAH-33.md): each carries the status and version it expects, none sends a time the
 * trigger stamps (acknowledged_at, closed_at, reopened_at), and a senior call leaves the version alone. The statements
 * are run against the real database by the QA tests.
 */
class JdbcEscalationBoardRepositoryTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private String sqlSent(Runnable call) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            call.run();
        } catch (RuntimeException e) {
            // the row mapping is not under test; the mock returns nothing after the statement
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue().replaceAll("\\s+", " ");
    }

    private JdbcEscalationRepository repository() {
        return new JdbcEscalationRepository(jdbc);
    }

    private static void neverSendsTheStampedTimes(String sql) {
        assertThat(sql).doesNotContain("acknowledged_at").doesNotContain("closed_at").doesNotContain("reopened_at")
                .doesNotContain("raised_at =").doesNotContain("raised_by =").doesNotContain("admission_id =")
                .doesNotContain("delete ");
    }

    @Test
    void acknowledgeIsOpenToAckByTheDoctorAtTheSentVersionWithVersionPlusOne() {
        String sql = sqlSent(() -> repository().acknowledge(TENANT, ID, UUID.randomUUID(), 3));

        assertThat(sql).contains("update ipd.ews_escalation").contains("set status = 'ACK'")
                .contains("acknowledged_by = :doctor").contains("version = version + 1")
                .contains("tenant_id = :tenantId and id = :id and status = 'OPEN' and version = :sent");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void closeFromOpenAlsoStoresTheDoctorAsTheAcknowledgingDoctorAndNeedsOpenAtTheSentVersion() {
        String sql = sqlSent(() -> repository().close(TENANT, ID, Status.OPEN, UUID.randomUUID(),
                ResponseAction.NO_CHANGE, "Plan", 3));

        assertThat(sql).contains("set status = 'DONE'").contains("response_action = :action")
                .contains("response_note = :note").contains("closed_by = :doctor")
                .contains("acknowledged_by = :doctor").contains("version = version + 1")
                .contains("status = 'OPEN' and version = :sent");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void closeFromAckLeavesTheAcknowledgementAloneAndNeedsAckAtTheSentVersion() {
        String sql = sqlSent(() -> repository().close(TENANT, ID, Status.ACK, UUID.randomUUID(),
                ResponseAction.NO_CHANGE, "Plan", 3));

        assertThat(sql).contains("set status = 'DONE'").contains("closed_by = :doctor")
                .contains("version = version + 1").contains("status = 'ACK' and version = :sent")
                .doesNotContain("acknowledged_by");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void callSeniorAddsOneCallOnlyAndNeverTouchesTheVersionOrTheStatus() {
        String sql = sqlSent(() -> repository().callSenior(TENANT, ID, 3, 0));

        assertThat(sql).contains("set senior_calls = senior_calls + 1")
                .contains("status = 'OPEN' and version = :sent and senior_calls = :seen")
                .doesNotContain("version = version").doesNotContain("set status");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void reopenSendsExactlyTheColumnsTheGuardAcceptsAndNeverTheStampedTimes() {
        String sql = sqlSent(() -> repository().reopen(TENANT, ID, 9));

        assertThat(sql).contains("set status = 'OPEN', acknowledged_by = null, ews_score = :score, "
                + "ews_risk = 'HIGH', target_minutes = 15, version = version + 1")
                .contains("status = 'ACK' and ews_risk = 'MEDIUM'");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void closeAtDischargeSendsNoNoteAndNoAcknowledgementAndNeverTheStampedTimes() {
        String sql = sqlSent(() -> repository().closeAtDischarge(TENANT, ID, UUID.randomUUID()));

        assertThat(sql).contains("set status = 'DONE', response_action = 'CLOSED_AT_DISCHARGE', closed_by = :user, "
                + "version = version + 1").contains("status <> 'DONE'").doesNotContain("response_note")
                .doesNotContain("acknowledged_by");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void theExistingScoreRaiseStillSendsScoreRiskTargetAndVersionPlusOne() {
        String sql = sqlSent(() -> repository().raiseScore(TENANT, ID, 9, com.sahaj.ipd.domain.EarlyWarningScore.Risk.HIGH,
                15));

        assertThat(sql).contains("set ews_score = :score, ews_risk = :risk, target_minutes = :target, "
                + "version = version + 1").contains("ews_score < :score");
        neverSendsTheStampedTimes(sql);
    }

    @Test
    void theBoardReadJoinsTheAdmittedAdmissionAndItsBedInOneQueryWithTheTimesAndTheCloser() {
        String sql = sqlSent(() -> repository().findOnBoard(TENANT, Instant.parse("2026-11-11T06:00:00Z")));

        assertThat(sql).contains("from ipd.ews_escalation e join ipd.admission a").contains("join ipd.bed b")
                .contains("a.status = 'ADMITTED'").contains("e.tenant_id = :tenantId").contains("a.patient_id")
                .contains("b.code as bed_code").contains("e.reopened_at").contains("e.acknowledged_at")
                .contains("e.closed_by").contains("e.senior_calls").contains("e.version");
    }

    @Test
    void theBoardReadKeepsClosedRowsOfTheLast24HoursOnlyAgainstTheGivenClock() {
        String sql = sqlSent(() -> repository().findOnBoard(TENANT, Instant.parse("2026-11-11T06:00:00Z")));

        assertThat(sql).contains("(e.status <> 'DONE' or e.closed_at > cast(:asOf as timestamptz) - interval "
                + "'24 hours')").doesNotContain("clock_timestamp");
        // the filter is inside the same where, after the admitted check: one query, no second read
        assertThat(sql.indexOf("a.status = 'ADMITTED'")).isLessThan(sql.indexOf("interval '24 hours'"));
    }

    @Test
    void theBoardReadPassesTheClockAsTheAsOfParameter() {
        Instant asOf = Instant.parse("2026-11-11T06:00:00Z");
        sqlSent(() -> repository().findOnBoard(TENANT, asOf));

        verify(spec).param("asOf", java.time.OffsetDateTime.ofInstant(asOf, java.time.ZoneOffset.UTC));
    }

    @Test
    void theOpenReadForTheDischargeCarriesTheReopenTimeSoTheClockStartIsKnown() {
        String sql = sqlSent(() -> repository().findOpen(TENANT, ID));

        assertThat(sql).contains("raised_at, reopened_at").contains("status <> 'DONE'");
    }

    @Test
    void theLiveReadIsTheSameQueryRestrictedToNotClosedEscalations() {
        String sql = sqlSent(() -> repository().findLiveOnBoard(TENANT));

        assertThat(sql).contains("a.status = 'ADMITTED'").contains("e.status <> 'DONE'")
                .contains("join ipd.admission a");
    }

    @Test
    void findByIdIsScopedToTheTenantAndReadsNoOtherTable() {
        String sql = sqlSent(() -> repository().findById(TENANT, ID));

        assertThat(sql).contains("e.tenant_id = :tenantId and e.id = :id").doesNotContain("join");
    }

    @Test
    void theClockIsTheDatabaseClockTimestamp() {
        assertThat(sqlSent(() -> repository().databaseNow())).contains("clock_timestamp()");
    }

    // ---- the clinical event page ----

    @Test
    void theFirstPageForOwnerAndDoctorHasNoScopeAndNoCursorAndIsNewestFirst() {
        String sql = sqlSent(() -> new JdbcClinicalEventRepository(jdbc).findPage(TENANT, null, null, 15));

        assertThat(sql).contains("from ipd.clinical_event e left join ipd.admission a")
                .contains("left join ipd.bed b").contains("e.tenant_id = :tenantId")
                .contains("order by e.at desc, e.seq desc limit :limit").doesNotContain("a.status = 'ADMITTED'")
                .doesNotContain("(e.at, e.seq) <").doesNotContain("update ").doesNotContain("delete ");
    }

    @Test
    void theNurseScopeIsHerOwnEntriesOrThoseOfAdmittedPatients() {
        String sql = sqlSent(() -> new JdbcClinicalEventRepository(jdbc).findPage(TENANT, UUID.randomUUID(), null,
                15));

        assertThat(sql).contains("and (e.actor = :nurse or a.status = 'ADMITTED')");
    }

    @Test
    void theNextPageIsAKeysetOnAtAndSeqNeverAnOffset() {
        String sql = sqlSent(() -> new JdbcClinicalEventRepository(jdbc).findPage(TENANT, null,
                new Position(Instant.parse("2026-11-11T05:30:00Z"), 5), 51));

        assertThat(sql).contains("and (e.at, e.seq) < (:afterAt, :afterSeq)")
                .contains("order by e.at desc, e.seq desc limit :limit").doesNotContain("offset");
    }
}
