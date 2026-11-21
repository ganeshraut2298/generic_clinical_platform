package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ComplianceRepository;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.pharmacist_registration}, the Schedule X licence columns of {@code pharmacy.unit} and the activity feed
 * over {@code pharmacy.document_event} (served by the index on store and time, migration 2010). Every statement names the tenant.
 */
@Repository("pharmacyJdbcComplianceRepository")
public class JdbcComplianceRepository implements ComplianceRepository {

    private static final String REGISTRATION_COLUMNS = "id, unit_id, membership_id, registration_no, valid_till, version";
    private static final String FEED = """
            select * from (
              select e.occurred_at, e.event, e.doc_type, e.actor_membership_id, e.acting_as, e.store_id, e.reason_key,
                     e.qty_before, e.qty_after,
                     coalesce(b.bill_no, rb.bill_no, sa.doc_no) as doc_no, sd.discount_percent as percent,
                     case when e.event = 'SECOND_SIGNATURE' then 'SECOND_SIGNATURE'
                          when e.doc_type = 'BILL' and e.event = 'ALLERGY_OVERRIDE' then 'ALLERGY_OVERRIDE'
                          when e.doc_type = 'BILL' and e.event = 'H1_OVERRIDE' then 'H1_OVERRIDE'
                          when e.doc_type = 'SALE_DISCOUNT' and e.event = 'DISCOUNT_REQUESTED' then 'DISCOUNT_REQUESTED'
                          when e.doc_type = 'SALE_DISCOUNT' and e.event = 'DISCOUNT_DECIDED' then 'DISCOUNT_DECIDED'
                          when e.doc_type = 'STOCK_ADJUSTMENT' and e.event = 'POSTED' then 'STOCK_ADJUSTED'
                          when e.doc_type = 'BILL' and e.event = 'CANCELLED' then 'BILL_CANCELLED'
                          when e.doc_type = 'SALES_RETURN' then 'SALES_RETURN' end as activity_type
                from pharmacy.document_event e
                left join pharmacy.bill b on e.doc_type = 'BILL' and b.tenant_id = e.tenant_id and b.id = e.doc_id
                left join pharmacy.sales_return sr on e.doc_type = 'SALES_RETURN' and sr.tenant_id = e.tenant_id and sr.id = e.doc_id
                left join pharmacy.bill rb on rb.tenant_id = sr.tenant_id and rb.id = sr.bill_id
                left join pharmacy.stock_adjustment sa on e.doc_type = 'STOCK_ADJUSTMENT' and sa.tenant_id = e.tenant_id
                     and sa.id = e.doc_id
                left join pharmacy.sale_discount_request sd on e.doc_type = 'SALE_DISCOUNT' and sd.tenant_id = e.tenant_id
                     and sd.id = e.doc_id
               where e.tenant_id = :t and e.store_id in (:stores) and e.occurred_at >= :from and e.occurred_at < :to
                 and (cast(:by as uuid) is null or e.actor_membership_id = cast(:by as uuid))
            ) x where x.activity_type is not null and (cast(:type as text) is null or x.activity_type = cast(:type as text))
            """;

    private final JdbcClient jdbc;

    public JdbcComplianceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Registration> registrations(UUID tenantId) {
        return jdbc.sql("select " + REGISTRATION_COLUMNS + " from pharmacy.pharmacist_registration where tenant_id = :t"
                + " order by registration_no, id").param("t", tenantId).query(JdbcComplianceRepository::registration).list();
    }

    @Override
    public Optional<Registration> registration(UUID tenantId, UUID id) {
        return jdbc.sql("select " + REGISTRATION_COLUMNS + " from pharmacy.pharmacist_registration where tenant_id = :t and id = :id")
                .param("t", tenantId).param("id", id).query(JdbcComplianceRepository::registration).optional();
    }

