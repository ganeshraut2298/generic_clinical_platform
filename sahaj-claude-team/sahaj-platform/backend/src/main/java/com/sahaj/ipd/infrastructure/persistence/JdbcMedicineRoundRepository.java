package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.MedicineRoundRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SAH-35 the two reads of the Medicine round (docs/database/SAH-35.md Q1 and Q2). Fixed statement text only; the
 * tenant and the values are bind parameters. The admission ids of Q2 travel as one {@code uuid[]} literal built from
 * {@link UUID} values (only hex digits and dashes can occur), so the statement is {@code admission_id = any(...)} with
 * {@code tenant_id} kept in the {@code where}. Nothing here is logged.
 */
@Repository
public class JdbcMedicineRoundRepository implements MedicineRoundRepository {

    private static final String ACTIVE_OF_ADMITTED = """
            select o.id, o.admission_id, o.drug_key, o.frequency_key, o.verified_at, a.patient_id, b.code as bed_code
            from ipd.admission a
            join ipd.medicine_order o on o.tenant_id = a.tenant_id and o.admission_id = a.id
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            where a.tenant_id = :tenantId and a.status = 'ADMITTED' and o.status = 'ACTIVE'
            """;
    private static final String DOSES_BETWEEN = """
            select d.order_id, d.slot_at, d.outcome
            from ipd.dose_administration d
            where d.tenant_id = :tenantId
              and d.admission_id = any(cast(:admissionIds as uuid[]))
              and d.slot_at >= :from and d.slot_at < :to
            """;

    private final JdbcClient jdbc;

    public JdbcMedicineRoundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ActiveOrder> findActiveOfAdmitted(UUID tenantId) {
        return jdbc.sql(ACTIVE_OF_ADMITTED).param("tenantId", tenantId)
                .query((rs, rowNum) -> new ActiveOrder(rs.getObject("id", UUID.class),
                        rs.getObject("admission_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("bed_code"), rs.getString("drug_key"), rs.getString("frequency_key"),
                        instant(rs.getObject("verified_at", OffsetDateTime.class))))
                .list();
    }

    @Override
    public List<TodayDose> findDosesBetween(UUID tenantId, Collection<UUID> admissionIds, Instant from, Instant to) {
        if (admissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(DOSES_BETWEEN).param("tenantId", tenantId).param("admissionIds", arrayLiteral(admissionIds))
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, rowNum) -> new TodayDose(rs.getObject("order_id", UUID.class),
                        instant(rs.getObject("slot_at", OffsetDateTime.class)), rs.getString("outcome")))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** {@code {id1,id2}}: a PostgreSQL array literal of canonical UUID text, cast to {@code uuid[]} in the statement. */
    static String arrayLiteral(Collection<UUID> ids) {
        StringBuilder literal = new StringBuilder("{");
        boolean first = true;
        for (UUID id : ids) {
            if (!first) {
                literal.append(',');
            }
            literal.append(id);
            first = false;
        }
        return literal.append('}').toString();
    }
}
