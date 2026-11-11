package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.WardScopeRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.ward_assignment}, {@code ipd.emergency_access_grant} and the switch {@code ipd.config.nurse_ward_scope}
 * (SAH-34, docs/database/SAH-34.md). The inserts and updates name only the columns the database role may write: the
 * trigger stamps {@code opened_at}, {@code expires_at}, {@code reviewed_at} and {@code removed_at}. Liveness of a grant is
 * {@code expires_at > clock_timestamp()} in SQL (never an application clock). The one {@code now()} in those two
 * statements is {@code expires_at > now() - interval '1 minute'}, a stable bound that lets the index skip old grants; it
 * can only remove rows that the exact {@code clock_timestamp()} test would also remove (SAH-34 QA F-1). The statements
 * are fixed text; every value is a bound parameter.
 */
@Repository
public class JdbcWardScopeRepository implements WardScopeRepository {

    static final String FIND_SWITCH = """
            select nurse_ward_scope from ipd.config where tenant_id = :tenantId
            """;
    static final String UPSERT_SWITCH = """
            insert into ipd.config (tenant_id, nurse_ward_scope, updated_at, updated_by)
            values (:tenantId, :on, :now, :actor)
            on conflict (tenant_id) do update set
                nurse_ward_scope = excluded.nurse_ward_scope,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;
    static final String LIVE_WARDS = """
            select ward_id from ipd.ward_assignment
            where tenant_id = :tenantId and membership_id = :membershipId and removed_at is null
            """;
    static final String LIVE_GRANTS = """
            select g.admission_id, max(g.expires_at) as expires_at
            from ipd.emergency_access_grant g
            join ipd.admission a on a.tenant_id = g.tenant_id and a.id = g.admission_id
            where g.tenant_id = :tenantId and g.membership_id = :membershipId and g.role = :role
              and g.expires_at > now() - interval '1 minute' and g.expires_at > clock_timestamp()
              and a.status = 'ADMITTED'
            group by g.admission_id
            """;
    static final String HAS_LIVE_GRANT = """
            select exists (
                select 1
                from ipd.emergency_access_grant g
                join ipd.admission a on a.tenant_id = g.tenant_id and a.id = g.admission_id
                where g.tenant_id = :tenantId and g.membership_id = :membershipId and g.role = :role
                  and g.admission_id = :admissionId and g.expires_at > now() - interval '1 minute'
                  and g.expires_at > clock_timestamp() and a.status = 'ADMITTED')
            """;
    static final String FACTS_COLUMNS = """
            select a.id, a.patient_id, a.bed_id, b.code as bed_code, b.ward_id, a.doctor_membership_id, a.status
            from ipd.admission a
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            """;
    static final String FIND_FACTS = FACTS_COLUMNS + """
            where a.tenant_id = :tenantId and a.id = :admissionId
            """;
    static final String FIND_ADMITTED_FACTS = FACTS_COLUMNS + """
            where a.tenant_id = :tenantId and a.status = 'ADMITTED'
            """;
    static final String NOW = "select clock_timestamp()";
    static final String LIVE_ASSIGNMENTS = """
            select ward_id, membership_id from ipd.ward_assignment
            where tenant_id = :tenantId and removed_at is null
            """;
    static final String INSERT_ASSIGNMENT = """
            insert into ipd.ward_assignment (ward_id, membership_id, assigned_by)
            values (:wardId, :membershipId, :by)
            """;
    static final String END_ASSIGNMENT = """
            update ipd.ward_assignment set removed_by = :by, version = version + 1
            where tenant_id = :tenantId and ward_id = :wardId and membership_id = :membershipId
              and removed_at is null
            """;
    static final String INSERT_GRANT = """
            insert into ipd.emergency_access_grant (admission_id, bed_id, user_id, membership_id, role, reason_key)
            values (:admissionId, :bedId, :userId, :membershipId, :role, :reasonKey)
            returning id, opened_at, expires_at
            """;
    static final String GRANT_COLUMNS = """
            select g.id, g.admission_id, a.patient_id, g.bed_id, b.code as bed_code, g.user_id, g.membership_id, g.role,
                   g.reason_key, g.opened_at, g.expires_at, g.reviewed_at, a.status
            from ipd.emergency_access_grant g
            join ipd.bed b on b.tenant_id = g.tenant_id and b.id = g.bed_id
            join ipd.admission a on a.tenant_id = g.tenant_id and a.id = g.admission_id
            where g.tenant_id = :tenantId
            """;
    static final String FIND_GRANT = GRANT_COLUMNS + " and g.id = :id";
    static final String MARK_REVIEWED = """
            update ipd.emergency_access_grant set reviewed_by = :me, version = version + 1
            where tenant_id = :tenantId and id = :id and reviewed_at is null
            returning reviewed_at
            """;
    static final String COUNT_UNREVIEWED = """
            select count(*) from ipd.emergency_access_grant where tenant_id = :tenantId and reviewed_at is null
            """;
    static final String UNREVIEWED_MARK = """
            select count(*) as waiting,
                   (select id from ipd.emergency_access_grant
                    where tenant_id = :tenantId and reviewed_at is null
                    order by opened_at desc, id desc limit 1) as newest_id
            from ipd.emergency_access_grant
            where tenant_id = :tenantId and reviewed_at is null
            """;
    static final String UNREVIEWED_ORDER = " and g.reviewed_at is null";
    static final String AFTER = " and (g.opened_at, g.id) > (:afterAt, :afterId)";
    static final String UNREVIEWED_TAIL = " order by g.opened_at, g.id limit :limit";
    static final String GRANTEES = """
            select id, user_id from ipd.emergency_access_grant
            where tenant_id = :tenantId and id = any(cast(:ids as uuid[]))
            """;

    private final JdbcClient jdbc;

    public JdbcWardScopeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean nurseWardScope(UUID tenantId) {
        return jdbc.sql(FIND_SWITCH).param("tenantId", tenantId).query((rs, rowNum) -> rs.getBoolean(1)).optional()
                .orElse(false);
    }

    @Override
    public void upsertNurseWardScope(UUID tenantId, boolean on, UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT_SWITCH).param("tenantId", tenantId).param("on", on).param("now", now)
                .param("actor", actorUserId).update();
    }

    @Override
    public Set<UUID> liveWardIds(UUID tenantId, UUID membershipId) {
        return new HashSet<>(jdbc.sql(LIVE_WARDS).param("tenantId", tenantId).param("membershipId", membershipId)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).list());
    }

    @Override
    public Map<UUID, Instant> liveGrants(UUID tenantId, UUID membershipId, String role) {
        Map<UUID, Instant> grants = new HashMap<>();
        List<Map.Entry<UUID, Instant>> rows = jdbc.sql(LIVE_GRANTS).param("tenantId", tenantId)
                .param("membershipId", membershipId).param("role", role)
                .query((rs, rowNum) -> Map.entry(rs.getObject("admission_id", UUID.class), instant(rs, "expires_at")))
                .list();
        for (Map.Entry<UUID, Instant> row : rows) {
            grants.put(row.getKey(), row.getValue());
        }
        return grants;
    }

    @Override
    public boolean hasLiveGrant(UUID tenantId, UUID membershipId, String role, UUID admissionId) {
        return jdbc.sql(HAS_LIVE_GRANT).param("tenantId", tenantId).param("membershipId", membershipId)
                .param("role", role).param("admissionId", admissionId).query((rs, rowNum) -> rs.getBoolean(1))
                .single();
    }

    @Override
    public Optional<Facts> findFacts(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_FACTS).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcWardScopeRepository::facts).optional();
    }

    @Override
    public List<Facts> findAdmittedFacts(UUID tenantId) {
        return jdbc.sql(FIND_ADMITTED_FACTS).param("tenantId", tenantId).query(JdbcWardScopeRepository::facts).list();
    }

    @Override
    public Instant databaseNow() {
        return jdbc.sql(NOW).query((rs, rowNum) -> instant(rs, 1)).single();
    }

    @Override
    public List<Assignment> liveAssignments(UUID tenantId) {
        return jdbc.sql(LIVE_ASSIGNMENTS).param("tenantId", tenantId)
                .query((rs, rowNum) -> new Assignment(rs.getObject("ward_id", UUID.class),
                        rs.getObject("membership_id", UUID.class)))
                .list();
    }

    @Override
    public void insertAssignment(UUID tenantId, UUID wardId, UUID membershipId, UUID actorUserId) {
        jdbc.sql(INSERT_ASSIGNMENT).param("wardId", wardId).param("membershipId", membershipId)
                .param("by", actorUserId).update();
    }

    @Override
    public boolean endAssignment(UUID tenantId, UUID wardId, UUID membershipId, UUID actorUserId) {
        return jdbc.sql(END_ASSIGNMENT).param("tenantId", tenantId).param("wardId", wardId)
                .param("membershipId", membershipId).param("by", actorUserId).update() == 1;
    }

    @Override
    public Stamped insertGrant(UUID tenantId, UUID admissionId, UUID bedId, UUID userId, UUID membershipId,
                               String role, String reasonKey) {
        return jdbc.sql(INSERT_GRANT).param("admissionId", admissionId).param("bedId", bedId)
                .param("userId", userId).param("membershipId", membershipId).param("role", role)
                .param("reasonKey", reasonKey)
                .query((rs, rowNum) -> new Stamped(rs.getObject("id", UUID.class), instant(rs, "opened_at"),
                        instant(rs, "expires_at")))
                .single();
    }

    @Override
    public Optional<Grant> findGrant(UUID tenantId, UUID grantId) {
        return jdbc.sql(FIND_GRANT).param("tenantId", tenantId).param("id", grantId)
                .query(JdbcWardScopeRepository::grant).optional();
    }

    @Override
    public Optional<Instant> markReviewed(UUID tenantId, UUID grantId, UUID reviewerUserId) {
        return jdbc.sql(MARK_REVIEWED).param("tenantId", tenantId).param("id", grantId).param("me", reviewerUserId)
                .query((rs, rowNum) -> instant(rs, "reviewed_at")).optional();
    }

    @Override
    public int countUnreviewed(UUID tenantId) {
        return jdbc.sql(COUNT_UNREVIEWED).param("tenantId", tenantId).query((rs, rowNum) -> rs.getInt(1)).single();
    }

    @Override
    public UnreviewedMark unreviewedMark(UUID tenantId) {
        return jdbc.sql(UNREVIEWED_MARK).param("tenantId", tenantId)
                .query((rs, rowNum) -> new UnreviewedMark(rs.getInt("waiting"), rs.getObject("newest_id", UUID.class)))
                .single();
    }

    @Override
    public List<Grant> findUnreviewed(UUID tenantId, Instant afterOpenedAt, UUID afterId, int limit) {
        boolean keyset = afterOpenedAt != null && afterId != null;
        String sql = GRANT_COLUMNS + UNREVIEWED_ORDER + (keyset ? AFTER : "") + UNREVIEWED_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit);
        if (keyset) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(afterOpenedAt, ZoneOffset.UTC))
                    .param("afterId", afterId);
        }
        return spec.query(JdbcWardScopeRepository::grant).list();
    }

    @Override
    public Map<UUID, UUID> granteeOf(UUID tenantId, Collection<UUID> grantIds) {
        Map<UUID, UUID> found = new HashMap<>();
        if (grantIds.isEmpty()) {
            return found;
        }
        List<Map.Entry<UUID, UUID>> rows = jdbc.sql(GRANTEES).param("tenantId", tenantId)
                .param("ids", JdbcMedicineRoundRepository.arrayLiteral(grantIds))
                .query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)))
                .list();
        for (Map.Entry<UUID, UUID> row : rows) {
            found.put(row.getKey(), row.getValue());
        }
        return found;
    }

    private static Facts facts(ResultSet rs, int rowNum) throws SQLException {
        return new Facts(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("bed_id", UUID.class), rs.getString("bed_code"), rs.getObject("ward_id", UUID.class),
                rs.getObject("doctor_membership_id", UUID.class), rs.getString("status"));
    }

    private static Grant grant(ResultSet rs, int rowNum) throws SQLException {
        return new Grant(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getObject("bed_id", UUID.class), rs.getString("bed_code"), rs.getObject("user_id", UUID.class),
                rs.getObject("membership_id", UUID.class), rs.getString("role"), rs.getString("reason_key"),
                instant(rs, "opened_at"), instant(rs, "expires_at"), instant(rs, "reviewed_at"),
                rs.getString("status"));
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
