package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.NursingProfileRepository.NewProfile;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-76-4 and 76-6 the statements of the nursing repositories (docs/database/SAH-76.md): the profile insert and update
 * name only granted columns, there is no delete, the ward profile is one update, the drug codes are read-only global
 * reads, and the round and settings statements. The statements run against the real database in the QA tests.
 */
class JdbcNursingRepositoriesTest {

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
        return sql.getValue().replaceAll("\\s+", " ").trim();
    }

    private static NewProfile profile() {
        return new NewProfile("icu", "ICU", true, 60, null, 30, 1);
    }

    // ---- profiles ----

    @Test
    void theProfileInsertNamesTheSevenGrantedColumns() {
        String sql = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).insert(TENANT, profile()));

        assertThat(sql).isEqualTo("insert into ipd.nursing_profile (key, name, med_verification_required, "
                + "vitals_every_min, dose_window_before_min, dose_window_after_min, sort_order) "
                + "values (:key, :name, :verification, :vitals, :before, :after, :sort)");
        verify(spec).param("before", null, Types.INTEGER);
        verify(spec).param("after", 30, Types.INTEGER);
    }

    @Test
    void theProfileUpdateNamesGrantedColumnsAndIsScopedToTheClinicAndKey() {
        String sql = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).update(TENANT, "icu", profile(), ID,
                Instant.parse("2026-11-15T05:30:00Z")));

        assertThat(sql).isEqualTo("update ipd.nursing_profile set name = :name, "
                + "med_verification_required = :verification, vitals_every_min = :vitals, "
                + "dose_window_before_min = :before, dose_window_after_min = :after, updated_at = :now, "
                + "updated_by = :actor where tenant_id = :tenantId and key = :key");
    }

    @Test
    void thereIsNoDeleteAndTheStartersAreCopiedFromTheTemplateTableOnly() {
        String copy = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).copyTemplates(TENANT));

        assertThat(copy).startsWith("insert into ipd.nursing_profile (key, name, med_verification_required, "
                + "vitals_every_min, sort_order) select t.key, t.name, t.med_verification_required, t.vitals_every_min, "
                + "t.sort_order from ipd.nursing_profile_template t");
        assertThat(copy).doesNotContain("delete");
    }

    @Test
    void theTemplatesAreReadWithoutATenant() {
        String sql = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).findTemplates());

        assertThat(sql).contains("from ipd.nursing_profile_template").doesNotContain("tenant_id")
                .contains("order by sort_order, key");
    }

    @Test
    void theNameCheckIgnoresCaseAndTheKeyBeingChanged() {
        String sql = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).nameTaken(TENANT, "ICU", "icu"));

        assertThat(sql).contains("lower(name) = lower(:name)").contains("key <> cast(:except as text)")
                .contains("tenant_id = :tenantId");
        verify(spec).param("except", "icu", Types.VARCHAR);
    }

    @Test
    void theProfileOfAnAdmissionComesFromTheWardOfTheCurrentBedInOneStatement() {
        String sql = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).findProfilesOfAdmissions(TENANT,
                List.of(ID)));

        assertThat(sql).contains("join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id")
                .contains("join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id")
                .contains("p.key = w.nursing_profile_key").contains("a.id = any(cast(:ids as uuid[]))");
        verify(spec).param("ids", "{" + ID + "}");
    }

    @Test
    void anEmptyAdmissionListReadsNothing() {
        assertThat(new JdbcNursingProfileRepository(jdbc).findProfilesOfAdmissions(TENANT, List.of())).isEmpty();
    }

    @Test
    void theWardProfileIsOneUpdateOfTheKeyColumn() {
        String sql = sqlSent(() -> new JdbcNursingProfileRepository(jdbc).setWardProfile(TENANT, ID, null));

        assertThat(sql).isEqualTo("update ipd.ward set nursing_profile_key = :key where tenant_id = :tenantId "
                + "and id = :wardId");
        verify(spec).param("key", null, Types.VARCHAR);
    }

    // ---- clinic settings ----

    @Test
    void theSettingsUpsertWritesTheSevenColumnsAndTheStampOfTheLazyRow() {
        String sql = sqlSent(() -> new JdbcIpdSettingsRepository(jdbc).upsertNursingSettings(TENANT,
                NursingSettings.DEFAULTS, ID, Instant.parse("2026-11-15T05:30:00Z")));

        assertThat(sql).contains("insert into ipd.config (tenant_id, med_verification_required, "
                + "dose_window_before_min, dose_window_after_min, stat_window_min, unack_flag_min, "
                + "unack_flag_stat_min, undo_seconds, updated_at, updated_by)")
                .contains("on conflict (tenant_id) do update set").contains("undo_seconds = excluded.undo_seconds");
        verify(spec).param("undo", 10);
        verify(spec).param("verification", true);
    }

    @Test
    void theSettingsReadIsScopedToTheClinic() {
        String sql = sqlSent(() -> new JdbcIpdSettingsRepository(jdbc).findNursingSettings(TENANT));

        assertThat(sql).contains("from ipd.config where tenant_id = :tenantId").contains("undo_seconds");
    }

    // ---- drug codes ----

    @Test
    void theDrugCodesAreGlobalReadsWithTheCodeAsABindValue() {
        String sql = sqlSent(() -> new JdbcDrugCodeRepository(jdbc).drugKeyOf("8901234567890"));

        assertThat(sql).isEqualTo("select drug_key from ipd.drug_code where code = :code");
        verify(spec).param("code", "8901234567890");
    }

    @Test
    void theCodesOfADrugAreOneSelect() {
        assertThat(sqlSent(() -> new JdbcDrugCodeRepository(jdbc).codesOf("hep")))
                .isEqualTo("select code from ipd.drug_code where drug_key = :drugKey");
    }

    @Test
    void theKeysWithCodesTravelAsOneArrayAndOnlyWellFormedKeysAreSent() {
        String sql = sqlSent(() -> new JdbcDrugCodeRepository(jdbc).drugKeysWithCodes(List.of("hep", "pcm",
                "x'; drop table ipd.drug;--")));

        assertThat(sql).contains("drug_key = any(cast(:keys as text[]))");
        verify(spec).param("keys", "{hep,pcm}");
    }

    @Test
    void noKeysReadNothing() {
        assertThat(new JdbcDrugCodeRepository(jdbc).drugKeysWithCodes(List.of())).isEmpty();
    }
}
