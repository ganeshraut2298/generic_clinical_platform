package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.ChargeRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.admission_charge} (SAH-25). The insert lists only the columns the database role may write; the id,
 * the clinic, the time and the quantity (always 1, since V2026_11_16_1000) come from the database defaults.
 */
@Repository
public class JdbcChargeRepository implements ChargeRepository {

    private static final String INSERT = """
            insert into ipd.admission_charge (admission_id, service_key, service_name, unit_paise, added_by)
            values (:admissionId, :key, :name, :unit, :by)
            returning id
            """;
    private static final String COUNT = """
            select count(*) from ipd.admission_charge
            where tenant_id = :tenantId and admission_id = :admissionId and service_key = :key
            """;
    private static final String FIND_BY_ADMISSION = """
            select admission_id, service_key, service_name, unit_paise, qty
            from ipd.admission_charge
            where tenant_id = :tenantId and admission_id = :admissionId
            order by added_at, id
            """;
    private static final String FIND_FOR_ADMITTED = """
            select c.admission_id, c.service_key, c.service_name, c.unit_paise, c.qty
            from ipd.admission_charge c
            join ipd.admission a on a.tenant_id = c.tenant_id and a.id = c.admission_id
            where c.tenant_id = :tenantId and a.status = 'ADMITTED'
            order by c.added_at, c.id
            """;

    private final JdbcClient jdbc;

    public JdbcChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(UUID tenantId, UUID admissionId, String serviceKey, String serviceName, long unitPaise,
                       UUID addedBy) {
        return jdbc.sql(INSERT).param("admissionId", admissionId).param("key", serviceKey)
                .param("name", serviceName).param("unit", unitPaise).param("by", addedBy)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public int countByService(UUID tenantId, UUID admissionId, String serviceKey) {
        return jdbc.sql(COUNT).param("tenantId", tenantId).param("admissionId", admissionId)
                .param("key", serviceKey).query((rs, rowNum) -> rs.getInt(1)).single();
    }

    @Override
    public List<ChargeRow> findByAdmission(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_BY_ADMISSION).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcChargeRepository::row).list();
    }

    @Override
    public List<ChargeRow> findForAdmitted(UUID tenantId) {
        return jdbc.sql(FIND_FOR_ADMITTED).param("tenantId", tenantId).query(JdbcChargeRepository::row).list();
    }

    private static ChargeRow row(ResultSet rs, int rowNum) throws SQLException {
        return new ChargeRow(rs.getObject("admission_id", UUID.class), rs.getString("service_key"),
                rs.getString("service_name"), rs.getLong("unit_paise"), rs.getInt("qty"));
    }
}
