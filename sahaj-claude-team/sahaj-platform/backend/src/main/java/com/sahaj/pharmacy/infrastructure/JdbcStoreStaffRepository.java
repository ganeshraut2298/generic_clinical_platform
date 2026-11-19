package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StoreStaffRepository;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code pharmacy.store_staff}. Row level security scopes every statement to the bound tenant. No delete: a removed
 * person's row is set to REMOVED and re-activated by a re-map (one row per person and store). The database holds the
 * one-active-HOD and one-active-delegate rules and the delegate date check.
 */
@Repository
public class JdbcStoreStaffRepository implements StoreStaffRepository {

    private static final String COLUMNS = "id, store_id, membership_id, store_role, delegate_from, delegate_to, status, version";

    private static final String FIND = "select " + COLUMNS + " from pharmacy.store_staff where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_MEMBER = "select " + COLUMNS + """
             from pharmacy.store_staff
            where tenant_id = :tenantId and store_id = :storeId and membership_id = :membershipId
            """;
    private static final String LIST_FOR_STORE = "select " + COLUMNS + """
             from pharmacy.store_staff
            where tenant_id = :tenantId and store_id = :storeId and (:includeRemoved or status = 'ACTIVE')
            order by created_at, id
            """;
    private static final String ACTIVE_FOR_STORES = "select " + COLUMNS + """
             from pharmacy.store_staff
            where tenant_id = :tenantId and store_id in (:storeIds) and status = 'ACTIVE'
            """;
    private static final String ACTIVE_WITH_ROLE = "select " + COLUMNS + """
             from pharmacy.store_staff
            where tenant_id = :tenantId and store_id = :storeId and store_role = :role and status = 'ACTIVE'
            """;
    private static final String ACTIVE_MAPPED_STORES = """
            select ss.id as staff_id, ss.store_id, ss.membership_id, ss.store_role, ss.delegate_from, ss.delegate_to,
                   ss.status as staff_status, ss.version as staff_version,
                   s.id as store_pk, s.unit_id, s.name, s.code, s.type, s.issues_from_store_id, s.sale_allowed,
                   s.consumption_allowed, s.has_stock, s.status as store_status, s.version as store_version
            from pharmacy.store_staff ss
            join pharmacy.store s on s.tenant_id = ss.tenant_id and s.id = ss.store_id
            where ss.tenant_id = :tenantId and ss.membership_id in (:membershipIds)
              and ss.status = 'ACTIVE' and s.status = 'ACTIVE'
            order by s.type <> 'CENTRAL', lower(s.name), ss.id
            """;
    private static final String HAS_ACTIVE_MAPPING = """
            select exists (select 1
                           from pharmacy.store_staff ss
                           join pharmacy.store s on s.tenant_id = ss.tenant_id and s.id = ss.store_id
                           where ss.tenant_id = :tenantId and ss.membership_id = :membershipId
                             and ss.status = 'ACTIVE' and s.status = 'ACTIVE')
            """;
    private static final String MAPPED_MEMBERS = """
            select distinct ss.membership_id
            from pharmacy.store_staff ss
            join pharmacy.store s on s.tenant_id = ss.tenant_id and s.id = ss.store_id
            where ss.tenant_id = :tenantId and ss.status = 'ACTIVE' and s.status = 'ACTIVE'
            """;
    private static final String INSERT = """
            insert into pharmacy.store_staff (tenant_id, unit_id, store_id, membership_id, store_role, delegate_from,
                                              delegate_to, created_by)
            values (:tenantId, :unitId, :storeId, :membershipId, :role, cast(:delegateFrom as date),
                    cast(:delegateTo as date), :actor)
            returning id
            """;
    private static final String UPDATE = """
            update pharmacy.store_staff
            set store_role = :role, delegate_from = cast(:delegateFrom as date), delegate_to = cast(:delegateTo as date),
                status = :status, updated_at = now(), updated_by = :actor, version = version + 1
            where tenant_id = :tenantId and id = :id and version = :version
            """;

    private final JdbcClient jdbc;

    public JdbcStoreStaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoreStaff> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcStoreStaffRepository::map).optional();
    }

    @Override
    public Optional<StoreStaff> findForMember(UUID tenantId, UUID storeId, UUID membershipId) {
        return jdbc.sql(FIND_FOR_MEMBER).param("tenantId", tenantId).param("storeId", storeId)
                .param("membershipId", membershipId).query(JdbcStoreStaffRepository::map).optional();
    }

    @Override
    public List<StoreStaff> listForStore(UUID tenantId, UUID storeId, boolean includeRemoved) {
        return jdbc.sql(LIST_FOR_STORE).param("tenantId", tenantId).param("storeId", storeId)
                .param("includeRemoved", includeRemoved).query(JdbcStoreStaffRepository::map).list();
    }

    @Override
    public List<StoreStaff> activeForStores(UUID tenantId, Collection<UUID> storeIds) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ACTIVE_FOR_STORES).param("tenantId", tenantId).param("storeIds", storeIds)
                .query(JdbcStoreStaffRepository::map).list();
    }

    @Override
    public Optional<StoreStaff> activeWithRole(UUID tenantId, UUID storeId, StoreRole role) {
        return jdbc.sql(ACTIVE_WITH_ROLE).param("tenantId", tenantId).param("storeId", storeId)
                .param("role", role.name()).query(JdbcStoreStaffRepository::map).optional();
    }

    @Override
    public List<Mapped> activeMappedStores(UUID tenantId, Collection<UUID> membershipIds) {
        if (membershipIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ACTIVE_MAPPED_STORES).param("tenantId", tenantId).param("membershipIds", membershipIds)
                .query(JdbcStoreStaffRepository::mapJoined).list();
    }

    @Override
    public boolean hasActiveMapping(UUID tenantId, UUID membershipId) {
        return Boolean.TRUE.equals(jdbc.sql(HAS_ACTIVE_MAPPING).param("tenantId", tenantId)
                .param("membershipId", membershipId).query(Boolean.class).single());
    }

    @Override
    public List<UUID> mappedMembersOfActiveStores(UUID tenantId) {
        return jdbc.sql(MAPPED_MEMBERS).param("tenantId", tenantId).query(UUID.class).list();
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, UUID storeId, UUID membershipId, StoreRole role,
                       LocalDate delegateFrom, LocalDate delegateTo, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("storeId", storeId)
                .param("membershipId", membershipId).param("role", role.name()).param("delegateFrom", delegateFrom)
                .param("delegateTo", delegateTo).param("actor", actorUserId).query(UUID.class).single();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, StoreRole role, LocalDate delegateFrom,
                          LocalDate delegateTo, boolean active, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("role", role.name()).param("delegateFrom", delegateFrom).param("delegateTo", delegateTo)
                .param("status", active ? "ACTIVE" : "REMOVED").param("actor", actorUserId).update() == 1;
    }

    private static StoreStaff map(ResultSet rs, int rowNum) throws SQLException {
        return new StoreStaff(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("membership_id", UUID.class), StoreRole.valueOf(rs.getString("store_role")),
                rs.getObject("delegate_from", LocalDate.class), rs.getObject("delegate_to", LocalDate.class),
                "ACTIVE".equals(rs.getString("status")), rs.getLong("version"));
    }

    private static Mapped mapJoined(ResultSet rs, int rowNum) throws SQLException {
        StoreStaff staff = new StoreStaff(rs.getObject("staff_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("membership_id", UUID.class), StoreRole.valueOf(rs.getString("store_role")),
                rs.getObject("delegate_from", LocalDate.class), rs.getObject("delegate_to", LocalDate.class),
                "ACTIVE".equals(rs.getString("staff_status")), rs.getLong("staff_version"));
        Store store = new Store(rs.getObject("store_pk", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getString("name"), rs.getString("code"), StoreType.valueOf(rs.getString("type")),
                rs.getObject("issues_from_store_id", UUID.class), rs.getBoolean("sale_allowed"),
                rs.getBoolean("consumption_allowed"), rs.getBoolean("has_stock"),
                "ACTIVE".equals(rs.getString("store_status")), rs.getLong("store_version"));
        return new Mapped(staff, store);
    }
}
