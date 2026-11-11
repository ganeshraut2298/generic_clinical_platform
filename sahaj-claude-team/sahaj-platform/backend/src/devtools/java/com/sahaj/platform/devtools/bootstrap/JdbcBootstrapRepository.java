package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.infrastructure.admin.IpLiterals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Calls the five functions with bound parameters only (docs/database/SAH-42.md, ADR-018 Revision 3). Every call runs in
 * the transaction {@link BootstrapService} opened for it. The SQLSTATE rule of the design note:
 * {@code SB001} (an admin exists) becomes {@link BootstrapExceptions.Closed}, {@code 55P03} (waited more than 10
 * seconds) becomes {@link BootstrapExceptions.Busy}, and every other failure, {@code SB003} (state row missing),
 * {@code SB004} (the try is not open: no lease, another try's lease or a lease that ran out), {@code 42501},
 * {@code 22023} and the rest, becomes a {@link BootstrapExceptions.Failed} with no message and no cause, because the
 * driver's text can hold the failing row (email, password hash, ticket hash). {@code SB002} is retired and is also a
 * {@code Failed}. Nothing here logs.
 */
final class JdbcBootstrapRepository implements BootstrapRepository {

    static final String CLOSED = "SB001";
    static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    JdbcBootstrapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim begin(UUID tryId) {
        return run(() -> jdbc.sql("""
                        select outcome, failures, locked_until
                          from platform.admin_bootstrap_begin(cast(:try as uuid))
                        """)
                .param("try", tryId)
                .query((rs, n) -> new Claim(ClaimOutcome.valueOf(rs.getString("outcome")), rs.getInt("failures"),
                        instant(rs, "locked_until")))
                .single());
    }

    @Override
    public Failure unlockFailed(UUID tryId, String remoteAddress, String requestId) {
        return run(() -> jdbc.sql("""
                        select failures, locked_until
                          from platform.admin_bootstrap_unlock_failed(cast(:try as uuid), cast(:ip as inet),
                                                                      cast(:req as text))
                        """)
                .param("try", tryId).param("ip", IpLiterals.forDatabase(remoteAddress)).param("req", requestId)
                .query((rs, n) -> new Failure(rs.getInt("failures"), instant(rs, "locked_until")))
                .single());
    }

    @Override
    public void unlockSucceeded(UUID tryId, String ticketHash, String bindingHash) {
        // The function returns the ticket's end time; the answer to the caller is the fixed 300 seconds, so it is not kept.
        run(() -> jdbc.sql("select platform.admin_bootstrap_unlock_succeeded(cast(:try as uuid), "
                        + "cast(:ticket as text), cast(:binding as text))")
                .param("try", tryId).param("ticket", ticketHash).param("binding", bindingHash)
                .query((rs, n) -> instant(rs, 1))
                .single());
    }

    @Override
    public TicketState ticketStatus(String ticketHash, String bindingHash) {
        return run(() -> jdbc.sql("""
                        select status, locked_until
                          from platform.admin_bootstrap_ticket_status(cast(:ticket as text), cast(:binding as text))
                        """)
                .param("ticket", ticketHash).param("binding", bindingHash)
                .query((rs, n) -> new TicketState(Status.valueOf(rs.getString("status")), instant(rs, "locked_until")))
                .single());
    }

    @Override
    public RegisterReply register(String ticketHash, String bindingHash, UUID adminId, String email, String fullName,
                                  String passwordHash, String remoteAddress, String requestId) {
        return run(() -> jdbc.sql("""
                        select outcome, locked_until
                          from platform.admin_bootstrap_register(cast(:ticket as text), cast(:binding as text),
                               cast(:id as uuid), cast(:email as text), cast(:name as text), cast(:hash as text),
                               cast(:ip as inet), cast(:req as text))
                        """)
                .param("ticket", ticketHash).param("binding", bindingHash).param("id", adminId)
                .param("email", email).param("name", fullName).param("hash", passwordHash)
                .param("ip", IpLiterals.forDatabase(remoteAddress)).param("req", requestId)
                .query((rs, n) -> new RegisterReply(RegisterResult.valueOf(rs.getString("outcome")),
                        instant(rs, "locked_until")))
                .single());
    }

    private static <T> T run(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException ex) {
            String sqlState = sqlStateOf(ex);
            if (CLOSED.equals(sqlState)) {
                throw new BootstrapExceptions.Closed();
            }
            if (LOCK_NOT_AVAILABLE.equals(sqlState)) {
                throw new BootstrapExceptions.Busy();
            }
            throw new BootstrapExceptions.Failed(sqlState);
        }
    }

    /** The SQLSTATE found in the cause chain, or null. */
    static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SQLException sql) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
