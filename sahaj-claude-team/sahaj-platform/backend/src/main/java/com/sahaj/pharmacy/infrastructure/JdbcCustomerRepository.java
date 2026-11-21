package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.CustomerRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.customer} and {@code pharmacy.customer_account_entry} (SAH-80-2, SAH-80-4). Every statement names the
 * tenant as well as relying on row level security. Search text is always a bind parameter, already lower case with the wildcards
 * escaped by the service. The bean is named for the module so that no other module's repository can clash with it.
 */
@Repository("pharmacyJdbcCustomerRepository")
public class JdbcCustomerRepository implements CustomerRepository {

    private static final String COLUMNS = """
            id, unit_id, kind, patient_id, name, phone, allergy_note, credit_limit_paise, due_paise, credit_note_balance_paise,
            status, created_at, version
            """;
    private static final String FIND = "select " + COLUMNS + " from pharmacy.customer where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_BY_PATIENT = "select " + COLUMNS
            + " from pharmacy.customer where tenant_id = :tenantId and patient_id = :patientId";
    private static final String FIND_MANY = "select " + COLUMNS
            + " from pharmacy.customer where tenant_id = :tenantId and id in (:ids)";
    private static final String SEARCH_WHERE = """
             where tenant_id = :tenantId
               and (:includeInactive or status = 'ACTIVE')
               and (:like = '' or lower(name) like :like escape '\\')
               and (:phone = '' or phone = :phone)
               and (cast(:patientId as uuid) is null or patient_id = cast(:patientId as uuid))
            """;
    private static final String SEARCH = "select " + COLUMNS + " from pharmacy.customer" + SEARCH_WHERE
            + " order by created_at desc, id limit :limit offset :offset";
    private static final String SEARCH_COUNT = "select count(*) from pharmacy.customer" + SEARCH_WHERE;
    private static final String BY_PHONE = "select " + COLUMNS + """
             from pharmacy.customer where tenant_id = :tenantId and kind = 'WALK_IN' and phone = :phone
              and (cast(:exceptId as uuid) is null or id <> cast(:exceptId as uuid))
            order by created_at, id limit :limit
            """;
    private static final String INSERT = """
            insert into pharmacy.customer (id, tenant_id, unit_id, kind, patient_id, name, phone, allergy_note,
                   credit_limit_paise, created_by)
            values (:id, :tenantId, :unitId, :kind, :patientId, :name, :phone, :allergyNote, :limit, :by)
            """;
    private static final String UPDATE = """
            update pharmacy.customer set name = :name, phone = :phone, allergy_note = :allergyNote,
                   credit_limit_paise = :limit, version = version + 1, updated_at = now(), updated_by = :by
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SWITCH_OFF = """
            update pharmacy.customer set status = 'INACTIVE', version = version + 1, updated_at = now(), updated_by = :by
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SET_BALANCES = """
            update pharmacy.customer set due_paise = :due, credit_note_balance_paise = :balance, updated_at = now(),
                   updated_by = :by
             where tenant_id = :tenantId and id = :id
            """;
    private static final String CREDIT_SINCE = """
            select customer_id, sum(amount_paise) as total from pharmacy.customer_account_entry
             where tenant_id = :tenantId and customer_id in (:ids) and kind = 'CREDIT_BILL' and occurred_at >= :since
             group by customer_id
            """;
    private static final String INSERT_ENTRY = """
            insert into pharmacy.customer_account_entry (id, tenant_id, unit_id, customer_id, store_id, kind, amount_paise,
                   due_after_paise, cn_balance_after_paise, bill_id, sales_return_id, mode, actor_membership_id)
            values (:id, :tenantId, :unitId, :customerId, :storeId, :kind, :amount, :dueAfter, :cnAfter, :billId, :returnId,
                    :mode, :actor)
            """;
    private static final String ENTRY_COLUMNS = """
            select e.id, e.kind, e.occurred_at, e.store_id, s.name as store_name,
                   coalesce(cn.credit_note_no, b.bill_no) as reference, e.mode, e.amount_paise, e.due_after_paise,
                   e.cn_balance_after_paise, e.actor_membership_id
              from pharmacy.customer_account_entry e
              join pharmacy.store s on s.tenant_id = e.tenant_id and s.id = e.store_id
              left join pharmacy.bill b on b.tenant_id = e.tenant_id and b.id = e.bill_id
              left join pharmacy.credit_note cn on cn.tenant_id = e.tenant_id and e.sales_return_id is not null
                   and cn.sales_return_id = e.sales_return_id
            """;
    private static final String FIND_ENTRY = ENTRY_COLUMNS + " where e.tenant_id = :tenantId and e.id = :id";
    private static final String ENTRIES = ENTRY_COLUMNS + """
             where e.tenant_id = :tenantId and e.customer_id = :customerId
             order by e.occurred_at desc, e.id desc limit :limit offset :offset
            """;
    private static final String ENTRIES_COUNT = """
            select count(*) from pharmacy.customer_account_entry where tenant_id = :tenantId and customer_id = :customerId
            """;

    private final JdbcClient jdbc;

