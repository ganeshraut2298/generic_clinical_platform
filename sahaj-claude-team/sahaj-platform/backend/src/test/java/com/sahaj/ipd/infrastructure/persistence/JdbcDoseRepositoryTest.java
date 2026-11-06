package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.DoseRepository.NewDose;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-32 the statements of the dose repository. The insert is the one statement of docs/database/SAH-32.md: exactly the
 * eleven granted columns, never {@code id}, {@code tenant_id} or {@code recorded_at}. There is no update and no delete
 * statement. The reads are scoped to the clinic and use the two indexes of the table. The statements are run against
 * the real database by the QA tests.
 */
class JdbcDoseRepositoryTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant SLOT = Instant.parse("2026-11-11T02:30:00Z");

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

    private JdbcDoseRepository repository() {
        return new JdbcDoseRepository(jdbc);
    }

    private static NewDose newDose(String outcome, String reason, UUID second, String scan, Boolean confirmed) {
        return new NewDose(ID, ID, ID, SLOT, outcome, reason, ID, second, second != null, scan, confirmed);
    }

    @Test
    void theInsertIsTheOneStatementOfTheDatabaseDesign() {
        String sql = sqlSent(() -> repository().insert(TENANT, newDose("GIVEN", null, null, "UH-000123", true)));

        assertThat(sql.replaceAll("\\s+", " ").trim()).isEqualTo("insert into ipd.dose_administration "
                + "(admission_id, order_id, client_key, slot_at, outcome, reason_key, recorded_by, second_check_by, "
                + "high_alert, identity_scan, identity_confirmed) "
                + "values (:admissionId, :orderId, :clientKey, :slotAt, :outcome, :reasonKey, :recordedBy, "
                + ":secondCheckBy, :highAlert, :identityScan, :identityConfirmed) returning id, recorded_at");
    }

    @Test
    void theInsertNeverNamesTheIdTheClinicOrTheRecordedTime() {
        String sql = sqlSent(() -> repository().insert(TENANT, newDose("GIVEN", null, null, "UH-000123", true)));
        String columns = sql.substring(0, sql.indexOf("values"));

        assertThat(columns).doesNotContain("tenant_id").doesNotContain(" id,").doesNotContain("recorded_at");
        assertThat(columns.split(",")).hasSize(11);
        assertThat(sql).doesNotContain("update ").doesNotContain("delete ").doesNotContain("on conflict");
    }

    @Test
    void theNullableValuesAreBoundWithTheirTypesSoANullIsNotGuessed() {
        sqlSent(() -> repository().insert(TENANT, newDose("HELD", "NIL_BY_MOUTH", null, null, null)));

        verify(spec).param("reasonKey", "NIL_BY_MOUTH", Types.VARCHAR);
        verify(spec).param("secondCheckBy", null, Types.OTHER);
        verify(spec).param("identityScan", null, Types.VARCHAR);
        verify(spec).param("identityConfirmed", null, Types.BOOLEAN);
        verify(spec).param("outcome", "HELD");
        verify(spec).param("highAlert", false);
    }

    @Test
    void theSlotIsBoundAsAUtcInstant() {
        sqlSent(() -> repository().insert(TENANT, newDose("GIVEN", null, null, "UH-000123", true)));

        verify(spec).param("slotAt", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
    }

    @Test
    void theClientKeyLookupIsScopedToTheClinicAndUsesTheUniqueKey() {
        String sql = sqlSent(() -> repository().findByClientKey(TENANT, ID));

        assertThat(sql).contains("from ipd.dose_administration d").contains("d.tenant_id = :tenantId")
                .contains("d.client_key = :clientKey").doesNotContain("identity_scan");
    }

    @Test
    void theSlotProbeUsesTheOrderAndSlotKey() {
        String sql = sqlSent(() -> repository().findByOrderAndSlot(TENANT, ID, SLOT));

        assertThat(sql).contains("d.tenant_id = :tenantId and d.order_id = :orderId and d.slot_at = :slotAt");
    }

    @Test
    void theDayReadIsARangeOnTheSlotTimeOfOneAdmission() {
        String sql = sqlSent(() -> repository().findBetween(TENANT, ID, SLOT, SLOT.plusSeconds(86_400)));

        assertThat(sql).contains("d.tenant_id = :tenantId and d.admission_id = :admissionId")
                .contains("d.slot_at >= :from and d.slot_at < :to").contains("order by d.slot_at, d.id");
        verify(spec).param("from", OffsetDateTime.ofInstant(SLOT, ZoneOffset.UTC));
        verify(spec).param("to", OffsetDateTime.ofInstant(SLOT.plusSeconds(86_400), ZoneOffset.UTC));
    }

    @Test
    void theLastDosePerOrderTakesTheLatestSlotOfEachOrderInOneQuery() {
        String sql = sqlSent(() -> repository().findLastPerOrder(TENANT, ID));

        assertThat(sql).contains("select distinct on (d.order_id)").contains("d.tenant_id = :tenantId")
                .contains("d.admission_id = :admissionId").contains("order by d.order_id, d.slot_at desc");
    }

    @Test
    void noReadSelectsTheScannedText() {
        String sql = sqlSent(() -> repository().findLastPerOrder(TENANT, ID));

        assertThat(sql).doesNotContain("identity_scan").doesNotContain("identity_confirmed");
        verify(spec).param("tenantId", TENANT);
    }
}
