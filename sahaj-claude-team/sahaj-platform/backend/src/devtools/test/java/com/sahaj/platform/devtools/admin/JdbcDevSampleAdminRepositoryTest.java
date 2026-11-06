package com.sahaj.platform.devtools.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The call to platform.admin_dev_create_sample_admin and the SQLSTATE rule of docs/database/ADR-008-dev-sample-admin.md. */
class JdbcDevSampleAdminRepositoryTest {

    private final Map<String, Object> params = new HashMap<>();
    private RuntimeException failure;
    private Boolean answer = Boolean.TRUE;
    private String sqlText;

    @SuppressWarnings("unchecked")
    private JdbcClient jdbc() {
        JdbcClient.MappedQuerySpec<Boolean> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(mapped.single()).thenAnswer(invocation -> {
            if (failure != null) {
                throw failure;
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

    private boolean call(String ip) {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new JdbcDevSampleAdminRepository(jdbc()).createSampleAdmin(id, "hash", new byte[] {1, 2}, 1, ip,
                "req-1");
    }

    @Test
    void callsTheFunctionWithBoundParametersOnly() {
        assertThat(call("127.0.0.1")).isTrue();

        assertThat(sqlText).contains("platform.admin_dev_create_sample_admin(")
                .contains("cast(:version as smallint)").contains("cast(:ip as inet)").contains("cast(:req as text)");
        assertThat(params).containsOnlyKeys("id", "hash", "enc", "version", "ip", "req");
        assertThat(params.get("id")).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(params.get("ip")).isEqualTo("127.0.0.1");
        assertThat(params.get("req")).isEqualTo("req-1");
        assertThat(params.get("version")).isEqualTo(1);
    }

    @Test
    void anAddressThatIsNotAnIpLiteralIsSentAsNull() {
        call("localhost");

        assertThat(params.get("ip")).isNull();
    }

    @Test
    void falseFromTheDatabaseIsFalseNotAnError() {
        answer = Boolean.FALSE;

        assertThat(call("127.0.0.1")).isFalse();
    }

    @Test
    void aUniqueViolationBecomesUnavailable() {
        failure = new DuplicateKeyException("could not insert", new SQLException("duplicate key", "23505"));

        assertThatThrownBy(() -> call("127.0.0.1"))
                .isInstanceOf(DevSampleAdminUnavailableException.class)
                .hasNoCause();
    }

    @Test
    void anyOtherSqlStateBecomesAMessageFreeCauseLessExceptionThatKeepsOnlyTheState() {
        for (String state : new String[] {"42501", "22023", "22P02", "42883", "3F000"}) {
            failure = new UncategorizedSQLException("task", "sql", new SQLException("message", state));
            assertThatThrownBy(() -> call("127.0.0.1")).as(state)
                    .isInstanceOfSatisfying(DevSampleAdminFailedException.class, e -> {
                        assertThat(e.getMessage()).isNull();
                        assertThat(e.getCause()).isNull();
                        assertThat(e.getSuppressed()).isEmpty();
                        assertThat(e.sqlState()).isEqualTo(state);
                        assertThat(e.toString()).isEqualTo("DevSampleAdminFailedException[sqlState=" + state + "]");
                        assertThat(e.getStackTrace()).isEmpty();
                    });
        }
    }

    @Test
    void theFailingRowInTheDriversMessageAndCauseNeverSurvives() {
        String row = "ERROR: new row violates check constraint \"ck_platform_admin_password_hash\" Detail: Failing row "
                + "contains (11111111, sample-admin@example.test, $2a$12$abcdefghijklmnopqrstuvwxyz, \\x00ff).";
        failure = new org.springframework.dao.DataIntegrityViolationException(row, new SQLException(row, "23514"));

        assertThatThrownBy(() -> call("127.0.0.1")).isInstanceOf(DevSampleAdminFailedException.class)
                .satisfies(e -> {
                    assertThat(String.valueOf(e.getMessage())).doesNotContain("sample-admin@example.test");
                    assertThat(e.toString()).doesNotContain("sample-admin@example.test").doesNotContain("$2a$")
                            .doesNotContain("Failing row").doesNotContain("ck_platform_admin_password_hash");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void aFailureWithNoSqlStateAtAllIsStillWrappedWithNone() {
        failure = new IllegalStateException("pool exhausted for sample-admin@example.test");

        assertThatThrownBy(() -> call("127.0.0.1")).isInstanceOfSatisfying(DevSampleAdminFailedException.class, e -> {
            assertThat(e.sqlState()).isNull();
            assertThat(e.toString()).isEqualTo("DevSampleAdminFailedException[sqlState=none]");
            assertThat(e.getCause()).isNull();
        });
    }

    @Test
    void aSqlStateThatIsNotFiveCapitalCharactersIsNotKept() {
        assertThat(new DevSampleAdminFailedException("23505 sample-admin@example.test").sqlState()).isNull();
        assertThat(new DevSampleAdminFailedException("2350").sqlState()).isNull();
        assertThat(new DevSampleAdminFailedException("23a05").sqlState()).isNull();
        assertThat(new DevSampleAdminFailedException(null).sqlState()).isNull();
        assertThat(new DevSampleAdminFailedException("3F000").sqlState()).isEqualTo("3F000");
    }

    @Test
    void findsTheSqlStateDeepInTheCauseChain() {
        Throwable failure = new RuntimeException("a", new RuntimeException("b", new SQLException("c", "23505")));

        assertThat(JdbcDevSampleAdminRepository.sqlStateOf(failure)).isEqualTo("23505");
        assertThat(JdbcDevSampleAdminRepository.sqlStateOf(new RuntimeException("none"))).isNull();
    }
}
