package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StoreRepository;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code pharmacy.store}. Row level security scopes every statement to the bound tenant. No delete: a store is switched
 * off by status. The database also holds the rules these statements rely on (one active Central Store, name and code
 * unique per unit ignoring case, type and code locked once the store has stock).
 */
@Repository
public class JdbcStoreRepository implements StoreRepository {

    static final String COLUMNS = "id, unit_id, name, code, type, issues_from_store_id, sale_allowed, "
            + "consumption_allowed, has_stock, status, version";

    private static final String FIND = "select " + COLUMNS + " from pharmacy.store where tenant_id = :tenantId and id = :id";
    private static final String LIST = "select " + COLUMNS + """
             from pharmacy.store
            where tenant_id = :tenantId and (:includeInactive or status = 'ACTIVE')
            order by (type <> 'CENTRAL'), lower(name), id
            """;
    private static final String ACTIVE_CENTRAL = "select " + COLUMNS + """
             from pharmacy.store
            where tenant_id = :tenantId and type = 'CENTRAL' and status = 'ACTIVE'
            """;
    private static final String NAME_IN_USE = """
            select exists (select 1 from pharmacy.store
                           where tenant_id = :tenantId and lower(name) = lower(:name)
                             and (cast(:except as uuid) is null or id <> cast(:except as uuid)))
            """;
    private static final String CODE_IN_USE = """
            select exists (select 1 from pharmacy.store
                           where tenant_id = :tenantId and lower(code) = lower(:code)
                             and (cast(:except as uuid) is null or id <> cast(:except as uuid)))
            """;
    private static final String COUNT_ACTIVE_EXCEPT = """
            select count(*) from pharmacy.store
            where tenant_id = :tenantId and status = 'ACTIVE' and id <> :except
            """;
    private static final String HAS_OPEN_IMPORT = """
            select exists (select 1 from pharmacy.opening_stock_import
                           where tenant_id = :tenantId and store_id = :id and status in ('DRAFT', 'WAITING_APPROVAL'))
            """;
    private static final String INSERT = """
            insert into pharmacy.store (tenant_id, unit_id, name, code, type, issues_from_store_id, sale_allowed,
                                        consumption_allowed, created_by)
            values (:tenantId, :unitId, :name, :code, :type, cast(:issuesFrom as uuid), :sale, :consumption, :actor)
            returning id
            """;
    private static final String UPDATE = """
            update pharmacy.store
            set name = :name, code = :code, type = :type, issues_from_store_id = cast(:issuesFrom as uuid),
                ipd_ward_id = case when :type = 'WARD' then ipd_ward_id else null end,
                sale_allowed = :sale, consumption_allowed = :consumption, updated_at = now(), updated_by = :actor,
                version = version + 1
            where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String LINKED_WARDS = """
            select id, ipd_ward_id from pharmacy.store where tenant_id = :tenantId and ipd_ward_id is not null
            """;
    private static final String SET_LINKED_WARD = """
            update pharmacy.store
            set ipd_ward_id = cast(:ward as uuid), updated_at = now(), updated_by = :actor, version = version + 1
            where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SWITCH_OFF = """
            update pharmacy.store
            set status = 'INACTIVE', updated_at = now(), updated_by = :actor, version = version + 1
            where tenant_id = :tenantId and id = :id
            """;

    private static final String LOCK_UNIT_SHARED = "select id from pharmacy.unit where tenant_id = :tenantId for share";

    private static final String MARK_HAS_STOCK = """
            update pharmacy.store
            set has_stock = true, updated_at = now(), updated_by = :actor, version = version + 1
            where tenant_id = :tenantId and id = :id and not has_stock
            """;

    private final JdbcClient jdbc;