    public JdbcCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcCustomerRepository::row).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", id).query(JdbcCustomerRepository::row)
                .optional();
    }

    @Override
    public Optional<Row> findByPatient(UUID tenantId, UUID patientId) {
        return jdbc.sql(FIND_BY_PATIENT).param("tenantId", tenantId).param("patientId", patientId)
                .query(JdbcCustomerRepository::row).optional();
    }

    @Override
    public Map<UUID, Row> findAll(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, Row> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql(FIND_MANY).param("tenantId", tenantId).param("ids", ids).query(JdbcCustomerRepository::row).list()
                .forEach(r -> out.put(r.id(), r));
        return out;
    }

    @Override
    public Slice search(UUID tenantId, String like, String phone, UUID patientId, boolean includeInactive, int limit,
                        int offset) {
        List<Row> items = jdbc.sql(SEARCH).param("tenantId", tenantId).param("includeInactive", includeInactive)
                .param("like", like).param("phone", phone).param("patientId", patientId).param("limit", limit)
                .param("offset", offset).query(JdbcCustomerRepository::row).list();
        long total = jdbc.sql(SEARCH_COUNT).param("tenantId", tenantId).param("includeInactive", includeInactive)
                .param("like", like).param("phone", phone).param("patientId", patientId).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public List<Row> byPhone(UUID tenantId, String phone, UUID exceptId, int limit) {
        return jdbc.sql(BY_PHONE).param("tenantId", tenantId).param("phone", phone).param("exceptId", exceptId)
                .param("limit", limit).query(JdbcCustomerRepository::row).list();
    }

    @Override
    public void insert(UUID tenantId, NewCustomer c, UUID actorMembershipId) {
        jdbc.sql(INSERT).param("id", c.id()).param("tenantId", tenantId).param("unitId", c.unitId()).param("kind", c.kind())
                .param("patientId", c.patientId()).param("name", c.name()).param("phone", c.phone())
                .param("allergyNote", c.allergyNote()).param("limit", c.creditLimitPaise()).param("by", actorMembershipId)
                .update();
    }

    @Override
    public boolean update(UUID tenantId, UUID id, long version, String name, String phone, String allergyNote,
                          long creditLimitPaise, UUID actorMembershipId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", id).param("version", version).param("name", name)
                .param("phone", phone).param("allergyNote", allergyNote).param("limit", creditLimitPaise)
                .param("by", actorMembershipId).update() == 1;
    }

    @Override
    public boolean switchOff(UUID tenantId, UUID id, long version, UUID actorMembershipId) {
        return jdbc.sql(SWITCH_OFF).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("by", actorMembershipId).update() == 1;
    }

    @Override
    public void setBalances(UUID tenantId, UUID id, long duePaise, long creditNoteBalancePaise, UUID actorMembershipId) {
        jdbc.sql(SET_BALANCES).param("tenantId", tenantId).param("id", id).param("due", duePaise)
                .param("balance", creditNoteBalancePaise).param("by", actorMembershipId).update();
    }

    @Override
    public Map<UUID, Long> creditBillsSince(UUID tenantId, Collection<UUID> customerIds, Instant since) {
        Map<UUID, Long> out = new HashMap<>();
        if (customerIds.isEmpty()) {
            return out;
        }
        jdbc.sql(CREDIT_SINCE).param("tenantId", tenantId).param("ids", customerIds).param("since", Timestamp.from(since))
                .query((rs, n) -> Map.entry(rs.getObject("customer_id", UUID.class), rs.getLong("total"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public void insertEntry(UUID tenantId, NewEntry e) {
        jdbc.sql(INSERT_ENTRY).param("id", e.id()).param("tenantId", tenantId).param("unitId", e.unitId())
                .param("customerId", e.customerId()).param("storeId", e.storeId()).param("kind", e.kind())
                .param("amount", e.amountPaise()).param("dueAfter", e.dueAfterPaise())
                .param("cnAfter", e.creditNoteBalanceAfterPaise()).param("billId", e.billId())
                .param("returnId", e.salesReturnId()).param("mode", e.mode()).param("actor", e.actor()).update();
    }

    @Override
    public Optional<Entry> findEntry(UUID tenantId, UUID entryId) {
        return jdbc.sql(FIND_ENTRY).param("tenantId", tenantId).param("id", entryId).query(JdbcCustomerRepository::entry)
                .optional();
    }

    @Override
    public EntrySlice entries(UUID tenantId, UUID customerId, int limit, int offset) {
        List<Entry> items = jdbc.sql(ENTRIES).param("tenantId", tenantId).param("customerId", customerId)
                .param("limit", limit).param("offset", offset).query(JdbcCustomerRepository::entry).list();
        long total = jdbc.sql(ENTRIES_COUNT).param("tenantId", tenantId).param("customerId", customerId).query(Long.class)
                .single();
        return new EntrySlice(items, total);
    }

    private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getString("kind"),
                rs.getObject("patient_id", UUID.class), rs.getString("name"), rs.getString("phone"),
                rs.getString("allergy_note"), rs.getLong("credit_limit_paise"), rs.getLong("due_paise"),
                rs.getLong("credit_note_balance_paise"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"));
    }

    private static Entry entry(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("store_id", UUID.class), rs.getString("store_name"), rs.getString("reference"),
                rs.getString("mode"), rs.getLong("amount_paise"), rs.getLong("due_after_paise"),
                rs.getLong("cn_balance_after_paise"), rs.getObject("actor_membership_id", UUID.class));
    }
}
