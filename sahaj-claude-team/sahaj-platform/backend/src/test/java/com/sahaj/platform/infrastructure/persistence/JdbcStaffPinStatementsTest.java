package com.sahaj.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SAH-38: the statements of {@code docs/database/SAH-38.md} section 4, checked as text. They run against PostgreSQL
 * in the integration tests of the qa-engineer; here every statement is proved to keep the two colleague flags legal
 * for the update guard and the event inserts to name only the five insertable columns.
 */
class JdbcStaffPinStatementsTest {

    private static String sql(Class<?> type, String constant) throws Exception {
        Field field = type.getDeclaredField(constant);
        field.setAccessible(true);
        return ((String) field.get(null)).replaceAll("\\s+", " ").trim();
    }

    private static String pin(String constant) throws Exception {
        return sql(JdbcStaffPinRepository.class, constant);
    }

    private static String event(String constant) throws Exception {
        return sql(JdbcStaffPinColleagueEventRepository.class, constant);
    }

    @Test
    void theReadAlsoReturnsBothFlagsAndTheDatabaseClock() throws Exception {
        assertThat(pin("FIND_FOR_UPDATE")).contains("run_has_colleague_try", "lock_by_colleagues",
                "clock_timestamp() as db_now").endsWith("for update");
    }

    @Test
    void aColleagueWrongTrySetsTheRunFlagAndAnOwnWrongTryLeavesItAlone() throws Exception {
        assertThat(pin("COLLEAGUE_WRONG_TRY")).contains("failed_count = failed_count + 1",
                "run_has_colleague_try = true", "version = version + 1").doesNotContain("lock_by_colleagues");
        assertThat(pin("WRONG_TRY")).contains("failed_count = failed_count + 1", "version = version + 1")
                .doesNotContain("run_has_colleague_try").doesNotContain("lock_by_colleagues");
    }

    @Test
    void aRightPinBringsTheRunFlagBackToFalseWithTheCount() throws Exception {
        assertThat(pin("RIGHT_PIN")).contains("failed_count = 0", "run_has_colleague_try = false")
                .doesNotContain("lock_by_colleagues");
    }

    @Test
    void aColleagueLockTouchesNeitherTheLockCountNorTheWindow() throws Exception {
        String lock = pin("LOCK_BY_COLLEAGUES");

        assertThat(lock).contains("failed_count = 0", "run_has_colleague_try = false", "lock_by_colleagues = true",
                "locked_until = clock_timestamp() + interval '15 minutes'", "version = version + 1")
                .doesNotContain("lock_count").doesNotContain("lock_window_started_at");
    }

    @Test
    void anOwnSessionLockIsTheSah31StatementPlusBothFlagsFalse() throws Exception {
        assertThat(pin("LOCK")).contains("lock_count = :n", "lock_window_started_at = case when :n = 1",
                "run_has_colleague_try = false", "lock_by_colleagues = false");
    }

    @Test
    void aNewPinAnOwnerResetAndTheSwitchOffClearBothFlags() throws Exception {
        for (String constant : new String[] {"REPLACE", "RESET", "SWITCH_OFF"}) {
            assertThat(pin(constant)).as(constant).contains("run_has_colleague_try = false",
                    "lock_by_colleagues = false");
        }
    }

    @Test
    void noStatementClearsAnExpiredLockSoTheLockCauseNeverNeedsToFall() throws Exception {
        for (String constant : new String[] {"WRONG_TRY", "COLLEAGUE_WRONG_TRY", "RIGHT_PIN"}) {
            assertThat(pin(constant)).as(constant).doesNotContain("locked_until");
        }
    }