    public JdbcStoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Store> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcStoreRepository::map).optional();
    }

    @Override
    public Optional<Store> findForUpdate(UUID tenantId, UUID id) {
        // D-2: one lock order for the clinic, unit row first. Every store write inserts a row that points at the unit (a key-share
        // lock), and every master write holds the unit FOR UPDATE before it takes a store: so the unit is taken here, shared, before
        // the store row. A caller that already holds the unit FOR UPDATE is not delayed.
        jdbc.sql(LOCK_UNIT_SHARED).param("tenantId", tenantId).query(UUID.class).optional();
        return jdbc.sql(FIND + " for update").param("tenantId", tenantId).param("id", id)
                .query(JdbcStoreRepository::map).optional();
    }

    @Override
    public List<Store> list(UUID tenantId, boolean includeInactive) {
        return jdbc.sql(LIST).param("tenantId", tenantId).param("includeInactive", includeInactive)
                .query(JdbcStoreRepository::map).list();
    }

    @Override
    public Optional<Store> activeCentral(UUID tenantId) {
        return jdbc.sql(ACTIVE_CENTRAL).param("tenantId", tenantId).query(JdbcStoreRepository::map).optional();
    }

    @Override
    public boolean nameInUse(UUID tenantId, String name, UUID exceptStoreId) {
        return Boolean.TRUE.equals(jdbc.sql(NAME_IN_USE).param("tenantId", tenantId).param("name", name)
                .param("except", exceptStoreId).query(Boolean.class).single());
    }

    @Override
    public boolean codeInUse(UUID tenantId, String code, UUID exceptStoreId) {
        return Boolean.TRUE.equals(jdbc.sql(CODE_IN_USE).param("tenantId", tenantId).param("code", code)
                .param("except", exceptStoreId).query(Boolean.class).single());
    }

    @Override
    public int countActiveExcept(UUID tenantId, UUID exceptStoreId) {
        return jdbc.sql(COUNT_ACTIVE_EXCEPT).param("tenantId", tenantId).param("except", exceptStoreId)
                .query(Integer.class).single();
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, String name, String code, StoreType type, UUID issuesFromStoreId,
                       boolean saleAllowed, boolean consumptionAllowed, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("name", name)
                .param("code", code).param("type", type.name()).param("issuesFrom", issuesFromStoreId)
                .param("sale", saleAllowed).param("consumption", consumptionAllowed).param("actor", actorUserId)
                .query(UUID.class).single();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, String name, String code, StoreType type,
                          UUID issuesFromStoreId, boolean saleAllowed, boolean consumptionAllowed, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("name", name).param("code", code).param("type", type.name())
                .param("issuesFrom", issuesFromStoreId).param("sale", saleAllowed)
                .param("consumption", consumptionAllowed).param("actor", actorUserId).update() == 1;
    }

    @Override
    public java.util.Map<UUID, UUID> linkedWards(UUID tenantId) {
        java.util.Map<UUID, UUID> out = new java.util.HashMap<>();
        jdbc.sql(LINKED_WARDS).param("tenantId", tenantId).query((rs, n) -> {
            out.put(rs.getObject("id", UUID.class), rs.getObject("ipd_ward_id", UUID.class));
            return 1;
        }).list();
        return out;
    }

    @Override
    public boolean setLinkedWard(UUID tenantId, UUID id, long version, UUID wardId, UUID actorUserId) {
        return jdbc.sql(SET_LINKED_WARD).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("ward", wardId).param("actor", actorUserId).update() == 1;
    }

    @Override
    public void switchOff(UUID tenantId, UUID id, UUID actorUserId) {
        jdbc.sql(SWITCH_OFF).param("tenantId", tenantId).param("id", id).param("actor", actorUserId).update();
    }

    @Override
    public boolean hasOpenImport(UUID tenantId, UUID storeId) {
        return jdbc.sql(HAS_OPEN_IMPORT).param("tenantId", tenantId).param("id", storeId).query(Boolean.class).single();
    }

    @Override
    public void markHasStock(UUID tenantId, UUID id, UUID actorUserId) {
        jdbc.sql(MARK_HAS_STOCK).param("tenantId", tenantId).param("id", id).param("actor", actorUserId).update();
    }

    static Store map(ResultSet rs, int rowNum) throws SQLException {
        return new Store(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getString("name"),
                rs.getString("code"), StoreType.valueOf(rs.getString("type")),
                rs.getObject("issues_from_store_id", UUID.class), rs.getBoolean("sale_allowed"),
                rs.getBoolean("consumption_allowed"), rs.getBoolean("has_stock"),
                "ACTIVE".equals(rs.getString("status")), rs.getLong("version"));
    }
}
