package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.DoseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.dose_administration} (SAH-32, docs/database/SAH-32.md). The insert is the one statement of the database
 * design: it names exactly the eleven granted columns and never {@code id}, {@code tenant_id} or {@code recorded_at}
 * (naming them is 42501). There is no update and no delete statement. The scanned text is written but never read back.
 * Statements are built from fixed text only; the values travel as bind parameters and are never logged.
 */
@Repository
public class JdbcDoseRepository implements DoseRepository {

    private static final String COLUMNS = """
            d.id, d.admission_id, d.order_id, d.client_key, d.slot_at, d.outcome, d.reason_key, d.recorded_by,
            d.second_check_by, d.high_alert, d.recorded_at
            """;
    private static final String FIND_BY_CLIENT_KEY = "select " + COLUMNS + """
            from ipd.dose_administration d
            where d.tenant_id = :tenantId and d.client_key = :clientKey
            """;
    private static final String FIND_BY_ORDER_SLOT = "select " + COLUMNS + """
            from ipd.dose_administration d
            where d.tenant_id = :tenantId and d.order_id = :orderId and d.slot_at = :slotAt
            """;
    private static final String FIND_BETWEEN = "select " + COLUMNS + """
            from ipd.dose_administration d
            where d.tenant_id = :tenantId and d.admission_id = :admissionId
              and d.slot_at >= :from and d.slot_at < :to
            order by d.slot_at, d.id
            """;
    private static final String FIND_LAST = "select distinct on (d.order_id) " + COLUMNS + """
            from ipd.dose_administration d
            where d.tenant_id = :tenantId and d.admission_id = :admissionId
            order by d.order_id, d.slot_at desc
            """;
    private static final String INSERT = """
            insert into ipd.dose_administration
              (admission_id, order_id, client_key, slot_at, outcome, reason_key,
               recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed)
            values (:admissionId, :orderId, :clientKey, :slotAt, :outcome, :reasonKey,
                    :recordedBy, :secondCheckBy, :highAlert, :identityScan, :identityConfirmed)
            returning id, recorded_at
            """;

    private final JdbcClient jdbc;

    public JdbcDoseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DoseRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(FIND_BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public Optional<DoseRow> findByOrderAndSlot(UUID tenantId, UUID orderId, Instant slotAt) {
        return jdbc.sql(FIND_BY_ORDER_SLOT).param("tenantId", tenantId).param("orderId", orderId)
                .param("slotAt", utc(slotAt)).query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public List<DoseRow> findBetween(UUID tenantId, UUID admissionId, Instant from, Instant to) {
        return jdbc.sql(FIND_BETWEEN).param("tenantId", tenantId).param("admissionId", admissionId)
                .param("from", utc(from)).param("to", utc(to)).query((rs, rowNum) -> map(rs)).list();
    }

    @Override
    public List<DoseRow> findLastPerOrder(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_LAST).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, rowNum) -> map(rs)).list();
    }

    @Override
    public Stored insert(UUID tenantId, NewDose dose) {
        return jdbc.sql(INSERT).param("admissionId", dose.admissionId()).param("orderId", dose.orderId())
                .param("clientKey", dose.clientKey()).param("slotAt", utc(dose.slotAt()))
                .param("outcome", dose.outcome()).param("reasonKey", dose.reasonKey(), Types.VARCHAR)
                .param("recordedBy", dose.recordedBy()).param("secondCheckBy", dose.secondCheckBy(), Types.OTHER)
                .param("highAlert", dose.highAlert()).param("identityScan", dose.identityScan(), Types.VARCHAR)
                .param("identityConfirmed", dose.identityConfirmed(), Types.BOOLEAN)
                .query((rs, rowNum) -> new Stored(rs.getObject("id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static DoseRow map(ResultSet rs) throws SQLException {
        return new DoseRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("client_key", UUID.class),
                rs.getObject("slot_at", OffsetDateTime.class).toInstant(), rs.getString("outcome"),
                rs.getString("reason_key"), rs.getObject("recorded_by", UUID.class),
                rs.getObject("second_check_by", UUID.class), rs.getBoolean("high_alert"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
    }
}
