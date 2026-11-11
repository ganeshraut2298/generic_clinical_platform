package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.ClinicalEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The clinical event log (SAH-30, SAH-33). The insert is the only write; the database sets the id, the order number,
 * the clinic and the time (the transaction time, so every event of one save shares one instant). The page query is
 * keyset based on {@code (at, seq)} and uses {@code ix_clinical_event_tenant_at}; the patient id and the bed come from
 * one left join, so a page costs one query. The statements are built from fixed fragments only, never from input.
 */
@Repository
public class JdbcClinicalEventRepository implements ClinicalEventRepository {

    static final String INSERT = """
            insert into ipd.clinical_event (admission_id, kind, text, actor)
            values (:admissionId, :kind, :text, :actor)
            """;
    static final String INSERT_FOR_GRANT = """
            insert into ipd.clinical_event (admission_id, kind, text, actor, grant_id)
            values (:admissionId, 'bg', :text, :actor, :grantId)
            """;
    static final String PAGE_HEAD = """
            select e.id, e.seq, e.kind, e.text, e.at, e.actor, e.admission_id, a.patient_id, b.code as bed_code,
                   g.user_id as grant_user_id
            from ipd.clinical_event e
            left join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id
            left join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            left join ipd.emergency_access_grant g on g.tenant_id = e.tenant_id and g.id = e.grant_id
            where e.tenant_id = :tenantId
            """;
    // SAH-34: a scoped nurse reads her own entries and those of ADMITTED admissions in her wards or under her grant.
    // The two id lists are an uncorrelated "in (select unnest(...))", not "= any(cast(:x as uuid[]))": the cast of a
    // text parameter to an array is a stable function that PostgreSQL runs again for every row of the log (about 225 ms
    // at 36,000 events, the unexplained 200 ms of docs/database/SAH-34.md section 10), while the subquery is read once
    // (about 9 ms in the same probe). Same rows; an empty list is an empty subquery.
    static final String SCOPED_NURSE = """
             and (e.actor = :nurse or (a.status = 'ADMITTED'
                  and (b.ward_id in (select unnest(cast(:wards as uuid[])))
                       or a.id in (select unnest(cast(:grants as uuid[]))))))
            """;
    // SAH-34 (F-2 part 1, design note 1.7): with no ward and no live grant the OR is its first branch alone (any of an
    // empty array is false for every row), so the statement drops it and the planner reads only her own entries.
    static final String ACTOR_ONLY = " and e.actor = :nurse\n";
    static final String NURSE_SCOPE = " and (e.actor = :nurse or a.status = 'ADMITTED')\n";
    static final String AFTER = " and (e.at, e.seq) < (:afterAt, :afterSeq)\n";
    static final String PAGE_TAIL = " order by e.at desc, e.seq desc limit :limit";

    private final JdbcClient jdbc;

    public JdbcClinicalEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID tenantId, UUID admissionId, String kind, String text, UUID actor) {
        jdbc.sql(INSERT).param("admissionId", admissionId).param("kind", kind).param("text", text)
                .param("actor", actor).update();
    }

    @Override
    public void insertForGrant(UUID tenantId, UUID admissionId, String text, UUID actor, UUID grantId) {
        jdbc.sql(INSERT_FOR_GRANT).param("admissionId", admissionId).param("text", text).param("actor", actor)
                .param("grantId", grantId).update();
    }

    @Override
    public List<EventRow> findPageInScope(UUID tenantId, UUID nurse, Set<UUID> wardIds, Set<UUID> grantAdmissionIds,
                                          Position after, int limit) {
        boolean actorOnly = wardIds.isEmpty() && grantAdmissionIds.isEmpty();
        String sql = pageInScopeSql(actorOnly, after != null);
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit)
                .param("nurse", nurse);
        if (!actorOnly) {
            spec = spec.param("wards", JdbcMedicineRoundRepository.arrayLiteral(wardIds))
                    .param("grants", JdbcMedicineRoundRepository.arrayLiteral(grantAdmissionIds));
        }
        if (after != null) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("afterSeq", after.seq());
        }
        return spec.query(JdbcClinicalEventRepository::row).list();
    }

    /** The scoped-nurse page statement: fixed fragments only; {@code actorOnly} when she has no ward and no grant. */
    static String pageInScopeSql(boolean actorOnly, boolean keyset) {
        return PAGE_HEAD + (actorOnly ? ACTOR_ONLY : SCOPED_NURSE) + (keyset ? AFTER : "") + PAGE_TAIL;
    }

    @Override
    public List<EventRow> findPage(UUID tenantId, UUID onlyForNurse, Position after, int limit) {
        String sql = PAGE_HEAD + (onlyForNurse == null ? "" : NURSE_SCOPE) + (after == null ? "" : AFTER)
                + PAGE_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit);
        if (onlyForNurse != null) {
            spec = spec.param("nurse", onlyForNurse);
        }
        if (after != null) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("afterSeq", after.seq());
        }
        return spec.query(JdbcClinicalEventRepository::row).list();
    }

    private static EventRow row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EventRow(rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getString("kind"),
                rs.getString("text"), rs.getObject("at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("bed_code"),
                rs.getObject("grant_user_id", UUID.class));
    }
}
