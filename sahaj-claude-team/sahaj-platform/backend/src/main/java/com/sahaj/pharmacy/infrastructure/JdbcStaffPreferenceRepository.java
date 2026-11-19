package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StaffPreferenceRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code pharmacy.staff_preference}: one row per member, updated in place, never deleted. Not audited. */
@Repository
public class JdbcStaffPreferenceRepository implements StaffPreferenceRepository {

    private static final String FIND = """
            select last_store_id from pharmacy.staff_preference
            where tenant_id = :tenantId and membership_id = :membershipId
            """;
    private static final String UPSERT = """
            insert into pharmacy.staff_preference (tenant_id, unit_id, membership_id, last_store_id, created_by)
            values (:tenantId, :unitId, :membershipId, :storeId, :actor)
            on conflict (tenant_id, membership_id) do update
            set last_store_id = excluded.last_store_id, updated_at = now(), updated_by = excluded.created_by,
                version = pharmacy.staff_preference.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcStaffPreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> lastStore(UUID tenantId, UUID membershipId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("membershipId", membershipId).query(UUID.class)
                .optional();
    }

    @Override
    public void save(UUID tenantId, UUID unitId, UUID membershipId, UUID storeId, UUID actorUserId) {
        jdbc.sql(UPSERT).param("tenantId", tenantId).param("unitId", unitId).param("membershipId", membershipId)
                .param("storeId", storeId).param("actor", actorUserId).update();
    }
}
