package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.VitalsRepository.NewVitals;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-30: the statements of the new repositories. Each insert names only the columns the database role may write
 * (ADR-006 D1), the escalation update changes score, risk and target together and only upwards, and the admission
 * lock is {@code for no key update}. The statements themselves are run against the real database by the QA tests.
 */
class JdbcVitalsRepositoriesTest {

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
        return sql.getValue();
    }

    @Test
    void theVitalsInsertNamesOnlyTheGrantedColumnsAndNeverTheTimeOrTheClinic() {
        String sql = sqlSent(() -> new JdbcVitalsRepository(jdbc).insert(TENANT, new NewVitals(ID, ID, 120, 80, 80,
                98, new BigDecimal("37.0"), 16, 0, Risk.LOW, false, ID)));

        assertThat(sql).contains("insert into ipd.vitals (admission_id, client_key, bp_systolic, bp_diastolic, pulse, "
                + "spo2, temp_c, rr,").contains("ews_score, ews_risk, note_added, recorded_by)")
                .contains("returning id, recorded_at").doesNotContain("recorded_at,").doesNotContain("tenant_id")
                .doesNotContain("update ").doesNotContain("delete ");
    }

    @Test
    void theNoteInsertNamesOnlyTheGrantedColumns() {
        String sql = sqlSent(() -> new JdbcVitalsRepository(jdbc).insertNote(TENANT, ID, "x", ID));

        assertThat(sql).contains("insert into ipd.nursing_note (admission_id, note, recorded_by)")
                .doesNotContain("recorded_at").doesNotContain("tenant_id");
    }

    @Test
    void theLatestRecordIsReadNewestFirstWithTheIdAsTieBreak() {
        assertThat(sqlSent(() -> new JdbcVitalsRepository(jdbc).findLatest(TENANT, ID)))
                .contains("order by v.recorded_at desc, v.id desc").contains("limit 1")
                .contains("v.tenant_id = :tenantId");
    }

    @Test
    void theRecentNotesAreReadNewestFirstWithTheIdAsTieBreak() {
        assertThat(sqlSent(() -> new JdbcVitalsRepository(jdbc).findRecentNotes(TENANT, ID, 3)))
                .contains("order by recorded_at desc, id desc").contains("limit :limit")
                .contains("tenant_id = :tenantId");
    }

    @Test
    void theClientKeyLookupIsScopedToTheClinic() {
        assertThat(sqlSent(() -> new JdbcVitalsRepository(jdbc).findByClientKey(TENANT, ID)))
                .contains("v.tenant_id = :tenantId").contains("v.client_key = :clientKey");
    }

    @Test
    void theEscalationInsertNamesOnlyTheGrantedColumns() {
        String sql = sqlSent(() -> new JdbcEscalationRepository(jdbc).insert(TENANT, ID, 6, Risk.MEDIUM, 60, ID));

        assertThat(sql).contains("insert into ipd.ews_escalation (admission_id, ews_score, ews_risk, target_minutes, "
                + "raised_by)").doesNotContain("status").doesNotContain("raised_at").doesNotContain("version");
    }

    @Test
    void theOpenEscalationIsTheOneThatIsNotDone() {
        assertThat(sqlSent(() -> new JdbcEscalationRepository(jdbc).findOpen(TENANT, ID)))
                .contains("status <> 'DONE'").contains("tenant_id = :tenantId").contains("admission_id");
    }

    @Test
    void theEscalationUpdateSetsScoreRiskTargetAndVersionTogetherAndOnlyUpwards() {
        String sql = sqlSent(() -> new JdbcEscalationRepository(jdbc).raiseScore(TENANT, ID, 8, Risk.HIGH, 15));

        assertThat(sql).contains("set ews_score = :score, ews_risk = :risk, target_minutes = :target, "
                + "version = version + 1").contains("ews_score < :score").contains("status <> 'DONE'")
                .contains("tenant_id = :tenantId").doesNotContain("raised_at").doesNotContain("raised_by");
    }

    @Test
    void anUpdateThatChangesNoRowIsReportedAsFalse() {
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.update()).thenReturn(0);
        assertThat(new JdbcEscalationRepository(jdbc).raiseScore(TENANT, ID, 8, Risk.HIGH, 15)).isFalse();

        when(spec.update()).thenReturn(1);
        assertThat(new JdbcEscalationRepository(jdbc).raiseScore(TENANT, ID, 8, Risk.HIGH, 15)).isTrue();
    }

    @Test
    void theEventInsertNamesOnlyTheGrantedColumns() {
        when(jdbc.sql(anyString())).thenReturn(spec);
        new JdbcClinicalEventRepository(jdbc).insert(TENANT, ID, "info", "Recorded vitals", ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("insert into ipd.clinical_event (admission_id, kind, text, actor)")
                .doesNotContain("seq").doesNotContain(" at");
    }

    /**
     * Runs a lock method with a statement mock whose first row read finds the admission (the lock) and returns every
     * SQL text sent, in order. Code review SAH-30 M2: the bare row is locked first, the joined row is read after.
     */
    @SuppressWarnings("unchecked")
    private List<String> lockStatements(Runnable call, Optional<UUID> lockResult) {
        MappedQuerySpec<Object> mapped = mock(MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.optional()).thenReturn((Optional<Object>) (Optional<?>) lockResult, Optional.empty());
        call.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).sql(sql.capture());
        return sql.getAllValues();
    }

    @Test
    void theNoKeyUpdateLockIsTakenOnTheBareAdmissionRowAndTheJoinedRowIsReadAfterIt() {
        List<String> sql = lockStatements(() -> new JdbcAdmissionRepository(jdbc).findForNoKeyUpdate(TENANT, ID),
                Optional.of(ID));

        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).contains("select id, status from ipd.admission").contains("for no key update")
                .contains("tenant_id = :tenantId and id = :id").doesNotContain("join").doesNotContain("ipd.bed")
                .doesNotContain("ipd.ward").doesNotContain("for update").doesNotContain(" of a");
        assertThat(sql.get(1)).contains("join ipd.bed b").contains("join ipd.ward w")
                .contains("a.tenant_id = :tenantId and a.id = :id").doesNotContain("for update")
                .doesNotContain("for no key update");
    }

    @Test
    void theForUpdateLockOfDischargeAndTransferIsTakenOnTheBareAdmissionRowAndTheJoinedRowIsReadAfterIt() {
        List<String> sql = lockStatements(() -> new JdbcAdmissionRepository(jdbc).findForUpdate(TENANT, ID),
                Optional.of(ID));

        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).contains("select id, status from ipd.admission").contains("for update")
                .doesNotContain("no key").contains("tenant_id = :tenantId and id = :id").doesNotContain("join")
                .doesNotContain("ipd.bed");
        assertThat(sql.get(1)).contains("join ipd.bed b").doesNotContain("for update");
    }

    @Test
    void anAdmissionThatIsMissingOrHiddenByRowLevelSecurityIsEmptyAndTheJoinedReadIsNotRun() {
        List<String> noKey = lockStatements(() -> assertThat(
                new JdbcAdmissionRepository(jdbc).findForNoKeyUpdate(TENANT, ID)).isEmpty(), Optional.empty());

        assertThat(noKey).hasSize(1);
        assertThat(noKey.get(0)).contains("for no key update").doesNotContain("join");
    }

    @Test
    void theDatabaseClockIsClockTimestampNotTheTransactionTime() {
        String sql = sqlSent(() -> new JdbcVitalsRepository(jdbc).databaseNow());

        assertThat(sql).contains("clock_timestamp()").doesNotContain("now()");
    }
}