    @Override
    public Optional<Registration> registrationOfMember(UUID tenantId, UUID membershipId) {
        return jdbc.sql("select " + REGISTRATION_COLUMNS
                + " from pharmacy.pharmacist_registration where tenant_id = :t and membership_id = :m")
                .param("t", tenantId).param("m", membershipId).query(JdbcComplianceRepository::registration).optional();
    }

    @Override
    public void insertRegistration(UUID tenantId, Registration r, UUID actor) {
        jdbc.sql("""
                insert into pharmacy.pharmacist_registration (id, tenant_id, unit_id, membership_id, registration_no, valid_till,
                       created_by)
                values (:id, :t, :unit, :m, :no, :till, :actor)
                """).param("id", r.id()).param("t", tenantId).param("unit", r.unitId()).param("m", r.membershipId())
                .param("no", r.registrationNo()).param("till", r.validTill() == null ? null : Date.valueOf(r.validTill()))
                .param("actor", actor).update();
    }

    @Override
    public boolean updateRegistration(UUID tenantId, UUID id, long version, String registrationNo, LocalDate validTill,
                                      UUID actor) {
        return jdbc.sql("""
                update pharmacy.pharmacist_registration set registration_no = :no, valid_till = :till, updated_at = now(),
                       updated_by = :actor, version = version + 1
                 where tenant_id = :t and id = :id and version = :version
                """).param("no", registrationNo).param("till", validTill == null ? null : Date.valueOf(validTill))
                .param("actor", actor).param("t", tenantId).param("id", id).param("version", version).update() == 1;
    }

    @Override
    public Optional<ScheduleXLicence> scheduleXLicence(UUID tenantId) {
        return jdbc.sql("select schedule_x_licence_no, schedule_x_licence_valid_till from pharmacy.unit where tenant_id = :t"
                + " and schedule_x_licence_no is not null").param("t", tenantId).query((rs, n) -> {
                    Date till = rs.getDate("schedule_x_licence_valid_till");
                    return new ScheduleXLicence(rs.getString("schedule_x_licence_no"), till == null ? null : till.toLocalDate());
                }).optional();
    }

    @Override
    public ActivitySlice activity(UUID tenantId, Collection<UUID> storeIds, Instant from, Instant to, UUID membershipId,
                                  String type, int limit, int offset) {
        if (storeIds.isEmpty()) {
            return new ActivitySlice(List.of(), 0);
        }
        long total = jdbc.sql("select count(*) from (" + FEED + ") c").param("t", tenantId).param("stores", storeIds)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("by", membershipId)
                .param("type", type).query(Long.class).single();
        List<ActivityEvent> items = jdbc.sql("select * from (" + FEED + ") c order by occurred_at desc limit :limit offset :offset")
                .param("t", tenantId).param("stores", storeIds).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).param("by", membershipId).param("type", type).param("limit", limit)
                .param("offset", offset).query(JdbcComplianceRepository::event).list();
        return new ActivitySlice(items, total);
    }

    private static Registration registration(ResultSet rs, int n) throws SQLException {
        Date till = rs.getDate("valid_till");
        return new Registration(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("membership_id", UUID.class), rs.getString("registration_no"),
                till == null ? null : till.toLocalDate(), rs.getLong("version"));
    }

    private static ActivityEvent event(ResultSet rs, int n) throws SQLException {
        int before = rs.getInt("qty_before");
        Integer qtyBefore = rs.wasNull() ? null : before;
        int after = rs.getInt("qty_after");
        Integer qtyAfter = rs.wasNull() ? null : after;
        return new ActivityEvent(rs.getTimestamp("occurred_at").toInstant(), rs.getString("activity_type"),
                rs.getString("doc_type"), rs.getString("doc_no"), rs.getBigDecimal("percent"), rs.getString("event"),
                rs.getObject("actor_membership_id", UUID.class), rs.getString("acting_as"), rs.getObject("store_id", UUID.class),
                rs.getString("reason_key"), qtyBefore, qtyAfter);
    }
}
