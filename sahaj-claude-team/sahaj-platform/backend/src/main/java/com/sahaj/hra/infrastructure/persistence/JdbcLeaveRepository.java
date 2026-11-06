package com.sahaj.hra.infrastructure.persistence;

import com.sahaj.hra.application.LeaveRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code hra.leave_request} (SAH-21). Row level security scopes every statement to the bound tenant; the
 * tenant id is also in every where clause and insert. {@code decided_at} and {@code decided_by} are always
 * written together (check {@code ck_leave_request_decided}). The reason text is never logged.
 */
@Repository
public class JdbcLeaveRepository implements LeaveRepository {

    private static final String SELECT = """
            select id, membership_id, leave_type, from_date, to_date, reason, status, created_at, decided_at,
                   decided_by
            from hra.leave_request
            """;
    private static final String LOCK_MEMBER =
            "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) locked";
    private static final String EXISTS_OVERLAP = """
            select count(*) from hra.leave_request
            where tenant_id = :tenantId and membership_id = :membershipId
              and status in ('pending', 'approved')
              and from_date <= :toDate and to_date >= :fromDate
            """;
    private static final String INSERT = """
            insert into hra.leave_request
                (id, tenant_id, membership_id, leave_type, from_date, to_date, reason, status, created_at, created_by)
            values
                (:id, :tenantId, :membershipId, :type, :fromDate, :toDate, :reason, 'pending', :now, :actor)
            """;
    private static final String FIND_BY_ID = SELECT + " where tenant_id = :tenantId and id = :id";
    private static final String FIND_RECENT = SELECT
            + " where tenant_id = :tenantId order by created_at desc, id limit :limit";
    private static final String COUNT_PENDING_FOR_MEMBER = """
            select count(*) from hra.leave_request
            where tenant_id = :tenantId and membership_id = :membershipId and status = 'pending'
            """;
    private static final String FIND_FOR_MEMBER = SELECT
            + " where tenant_id = :tenantId and membership_id = :membershipId order by created_at desc, id";
    private static final String FIND_APPROVED = SELECT
            + " where tenant_id = :tenantId and status = 'approved'"
            + " and from_date <= :toDate and to_date >= :fromDate";
    private static final String FIND_APPROVED_FOR_MEMBER = FIND_APPROVED + " and membership_id = :membershipId";
    private static final String COUNT_PENDING = """
            select count(*) from hra.leave_request where tenant_id = :tenantId and status = 'pending'
            """;
    private static final String DECIDE = """
            update hra.leave_request
            set status = :status, decided_at = :now, decided_by = :actor, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'pending'
            """;

    private final JdbcClient jdbc;

    public JdbcLeaveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockMember(UUID tenantId, UUID membershipId) {
        jdbc.sql(LOCK_MEMBER).param("key", "leave|" + tenantId + "|" + membershipId).query(Long.class).single();
    }

    @Override
    public boolean existsActiveOverlap(UUID tenantId, UUID membershipId, LocalDate from, LocalDate to) {
        Long count = jdbc.sql(EXISTS_OVERLAP)
                .param("tenantId", tenantId)
                .param("membershipId", membershipId)
                .param("fromDate", from)
                .param("toDate", to)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    @Override
    public void insert(UUID tenantId, UUID id, UUID membershipId, String type, LocalDate from, LocalDate to,
                       String reason, UUID actorUserId, Instant now) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("membershipId", membershipId)
                .param("type", type)
                .param("fromDate", from)
                .param("toDate", to)
                .param("reason", reason)
                .param("now", now)
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public Optional<LeaveRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcLeaveRepository::map).optional();
    }

    @Override
    public List<LeaveRow> findRecent(UUID tenantId, int limit) {
        return jdbc.sql(FIND_RECENT).param("tenantId", tenantId).param("limit", limit)
                .query(JdbcLeaveRepository::map).list();
    }

    @Override
    public long countPendingForMember(UUID tenantId, UUID membershipId) {
        Long count = jdbc.sql(COUNT_PENDING_FOR_MEMBER).param("tenantId", tenantId)
                .param("membershipId", membershipId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public List<LeaveRow> findForMember(UUID tenantId, UUID membershipId) {
        return jdbc.sql(FIND_FOR_MEMBER).param("tenantId", tenantId).param("membershipId", membershipId)
                .query(JdbcLeaveRepository::map).list();
    }

    @Override
    public List<LeaveRow> findApprovedOverlapping(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql(FIND_APPROVED).param("tenantId", tenantId).param("fromDate", from).param("toDate", to)
                .query(JdbcLeaveRepository::map).list();
    }

    @Override
    public List<LeaveRow> findApprovedOverlappingForMember(UUID tenantId, UUID membershipId, LocalDate from,
                                                           LocalDate to) {
        return jdbc.sql(FIND_APPROVED_FOR_MEMBER).param("tenantId", tenantId).param("membershipId", membershipId)
                .param("fromDate", from).param("toDate", to)
                .query(JdbcLeaveRepository::map).list();
    }

    @Override
    public long countPending(UUID tenantId) {
        Long count = jdbc.sql(COUNT_PENDING).param("tenantId", tenantId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public boolean decide(UUID tenantId, UUID id, String status, UUID actorUserId, Instant now) {
        return jdbc.sql(DECIDE)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("status", status)
                .param("now", now)
                .param("actor", actorUserId)
                .update() == 1;
    }

    private static LeaveRow map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new LeaveRow(rs.getObject("id", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getString("leave_type"), rs.getObject("from_date", LocalDate.class),
                rs.getObject("to_date", LocalDate.class), rs.getString("reason"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(), rs.getObject("decided_by", UUID.class));
    }
}
