package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.EmergencyCaseRepository.NewCase;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-28 statements of the emergency repositories, checked without a database: each update names the statuses it may
 * start from and sets every column the table checks need in one statement, an insert lists only the columns the
 * database role may write, and the setup upsert writes the five new columns only. The behaviour against the real
 * database (grants, checks, triggers, row-level security) is tested by the integration tests.
 */
class JdbcEmergencyRepositoriesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-11T05:30:00Z");

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private void updates(int updateCount) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.update()).thenReturn(updateCount);
    }

    private String sentSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue().replaceAll("\\s+", " ").trim();
    }

    @Test
    void markSeenStartsFromWaitingOnlyAndSetsTheTimeAndTheUserTogether() {
        updates(1);
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        assertThat(repository.markSeen(TENANT, ID, AT, UUID.randomUUID())).isTrue();

        assertThat(sentSql()).contains("status = 'SEEN'").doesNotContain("mobile").contains("seen_at = :at").contains("seen_by = :by")
                .contains("version = version + 1").contains("tenant_id = :tenantId").contains("status = 'WAITING'");
    }

    @Test
    void anUpdateThatChangesNoRowReturnsFalse() {
        updates(0);
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        assertThat(repository.close(TENANT, ID, "RELEASED", AT, UUID.randomUUID())).isFalse();
    }

    @Test
    void closeSetsTheStatusAndTheClosureTogetherAndOnlyFromAnOpenStatus() {
        updates(1);
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        assertThat(repository.close(TENANT, ID, "REFERRED", AT, UUID.randomUUID())).isTrue();

        assertThat(sentSql()).contains("status = :status").contains("closed_at = :at").contains("closed_by = :by")
                .contains("status in ('WAITING', 'SEEN')").contains("version = version + 1")
                .contains("mobile = null").doesNotContain("admission_id").doesNotContain("patient_id");
    }

    @Test
    void markAdmittedSetsStatusClosureAdmissionAndPatientInOneUpdate() {
        updates(1);
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        assertThat(repository.markAdmitted(TENANT, ID, UUID.randomUUID(), UUID.randomUUID(), AT, UUID.randomUUID()))
                .isTrue();

        assertThat(sentSql()).contains("status = 'ADMITTED'").contains("admission_id = :admissionId")
                .contains("patient_id = :patientId").contains("closed_at = :at").contains("closed_by = :by")
                .contains("mobile = null").contains("status in ('WAITING', 'SEEN')");
    }

    @Test
    void updateLevelChangesTheLevelOnlyOfAnOpenCaseAndNeverTheBayOrArrival() {
        updates(1);
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        assertThat(repository.updateLevel(TENANT, ID, 3)).isTrue();

        assertThat(sentSql()).contains("set level = :level").contains("status in ('WAITING', 'SEEN')")
                .doesNotContain("bay").doesNotContain("arrived_at").doesNotContain("mobile").doesNotContain("patient_id");
    }

    @Test
    void theInsertListsOnlyTheColumnsTheDatabaseRoleMayWrite() {
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<UUID>>any()))
                .thenReturn(mock(JdbcClient.MappedQuerySpec.class));
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        repository.insert(TENANT, new NewCase(UUID.randomUUID(), "Name", 40, "M", null, "Complaint", false, 1,
                "ER-1", UUID.randomUUID()));

        String sql = sentSql();
        assertThat(sql).startsWith("insert into ipd.emergency_case (display_name, age, sex, mobile, complaint, "
                + "identity_pending, level, bay, registered_by, client_key)");
        assertThat(sql).doesNotContain("status").doesNotContain("version").doesNotContain("patient_id")
                .contains("returning id, arrived_at");
        assertThat(sql.substring(0, sql.indexOf("values"))).doesNotContain("arrived_at");
    }

    @Test
    void theSetupUpsertWritesTheFiveEmergencyColumnsAsAnArrayLiteral() {
        updates(1);
        JdbcIpdSettingsRepository repository = new JdbcIpdSettingsRepository(jdbc);

        repository.upsertEmergencySetup(TENANT,
                new EmergencySetup(5, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 4, false), UUID.randomUUID(), AT);

        assertThat(sentSql()).contains("insert into ipd.config").contains("triage_scale").contains("triage_targets_3")
                .contains("triage_targets_5").contains("er_bays").contains("unknown_patient_registration")
                .contains("on conflict (tenant_id) do update").contains("cast(:targets3 as smallint[])")
                .doesNotContain("room_level").doesNotContain("dep_planned_paise");
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(spec, org.mockito.Mockito.atLeastOnce()).param(anyString(), values.capture());
        assertThat(values.getAllValues()).contains("{5,30,120}", "{0,10,30,60,120}");
    }

    @Test
    void theTriageEventInsertListsOnlyWritableColumnsAndAllowsANullFromLevel() {
        updates(1);
        JdbcTriageEventRepository repository = new JdbcTriageEventRepository(jdbc);

        repository.insert(TENANT, ID, null, 2, 3, "REGISTERED", UUID.randomUUID());

        assertThat(sentSql()).isEqualTo("insert into ipd.emergency_triage_event (case_id, from_level, level, scale, "
                + "reason, recorded_by) values (:caseId, :fromLevel, :level, :scale, :reason, :by)");
    }

    @SuppressWarnings("unchecked")
    private void queries() {
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()))
                .thenReturn(mock(JdbcClient.MappedQuerySpec.class));
    }

    @Test
    void onlyTheCaseClosingUpdatesSendTheMobileAndThenOnlyAsNull() {
        updates(1);
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);
        repository.markSeen(TENANT, ID, AT, UUID.randomUUID());
        repository.updateLevel(TENANT, ID, 2);
        repository.close(TENANT, ID, "RELEASED", AT, UUID.randomUUID());
        repository.markAdmitted(TENANT, ID, UUID.randomUUID(), UUID.randomUUID(), AT, UUID.randomUUID());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4)).sql(sql.capture());
        assertThat(sql.getAllValues().get(0)).doesNotContain("mobile");
        assertThat(sql.getAllValues().get(1)).doesNotContain("mobile");
        assertThat(sql.getAllValues().get(2)).contains("mobile = null").doesNotContain(":mobile");
        assertThat(sql.getAllValues().get(3)).contains("mobile = null").doesNotContain(":mobile");
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(spec, org.mockito.Mockito.atLeastOnce()).param(names.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(names.getAllValues()).doesNotContain("mobile");
    }

    @Test
    void thePlainReadsLeaveTheMobileOutAndOnlyTheHandoffReadSelectsIt() {
        queries();
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);
        repository.findOpen(TENANT);
        repository.find(TENANT, ID);
        repository.findForUpdate(TENANT, ID);
        repository.findByClientKey(TENANT, UUID.randomUUID());
        repository.findForHandoff(TENANT, ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(5)).sql(sql.capture());
        List<String> sent = sql.getAllValues().stream().map(text -> text.replaceAll("\\s+", " ").trim()).toList();
        for (int i = 0; i < 4; i++) {
            assertThat(sent.get(i)).as("read " + i).contains("cast(null as text) as mobile")
                    .doesNotContain(", mobile,");
        }
        assertThat(sent.get(2)).endsWith("for update");
        assertThat(sent.get(4)).contains("sex, mobile, complaint").doesNotContain("cast(null");
    }

    @Test
    void theDatabaseClockIsReadWithClockTimestampNotWithNow() {
        queries();
        JdbcEmergencyCaseRepository repository = new JdbcEmergencyCaseRepository(jdbc);

        repository.databaseNow();

        assertThat(sentSql()).startsWith("select clock_timestamp()").doesNotContain("now()");
    }
}
