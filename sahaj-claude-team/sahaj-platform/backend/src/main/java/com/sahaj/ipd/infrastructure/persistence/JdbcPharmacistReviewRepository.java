package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.PharmacistReviewRepository;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.pharmacist_review} (SAH-76, docs/database/SAH-76.md section 1.6): the insert names the three business
 * columns, the close updates {@code closed_by, outcome} and the guard trigger stamps {@code closed_at}. The list of
 * open items uses the partial index on open items (oldest first, keyset). Fixed statements only.
 */
@Repository
public class JdbcPharmacistReviewRepository implements PharmacistReviewRepository {

    private static final String COLUMNS = """
            r.id, r.order_id, r.order_version, r.dose_id, r.created_at, r.closed_at, r.closed_by, r.outcome
            """;
    private static final String INSERT = """
            insert into ipd.pharmacist_review (order_id, order_version, dose_id)
            values (:orderId, :orderVersion, :doseId)
            on conflict (tenant_id, dose_id) do nothing
            """;
    private static final String FIND_BY_ID = "select " + COLUMNS + """
            from ipd.pharmacist_review r
            where r.tenant_id = :tenantId and r.id = :id
            """;
    private static final String FIND_BY_DOSE = "select " + COLUMNS + """
            from ipd.pharmacist_review r
            where r.tenant_id = :tenantId and r.dose_id = :doseId
            """;
    private static final String OPEN_HEAD = "select " + COLUMNS + """
            , o.admission_id, a.patient_id, b.code as bed_code, o.drug_key, o.status as order_status, o.ordered_by,
              d.recorded_at as given_at, d.recorded_by as given_by
            from ipd.pharmacist_review r
            join ipd.medicine_order o on o.tenant_id = r.tenant_id and o.id = r.order_id
            join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            join ipd.dose_administration d on d.tenant_id = r.tenant_id and d.id = r.dose_id
            where r.tenant_id = :tenantId and r.closed_at is null
            """;
    private static final String OPEN_AFTER = " and (r.created_at, r.id) > (:afterAt, :afterId)\n";
    private static final String OPEN_TAIL = " order by r.created_at, r.id limit :limit";
    private static final String COUNT_OPEN = """
            select count(*) from ipd.pharmacist_review r where r.tenant_id = :tenantId and r.closed_at is null
            """;
    private static final String OPEN_IDS = """
            select r.id from ipd.pharmacist_review r where r.tenant_id = :tenantId and r.closed_at is null
            """;
    private static final String CLOSE_BY_DOSE = """
            update ipd.pharmacist_review
            set closed_by = :user, outcome = :outcome
            where tenant_id = :tenantId and dose_id = :doseId and closed_at is null
            """;
    private static final String CLOSE_BY_ORDER = """
            update ipd.pharmacist_review
            set closed_by = :user, outcome = :outcome
            where tenant_id = :tenantId and order_id = :orderId and closed_at is null
            """;

    private final JdbcClient jdbc;

    public JdbcPharmacistReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(UUID tenantId, UUID orderId, int orderVersion, UUID doseId) {
        return jdbc.sql(INSERT).param("orderId", orderId).param("orderVersion", orderVersion).param("doseId", doseId)
                .update() == 1;
    }

    @Override
    public Optional<ReviewRow> findById(UUID tenantId, UUID reviewId) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", reviewId)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public Optional<ReviewRow> findByDose(UUID tenantId, UUID doseId) {
        return jdbc.sql(FIND_BY_DOSE).param("tenantId", tenantId).param("doseId", doseId)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public List<ListRow> findOpen(UUID tenantId, Cursor after, int limit) {
        String sql = OPEN_HEAD + (after == null ? "" : OPEN_AFTER) + OPEN_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit);
        if (after != null) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
                    .param("afterId", after.id());
        }
        return spec.query((rs, rowNum) -> new ListRow(map(rs), rs.getObject("admission_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("bed_code"), rs.getString("drug_key"),
                rs.getString("order_status"), rs.getObject("ordered_by", UUID.class), instant(rs, "given_at"),
                rs.getObject("given_by", UUID.class))).list();
    }

    @Override
    public int countOpen(UUID tenantId) {
        return jdbc.sql(COUNT_OPEN).param("tenantId", tenantId).query(Integer.class).single();
    }

    @Override
    public List<UUID> findOpenIds(UUID tenantId) {
        return jdbc.sql(OPEN_IDS).param("tenantId", tenantId).query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }

    @Override
    public boolean closeByDose(UUID tenantId, UUID doseId, UUID closedBy, ReviewOutcome outcome) {
        return jdbc.sql(CLOSE_BY_DOSE).param("tenantId", tenantId).param("doseId", doseId).param("user", closedBy)
                .param("outcome", outcome.name()).update() == 1;
    }

    @Override
    public int closeByOrder(UUID tenantId, UUID orderId, UUID closedBy, ReviewOutcome outcome) {
        return jdbc.sql(CLOSE_BY_ORDER).param("tenantId", tenantId).param("orderId", orderId)
                .param("user", closedBy).param("outcome", outcome.name()).update();
    }

    private static ReviewRow map(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome");
        return new ReviewRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getInt("order_version"), rs.getObject("dose_id", UUID.class), instant(rs, "created_at"),
                instant(rs, "closed_at"), rs.getObject("closed_by", UUID.class),
                outcome == null ? null : ReviewOutcome.valueOf(outcome));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
