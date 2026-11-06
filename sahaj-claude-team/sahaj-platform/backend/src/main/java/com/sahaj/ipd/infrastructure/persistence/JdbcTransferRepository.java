package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.TransferRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.bed_transfer} (SAH-26). The insert lists only the columns the database role may write; the id, the
 * clinic and the time come from the database defaults.
 */
@Repository
public class JdbcTransferRepository implements TransferRepository {

    private static final String INSERT = """
            insert into ipd.bed_transfer (admission_id, from_bed_id, to_bed_id, from_category_key, to_category_key,
                                          segment_days, new_rate_from, reason, transferred_by)
            values (:admissionId, :fromBed, :toBed, :fromCategory, :toCategory, :segmentDays, :newRateFrom, :reason, :by)
            returning id
            """;
    private static final String FIND_BY_ADMISSION = """
            select admission_id, from_category_key, segment_days
            from ipd.bed_transfer
            where tenant_id = :tenantId and admission_id = :admissionId
            order by transferred_at, id
            """;
    private static final String FIND_FOR_ADMITTED = """
            select t.admission_id, t.from_category_key, t.segment_days
            from ipd.bed_transfer t
            join ipd.admission a on a.tenant_id = t.tenant_id and a.id = t.admission_id
            where t.tenant_id = :tenantId and a.status = 'ADMITTED'
            order by t.transferred_at, t.id
            """;

    private final JdbcClient jdbc;

    public JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(UUID tenantId, NewTransfer t) {
        return jdbc.sql(INSERT).param("admissionId", t.admissionId()).param("fromBed", t.fromBedId())
                .param("toBed", t.toBedId()).param("fromCategory", t.fromCategoryKey())
                .param("toCategory", t.toCategoryKey()).param("segmentDays", t.segmentDays())
                .param("newRateFrom", t.newRateFrom()).param("reason", t.reason(), Types.VARCHAR)
                .param("by", t.transferredBy())
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public List<TransferRow> findByAdmission(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_BY_ADMISSION).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcTransferRepository::row).list();
    }

    @Override
    public List<TransferRow> findForAdmitted(UUID tenantId) {
        return jdbc.sql(FIND_FOR_ADMITTED).param("tenantId", tenantId).query(JdbcTransferRepository::row).list();
    }

    private static TransferRow row(ResultSet rs, int rowNum) throws SQLException {
        return new TransferRow(rs.getObject("admission_id", UUID.class), rs.getString("from_category_key"),
                rs.getInt("segment_days"));
    }
}
