package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-36 (ADR-013 R1, R4): the two new statements of the Admitted cards. They run against PostgreSQL in the integration
 * tests of the qa-engineer; here the shape that matters for cost and privacy is checked: one set statement each, the
 * tenant in the {@code where}, joins by primary key, a lateral probe with {@code limit 1} for the latest vitals, and no
 * table of another module.
 */
class JdbcAdmittedCardsRepositoriesTest {

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

    @Test
    void theAdmittedRowsComeWithWardIdAndRoomNameFromOneStatementJoinedByPrimaryKey() {
        String sql = sqlSent(() -> new JdbcAdmissionRepository(jdbc).findAdmittedWithPlacement(TENANT));

        assertThat(sql).contains("b.ward_id as ward_id", "r.name as room_name")
                .contains("join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id")
                .contains("join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id")
                .contains("left join ipd.room r on r.tenant_id = b.tenant_id and r.id = b.room_id")
                .contains("where a.tenant_id = :tenantId and a.status = 'ADMITTED'")
                .contains("order by a.admitted_at desc, a.id")
                .doesNotContain("patient.").doesNotContain("update ").doesNotContain("insert ");
    }

    @Test
    void theLatestVitalsOfAllAdmittedAreOneStatementWithOneIndexProbePerAdmission() {
        String sql = sqlSent(() -> new JdbcVitalsRepository(jdbc).findLatestOfAdmitted(TENANT));

        assertThat(sql).contains("cross join lateral").contains("order by x.recorded_at desc, x.id desc")
                .contains("limit 1")
                .contains("x.tenant_id = a.tenant_id and x.admission_id = a.id")
                .contains("where a.tenant_id = :tenantId and a.status = 'ADMITTED'")
                .contains("select v.admission_id, v.ews_score, v.ews_risk, v.recorded_at")
                // only the score columns: none of the five clinical values and no note
                .doesNotContain("bp_systolic").doesNotContain("pulse").doesNotContain("spo2").doesNotContain("temp_c")
                .doesNotContain("note").doesNotContain("patient.");
    }
}
