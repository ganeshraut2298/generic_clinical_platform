package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminDbStateException;
import com.sahaj.platform.application.admin.AdminIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calls the thirteen SECURITY DEFINER functions of ADM-1 (docs/database/ADM-1.md) with bound
 * parameters only. Each method is one transaction of its own, opened here with a
 * {@link TransactionTemplate} (not {@code @Transactional}) so the commit-on-empty-result rule for
 * refresh-token reuse is explicit: the transaction rolls back only when a method throws.
 *
 * <p>The pre-auth functions bind nothing. {@code admin_sign_out} and {@code admin_me} need
 * {@code app.admin_id} and {@code app.admin_session_id}, which the transaction manager binds from
 * the {@link AdminAuthentication} of the request.
 */
public class JdbcAdminAuthRepository implements AdminAuthRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcAdminAuthRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<LoginLookup> loginLookup(String email) {
        return inTransaction(() -> jdbc
                .sql("select admin_id, password_hash, totp_enrolled from platform.admin_login_lookup(:email)")
                .param("email", email)
                .query((rs, n) -> new LoginLookup(rs.getObject("admin_id", UUID.class),
                        rs.getString("password_hash"), rs.getBoolean("totp_enrolled")))
                .optional());
    }

    @Override
    public Optional<Instant> throttleStatus(String keyHash) {
        return inTransaction(() -> {
            List<Instant> rows = jdbc.sql("select platform.admin_throttle_status(:key)")
                    .param("key", keyHash)
                    .query((rs, n) -> instant(rs, 1))
                    .list();
            return rows.isEmpty() ? Optional.<Instant>empty() : Optional.ofNullable(rows.get(0));
        });
    }

    @Override
    public FailureResult authFailed(String keyHash, UUID adminId, String preAuthHash, String step, String ip,
                                     String requestId) {
        return inTransaction(() -> jdbc.sql("""
                        select locked_until, preauth_consumed
                          from platform.admin_auth_failed(cast(:key as text), cast(:admin as uuid),
                                                          cast(:pre as text), :step, cast(:ip as inet),
                                                          cast(:req as text))
                        """)
                .param("key", keyHash).param("admin", adminId).param("pre", preAuthHash).param("step", step)
                .param("ip", IpLiterals.forDatabase(ip)).param("req", requestId)
                .query((rs, n) -> new FailureResult(instant(rs, "locked_until"), rs.getBoolean("preauth_consumed")))
                .single());
    }

    @Override
    public Instant preAuthCreate(UUID adminId, String keyHash, String tokenHash, String purpose, String ip) {
        return inTransaction(() -> jdbc.sql("""
                        select platform.admin_preauth_create(:admin, :key, :token, :purpose, cast(:ip as inet))
                        """)
                .param("admin", adminId).param("key", keyHash).param("token", tokenHash).param("purpose", purpose)
                .param("ip", IpLiterals.forDatabase(ip))
                .query((rs, n) -> instant(rs, 1))
                .single());
    }

    @Override
    public Optional<PreAuth> preAuthGet(String tokenHash) {
        return inTransaction(() -> jdbc.sql("""
                        select preauth_id, admin_id, purpose, totp_secret_enc, totp_key_version,
                               pending_secret_enc, pending_key_version, totp_last_used_step, email, throttle_key
                          from platform.admin_preauth_get(:token)
                        """)
                .param("token", tokenHash)
                .query((rs, n) -> new PreAuth(rs.getObject("preauth_id", UUID.class),
                        rs.getObject("admin_id", UUID.class), rs.getString("purpose"),
                        rs.getBytes("totp_secret_enc"), smallint(rs, "totp_key_version"),
                        rs.getBytes("pending_secret_enc"), smallint(rs, "pending_key_version"),
                        longOrNull(rs, "totp_last_used_step"), rs.getString("email"),
                        rs.getString("throttle_key")))
                .optional());
    }

    @Override
    public void totpSetPending(String tokenHash, byte[] secretEnc, int keyVersion) {
        inTransaction(() -> {
            jdbc.sql("select platform.admin_totp_set_pending(:token, :secret, cast(:version as smallint))")
                    .param("token", tokenHash).param("secret", secretEnc).param("version", keyVersion)
                    .query().listOfRows();
            return null;
        });
    }

    @Override
    public SessionRow signIn(String tokenHash, String method, Long totpStep, String recoveryHash,
                              String refreshHash, String ip, String requestId, String summary) {
        return inTransaction(() -> jdbc.sql("""
                        select session_id, admin_id, full_name
                          from platform.admin_sign_in(:token, :method, cast(:step as bigint),
                                                      cast(:recovery as text), :refresh, cast(:ip as inet),
                                                      cast(:req as text), cast(:summary as text))
                        """)
                .param("token", tokenHash).param("method", method).param("step", totpStep)
                .param("recovery", recoveryHash).param("refresh", refreshHash)
                .param("ip", IpLiterals.forDatabase(ip)).param("req", requestId).param("summary", summary)
                .query(JdbcAdminAuthRepository::sessionRow)
                .single());
    }

    @Override
    public SessionRow completeEnrolment(String tokenHash, Long totpStep, List<String> recoveryHashes,
                                         String refreshHash, String ip, String requestId) {
        // The hashes are 64 lower-case hex characters made by this application, so the array literal is safe.
        String literal = "{" + String.join(",", recoveryHashes) + "}";
        return inTransaction(() -> jdbc.sql("""
                        select session_id, admin_id, full_name
                          from platform.admin_complete_enrolment(:token, cast(:step as bigint),
                                                                 cast(:hashes as text[]), :refresh,
                                                                 cast(:ip as inet), cast(:req as text))
                        """)
                .param("token", tokenHash).param("step", totpStep).param("hashes", literal)
                .param("refresh", refreshHash).param("ip", IpLiterals.forDatabase(ip)).param("req", requestId)
                .query(JdbcAdminAuthRepository::sessionRow)
                .single());
    }

    @Override
    public boolean sessionTouch(UUID sessionId, UUID adminId) {
        return inTransaction(() -> Boolean.TRUE.equals(jdbc
                .sql("select platform.admin_session_touch(:session, :admin)")
                .param("session", sessionId).param("admin", adminId)
                .query(Boolean.class)
                .single()));
    }

    @Override
    public Optional<RotatedSession> sessionRotate(String oldHash, String newHash) {
        // No exception on an empty result: a detected reuse revokes the session and that write must commit.
        return inTransaction(() -> jdbc
                .sql("""
                        select session_id, admin_id, full_name, auth_method
                          from platform.admin_session_rotate(:oldHash, :newHash)
                        """)
                .param("oldHash", oldHash).param("newHash", newHash)
                .query((rs, n) -> new RotatedSession(sessionRow(rs, n), rs.getString("auth_method")))
                .optional());
    }

    @Override
    public void signOut() {
        inTransaction(() -> {
            jdbc.sql("select platform.admin_sign_out()").query().listOfRows();
            return null;
        });
    }

    @Override
    public Optional<AdminIdentity> me() {
        return inTransaction(() -> jdbc.sql("select admin_id, full_name from platform.admin_me()")
                .query((rs, n) -> new AdminIdentity(rs.getObject("admin_id", UUID.class), rs.getString("full_name")))
                .optional());
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (RuntimeException ex) {
            AdminDbStateException state = stateOf(ex);
            if (state != null) {
                throw state;
            }
            throw ex;
        }
    }

    /** Finds the SQLSTATE in the cause chain and maps it; null for any other failure. */
    static AdminDbStateException stateOf(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof SQLException sql) {
                AdminDbStateException.State state = AdminDbStateException.State.fromSqlState(sql.getSQLState());
                return state == null ? null : new AdminDbStateException(state);
            }
            current = current.getCause();
        }
        return null;
    }

    private static SessionRow sessionRow(ResultSet rs, int n) throws SQLException {
        return new SessionRow(rs.getObject("session_id", UUID.class), rs.getObject("admin_id", UUID.class),
                rs.getString("full_name"));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer smallint(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
