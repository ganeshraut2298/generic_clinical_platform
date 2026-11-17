package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.DoseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.dose_administration} and {@code ipd.dose_correction} (SAH-32, SAH-76; docs/database/SAH-32.md and
 * SAH-76.md). The dose insert names exactly the granted columns and never {@code id}, {@code tenant_id},
 * {@code recorded_at} or {@code undo_until} (naming them is 42501; the guard trigger stamps the undo time and it is read
 * back with {@code returning}). The correction insert names the five granted columns. There is no update and no delete
 * statement. The typed texts (identity scan, medicine scan, notes) are written but never read back. Statements are built
 * from fixed text only; the values travel as bind parameters and are never logged.
 */
@Repository
public class JdbcDoseRepository implements DoseRepository {

    private static final String COLUMNS = """
            d.id, d.admission_id, d.order_id, d.client_key, d.slot_at, d.outcome, d.reason_key, d.recorded_by,
            d.second_check_by, d.high_alert, d.recorded_at, d.attempt, d.order_version, d.dose_kind, d.scan_method,
            d.override_reason_key, d.before_verification, d.undo_until,
            c.kind as c_kind, c.reason_key as c_reason_key, c.corrected_by as c_corrected_by,
            c.corrected_at as c_corrected_at, c.client_key as c_client_key
            """;
    private static final String FROM = """
            from ipd.dose_administration d
            left join ipd.dose_correction c on c.tenant_id = d.tenant_id and c.dose_id = d.id
            """;
    private static final String FIND_BY_CLIENT_KEY = "select " + COLUMNS + FROM + """
            where d.tenant_id = :tenantId and d.client_key = :clientKey
            """;
    private static final String FIND_BY_ORDER_SLOT = "select " + COLUMNS + FROM + """
            where d.tenant_id = :tenantId and d.order_id = :orderId and d.slot_at = :slotAt
            order by d.attempt desc
            limit 1
            """;
    private static final String FIND_BY_ID = "select " + COLUMNS + FROM + """
            where d.tenant_id = :tenantId and d.id = :id
            """;
    private static final String FIND_BETWEEN = "select " + COLUMNS + FROM + """
            where d.tenant_id = :tenantId and d.admission_id = :admissionId
              and d.slot_at >= :from and d.slot_at < :to
            order by d.slot_at, d.attempt, d.id
            """;
    private static final String FIND_LAST = "select distinct on (d.order_id) " + COLUMNS + FROM + """
            where d.tenant_id = :tenantId and d.admission_id = :admissionId and c.id is null
            order by d.order_id, d.slot_at desc
            """;
    private static final String FIND_LAST_GIVEN = """
            select d.order_id, max(d.recorded_at) as recorded_at
            from ipd.dose_administration d
            where d.tenant_id = :tenantId and d.admission_id = :admissionId and d.outcome = 'GIVEN'
              and not exists (select 1 from ipd.dose_correction c where c.tenant_id = d.tenant_id and c.dose_id = d.id)
            group by d.order_id
            """;
    private static final String FIND_GIVEN_SINCE = """
            select d.order_id, d.recorded_at
            from ipd.dose_administration d
            where d.tenant_id = :tenantId and d.admission_id = :admissionId and d.outcome = 'GIVEN'
              and d.recorded_at >= :since
              and not exists (select 1 from ipd.dose_correction c where c.tenant_id = d.tenant_id and c.dose_id = d.id)
            """;
    private static final String HAS_LIVE_GIVEN = """
            select exists (
              select 1 from ipd.dose_administration d
              where d.tenant_id = :tenantId and d.order_id = :orderId and d.outcome = 'GIVEN'
                and not exists (select 1 from ipd.dose_correction c
                                where c.tenant_id = d.tenant_id and c.dose_id = d.id))
            """;
    private static final String INSERT = """
            insert into ipd.dose_administration
              (admission_id, order_id, client_key, slot_at, outcome, reason_key,
               recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed,
               attempt, supersedes_dose_id, order_version, dose_kind, scan_method, override_reason_key, override_note,
               medicine_scan, before_verification, not_given_note)
            values (:admissionId, :orderId, :clientKey, :slotAt, :outcome, :reasonKey,
                    :recordedBy, :secondCheckBy, :highAlert, :identityScan, :identityConfirmed,
                    :attempt, :supersedesDoseId, :orderVersion, :doseKind, :scanMethod, :overrideReasonKey,
                    :overrideNote, :medicineScan, :beforeVerification, :notGivenNote)
            returning id, recorded_at, undo_until
            """;
    private static final String INSERT_CORRECTION = """
            insert into ipd.dose_correction (dose_id, kind, reason_key, corrected_by, client_key)
            values (:doseId, :kind, :reasonKey, :correctedBy, :clientKey)
            returning corrected_at
            """;
    private static final String INSERT_SCAN_STOP = """
            insert into ipd.dose_scan_stop (admission_id, order_id, slot_at, check_kind, actor)
            values (:admissionId, :orderId, :slotAt, :checkKind, :actor)
            """;
    private static final String HAS_SCAN_STOP_SLOT = """
            select exists (
              select 1 from ipd.dose_scan_stop s
              where s.tenant_id = :tenantId and s.order_id = :orderId and s.actor = :actor
                and s.slot_at = :slotAt and s.stopped_at > now() - interval '15 minutes')
            """;
    private static final String HAS_SCAN_STOP_ORDER = """
            select exists (
              select 1 from ipd.dose_scan_stop s
              where s.tenant_id = :tenantId and s.order_id = :orderId and s.actor = :actor
                and s.stopped_at > now() - interval '15 minutes')
            """;
    private static final String FIND_CORRECTION_BY_KEY = """
            select c.dose_id, c.kind, c.reason_key, c.corrected_by, c.corrected_at, c.client_key
            from ipd.dose_correction c
            where c.tenant_id = :tenantId and c.client_key = :clientKey
            """;
    private static final String FIND_MAR_HEADS = """
            select o.id, o.drug_key, o.status, o.ordered_by, o.ordered_at, o.current_version, o.stopped_by, o.stopped_at
            from ipd.medicine_order o
            where o.tenant_id = :tenantId and o.admission_id = :admissionId
              and (o.status in ('PENDING', 'QUERIED', 'ACTIVE', 'ON_HOLD')
                   or (o.status = 'STOPPED' and o.stopped_at >= :from and o.stopped_at < :to)
                   or (o.status = 'REJECTED' and o.rejected_at >= :from and o.rejected_at < :to))
            order by o.ordered_at, o.id
            """;
    private static final String FIND_OPEN_NOTICES = """
            select n.medicine_order_id, n.priority, n.created_at
            from ipd.order_notice n
            where n.tenant_id = :tenantId and n.admission_id = :admissionId and n.acknowledged_at is null
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
    public Optional<DoseRow> findById(UUID tenantId, UUID doseId) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", doseId)
                .query((rs, rowNum) -> map(rs)).optional();
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
    public Map<UUID, Instant> findLastGiven(UUID tenantId, UUID admissionId) {
        Map<UUID, Instant> last = new HashMap<>();
        jdbc.sql(FIND_LAST_GIVEN).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, rowNum) -> {
                    last.put(rs.getObject("order_id", UUID.class),
                            rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
                    return 1;
                }).list();
        return last;
    }

    @Override
    public List<GivenAt> findGivenSince(UUID tenantId, UUID admissionId, Instant since) {
        return jdbc.sql(FIND_GIVEN_SINCE).param("tenantId", tenantId).param("admissionId", admissionId)
                .param("since", utc(since))
                .query((rs, rowNum) -> new GivenAt(rs.getObject("order_id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public boolean hasLiveGiven(UUID tenantId, UUID orderId) {
        return Boolean.TRUE.equals(jdbc.sql(HAS_LIVE_GIVEN).param("tenantId", tenantId).param("orderId", orderId)
                .query(Boolean.class).single());
    }

    @Override
    public Stored insert(UUID tenantId, NewDose dose) {
        return jdbc.sql(INSERT).param("admissionId", dose.admissionId()).param("orderId", dose.orderId())
                .param("clientKey", dose.clientKey()).param("slotAt", utc(dose.slotAt()))
                .param("outcome", dose.outcome()).param("reasonKey", dose.reasonKey(), Types.VARCHAR)
                .param("recordedBy", dose.recordedBy()).param("secondCheckBy", dose.secondCheckBy(), Types.OTHER)
                .param("highAlert", dose.highAlert()).param("identityScan", dose.identityScan(), Types.VARCHAR)
                .param("identityConfirmed", dose.identityConfirmed(), Types.BOOLEAN)
                .param("attempt", dose.attempt()).param("supersedesDoseId", dose.supersedesDoseId(), Types.OTHER)
                .param("orderVersion", dose.orderVersion()).param("doseKind", dose.doseKind())
                .param("scanMethod", dose.scanMethod(), Types.VARCHAR)
                .param("overrideReasonKey", dose.overrideReasonKey(), Types.VARCHAR)
                .param("overrideNote", dose.overrideNote(), Types.VARCHAR)
                .param("medicineScan", dose.medicineScan(), Types.VARCHAR)
                .param("beforeVerification", dose.beforeVerification())
                .param("notGivenNote", dose.notGivenNote(), Types.VARCHAR)
                .query((rs, rowNum) -> new Stored(rs.getObject("id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("undo_until", OffsetDateTime.class))))
                .single();
    }

    @Override
    public void insertScanStop(UUID tenantId, UUID admissionId, UUID orderId, Instant slotAt, String checkKind,
                               UUID actor) {
        jdbc.sql(INSERT_SCAN_STOP).param("admissionId", admissionId).param("orderId", orderId)
                .param("slotAt", utc(slotAt)).param("checkKind", checkKind).param("actor", actor).update();
    }

    @Override
    public boolean hasScanStopWithin15Minutes(UUID tenantId, UUID orderId, UUID actor, Instant slotAtOrNull) {
        JdbcClient.StatementSpec spec = jdbc.sql(slotAtOrNull == null ? HAS_SCAN_STOP_ORDER : HAS_SCAN_STOP_SLOT)
                .param("tenantId", tenantId).param("orderId", orderId).param("actor", actor);
        if (slotAtOrNull != null) {
            spec = spec.param("slotAt", utc(slotAtOrNull));
        }
        return Boolean.TRUE.equals(spec.query(Boolean.class).single());
    }

    @Override
    public Instant insertCorrection(UUID tenantId, NewCorrection correction) {
        return jdbc.sql(INSERT_CORRECTION).param("doseId", correction.doseId()).param("kind", correction.kind())
                .param("reasonKey", correction.reasonKey(), Types.VARCHAR)
                .param("correctedBy", correction.correctedBy()).param("clientKey", correction.clientKey())
                .query((rs, rowNum) -> rs.getObject("corrected_at", OffsetDateTime.class).toInstant()).single();
    }

    @Override
    public Optional<CorrectionRow> findCorrectionByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(FIND_CORRECTION_BY_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query((rs, rowNum) -> new CorrectionRow(rs.getObject("dose_id", UUID.class), rs.getString("kind"),
                        rs.getString("reason_key"), rs.getObject("corrected_by", UUID.class),
                        rs.getObject("corrected_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("client_key", UUID.class)))
                .optional();
    }

    @Override
    public List<MarHead> findMarHeads(UUID tenantId, UUID admissionId, Instant from, Instant to) {
        return jdbc.sql(FIND_MAR_HEADS).param("tenantId", tenantId).param("admissionId", admissionId)
                .param("from", utc(from)).param("to", utc(to))
                .query((rs, rowNum) -> new MarHead(rs.getObject("id", UUID.class), rs.getString("drug_key"),
                        rs.getString("status"), rs.getObject("ordered_by", UUID.class),
                        rs.getObject("ordered_at", OffsetDateTime.class).toInstant(), rs.getInt("current_version"),
                        rs.getObject("stopped_by", UUID.class),
                        instant(rs.getObject("stopped_at", OffsetDateTime.class))))
                .list();
    }

    @Override
    public List<OpenNotice> findOpenNotices(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_OPEN_NOTICES).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, rowNum) -> new OpenNotice(rs.getObject("medicine_order_id", UUID.class),
                        rs.getString("priority"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static DoseRow map(ResultSet rs) throws SQLException {
        CorrectionRow correction = null;
        String kind = rs.getString("c_kind");
        UUID doseId = rs.getObject("id", UUID.class);
        if (kind != null) {
            correction = new CorrectionRow(doseId, kind, rs.getString("c_reason_key"),
                    rs.getObject("c_corrected_by", UUID.class),
                    rs.getObject("c_corrected_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("c_client_key", UUID.class));
        }
        return new DoseRow(doseId, rs.getObject("admission_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("client_key", UUID.class), rs.getObject("slot_at", OffsetDateTime.class).toInstant(),
                rs.getString("outcome"), rs.getString("reason_key"), rs.getObject("recorded_by", UUID.class),
                rs.getObject("second_check_by", UUID.class), rs.getBoolean("high_alert"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(), rs.getInt("attempt"),
                rs.getInt("order_version"), rs.getString("dose_kind"), rs.getString("scan_method"),
                rs.getString("override_reason_key"), rs.getBoolean("before_verification"),
                instant(rs.getObject("undo_until", OffsetDateTime.class)), correction);
    }
}
