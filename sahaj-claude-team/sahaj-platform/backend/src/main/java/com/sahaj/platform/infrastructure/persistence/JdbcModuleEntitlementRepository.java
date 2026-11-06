package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.ModuleEntitlementRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.module_entitlement}, one row per clinic, created on the first write (SAH-19). No delete statement. */
@Repository
public class JdbcModuleEntitlementRepository implements ModuleEntitlementRepository {

    private static final String FIND = """
            select ipd_state, ipd_trial_ends_at, hra_on, bed_limit, ipd_requested_at
            from platform.module_entitlement
            where tenant_id = :tenantId
            """;
    private static final String INSERT_DEFAULTS = """
            insert into platform.module_entitlement
                (tenant_id, ipd_state, ipd_trial_ends_at, hra_on, bed_limit, ipd_requested_at, updated_at, updated_by)
            values
                (:tenantId, :ipdState, :trialEndsAt, :hraOn, :bedLimit, :requestedAt, now(), :actor)
            on conflict (tenant_id) do nothing
            """;
    private static final String LOCK =
            "select tenant_id from platform.module_entitlement where tenant_id = :tenantId for update";
    private static final String UPSERT = """
            insert into platform.module_entitlement
                (tenant_id, ipd_state, ipd_trial_ends_at, hra_on, bed_limit, ipd_requested_at, updated_at, updated_by)
            values
                (:tenantId, :ipdState, :trialEndsAt, :hraOn, :bedLimit, :requestedAt, now(), :actor)
            on conflict (tenant_id) do update set
                ipd_state = excluded.ipd_state,
                ipd_trial_ends_at = excluded.ipd_trial_ends_at,
                hra_on = excluded.hra_on,
                bed_limit = excluded.bed_limit,
                ipd_requested_at = excluded.ipd_requested_at,
                updated_at = now(),
                updated_by = excluded.updated_by,
                version = platform.module_entitlement.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcModuleEntitlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EntitlementRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(JdbcModuleEntitlementRepository::map).optional();
    }

    @Override
    public void lockForWrite(UUID tenantId, EntitlementRow defaults, UUID actorUserId) {
        jdbc.sql(INSERT_DEFAULTS)
                .param("tenantId", tenantId)
                .param("ipdState", defaults.ipdState())
                .param("trialEndsAt", defaults.ipdTrialEndsAt())
                .param("hraOn", defaults.hraOn())
                .param("bedLimit", defaults.bedLimit())
                .param("requestedAt", defaults.ipdRequestedAt())
                .param("actor", actorUserId)
                .update();
        jdbc.sql(LOCK).param("tenantId", tenantId).query(UUID.class).single();
    }

    @Override
    public void upsert(UUID tenantId, EntitlementRow row, UUID actorUserId) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("ipdState", row.ipdState())
                .param("trialEndsAt", row.ipdTrialEndsAt())
                .param("hraOn", row.hraOn())
                .param("bedLimit", row.bedLimit())
                .param("requestedAt", row.ipdRequestedAt())
                .param("actor", actorUserId)
                .update();
    }


    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static EntitlementRow map(ResultSet rs, int rowNum) throws SQLException {
        return new EntitlementRow(rs.getString("ipd_state"), instant(rs, "ipd_trial_ends_at"),
                rs.getBoolean("hra_on"), rs.getInt("bed_limit"), instant(rs, "ipd_requested_at"));
    }
}
