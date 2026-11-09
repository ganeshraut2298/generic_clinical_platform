package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.CardSetupRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-37 (ADR-015 D1, D5, D10): the statements of the two card setup tables and the readings variant of the latest
 * vitals read. They run against PostgreSQL in the integration tests of the qa-engineer; here the shape that matters for
 * the grants, the cost and the privacy is checked: only the columns the database role may write, the tenant in every
 * {@code where}, one index probe for the latest version, and no table of another module.
 */
class JdbcCardSetupRepositoryTest {

    private static final UUID TENANT = UUID.randomUUID();

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

    private JdbcCardSetupRepository repository() {
        return new JdbcCardSetupRepository(jdbc);
    }

    @Test
    void theLatestVersionIsOneProbeOfTheUniqueIndexBackwardsWithLimitOne() {
        String sql = sqlSent(() -> repository().findLatestVersion(TENANT));

        assertThat(sql).contains("from ipd.card_setup_version").contains("where tenant_id = :tenantId")
                .contains("order by version desc").contains("limit 1").contains("cast(setup as text)")
                .doesNotContain("patient").doesNotContain("insert ").doesNotContain("update ");
    }

    @Test
    void theVersionListIsNewestFirstWithoutTheSetupText() {
        String sql = sqlSent(() -> repository().findVersionHeads(TENANT, 50));

        assertThat(sql).contains("where tenant_id = :tenantId").contains("order by version desc")
                .contains("limit :limit").doesNotContain("cast(setup").doesNotContain("select setup");
    }

    @Test
    void theVersionInsertListsOnlyTheColumnsTheRoleMayWrite() {
        String sql = sqlSent(() -> repository().insertVersion(TENANT, 2, "{}", false, List.of("layout", "fields"),
                UUID.randomUUID()));

        assertThat(sql.replaceAll("\\s+", " ")).contains("insert into ipd.card_setup_version (version, setup, "
                + "is_standard, changed_parts, published_by, schema_version)").contains("cast(:setup as jsonb)")
                .contains("string_to_array(:parts, ',')").contains(":schemaVersion")
                .doesNotContain("tenant_id").doesNotContain("published_at").doesNotContain("id,");
        verify(spec).param("schemaVersion", 1);
    }

    @Test
    void theDraftWritesListOnlyTheColumnsTheRoleMayWrite() {
        String insert = sqlSent(() -> repository().insertDraft(TENANT, "{}", 1, 1L, UUID.randomUUID()));
        assertThat(insert).contains("insert into ipd.card_setup_draft (setup, base_version, revision, saved_by, "
                + "schema_version)").contains("cast(:setup as jsonb)").contains(":schemaVersion")
                .doesNotContain("tenant_id");
        verify(spec).param("schemaVersion", 1);
    }

    @Test
    void theSchemaVersionIsWrittenExplicitlyAsOneNotLeftToTheColumnDefault() {
        assertThat(CardSetupRepository.SCHEMA_VERSION).isEqualTo(1);
        sqlSent(() -> repository().updateDraft(TENANT, "{}", 1, 2L, UUID.randomUUID(), Instant.now()));
        verify(spec).param("schemaVersion", 1);
    }

    @Test
    void theReadsAskForTheSchemaVersionOfTheStoredDocument() {
        assertThat(sqlSent(() -> repository().findLatestVersion(TENANT))).contains("schema_version");
    }

    @Test
    void theDraftReadAsksForTheSchemaVersion() {
        assertThat(sqlSent(() -> repository().findDraft(TENANT))).contains("schema_version");
    }

