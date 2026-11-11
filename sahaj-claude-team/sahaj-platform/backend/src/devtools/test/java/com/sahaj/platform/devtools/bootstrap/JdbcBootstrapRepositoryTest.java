package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The calls to the five functions of docs/database/SAH-42.md and the SQLSTATE rule. The database itself is not used here:
 * the functions are covered by the integration tests that need the admin tables and the role
 * {@code sahaj_admin_definer} (QA cases S1 to S30).
 */
class JdbcBootstrapRepositoryTest {

    private static final String ROW_TEXT = "Failing row contains (first.admin@example.test, $2a$12$abcdefghijklmnop, "
            + "a-throwaway-ticket-hash)";

    private final Map<String, Object> params = new HashMap<>();
    private RuntimeException failure;
    private Object answer;
    private ResultSet resultSet;
    private String sqlText;

    @SuppressWarnings("unchecked")
    private JdbcClient jdbc() {
        final RowMapper<?>[] mapper = new RowMapper<?>[1];
        JdbcClient.MappedQuerySpec<Object> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(mapped.single()).thenAnswer(invocation -> {
            if (failure != null) {
                throw failure;
            }
            if (mapper[0] != null && resultSet != null) {
                return mapper[0].mapRow(resultSet, 0);
            }
            return answer;
        });
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("param") && invocation.getArguments().length == 2) {
                params.put((String) invocation.getArguments()[0], invocation.getArguments()[1]);
                return invocation.getMock();
            }
            if (method.equals("query")) {
                mapper[0] = invocation.getArguments().length == 1 && invocation.getArguments()[0] instanceof RowMapper<?> rm
                        ? rm : null;
                return mapped;
            }
            return null;
        });
        JdbcClient client = mock(JdbcClient.class);
        when(client.sql(anyString())).thenAnswer(invocation -> {
            sqlText = invocation.getArgument(0);
            return spec;
        });
        return client;
    }

    private JdbcBootstrapRepository repository() {
        return new JdbcBootstrapRepository(jdbc());
    }

    private static OffsetDateTime at(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static final UUID TRY = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // ---- the five calls (Revision 3 shapes) ----

    @Test
    void beginBindsOnlyTheTryIdAndReadsTheThreeColumns() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getString("outcome")).thenReturn("COUNTED");
        when(resultSet.getInt("failures")).thenReturn(3);
        when(resultSet.getObject("locked_until", OffsetDateTime.class)).thenReturn(at("2026-11-10T11:05:00Z"));

        BootstrapRepository.Claim claim = repository().begin(TRY);

        assertThat(sqlText).contains("platform.admin_bootstrap_begin(cast(:try as uuid))").contains("outcome");
        assertThat(claim).isEqualTo(new BootstrapRepository.Claim(BootstrapRepository.ClaimOutcome.COUNTED, 3,
                Instant.parse("2026-11-10T11:05:00Z")));
        assertThat(params).containsOnlyKeys("try");
        assertThat(params.get("try")).isEqualTo(TRY);
    }

    @Test
    void beginMapsEachOfTheFourOutcomesAndAnUnlockedRowHasNoLockTime() throws Exception {
        resultSet = mock(ResultSet.class);
        for (BootstrapRepository.ClaimOutcome outcome : BootstrapRepository.ClaimOutcome.values()) {
            when(resultSet.getString("outcome")).thenReturn(outcome.name());

            BootstrapRepository.Claim claim = repository().begin(TRY);

            assertThat(claim.outcome()).isEqualTo(outcome);
            assertThat(claim.lockedUntil()).isNull();
        }
    }

    @Test
    void anOutcomeTheDatabaseNeverSendsIsAFailureNotAnEnumError() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getString("outcome")).thenReturn("OPEN");

        assertThatThrownBy(() -> repository().begin(TRY)).isInstanceOf(BootstrapExceptions.Failed.class);
    }

    @Test
    void unlockFailedBindsTheTryIdTheAddressAndTheRequestIdOnly() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getInt("failures")).thenReturn(5);
        when(resultSet.getObject("locked_until", OffsetDateTime.class)).thenReturn(at("2026-11-10T12:00:00Z"));

        BootstrapRepository.Failure result = repository().unlockFailed(TRY, "127.0.0.1", "req-1");

        assertThat(sqlText).contains("platform.admin_bootstrap_unlock_failed(").contains("cast(:try as uuid)")
                .contains("cast(:ip as inet)").contains("cast(:req as text)");
        assertThat(params).containsOnlyKeys("try", "ip", "req");
        assertThat(params.get("try")).isEqualTo(TRY);
        assertThat(params.get("ip")).isEqualTo("127.0.0.1");
        assertThat(params.get("req")).isEqualTo("req-1");
        assertThat(result.failures()).isEqualTo(5);
        assertThat(result.lockedUntil()).isEqualTo(Instant.parse("2026-11-10T12:00:00Z"));
    }

    @Test
    void anAddressThatIsNotAnIpLiteralIsSentAsNull() {
        resultSet = mock(ResultSet.class);

        repository().unlockFailed(TRY, "localhost", "req-1");

        assertThat(params.get("ip")).isNull();
    }

    @Test
    void unlockSucceededSendsTheTryIdAndTheTwoHashesAndKeepsNothingOfTheExpiry() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getObject(1, OffsetDateTime.class)).thenReturn(at("2026-11-10T10:05:00Z"));

        repository().unlockSucceeded(TRY, "a".repeat(64), "b".repeat(64));

        assertThat(sqlText).contains("platform.admin_bootstrap_unlock_succeeded(").contains("cast(:try as uuid)");
        assertThat(params).containsOnlyKeys("try", "ticket", "binding");
        assertThat(params.get("try")).isEqualTo(TRY);
    }

    @Test
    void ticketStatusMapsTheFourTextsWithTheLockTimeAndAcceptsNullHashes() throws Exception {
        resultSet = mock(ResultSet.class);
        for (BootstrapRepository.Status status : BootstrapRepository.Status.values()) {
            when(resultSet.getString("status")).thenReturn(status.name());
            when(resultSet.getObject("locked_until", OffsetDateTime.class))
                    .thenReturn(status == BootstrapRepository.Status.LOCKED ? at("2026-11-10T12:00:00Z") : null);

            BootstrapRepository.TicketState state = repository().ticketStatus(null, null);

            assertThat(state.status()).isEqualTo(status);
            assertThat(state.lockedUntil()).isEqualTo(
                    status == BootstrapRepository.Status.LOCKED ? Instant.parse("2026-11-10T12:00:00Z") : null);
        }
        assertThat(sqlText).contains("platform.admin_bootstrap_ticket_status(").contains("cast(:ticket as text)");
        assertThat(params).containsOnlyKeys("ticket", "binding");
    }

    @Test
    void registerBindsEveryValueAndMapsTheFourTextsWithTheLockTime() throws Exception {
        resultSet = mock(ResultSet.class);
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        for (BootstrapRepository.RegisterResult result : BootstrapRepository.RegisterResult.values()) {
            when(resultSet.getString("outcome")).thenReturn(result.name());
            when(resultSet.getObject("locked_until", OffsetDateTime.class))
                    .thenReturn(result == BootstrapRepository.RegisterResult.LOCKED ? at("2026-11-10T12:00:00Z") : null);

            BootstrapRepository.RegisterReply reply = repository().register("t".repeat(64), "b".repeat(64), id,
                    "first.admin@example.test", "First admin", "$2a$12$" + "a".repeat(53), "::1", "req-2");

            assertThat(reply.result()).isEqualTo(result);
            assertThat(reply.lockedUntil()).isEqualTo(
                    result == BootstrapRepository.RegisterResult.LOCKED ? Instant.parse("2026-11-10T12:00:00Z") : null);
        }
        assertThat(sqlText).contains("platform.admin_bootstrap_register(").contains("cast(:ip as inet)")
                .doesNotContain("first.admin@example.test");
        assertThat(params).containsOnlyKeys("ticket", "binding", "id", "email", "name", "hash", "ip", "req");
        assertThat(params.get("id")).isEqualTo(id);
        assertThat(params.get("ip")).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void noValueIsEverPutIntoTheSqlText() {
        resultSet = mock(ResultSet.class);
        repository().unlockFailed(TRY, "127.0.0.1", "req-secret-looking");
        assertThat(sqlText).doesNotContain("127.0.0.1").doesNotContain("req-secret-looking")
                .doesNotContain(TRY.toString());
    }

    @Test
    void noFunctionOfTheOldShapeIsCalled() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getString("outcome")).thenReturn("COUNTED");
        repository().begin(TRY);

        assertThat(sqlText).doesNotContain("admin_bootstrap_begin()").doesNotContain("\"open\"");
    }

    // ---- the SQLSTATE rule ----

    private void failWith(String sqlState, String message) {
        failure = new UncategorizedSQLException("task", "sql", new SQLException(message, sqlState));
    }

    @Test
    void sb001IsClosedWithNoMessageAndNoCause() {
        failWith("SB001", ROW_TEXT);

        assertThatThrownBy(() -> repository().unlockFailed(TRY, "127.0.0.1", "req"))
                .isInstanceOf(BootstrapExceptions.Closed.class).hasNoCause().hasMessage(null);
    }

    @Test
    void lockNotAvailableIsBusyEvenWhenSpringTranslatedIt() {
        failure = new CannotAcquireLockException("task", new SQLException(ROW_TEXT, "55P03"));

        assertThatThrownBy(() -> repository().begin(TRY)).isInstanceOf(BootstrapExceptions.Busy.class).hasNoCause();
    }

    @Test
    void everyOtherStateIsAMessageFreeCauseLessFailureThatKeepsOnlyTheState() {
        for (String state : new String[] {"SB002", "SB003", "SB004", "42501", "22023", "40001", "40P01", "57014", "42883",
                "3F000", "23505", "08006"}) {
            failWith(state, ROW_TEXT);
            assertThatThrownBy(() -> repository().begin(TRY)).as(state)
                    .isInstanceOfSatisfying(BootstrapExceptions.Failed.class, e -> {
                        assertThat(e.getMessage()).isNull();
                        assertThat(e.getCause()).isNull();
                        assertThat(e.getSuppressed()).isEmpty();
                        assertThat(e.getStackTrace()).isEmpty();
                        assertThat(e.sqlState()).isEqualTo(state);
                        assertThat(e.toString()).isEqualTo("BootstrapExceptions.Failed[sqlState=" + state + "]");
                    });
        }
    }

    @Test
    void theFailingRowInTheDriversMessageAndCauseNeverSurvives() {
        failure = new DataIntegrityViolationException(ROW_TEXT, new SQLException(ROW_TEXT, "23514"));

        assertThatThrownBy(() -> repository().begin(TRY)).isInstanceOf(BootstrapExceptions.Failed.class)
                .satisfies(e -> {
                    assertThat(String.valueOf(e.getMessage())).doesNotContain("first.admin@example.test");
                    assertThat(e.toString()).doesNotContain("first.admin").doesNotContain("$2a$").doesNotContain("ticket");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void aFailureWithNoSqlStateIsStillOneOfOurs() {
        failure = new IllegalStateException(ROW_TEXT);

        assertThatThrownBy(() -> repository().begin(TRY)).isInstanceOfSatisfying(BootstrapExceptions.Failed.class,
                e -> assertThat(e.sqlState()).isNull());
    }

    @Test
    void anUnexpectedTextFromTheFunctionIsAFailureNotAnEnumError() throws Exception {
        resultSet = mock(ResultSet.class);
        when(resultSet.getString("status")).thenReturn("SOMETHING_ELSE");

        assertThatThrownBy(() -> repository().ticketStatus("a", "b")).isInstanceOf(BootstrapExceptions.Failed.class);
    }

    @Test
    void aWeirdStateStringIsNotKept() {
        BootstrapExceptions.Failed odd = new BootstrapExceptions.Failed("first.admin@example.test");
        assertThat(odd.sqlState()).isNull();
        assertThat(odd.toString()).isEqualTo("BootstrapExceptions.Failed[sqlState=none]");
    }
}
