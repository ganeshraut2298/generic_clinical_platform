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
 * SAH-33 R4: the statements on {@code ipd.ews_senior_call}. The insert lists exactly the four columns the database
 * role may write (never id, tenant_id or called_at); the throttle is measured on the database clock. The statements
 * run against the real database in the QA tests.
 */
class JdbcSeniorCallRepositoryTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ESC = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private String sqlSent(Runnable call) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            call.run();
        } catch (RuntimeException e) {
            // the result mapping is not under test; the mock returns nothing after the statement
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue().replaceAll("\s+", " ");
    }

    private JdbcSeniorCallRepository repository() {
        return new JdbcSeniorCallRepository(jdbc);
    }

    @Test
    void theCalledInsertListsExactlyTheFourWritableColumnsAndTheCallNumber() {
        String sql = sqlSent(() -> repository().insertCalled(TENANT, ESC, USER, 4));

        assertThat(sql).contains("insert into ipd.ews_senior_call (escalation_id, called_by, outcome, call_no)")
                .contains("'CALLED'").contains(":callNo").doesNotContain("called_at").doesNotContain("tenant_id")
                .doesNotContain(" id,");
    }

    @Test
    void theAlreadyCalledInsertHasNoCallNumberAndNeverTheStampedColumns() {
        String sql = sqlSent(() -> repository().insertAlreadyCalled(TENANT, ESC, USER));

        assertThat(sql).contains("insert into ipd.ews_senior_call (escalation_id, called_by, outcome, call_no)")
                .contains("'ALREADY_CALLED', null").doesNotContain("called_at").doesNotContain("tenant_id");
    }

    @Test
    void theThrottleReadUsesTheDatabaseClockAndTheTenantEscalationAndUser() {
        String sql = sqlSent(() -> repository().hasEnteredWithin(TENANT, ESC, USER, 60));

        assertThat(sql).contains("tenant_id = :tenantId").contains("escalation_id = :escalationId")
                .contains("called_by = :user").contains("called_at > clock_timestamp() - make_interval(secs => :seconds)")
                .doesNotContain("outcome");
    }

    @Test
    void theOwnCallReadLooksForACalledRowAboveTheSentCountOfTheSameUser() {
        String sql = sqlSent(() -> repository().hasCalledAbove(TENANT, ESC, USER, 2));

        assertThat(sql).contains("tenant_id = :tenantId").contains("escalation_id = :escalationId")
                .contains("called_by = :user").contains("outcome = 'CALLED'").contains("call_no > :sent");
    }

    @Test
    void noStatementUpdatesOrDeletes() {
        assertThat(sqlSent(() -> repository().insertCalled(TENANT, ESC, USER, 1))).doesNotContain("update ")
                .doesNotContain("delete ");
    }
}
