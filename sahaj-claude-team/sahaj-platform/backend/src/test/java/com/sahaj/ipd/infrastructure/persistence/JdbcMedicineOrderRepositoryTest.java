package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MedicineOrderRepository.NewOrder;
import com.sahaj.ipd.application.MedicineOrderRepository.Position;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-31 the statements of the medicine order repository. The insert names exactly the seven granted columns; the
 * three updates name exactly the granted columns, never a time, and keep the allowed source statuses; the queue is the
 * keyset on the order time and id for ADMITTED admissions. The statements are run against the real database by the QA
 * tests (docs/database/SAH-31.md).
 */
class JdbcMedicineOrderRepositoryTest {

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

    private JdbcMedicineOrderRepository repository() {
        return new JdbcMedicineOrderRepository(jdbc);
    }

    @Test
    void theInsertNamesOnlyTheSevenGrantedColumnsAndNeverTheStatusTheTimeTheVersionOrTheClinic() {
        String sql = sqlSent(() -> repository().insert(TENANT,
                new NewOrder(ID, ID, "asp", "od", ID, null, List.of())));

        assertThat(sql).contains("insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, "
                + "ordered_by,").contains("override_reason_key, warnings)").contains("cast(:warnings as text[])")
                .contains("returning id, ordered_at, version").doesNotContain("tenant_id").doesNotContain("status")
                .doesNotContain("update ").doesNotContain("delete ");
        assertThat(sql.substring(0, sql.indexOf("values"))).doesNotContain("ordered_at").doesNotContain("version");
    }

    @Test
    void theVerifyUpdateChangesOnlyStatusVerifiedByAndVersionFromPending() {
        String sql = sqlSent(() -> repository().markActive(TENANT, ID, ID));

        assertThat(sql).contains("set status = 'ACTIVE', verified_by = :user, version = version + 1")
                .contains("tenant_id = :tenantId and id = :id and status = 'PENDING'")
                .doesNotContain("verified_at");
    }

    @Test
    void theRejectUpdateChangesOnlyTheGrantedColumnsFromPending() {
        String sql = sqlSent(() -> repository().markRejected(TENANT, ID, ID, "WRONG_DOSE"));

        assertThat(sql).contains("set status = 'REJECTED', rejected_by = :user, reject_reason_key = :reason, "
                + "version = version + 1").contains("and status = 'PENDING'").doesNotContain("rejected_at");
    }

    @Test
    void theStopUpdateAcceptsPendingAndActiveAndNeverSendsTheTime() {
        String sql = sqlSent(() -> repository().markStopped(TENANT, ID, ID));

        assertThat(sql).contains("set status = 'STOPPED', stopped_by = :user, version = version + 1")
                .contains("and status in ('PENDING', 'ACTIVE')").doesNotContain("stopped_at");
    }

    @Test
    void theReadsAreScopedToTheTenantAndInChartOrder() {
        assertThat(sqlSent(() -> repository().findById(TENANT, ID))).contains("o.tenant_id = :tenantId")
                .contains("o.id = :id");
    }

    @Test
    void theClientKeyLookupIsScopedToTheTenant() {
        assertThat(sqlSent(() -> repository().findByClientKey(TENANT, ID))).contains("o.tenant_id = :tenantId")
                .contains("o.client_key = :clientKey");
    }

    @Test
    void theAdmissionListIsInChartOrderWithTheIdAsTieBreak() {
        assertThat(sqlSent(() -> repository().findByAdmission(TENANT, ID)))
                .contains("o.tenant_id = :tenantId and o.admission_id = :admissionId")
                .contains("order by o.ordered_at, o.id");
    }

    @Test
    void theLiveReadTakesPendingAndActiveOfTheGivenAdmissionsOnly() {
        assertThat(sqlSent(() -> repository().findLiveByAdmissions(TENANT, List.of(ID))))
                .contains("o.tenant_id = :tenantId").contains("o.admission_id in (:admissionIds)")
                .contains("o.status in ('PENDING', 'ACTIVE')").contains("order by o.admission_id, o.ordered_at, o.id");
    }

    @Test
    void theQueueIsPendingOrdersOfAdmittedAdmissionsOldestFirstWithAKeysetAndALimit() {
        String first = sqlSent(() -> repository().findPending(TENANT, null, 51));

        assertThat(first).contains("o.tenant_id = :tenantId and o.status = 'PENDING'")
                .contains("a.status = 'ADMITTED'").contains("order by o.ordered_at, o.id limit :limit")
                .doesNotContain("(o.ordered_at, o.id) >");
    }

    @Test
    void theNextPageStartsStrictlyAfterTheCursorPosition() {
        String next = sqlSent(() -> repository().findPending(TENANT, new Position(Instant.parse("2026-11-11T06:00:00Z"),
                ID), 51));

        assertThat(next).contains("(o.ordered_at, o.id) > (:afterAt, :afterId)")
                .contains("order by o.ordered_at, o.id limit :limit");
    }

    @Test
    void theWaitingKeysUseTheSameRuleAsTheQueue() {
        assertThat(sqlSent(() -> repository().findWaitingKeys(TENANT)))
                .contains("o.tenant_id = :tenantId and o.status = 'PENDING'").contains("a.status = 'ADMITTED'")
                .contains("select o.id, o.version");
    }

    @Test
    void theWaitingCountIsACountWithTheSameFiltersAsTheQueue() {
        assertThat(sqlSent(() -> repository().countWaiting(TENANT)))
                .contains("select count(*)").contains("from ipd.medicine_order o")
                .contains("join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id and a.status = 'ADMITTED'")
                .contains("o.tenant_id = :tenantId and o.status = 'PENDING'")
                .doesNotContain("version");
    }

    @Test
    void theDatabaseClockIsRead() {
        assertThat(sqlSent(() -> repository().databaseNow())).isEqualTo("select clock_timestamp() as db_now");
    }

    // ---- the array literal of the warning lines ----

    @Test
    void anEmptyListIsAnEmptyArray() {
        assertThat(JdbcMedicineOrderRepository.arrayLiteral(List.of())).isEqualTo("{}");
    }

    @Test
    void everyElementIsQuotedSoCommasBracesAndQuotesStayInsideTheElement() {
        assertThat(JdbcMedicineOrderRepository.arrayLiteral(List.of("Major: a, b", "Moderate: \"x\" {y}")))
                .isEqualTo("{\"Major: a, b\",\"Moderate: \\\"x\\\" {y}\"}");
    }

    @Test
    void aBackslashIsEscaped() {
        assertThat(JdbcMedicineOrderRepository.arrayLiteral(List.of("a\\b"))).isEqualTo("{\"a\\\\b\"}");
    }

    @Test
    void aTextThatLooksLikeASqlStatementStaysAnElement() {
        assertThat(JdbcMedicineOrderRepository.arrayLiteral(List.of("x\", \"y'); drop table ipd.medicine_order; --")))
                .isEqualTo("{\"x\\\", \\\"y'); drop table ipd.medicine_order; --\"}");
    }

    @Test
    void theNewOrderAndTheRowHideTheWarningLinesFromToString() {
        NewOrder order = new NewOrder(ID, ID, "asp", "od", ID, "CARDIOLOGY_STENT", List.of("Major: Penicillin (rash)"));

        assertThat(order.toString()).doesNotContain("Penicillin").doesNotContain("Major");
    }
}
