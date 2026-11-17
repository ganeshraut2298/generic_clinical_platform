package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.OrderVersionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.medicine_order_version} and {@code ipd.order_hold} writes (SAH-76, docs/database/SAH-76.md sections 1.5,
 * 1.6, 6). Each insert names exactly the columns the database role may write; the two stamps are sent as a bare marker
 * and the guard trigger stamps the real time, which {@code returning} reads back. The indication, the change reason and
 * the hold reason are clinical text: bind parameters only, never logged. Fixed statements only.
 */
@Repository
public class JdbcOrderVersionRepository implements OrderVersionRepository {

    private static final String INSERT_VERSION = """
            insert into ipd.medicine_order_version
              (order_id, version_no, kind, frequency_key, dose_amount, dose_unit, route_key, start_at, end_at,
               indication, priority, min_interval_min, max_per_24h, change_reason, created_by, verification)
            values (:orderId, :versionNo, :kind, :frequencyKey, :doseAmount, :doseUnit, :routeKey, :startAt, :endAt,
                    :indication, :priority, :minInterval, :maxPer24h, :changeReason, :createdBy, :verification)
            returning created_at
            """;
    private static final String SUPERSEDE = """
            update ipd.medicine_order_version
            set superseded_at = clock_timestamp()
            where tenant_id = :tenantId and order_id = :orderId and version_no = :versionNo and superseded_at is null
            """;
    private static final String VERIFY = """
            update ipd.medicine_order_version
            set verified_by = :user
            where tenant_id = :tenantId and order_id = :orderId and version_no = :versionNo and verified_by is null
            returning verified_at
            """;
    private static final String OPEN_HOLD = """
            insert into ipd.order_hold (medicine_order_id, held_by, hold_reason)
            values (:orderId, :user, :reason)
            returning held_at
            """;
    private static final String CLOSE_HOLD = """
            update ipd.order_hold
            set resumed_by = :user
            where tenant_id = :tenantId and medicine_order_id = :orderId and resumed_at is null
            """;

    private final JdbcClient jdbc;

    public JdbcOrderVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant insert(UUID tenantId, NewVersion v) {
        return jdbc.sql(INSERT_VERSION).param("orderId", v.orderId()).param("versionNo", v.versionNo())
                .param("kind", v.kind().name()).param("frequencyKey", v.frequencyKey())
                .param("doseAmount", v.doseAmount()).param("doseUnit", v.doseUnit()).param("routeKey", v.routeKey())
                .param("startAt", odt(v.startAt())).param("endAt", odt(v.endAt()))
                .param("indication", v.indication()).param("priority", v.priority().name())
                .param("minInterval", v.minIntervalMin()).param("maxPer24h", v.maxPer24h())
                .param("changeReason", v.changeReason()).param("createdBy", v.createdBy())
                .param("verification", v.verification().name())
                .query((rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class).toInstant()).single();
    }

    @Override
    public void markSuperseded(UUID tenantId, UUID orderId, int versionNo) {
        int rows = jdbc.sql(SUPERSEDE).param("tenantId", tenantId).param("orderId", orderId)
                .param("versionNo", versionNo).update();
        if (rows != 1) {
            throw new IllegalStateException("The order version was not replaced");
        }
    }

    @Override
    public Instant markVerified(UUID tenantId, UUID orderId, int versionNo, UUID verifiedBy) {
        return jdbc.sql(VERIFY).param("tenantId", tenantId).param("orderId", orderId).param("versionNo", versionNo)
                .param("user", verifiedBy)
                .query((rs, rowNum) -> rs.getObject("verified_at", OffsetDateTime.class).toInstant()).optional()
                .orElseThrow(() -> new IllegalStateException("The order version was not verified"));
    }

    @Override
    public Instant openHold(UUID tenantId, UUID orderId, UUID heldBy, String reason) {
        return jdbc.sql(OPEN_HOLD).param("orderId", orderId).param("user", heldBy).param("reason", reason)
                .query((rs, rowNum) -> rs.getObject("held_at", OffsetDateTime.class).toInstant()).single();
    }

    @Override
    public boolean closeHold(UUID tenantId, UUID orderId, UUID resumedBy) {
        return jdbc.sql(CLOSE_HOLD).param("tenantId", tenantId).param("orderId", orderId).param("user", resumedBy)
                .update() == 1;
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
