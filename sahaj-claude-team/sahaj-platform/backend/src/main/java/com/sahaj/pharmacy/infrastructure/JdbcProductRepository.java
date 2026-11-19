package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.PharmacyViews.DuplicateCandidate;
import com.sahaj.pharmacy.application.ProductRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code pharmacy.product}, {@code pharmacy.product_store_setting} and the global catalogue. Row level security scopes
 * every tenant statement to the bound tenant; statements whose name starts with {@code GLOBAL_} read the catalogue only
 * (no tenant column, select-only for the application role). No delete: a product is switched off by status. Search text is
 * always a bind parameter (lower case, with {@code %} and {@code _} already escaped by the service), never part of the SQL.
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    /** The effective fields of a unit product: the catalogue's for an adopted one, its own for an own one. */
    private static final String PRODUCT_COLUMNS = """
            p.id as product_id, p.kind, p.status, p.catalogue_product_id,
            coalesce(g.code, p.code) as code, coalesce(g.name, p.name) as name,
            coalesce(g.manufacturer, p.manufacturer) as manufacturer, p.made_in_house,
            coalesce(g.salt, p.salt) as salt, coalesce(g.strength, p.strength) as strength,
            coalesce(g.form, p.form) as form, coalesce(g.purchase_unit, p.purchase_unit) as purchase_unit,
            coalesce(g.issue_unit, p.issue_unit) as issue_unit, coalesce(g.conversion, p.conversion) as conversion,
            coalesce(g.hsn, p.hsn) as hsn, coalesce(g.gst_rate, p.gst_rate) as gst_rate,
            coalesce(g.schedule, p.schedule) as schedule, coalesce(g.ndps, p.ndps) as ndps,
            coalesce(g.sample, false) as sample, p.alias, p.barcode, p.returnable, p.preferred_vendor_id, p.has_stock,
            p.version, p.created_by,
            case when p.kind = 'OWN' then (case when p.status = 'ACTIVE' then 'ACTIVE' else 'INACTIVE' end)
                 when not g.active then 'WITHDRAWN' when p.status = 'INACTIVE' then 'INACTIVE' else 'ADOPTED' end as in_unit
            """;
    private static final String PRODUCT_FROM = """
             from pharmacy.product p
             left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
            """;
    private static final String FIND = "select " + PRODUCT_COLUMNS + PRODUCT_FROM
            + " where p.tenant_id = :tenantId and p.id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update of p";
    private static final String FIND_ADOPTED = "select " + PRODUCT_COLUMNS + PRODUCT_FROM
            + " where p.tenant_id = :tenantId and p.catalogue_product_id = :catalogueId";
    private static final String GLOBAL_CATALOGUE = "select id, code, active from pharmacy.catalogue_product where id = :id";
    private static final String GLOBAL_BARCODES = """
            select barcode from pharmacy.catalogue_barcode where catalogue_product_id = :id order by barcode
            """;
    private static final String FIND_BY_CLIENT_KEY = """
            select id, created_by from pharmacy.product where tenant_id = :tenantId and client_key = :clientKey
            """;

    /** One list of global rows (with this unit's adoption) and own rows. Empty {@code :like}, {@code :exact}, {@code :source} mean no filter. */
    private static final String SEARCH_ROWS = """
            select 'GLOBAL' as source, g.id as catalogue_product_id, p.id as product_id, g.code, g.name, g.manufacturer,
                   false as made_in_house, g.salt, g.strength, g.form, g.purchase_unit, g.issue_unit, g.conversion, g.hsn,
                   g.gst_rate, g.schedule, g.ndps,
                   case when p.id is null then 'NOT_ADOPTED' when not g.active then 'WITHDRAWN'
                        when p.status = 'INACTIVE' then 'INACTIVE' else 'ADOPTED' end as in_unit,
                   g.sample, p.alias,
                   case when :exact <> '' and (lower(g.code) = :exact or lower(p.barcode) = :exact
                        or exists (select 1 from pharmacy.catalogue_barcode b
                                   where b.catalogue_product_id = g.id and lower(b.barcode) = :exact)) then 0 else 1 end as match_rank
              from pharmacy.catalogue_product g
              left join pharmacy.product p on p.tenant_id = :tenantId and p.catalogue_product_id = g.id
             where (:source = '' or :source = 'GLOBAL') and (g.active or p.id is not null)
               and (:like = '' or lower(g.name) like :like or lower(g.salt) like :like or lower(g.code) like :like
                    or lower(p.alias) like :like or lower(p.barcode) = :exact
                    or exists (select 1 from pharmacy.catalogue_barcode b
                               where b.catalogue_product_id = g.id and lower(b.barcode) = :exact))
            union all
            select 'OWN', cast(null as uuid), p.id, p.code, p.name, p.manufacturer, p.made_in_house, p.salt, p.strength,
                   p.form, p.purchase_unit, p.issue_unit, p.conversion, p.hsn, p.gst_rate, p.schedule, p.ndps,
                   case when p.status = 'ACTIVE' then 'ACTIVE' else 'INACTIVE' end, false, p.alias,
                   case when :exact <> '' and (lower(p.code) = :exact or lower(p.barcode) = :exact) then 0 else 1 end
              from pharmacy.product p
             where p.tenant_id = :tenantId and p.kind = 'OWN' and (:source = '' or :source = 'OWN')
               and (:like = '' or lower(p.name) like :like or lower(p.salt) like :like or lower(p.alias) like :like
                    or lower(p.code) like :like or lower(p.barcode) = :exact)
            """;
    private static final String SEARCH = "select * from (" + SEARCH_ROWS
            + ") r order by r.match_rank, lower(r.name), r.code limit :limit offset :offset";
    private static final String SEARCH_COUNT = "select count(*) from (" + SEARCH_ROWS + ") r";

    private static final String DUPLICATES_IN_CATALOGUE = """
            select 'GLOBAL' as source, g.id as catalogue_product_id, p.id as product_id, g.code, g.name, g.manufacturer,
                   false as made_in_house, case when p.id is null then 'NOT_ADOPTED' else 'ADOPTED' end as in_unit
              from pharmacy.catalogue_product g
              left join pharmacy.product p on p.tenant_id = :tenantId and p.catalogue_product_id = g.id
             where g.active and g.dup_key = :dupKey
             order by g.code limit :limit
            """;
    private static final String DUPLICATES_OWN = """
            select 'OWN' as source, cast(null as uuid) as catalogue_product_id, p.id as product_id, p.code, p.name,
                   p.manufacturer, p.made_in_house, 'ACTIVE' as in_unit
              from pharmacy.product p
             where p.tenant_id = :tenantId and p.kind = 'OWN' and p.status = 'ACTIVE' and p.dup_key = :dupKey
               and (cast(:except as uuid) is null or p.id <> cast(:except as uuid))
             order by p.code limit :limit
            """;
    private static final String BARCODE_OWNER = """
            select coalesce(g.name, p.name)
              from pharmacy.product p
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
             where p.tenant_id = :tenantId and p.status = 'ACTIVE' and lower(p.barcode) = lower(:barcode)
               and (cast(:except as uuid) is null or p.id <> cast(:except as uuid))
             limit 1
            """;
    private static final String STORE_SETTINGS = """
            select store_id, reorder_level, rack from pharmacy.product_store_setting
             where tenant_id = :tenantId and product_id = :productId
            """;
    private static final String VENDOR = """
            select id, name, status, licence_required, licence_valid_till from pharmacy.vendor
             where tenant_id = :tenantId and id = :id
            """;
    private static final String USED_UNITS = """
            select u from (select purchase_unit as u from pharmacy.product where tenant_id = :tenantId and kind = 'OWN'
                           union
                           select issue_unit from pharmacy.product where tenant_id = :tenantId and kind = 'OWN') x
             order by lower(u), u
            """;
    private static final String USED_FORMS = """
            select distinct form from pharmacy.product where tenant_id = :tenantId and kind = 'OWN'
             order by form
            """;
    private static final String ACTIVE_BY_CODES = """
            select p.id, coalesce(g.code, p.code) as code, coalesce(g.name, p.name) as name
              from pharmacy.product p
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
             where p.tenant_id = :tenantId and p.status = 'ACTIVE' and lower(coalesce(g.code, p.code)) in (:codes)
               for share of p
            """;

    /** Uses ix_opening_stock_line_product (tenant, product) and the import status. */
    private static final String ON_OPEN_IMPORT = """
            select exists (select 1 from pharmacy.opening_stock_line l
                             join pharmacy.opening_stock_import i on i.tenant_id = l.tenant_id and i.id = l.import_id
                            where l.tenant_id = :tenantId and l.product_id = :id
                              and i.status in ('DRAFT', 'WAITING_APPROVAL'))
            """;

    private static final String TAKE_OWN_NUMBER = """
            update pharmacy.unit
               set own_product_next_no = own_product_next_no + 1, updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :unitId
            returning own_product_next_no - 1
            """;
    private static final String INSERT_ADOPTED = """
            insert into pharmacy.product (tenant_id, unit_id, kind, catalogue_product_id, created_by)
            values (:tenantId, :unitId, 'ADOPTED', :catalogueId, :actor)
            returning id
            """;
    private static final String INSERT_OWN = """
            insert into pharmacy.product (tenant_id, unit_id, kind, code, name, salt, strength, form, manufacturer,
                                          made_in_house, hsn, gst_rate, schedule, ndps, purchase_unit, issue_unit,
                                          conversion, dup_key, client_key, barcode, created_by)
            values (:tenantId, :unitId, 'OWN', :code, :name, :salt, :strength, :form, cast(:manufacturer as text),
                    :madeInHouse, :hsn, :gstRate, :schedule, false, :purchaseUnit, :issueUnit, :conversion, :dupKey,
                    :clientKey, cast(:barcode as text), :actor)
            returning id
            """;
    private static final String UPDATE_OWN = """
            update pharmacy.product
               set name = :name, salt = :salt, strength = :strength, form = :form, manufacturer = cast(:manufacturer as text),
                   made_in_house = :madeInHouse, hsn = :hsn, gst_rate = :gstRate, schedule = :schedule,
                   purchase_unit = :purchaseUnit, issue_unit = :issueUnit, conversion = :conversion, dup_key = :dupKey,
                   barcode = cast(:barcode as text), updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and kind = 'OWN'
            """;
    private static final String UPDATE_LOCAL = """
            update pharmacy.product
               set alias = cast(:alias as text), barcode = cast(:barcode as text), returnable = :returnable,
                   preferred_vendor_id = cast(:vendorId as uuid), status = :status, updated_at = now(),
                   updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SAVE_STORE_SETTING = """
            insert into pharmacy.product_store_setting (tenant_id, unit_id, product_id, store_id, reorder_level, rack, created_by)
            values (:tenantId, :unitId, :productId, :storeId, cast(:reorder as integer), cast(:rack as text), :actor)
            on conflict (tenant_id, product_id, store_id)
            do update set reorder_level = excluded.reorder_level, rack = excluded.rack, updated_at = now(),
                          updated_by = excluded.created_by,
                          version = pharmacy.product_store_setting.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Slice<Row> search(UUID tenantId, String like, String exact, String source, int limit, int offset) {
        List<Row> items = jdbc.sql(SEARCH).param("tenantId", tenantId).param("like", like).param("exact", exact)
                .param("source", source).param("limit", limit).param("offset", offset)
                .query(JdbcProductRepository::mapSearchRow).list();
        long total = jdbc.sql(SEARCH_COUNT).param("tenantId", tenantId).param("like", like).param("exact", exact)
                .param("source", source).query(Long.class).single();
        return new Slice<>(items, total);
    }

    @Override
    public Optional<Full> findForUpdate(UUID tenantId, UUID productId) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", productId)
                .query(JdbcProductRepository::mapFull).optional();
    }

    @Override
    public Optional<Full> find(UUID tenantId, UUID productId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", productId).query(JdbcProductRepository::mapFull)
                .optional();
    }

    @Override
    public Optional<Full> findAdopted(UUID tenantId, UUID catalogueProductId) {
        return jdbc.sql(FIND_ADOPTED).param("tenantId", tenantId).param("catalogueId", catalogueProductId)
                .query(JdbcProductRepository::mapFull).optional();
    }

    @Override
    public Optional<CatalogueRef> catalogue(UUID catalogueProductId) {
        return jdbc.sql(GLOBAL_CATALOGUE).param("id", catalogueProductId)
                .query((rs, n) -> new CatalogueRef(rs.getObject("id", UUID.class), rs.getString("code"), rs.getBoolean("active"))).optional();
    }

    @Override
    public List<String> catalogueBarcodes(UUID catalogueProductId) {
        return jdbc.sql(GLOBAL_BARCODES).param("id", catalogueProductId).query(String.class).list();
    }

    @Override
    public Optional<ClientKeyHit> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(FIND_BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query((rs, n) -> new ClientKeyHit(rs.getObject("id", UUID.class), rs.getObject("created_by", UUID.class)))
                .optional();
    }

    @Override
    public List<DuplicateCandidate> duplicatesInCatalogue(UUID tenantId, String dupKey, int limit) {
        return jdbc.sql(DUPLICATES_IN_CATALOGUE).param("tenantId", tenantId).param("dupKey", dupKey)
                .param("limit", limit).query(JdbcProductRepository::mapCandidate).list();
    }

    @Override
    public List<DuplicateCandidate> duplicatesOwn(UUID tenantId, String dupKey, UUID exceptProductId, int limit) {
        return jdbc.sql(DUPLICATES_OWN).param("tenantId", tenantId).param("dupKey", dupKey)
                .param("except", exceptProductId).param("limit", limit).query(JdbcProductRepository::mapCandidate).list();
    }

    @Override
    public Optional<String> barcodeOwner(UUID tenantId, String barcode, UUID exceptProductId) {
        return jdbc.sql(BARCODE_OWNER).param("tenantId", tenantId).param("barcode", barcode)
                .param("except", exceptProductId).query(String.class).optional();
    }

    @Override
    public List<StoreSettingRow> storeSettings(UUID tenantId, UUID productId) {
        return jdbc.sql(STORE_SETTINGS).param("tenantId", tenantId).param("productId", productId)
                .query((rs, n) -> {
                    int reorder = rs.getInt("reorder_level");
                    Integer level = rs.wasNull() ? null : reorder;
                    return new StoreSettingRow(rs.getObject("store_id", UUID.class), level, rs.getString("rack"));
                }).list();
    }

    @Override
    public Optional<VendorRef> vendor(UUID tenantId, UUID vendorId) {
        return jdbc.sql(VENDOR).param("tenantId", tenantId).param("id", vendorId)
                .query((rs, n) -> new VendorRef(rs.getObject("id", UUID.class), rs.getString("name"),
                        "ACTIVE".equals(rs.getString("status")), rs.getBoolean("licence_required"),
                        rs.getObject("licence_valid_till", LocalDate.class))).optional();
    }

    @Override
    public List<String> usedUnits(UUID tenantId) {
        return jdbc.sql(USED_UNITS).param("tenantId", tenantId).query(String.class).list();
    }

    @Override
    public List<String> usedForms(UUID tenantId) {
        return jdbc.sql(USED_FORMS).param("tenantId", tenantId).query(String.class).list();
    }

    @Override
    public boolean onOpenImport(UUID tenantId, UUID productId) {
        return jdbc.sql(ON_OPEN_IMPORT).param("tenantId", tenantId).param("id", productId).query(Boolean.class).single();
    }

    @Override
    public Map<String, ProductRef> activeByCodesForShare(UUID tenantId, Collection<String> lowerCodes) {
        Map<String, ProductRef> found = new HashMap<>();
        if (lowerCodes.isEmpty()) {
            return found;
        }
        jdbc.sql(ACTIVE_BY_CODES).param("tenantId", tenantId).param("codes", lowerCodes)
                .query((rs, n) -> new ProductRef(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name")))
                .list().forEach(ref -> found.put(ref.code().toLowerCase(java.util.Locale.ROOT), ref));
        return found;
    }

    @Override
    public int takeOwnNumber(UUID tenantId, UUID unitId, UUID actorUserId) {
        return jdbc.sql(TAKE_OWN_NUMBER).param("tenantId", tenantId).param("unitId", unitId).param("actor", actorUserId)
                .query(Integer.class).single();
    }

    @Override
    public UUID insertAdopted(UUID tenantId, UUID unitId, UUID catalogueProductId, UUID actorUserId) {
        return jdbc.sql(INSERT_ADOPTED).param("tenantId", tenantId).param("unitId", unitId)
                .param("catalogueId", catalogueProductId).param("actor", actorUserId).query(UUID.class).single();
    }

    @Override
    public UUID insertOwn(UUID tenantId, NewOwn product, UUID actorUserId) {
        OwnFields f = product.fields();
        return jdbc.sql(INSERT_OWN).param("tenantId", tenantId).param("unitId", product.unitId())
                .param("code", product.code()).param("name", f.name()).param("salt", f.salt())
                .param("strength", f.strength()).param("form", f.form()).param("manufacturer", f.manufacturer())
                .param("madeInHouse", f.madeInHouse()).param("hsn", f.hsn()).param("gstRate", f.gstRate())
                .param("schedule", f.schedule()).param("purchaseUnit", f.purchaseUnit())
                .param("issueUnit", f.issueUnit()).param("conversion", f.conversion()).param("dupKey", f.dupKey())
                .param("clientKey", product.clientKey()).param("barcode", f.barcode()).param("actor", actorUserId)
                .query(UUID.class).single();
    }

    @Override
    public boolean updateOwn(UUID tenantId, UUID id, long version, OwnFields f, UUID actorUserId) {
        return jdbc.sql(UPDATE_OWN).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("name", f.name()).param("salt", f.salt()).param("strength", f.strength())
                .param("form", f.form()).param("manufacturer", f.manufacturer()).param("madeInHouse", f.madeInHouse())
                .param("hsn", f.hsn()).param("gstRate", f.gstRate()).param("schedule", f.schedule())
                .param("purchaseUnit", f.purchaseUnit()).param("issueUnit", f.issueUnit())
                .param("conversion", f.conversion()).param("dupKey", f.dupKey()).param("barcode", f.barcode())
                .param("actor", actorUserId).update() == 1;
    }

    @Override
    public boolean updateLocal(UUID tenantId, UUID id, long version, String alias, String barcode, boolean returnable,
                               UUID preferredVendorId, boolean active, UUID actorUserId) {
        return jdbc.sql(UPDATE_LOCAL).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("alias", alias).param("barcode", barcode).param("returnable", returnable)
                .param("vendorId", preferredVendorId).param("status", active ? "ACTIVE" : "INACTIVE")
                .param("actor", actorUserId).update() == 1;
    }

    @Override
    public void saveStoreSetting(UUID tenantId, UUID unitId, UUID productId, UUID storeId, Integer reorderLevel,
                                 String rack, UUID actorUserId) {
        jdbc.sql(SAVE_STORE_SETTING).param("tenantId", tenantId).param("unitId", unitId).param("productId", productId)
                .param("storeId", storeId).param("reorder", reorderLevel).param("rack", rack).param("actor", actorUserId)
                .update();
    }

    private static Row mapSearchRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("source"), rs.getObject("catalogue_product_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("manufacturer"), rs.getBoolean("made_in_house"), rs.getString("salt"),
                rs.getString("strength"), rs.getString("form"), rs.getString("purchase_unit"),
                rs.getString("issue_unit"), rs.getInt("conversion"), rs.getString("hsn"), rs.getBigDecimal("gst_rate"),
                rs.getString("schedule"), rs.getBoolean("ndps"), rs.getString("in_unit"), rs.getBoolean("sample"),
                rs.getString("alias"));
    }

    private static Full mapFull(ResultSet rs, int rowNum) throws SQLException {
        boolean own = "OWN".equals(rs.getString("kind"));
        Row row = new Row(own ? "OWN" : "GLOBAL", rs.getObject("catalogue_product_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("manufacturer"), rs.getBoolean("made_in_house"), rs.getString("salt"),
                rs.getString("strength"), rs.getString("form"), rs.getString("purchase_unit"),
                rs.getString("issue_unit"), rs.getInt("conversion"), rs.getString("hsn"), rs.getBigDecimal("gst_rate"),
                rs.getString("schedule"), rs.getBoolean("ndps"), rs.getString("in_unit"), rs.getBoolean("sample"),
                rs.getString("alias"));
        return new Full(row, own, "ACTIVE".equals(rs.getString("status")), rs.getString("barcode"),
                rs.getBoolean("returnable"), rs.getObject("preferred_vendor_id", UUID.class), rs.getBoolean("has_stock"),
                rs.getLong("version"), rs.getObject("created_by", UUID.class));
    }

    private static DuplicateCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new DuplicateCandidate(rs.getString("source"), rs.getObject("catalogue_product_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("manufacturer"), rs.getBoolean("made_in_house"), rs.getString("in_unit"));
    }
}
