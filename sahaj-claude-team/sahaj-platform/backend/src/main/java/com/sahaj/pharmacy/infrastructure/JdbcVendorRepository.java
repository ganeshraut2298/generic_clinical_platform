package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.PharmacyMasterViews.StoreName;
import com.sahaj.pharmacy.application.VendorRepository;
import com.sahaj.pharmacy.domain.Vendor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code pharmacy.vendor} and {@code pharmacy.vendor_store} (SAH-78-8). Row level security scopes every statement to the bound
 * tenant and each statement names the tenant as well. No delete: a vendor is switched off by status and a store taken off a
 * vendor keeps its row with {@code active = false}. Values are always bind parameters. The vendor's contact phone and licence
 * number are never logged.
 */
@Repository
public class JdbcVendorRepository implements VendorRepository {

    private static final String COLUMNS = "v.id, v.name, v.gstin, v.licence_required, v.licence_no, v.licence_valid_till, "
            + "v.contact_name, v.contact_phone, v.credit_days, v.return_days_before_expiry, v.return_credit_pct, "
            + "v.damaged_credit_pct, v.expired_credit_pct, v.status, v.version";

    private static final String WHERE = """
             where v.tenant_id = :tenantId
               and (:like = '' or lower(v.name) like :like)
               and (case cast(:filter as text)
                      when 'ALL' then true
                      when 'INACTIVE' then v.status = 'INACTIVE'
                      when 'BLOCKED' then v.status = 'ACTIVE' and v.licence_required
                                          and v.licence_valid_till < cast(:today as date)
                      when 'ACTIVE' then v.status = 'ACTIVE' and not (v.licence_required
                                          and v.licence_valid_till < cast(:today as date))
                      else v.status = 'ACTIVE' end)
            """;
    private static final String LIST = "select " + COLUMNS + """
            , (select count(*) from pharmacy.product p
                where p.tenant_id = v.tenant_id and p.preferred_vendor_id = v.id and p.status = 'ACTIVE') as products_count
              from pharmacy.vendor v
            """ + WHERE + " order by lower(v.name), v.id limit :limit offset :offset";
    private static final String LIST_COUNT = "select count(*) from pharmacy.vendor v" + WHERE;
    private static final String SUPPLIES_FILTER = """
               and exists (select 1 from pharmacy.vendor_store vs
                            join pharmacy.store st on st.tenant_id = vs.tenant_id and st.id = vs.store_id
                           where vs.tenant_id = v.tenant_id and vs.vendor_id = v.id and vs.store_id = :storeId and vs.active
                             and st.status = 'ACTIVE')
            """;
    private static final String LIST_SUPPLYING = "select " + COLUMNS + """
            , (select count(*) from pharmacy.product p
                where p.tenant_id = v.tenant_id and p.preferred_vendor_id = v.id and p.status = 'ACTIVE') as products_count
              from pharmacy.vendor v
            """ + WHERE + SUPPLIES_FILTER + " order by lower(v.name), v.id limit :limit offset :offset";
    private static final String COUNT_SUPPLYING = "select count(*) from pharmacy.vendor v" + WHERE + SUPPLIES_FILTER;
    private static final String FIND = "select " + COLUMNS + " from pharmacy.vendor v where v.tenant_id = :tenantId and v.id = :id";
    private static final String PRODUCTS_COUNT = """
            select count(*) from pharmacy.product
             where tenant_id = :tenantId and preferred_vendor_id = :id and status = 'ACTIVE'
            """;
    private static final String SUPPLIES_TO = """
            select vs.vendor_id, s.id as store_id, s.name
              from pharmacy.vendor_store vs
              join pharmacy.store s on s.tenant_id = vs.tenant_id and s.id = vs.store_id
             where vs.tenant_id = :tenantId and vs.active and s.status = 'ACTIVE' and vs.vendor_id in (:ids)
             order by (s.type <> 'CENTRAL'), lower(s.name), s.id
            """;
    private static final String BY_CLIENT_KEY = """
            select id, created_by from pharmacy.vendor where tenant_id = :tenantId and client_key = :clientKey
            """;
    private static final String NAME_IN_USE = """
            select exists (select 1 from pharmacy.vendor
                           where tenant_id = :tenantId and lower(name) = lower(:name)
                             and (cast(:except as uuid) is null or id <> cast(:except as uuid)))
            """;
    private static final String INSERT = """
            insert into pharmacy.vendor (tenant_id, unit_id, name, gstin, licence_required, licence_no, licence_valid_till,
                                         contact_name, contact_phone, credit_days, return_days_before_expiry,
                                         return_credit_pct, damaged_credit_pct, expired_credit_pct, client_key,
                                         created_by)
            values (:tenantId, :unitId, :name, cast(:gstin as text), :licenceRequired, cast(:licenceNo as text),
                    cast(:licenceTill as date), cast(:contactName as text), cast(:contactPhone as text), :creditDays,
                    cast(:returnDays as integer), cast(:returnPct as numeric), cast(:damagedPct as numeric),
                    cast(:expiredPct as numeric), cast(:clientKey as uuid), :actor)
            returning id
            """;
    private static final String UPDATE = """
            update pharmacy.vendor
               set name = :name, gstin = cast(:gstin as text), licence_required = :licenceRequired,
                   licence_no = cast(:licenceNo as text), licence_valid_till = cast(:licenceTill as date),
                   contact_name = cast(:contactName as text), contact_phone = cast(:contactPhone as text),
                   credit_days = :creditDays, return_days_before_expiry = cast(:returnDays as integer),
                   return_credit_pct = cast(:returnPct as numeric), damaged_credit_pct = cast(:damagedPct as numeric),
                   expired_credit_pct = cast(:expiredPct as numeric), updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SWITCH_OFF = """
            update pharmacy.vendor
               set status = 'INACTIVE', updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id
            """;
    private static final String SWITCH_ON = """
            update pharmacy.vendor
               set status = 'ACTIVE', updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id
            """;
    private static final String UPSERT_STORE = """
            insert into pharmacy.vendor_store as vs (tenant_id, unit_id, vendor_id, store_id, created_by)
            values (:tenantId, :unitId, :vendorId, :storeId, :actor)
            on conflict (tenant_id, vendor_id, store_id) do update
               set active = true, updated_at = now(), updated_by = :actor, version = vs.version + 1
             where vs.active = false
            """;
    private static final String DEACTIVATE_STORES = """
            update pharmacy.vendor_store
               set active = false, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and vendor_id = :vendorId and active and store_id not in (:keep)
            """;

    private final JdbcClient jdbc;

    public JdbcVendorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Slice list(UUID tenantId, String like, Filter filter, LocalDate today, int limit, int offset) {
        List<Row> items = jdbc.sql(LIST).param("tenantId", tenantId).param("like", like).param("filter", filter.name())
                .param("today", today).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Row(read(rs), rs.getInt("products_count"))).list();
        long total = jdbc.sql(LIST_COUNT).param("tenantId", tenantId).param("like", like).param("filter", filter.name())
                .param("today", today).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public Slice listSupplying(UUID tenantId, String like, Filter filter, LocalDate today, UUID storeId, int limit, int offset) {
        List<Row> items = jdbc.sql(LIST_SUPPLYING).param("tenantId", tenantId).param("like", like).param("filter", filter.name())
                .param("today", today).param("storeId", storeId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Row(read(rs), rs.getInt("products_count"))).list();
        long total = jdbc.sql(COUNT_SUPPLYING).param("tenantId", tenantId).param("like", like).param("filter", filter.name())
                .param("today", today).param("storeId", storeId).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public Optional<Vendor> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcVendorRepository::map).optional();
    }

    @Override
    public int productsCount(UUID tenantId, UUID vendorId) {
        return jdbc.sql(PRODUCTS_COUNT).param("tenantId", tenantId).param("id", vendorId).query(Integer.class).single();
    }

    @Override
    public Map<UUID, List<StoreName>> suppliesTo(UUID tenantId, Collection<UUID> vendorIds) {
        Map<UUID, List<StoreName>> found = new LinkedHashMap<>();
        if (vendorIds.isEmpty()) {
            return found;
        }
        jdbc.sql(SUPPLIES_TO).param("tenantId", tenantId).param("ids", vendorIds).query((rs, n) -> {
            found.computeIfAbsent(rs.getObject("vendor_id", UUID.class), k -> new ArrayList<>())
                    .add(new StoreName(rs.getObject("store_id", UUID.class), rs.getString("name")));
            return null;
        }).list();
        return found;
    }

    @Override
    public Optional<ClientKeyHit> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query((rs, n) -> new ClientKeyHit(rs.getObject("id", UUID.class), rs.getObject("created_by", UUID.class)))
                .optional();
    }

    @Override
    public boolean nameInUse(UUID tenantId, String name, UUID exceptVendorId) {
        return jdbc.sql(NAME_IN_USE).param("tenantId", tenantId).param("name", name).param("except", exceptVendorId)
                .query(Boolean.class).single();
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, UUID clientKey, Fields f, UUID actorUserId) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("name", f.name())
                .param("gstin", f.gstin()).param("licenceRequired", f.licenceRequired()).param("licenceNo", f.licenceNo())
                .param("licenceTill", f.licenceValidTill()).param("contactName", f.contactName())
                .param("contactPhone", f.contactPhone()).param("creditDays", f.creditDays())
                .param("returnDays", f.returnDaysBeforeExpiry()).param("returnPct", f.returnCreditPercent())
                .param("damagedPct", f.damagedCreditPercent()).param("expiredPct", f.expiredCreditPercent())
                .param("clientKey", clientKey).param("actor", actorUserId).query(UUID.class).single();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, Fields f, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("name", f.name()).param("gstin", f.gstin()).param("licenceRequired", f.licenceRequired())
                .param("licenceNo", f.licenceNo()).param("licenceTill", f.licenceValidTill())
                .param("contactName", f.contactName()).param("contactPhone", f.contactPhone())
                .param("creditDays", f.creditDays()).param("returnDays", f.returnDaysBeforeExpiry())
                .param("returnPct", f.returnCreditPercent()).param("damagedPct", f.damagedCreditPercent())
                .param("expiredPct", f.expiredCreditPercent()).param("actor", actorUserId).update() == 1;
    }

    @Override
    public void switchOff(UUID tenantId, UUID id, UUID actorUserId) {
        jdbc.sql(SWITCH_OFF).param("tenantId", tenantId).param("id", id).param("actor", actorUserId).update();
    }

    @Override
    public void switchOn(UUID tenantId, UUID id, UUID actorUserId) {
        jdbc.sql(SWITCH_ON).param("tenantId", tenantId).param("id", id).param("actor", actorUserId).update();
    }

    @Override
    public void replaceStores(UUID tenantId, UUID unitId, UUID vendorId, Collection<UUID> storeIds, UUID actorUserId) {
        for (UUID storeId : storeIds) {
            jdbc.sql(UPSERT_STORE).param("tenantId", tenantId).param("unitId", unitId).param("vendorId", vendorId)
                    .param("storeId", storeId).param("actor", actorUserId).update();
        }
        if (!storeIds.isEmpty()) {
            jdbc.sql(DEACTIVATE_STORES).param("tenantId", tenantId).param("vendorId", vendorId)
                    .param("keep", storeIds).param("actor", actorUserId).update();
        }
    }

    private static Vendor map(ResultSet rs, int rowNum) throws SQLException {
        return read(rs);
    }

    private static Vendor read(ResultSet rs) throws SQLException {
        int returnDays = rs.getInt("return_days_before_expiry");
        Integer returnDaysValue = rs.wasNull() ? null : returnDays;
        BigDecimal pct = rs.getBigDecimal("return_credit_pct");
        BigDecimal damaged = rs.getBigDecimal("damaged_credit_pct");
        BigDecimal expired = rs.getBigDecimal("expired_credit_pct");
        return new Vendor(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("gstin"),
                rs.getBoolean("licence_required"), rs.getString("licence_no"),
                rs.getObject("licence_valid_till", LocalDate.class), rs.getString("contact_name"),
                rs.getString("contact_phone"), rs.getInt("credit_days"), returnDaysValue, pct,
                damaged, expired, "ACTIVE".equals(rs.getString("status")), rs.getLong("version"));
    }
}