    @Test
    void aVersionRowCarriesTheStoredSchemaVersion() throws SQLException {
        for (int stored : new int[] {1, 2, 7}) {
            ResultSet rs = mock(ResultSet.class);
            UUID by = UUID.randomUUID();
            OffsetDateTime at = OffsetDateTime.parse("2026-11-09T10:00:00Z");
            when(rs.getInt("version")).thenReturn(3);
            when(rs.getString("setup")).thenReturn("{}");
            when(rs.getBoolean("is_standard")).thenReturn(false);
            when(rs.getString("changed_parts")).thenReturn("layout,fields");
            when(rs.getObject("published_by", UUID.class)).thenReturn(by);
            when(rs.getObject("published_at", OffsetDateTime.class)).thenReturn(at);
            when(rs.getInt("schema_version")).thenReturn(stored);

            CardSetupRepository.VersionRow row = JdbcCardSetupRepository.versionRow(rs);

            assertThat(row.schemaVersion()).isEqualTo(stored);
            assertThat(row.version()).isEqualTo(3);
            assertThat(row.changedParts()).containsExactly("layout", "fields");
            assertThat(row.publishedAt()).isEqualTo(at.toInstant());
        }
    }

    @Test
    void aDraftRowCarriesTheStoredSchemaVersion() throws SQLException {
        for (int stored : new int[] {1, 2}) {
            ResultSet rs = mock(ResultSet.class);
            OffsetDateTime at = OffsetDateTime.parse("2026-11-09T10:00:00Z");
            when(rs.getString("setup")).thenReturn("{}");
            when(rs.getInt("base_version")).thenReturn(2);
            when(rs.getLong("revision")).thenReturn(9L);
            when(rs.getObject("saved_by", UUID.class)).thenReturn(TENANT);
            when(rs.getObject("saved_at", OffsetDateTime.class)).thenReturn(at);
            when(rs.getInt("schema_version")).thenReturn(stored);

            CardSetupRepository.DraftRow row = JdbcCardSetupRepository.draftRow(rs);

            assertThat(row.schemaVersion()).isEqualTo(stored);
            assertThat(row.revision()).isEqualTo(9L);
            assertThat(row.baseVersion()).isEqualTo(2);
        }
    }

    @Test
    void theDraftUpdateIsScopedToTheTenantAndNeverChangesTheTenant() {
        String update = sqlSent(() -> repository().updateDraft(TENANT, "{}", 1, 2L, UUID.randomUUID(), Instant.now()));

        assertThat(update).contains("update ipd.card_setup_draft").contains("where tenant_id = :tenantId")
                .contains("setup = cast(:setup as jsonb)").contains("base_version = :baseVersion")
                .contains("revision = :revision").contains("saved_by = :by").contains("saved_at = :savedAt")
                .contains("schema_version = :schemaVersion").doesNotContain("set tenant_id");
    }

    @Test
    void theDraftReadAndDeleteAreScopedToTheTenant() {
        String select = sqlSent(() -> repository().findDraft(TENANT));
        assertThat(select).contains("from ipd.card_setup_draft").contains("where tenant_id = :tenantId");

        JdbcClient other = mock(JdbcClient.class);
        StatementSpec otherSpec = mock(StatementSpec.class, RETURNS_SELF);
        when(other.sql(anyString())).thenReturn(otherSpec);
        new JdbcCardSetupRepository(other).deleteDraft(TENANT);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(other).sql(sql.capture());
        assertThat(sql.getValue()).isEqualTo("delete from ipd.card_setup_draft where tenant_id = :tenantId");
    }

    @Test
    void theReadingsVariantIsTheSameSingleProbeWithTheFiveReadingsAndTheRecorder() {
        String sql = sqlSent(() -> new JdbcVitalsRepository(jdbc).findLatestReadingsOfAdmitted(TENANT));

        assertThat(sql).contains("cross join lateral").contains("order by x.recorded_at desc, x.id desc")
                .contains("limit 1").contains("x.tenant_id = a.tenant_id and x.admission_id = a.id")
                .contains("where a.tenant_id = :tenantId and a.status = 'ADMITTED'")
                .contains("v.bp_systolic", "v.bp_diastolic", "v.pulse", "v.spo2", "v.temp_c", "v.rr", "v.recorded_by")
                .doesNotContain("note").doesNotContain("patient.");
    }
}
