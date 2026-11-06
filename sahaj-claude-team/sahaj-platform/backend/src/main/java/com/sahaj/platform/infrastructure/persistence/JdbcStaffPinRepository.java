package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.StaffPinRepository;
import com.sahaj.platform.domain.StaffPinRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.staff_pin} (SAH-31, ADR-010). The statements are the ones in {@code docs/database/SAH-31-pin.md}:
 * each is exactly one kind of change the guard triggers accept, always {@code version = version + 1}, and times that
 * decide anything ({@code set_at}, {@code locked_until}, {@code reset_at}, the window start) are the database clock,
 * never a value from the application. {@code tenant_id} is never inserted (it defaults to the bound clinic) and
 * identity columns are never updated. Row level security limits every statement to the bound clinic; the tenant id
 * is also in each query. Nothing here logs a hash.
 */
@Repository
public class JdbcStaffPinRepository implements StaffPinRepository {

    private static final String COLUMNS = """
            membership_id, pin_hash, pepper_version, failed_count, locked_until, lock_count,
            lock_window_started_at, set_at, not_set_reason, reset_at, version, clock_timestamp() as db_now
            """;
    private static final String FIND = "select " + COLUMNS
            + " from platform.staff_pin where tenant_id = :t and membership_id = :m";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_ALL = "select " + COLUMNS + " from platform.staff_pin where tenant_id = :t";
    private static final String COUNT_WITHOUT_PIN = """
            select count(*) from platform.membership m
            left join platform.staff_pin p on p.tenant_id = m.tenant_id and p.membership_id = m.id
            where m.tenant_id = :t and m.status = 'ACTIVE' and p.pin_hash is null
            """;
    private static final String INSERT = """
            insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, created_by)
            values (:m, :hash, :pv, clock_timestamp(), :u)
            """;
    private static final String REPLACE = """
            update platform.staff_pin
               set pin_hash = :hash, pepper_version = :pv, failed_count = 0, locked_until = null, lock_count = 0,
                   lock_window_started_at = null, set_at = clock_timestamp(), not_set_reason = null,
                   updated_by = :u, version = version + 1
             where tenant_id = :t and membership_id = :m
            """;
    private static final String WRONG_TRY = """
            update platform.staff_pin
               set failed_count = failed_count + 1, updated_by = :u, version = version + 1
             where tenant_id = :t and membership_id = :m
            """;
    private static final String RIGHT_PIN = """
            update platform.staff_pin
               set failed_count = 0, updated_by = :u, version = version + 1
             where tenant_id = :t and membership_id = :m
            """;
    private static final String LOCK = """
            update platform.staff_pin
               set failed_count = 0, lock_count = :n, locked_until = clock_timestamp() + interval '15 minutes',
                   lock_window_started_at = case when :n = 1 then clock_timestamp() else lock_window_started_at end,
                   updated_by = :u, version = version + 1
             where tenant_id = :t and membership_id = :m
            """;
    private static final String SWITCH_OFF = """
            update platform.staff_pin
               set pin_hash = null, set_at = null, not_set_reason = 'TOO_MANY_LOCKS', failed_count = 0,
                   locked_until = null, lock_count = 0, lock_window_started_at = null,
                   updated_by = :u, version = version + 1
             where tenant_id = :t and membership_id = :m
            """;
    private static final String RESET = """
            update platform.staff_pin
               set pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', failed_count = 0,
                   locked_until = null, lock_count = 0, lock_window_started_at = null,
                   reset_by = :u, reset_at = clock_timestamp(), updated_by = :u, version = version + 1
             where tenant_id = :t and membership_id = :m
            """;

    private final JdbcClient jdbc;

    public JdbcStaffPinRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StaffPinRow> find(UUID tenantId, UUID membershipId, boolean forUpdate) {
        return jdbc.sql(forUpdate ? FIND_FOR_UPDATE : FIND).param("t", tenantId).param("m", membershipId)
                .query(JdbcStaffPinRepository::map).optional();
    }

    @Override
    public List<StaffPinRow> findAll(UUID tenantId) {
        return jdbc.sql(FIND_ALL).param("t", tenantId).query(JdbcStaffPinRepository::map).list();
    }

    @Override
    public int countActiveMembersWithoutPin(UUID tenantId) {
        return jdbc.sql(COUNT_WITHOUT_PIN).param("t", tenantId).query(Integer.class).single();
    }

    @Override
    public void insert(UUID membershipId, String pinHash, int pepperVersion, UUID actorUserId) {
        jdbc.sql(INSERT).param("m", membershipId).param("hash", pinHash).param("pv", pepperVersion)
                .param("u", actorUserId).update();
    }

    @Override
    public void replace(UUID tenantId, UUID membershipId, String pinHash, int pepperVersion, UUID actorUserId) {
        jdbc.sql(REPLACE).param("t", tenantId).param("m", membershipId).param("hash", pinHash)
                .param("pv", pepperVersion).param("u", actorUserId).update();
    }

    @Override
    public void recordWrongTry(UUID tenantId, UUID membershipId, UUID actorUserId) {
        jdbc.sql(WRONG_TRY).param("t", tenantId).param("m", membershipId).param("u", actorUserId).update();
    }

    @Override
    public void recordRightPin(UUID tenantId, UUID membershipId, UUID actorUserId) {
        jdbc.sql(RIGHT_PIN).param("t", tenantId).param("m", membershipId).param("u", actorUserId).update();
    }

    @Override
    public void lock(UUID tenantId, UUID membershipId, int newLockCount, UUID actorUserId) {
        jdbc.sql(LOCK).param("t", tenantId).param("m", membershipId).param("n", newLockCount)
                .param("u", actorUserId).update();
    }

    @Override
    public void switchOffAfterLocks(UUID tenantId, UUID membershipId, UUID actorUserId) {
        jdbc.sql(SWITCH_OFF).param("t", tenantId).param("m", membershipId).param("u", actorUserId).update();
    }

    @Override
    public void reset(UUID tenantId, UUID membershipId, UUID ownerUserId) {
        jdbc.sql(RESET).param("t", tenantId).param("m", membershipId).param("u", ownerUserId).update();
    }

    private static StaffPinRow map(ResultSet rs, int rowNum) throws SQLException {
        return new StaffPinRow(
                rs.getObject("membership_id", UUID.class),
                rs.getString("pin_hash"),
                rs.getInt("pepper_version"),
                rs.getInt("failed_count"),
                instant(rs.getTimestamp("locked_until")),
                rs.getInt("lock_count"),
                instant(rs.getTimestamp("lock_window_started_at")),
                instant(rs.getTimestamp("set_at")),
                rs.getString("not_set_reason"),
                instant(rs.getTimestamp("reset_at")),
                rs.getLong("version"),
                instant(rs.getTimestamp("db_now")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
