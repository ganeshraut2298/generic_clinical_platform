package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.OtpChallengeRepository;
import com.sahaj.platform.domain.OtpChallenge;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.otp_challenge}: no tenant, no row level security (SAH-3). */
@Repository
public class JdbcOtpChallengeRepository implements OtpChallengeRepository {

    private static final String FIND_LATEST = """
            select id, mobile, code_hash, expires_at, failed_count, locked_until, last_sent_at, consumed_at
            from platform.otp_challenge where mobile = :mobile order by created_at desc limit 1
            """;
    private static final String INSERT = """
            insert into platform.otp_challenge (id, mobile, code_hash, expires_at, last_sent_at)
            values (:id, :mobile, :codeHash, :expiresAt, :lastSentAt)
            """;
    private static final String RECORD_FAILED_ATTEMPT = """
            update platform.otp_challenge set failed_count = :failedCount, locked_until = :lockedUntil where id = :id
            """;
    private static final String MARK_CONSUMED = """
            update platform.otp_challenge set consumed_at = :consumedAt where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcOtpChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OtpChallenge> findLatestByMobile(String mobile) {
        return jdbc.sql(FIND_LATEST).param("mobile", mobile).query(JdbcOtpChallengeRepository::map).optional();
    }

    @Override
    public UUID insert(String mobile, String codeHash, Instant expiresAt, Instant lastSentAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT)
                .param("id", id).param("mobile", mobile).param("codeHash", codeHash)
                .param("expiresAt", expiresAt).param("lastSentAt", lastSentAt)
                .update();
        return id;
    }

    @Override
    public void recordFailedAttempt(UUID id, int failedCount, Instant lockedUntil) {
        jdbc.sql(RECORD_FAILED_ATTEMPT).param("id", id).param("failedCount", failedCount).param("lockedUntil", lockedUntil).update();
    }

    @Override
    public void markConsumed(UUID id, Instant consumedAt) {
        jdbc.sql(MARK_CONSUMED).param("id", id).param("consumedAt", consumedAt).update();
    }

    private static OtpChallenge map(ResultSet rs, int rowNum) throws SQLException {
        return new OtpChallenge(
                rs.getObject("id", UUID.class),
                rs.getString("mobile"),
                rs.getString("code_hash"),
                toInstant(rs.getTimestamp("expires_at")),
                rs.getInt("failed_count"),
                toInstant(rs.getTimestamp("locked_until")),
                toInstant(rs.getTimestamp("last_sent_at")),
                toInstant(rs.getTimestamp("consumed_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
