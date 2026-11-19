package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ReasonRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reason lists (SAH-78-10). {@code pharmacy.reason_default} is global reference data, read through the unit's switch
 * ({@code pharmacy.reason_default_state}, tenant scoped); nothing here changes it. Row level security scopes the tenant
 * tables and each statement names the tenant as well. No delete: a reason is switched off by {@code active}.
 */
@Repository
public class JdbcReasonRepository implements ReasonRepository {

    private static final String SEEDED = """
            select d.reason_key, d.list_key, d.label, d.sort, coalesce(s.active, true) as active
              from pharmacy.reason_default d
              left join pharmacy.reason_default_state s on s.tenant_id = :tenantId and s.reason_key = d.reason_key
             order by d.list_key, d.sort
            """;
    private static final String OWN = """
            select id, list_key, reason_key, label, active, sort from pharmacy.reason
             where tenant_id = :tenantId order by list_key, sort, created_at, id
            """;
    private static final String NEXT_SORT = """
            select coalesce(max(sort), 0) + 1 from pharmacy.reason where tenant_id = :tenantId and list_key = :listKey
            """;
    private static final String INSERT_OWN = """
            insert into pharmacy.reason (tenant_id, unit_id, list_key, reason_key, label, sort, created_by)
            values (:tenantId, :unitId, :listKey, :reasonKey, :label, :sort, :actor)
            returning id
            """;
    private static final String SET_OWN_ACTIVE = """
            update pharmacy.reason
               set active = :active, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id
            """;
    private static final String SET_SEEDED_ACTIVE = """
            insert into pharmacy.reason_default_state as s (tenant_id, unit_id, reason_key, active, created_by)
            values (:tenantId, :unitId, :reasonKey, :active, :actor)
            on conflict (tenant_id, unit_id, reason_key) do update
               set active = :active, updated_at = now(), updated_by = :actor, version = s.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcReasonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SeededRow> seeded(UUID tenantId) {
        return jdbc.sql(SEEDED).param("tenantId", tenantId)
                .query((rs, n) -> new SeededRow(rs.getString("reason_key"), rs.getString("list_key"),
                        rs.getString("label"), rs.getInt("sort"), rs.getBoolean("active")))
                .list();
    }

    @Override
    public List<OwnRow> own(UUID tenantId) {
        return jdbc.sql(OWN).param("tenantId", tenantId)
                .query((rs, n) -> new OwnRow(rs.getObject("id", UUID.class), rs.getString("list_key"),
                        rs.getString("reason_key"), rs.getString("label"), rs.getBoolean("active"), rs.getInt("sort")))
                .list();
    }

    @Override
    public int nextOwnSort(UUID tenantId, String listKey) {
        return jdbc.sql(NEXT_SORT).param("tenantId", tenantId).param("listKey", listKey).query(Integer.class).single();
    }

    @Override
    public UUID insertOwn(UUID tenantId, UUID unitId, String listKey, String reasonKey, String label, int sort,
                          UUID actorUserId) {
        return jdbc.sql(INSERT_OWN).param("tenantId", tenantId).param("unitId", unitId).param("listKey", listKey)
                .param("reasonKey", reasonKey).param("label", label).param("sort", sort).param("actor", actorUserId)
                .query(UUID.class).single();
    }

    @Override
    public void setOwnActive(UUID tenantId, UUID id, boolean active, UUID actorUserId) {
        jdbc.sql(SET_OWN_ACTIVE).param("tenantId", tenantId).param("id", id).param("active", active)
                .param("actor", actorUserId).update();
    }

    @Override
    public void setSeededActive(UUID tenantId, UUID unitId, String reasonKey, boolean active, UUID actorUserId) {
        jdbc.sql(SET_SEEDED_ACTIVE).param("tenantId", tenantId).param("unitId", unitId).param("reasonKey", reasonKey)
                .param("active", active).param("actor", actorUserId).update();
    }
}
