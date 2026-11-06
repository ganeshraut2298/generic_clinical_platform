package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Committed raw SQL for the dashboard and revenue ITs, because the application clock cannot move. Runs as
 * {@code sahaj_app} with the tenant bound (the same role and datasource properties the other ITs use):
 * {@code opd.visit} may be UPDATEd by that role, and {@code billing.payment} is append-only (a trigger refuses
 * UPDATE), so an old payment is INSERTed with an explicit {@code recorded_at}.
 */
final class BackdateSupport {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private BackdateSupport() {
    }

    static LocalDate istToday() {
        return LocalDate.now(IST);
    }

    /** Noon IST, {@code daysAgo} days before today: far from either midnight, so the IST day is unambiguous. */
    static Instant noon(int daysAgo) {
        return istToday().minusDays(daysAgo).atTime(12, 0).atZone(IST).toInstant();
    }

    /** Seconds elapsed since the start of the current IST day. */
    static long secondsSinceIstMidnight() {
        return Instant.now().getEpochSecond() - istToday().atStartOfDay(IST).toInstant().getEpochSecond();
    }

    /**
     * Scale factor (at most 1) that keeps a backdating of {@code maxMinutes} inside the current IST day, with a
     * 30 s margin. Without it, shortly after IST midnight a backdated visit would fall on yesterday and drop out of
     * "today". Callers multiply every offset by this factor, so relative order is preserved.
     */
    static double fitToIstDay(int maxMinutes) {
        return Math.min(1.0, Math.max(0, secondsSinceIstMidnight() - 30) / (maxMinutes * 60.0));
    }

    /** {@code minutes * factor} minutes before now. */
    static Instant ago(double minutes, double factor) {
        return Instant.now().minusMillis((long) (minutes * factor * 60_000));
    }

    private static void inTenant(DbAccess db, UUID tenantId, DbAccess.SqlWork<Void> work) throws SQLException {
        try (Connection c = db.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            work.apply(c);
            c.commit();
        }
    }

    private static void updateOne(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement u = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                u.setObject(i + 1, params[i]);
            }
            assertThat(u.executeUpdate()).as(sql).isEqualTo(1);
        }
    }

    static void setVisitCreatedAt(DbAccess db, UUID tenantId, String visitId, Instant createdAt) throws SQLException {
        inTenant(db, tenantId, c -> {
            updateOne(c, "update opd.visit set created_at = ? where id = ?::uuid", Timestamp.from(createdAt), visitId);
            return null;
        });
    }

    static void setVisitStage(DbAccess db, UUID tenantId, String visitId, String stage) throws SQLException {
        inTenant(db, tenantId, c -> {
            updateOne(c, "update opd.visit set stage = ? where id = ?::uuid", stage, visitId);
            return null;
        });
    }

    /** Appends a payment (or a refund, negative) to the visit's existing bill with a chosen {@code recorded_at}. */
    static void insertPayment(DbAccess db, UUID tenantId, UUID recordedBy, String visitId, String mode, long paise,
                              String reference, Instant recordedAt) throws SQLException {
        inTenant(db, tenantId, c -> {
            updateOne(c, "insert into billing.payment (bill_id, mode, amount_paise, reference, recorded_by, recorded_at) "
                            + "select id, ?, ?, ?, ?::uuid, ? from billing.bill where visit_id = ?::uuid",
                    mode, paise, reference, recordedBy.toString(), Timestamp.from(recordedAt), visitId);
            return null;
        });
    }

    static void setFollowUpDueDate(DbAccess db, UUID tenantId, String sourceVisitId, LocalDate dueDate)
            throws SQLException {
        inTenant(db, tenantId, c -> {
            updateOne(c, "update opd.follow_up set due_date = ?::date where source_visit_id = ?::uuid",
                    dueDate.toString(), sourceVisitId);
            return null;
        });
    }

    /** Moves the return time of the follow-up that this visit's token closed (the app role may UPDATE it). */
    static void setFollowUpReturnedAt(DbAccess db, UUID tenantId, String returnedVisitId, Instant returnedAt)
            throws SQLException {
        inTenant(db, tenantId, c -> {
            updateOne(c, "update opd.follow_up set returned_at = ? where returned_visit_id = ?::uuid",
                    Timestamp.from(returnedAt), returnedVisitId);
            return null;
        });
    }

    static long auditRows(DbAccess db, UUID tenantId) throws SQLException {
        return db.<Long>inTenant(tenantId, c -> DbAccess.count(c, "select count(*) from platform.audit_event"));
    }
}