    @Test
    void theEventInsertsNameOnlyTheFiveInsertableColumnsNeverIdTenantOrTime() throws Exception {
        Pattern forbidden = Pattern.compile("\\b(id|tenant_id|at)\\b");
        for (String constant : new String[] {"INSERT_WRONG", "INSERT_LOCKED", "INSERT_RIGHT_AFTER_WRONG",
            "INSERT_NOTICE_SEEN"}) {
            String insert = event(constant);
            String columns = insert.substring(insert.indexOf('(') + 1, insert.indexOf(')'));
            assertThat(forbidden.matcher(columns).find()).as(constant + " columns: " + columns).isFalse();
            assertThat(insert).as(constant).startsWith("insert into platform.staff_pin_colleague_event (");
            assertThat(columns).as(constant).contains("membership_id", "kind", "created_by");
        }
        assertThat(event("INSERT_WRONG")).contains("by_membership_id", "'WRONG'");
        assertThat(event("INSERT_LOCKED")).doesNotContain("by_membership_id").contains("'LOCKED'");
        assertThat(event("INSERT_RIGHT_AFTER_WRONG")).contains("by_membership_id", "'RIGHT_AFTER_WRONG'");
        assertThat(event("INSERT_NOTICE_SEEN")).contains("seen_through", "'NOTICE_SEEN'")
                .doesNotContain("by_membership_id");
    }

    @Test
    void thePairCountUsesTheStableStatementClockTheTimeThePinWasSetAndTheLatestRightAfterWrong() throws Exception {
        assertThat(event("COUNT_PAIR")).contains("kind = 'WRONG'", "by_membership_id = :a", "membership_id = :b",
                "greatest(statement_timestamp() - interval '7 days'", ":setAt", "kind = 'RIGHT_AFTER_WRONG'",
                "tenant_id = :t").doesNotContain("clock_timestamp()");
    }

    @Test
    void everyReadStartsFromTheTenantAndTheTeamPageUsesOneGroupedQuery() throws Exception {
        for (String constant : new String[] {"COUNT_PAIR", "LATEST_SEEN", "UNSEEN", "EXISTS_WRONG_BETWEEN",
            "WRONG_7D_BY_MEMBER", "WRONG_7D_OF_MEMBER", "HISTORY_WRONG_COUNT", "HISTORY"}) {
            assertThat(event(constant)).as(constant).contains("tenant_id = :t");
        }
        assertThat(event("WRONG_7D_BY_MEMBER")).contains("kind = 'WRONG'", "group by membership_id",
                "statement_timestamp() - interval '7 days'").doesNotContain("clock_timestamp()");
    }

    @Test
    void theHistoryGroupsWrongTriesByCallerAndIstDayAndLeavesTheNoticeMarkerOut() throws Exception {
        assertThat(event("HISTORY")).contains("kind <> 'NOTICE_SEEN'", "Asia/Kolkata", "group by kind, by_membership_id",
                "order by max(at) desc", "limit :limit");
    }

    @Test
    void theWindowOfTheTeamCountIsAStableExpressionSoThePlannerCanUseThePartialIndex() throws Exception {
        for (String constant : new String[] {"WRONG_7D_BY_MEMBER", "WRONG_7D_OF_MEMBER"}) {
            assertThat(event(constant)).as(constant).contains("at > statement_timestamp() - interval '7 days'")
                    .doesNotContain("clock_timestamp()");
        }
    }

    @Test
    void theNoticeMarkerIsReadAsTheNewestRowNotAnAggregateSoNoRowMeansNoMarker() throws Exception {
        assertThat(event("LATEST_SEEN")).contains("kind = 'NOTICE_SEEN'", "order by seen_through desc", "limit 1")
                .doesNotContain("max(");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMemberWhoNeverTappedGotItHasNoMarkerAndTheReadDoesNotThrow() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Instant> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        Optional<Instant> marker = new JdbcStaffPinColleagueEventRepository(jdbc)
                .latestSeenThrough(UUID.randomUUID(), UUID.randomUUID());

        assertThat(marker).isEmpty();
        verify(query).optional();
        verify(query, never()).single();
    }
}
