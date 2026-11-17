package com.sahaj.ipd.application;

import com.sahaj.ipd.application.OrderNotices.AckState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-76-3 (ADR-027 D5; docs/database/SAH-76.md section 1.6): {@code ipd.order_notice}, the nurse's order inbox. The
 * insert names the six business columns; an acknowledgement updates {@code acknowledged_by} and
 * {@code acknowledged_via} and the guard trigger stamps {@code acknowledged_at}. Every method is scoped to the tenant.
 * Reads that show a notice join the order head (drug, status, stop reason), the admission (patient, bed) and whether a
 * live dose exists for the noticed version, so a list needs no read per row. Nothing here holds clinical text except
 * what the order head already holds ({@code stopReason}); never log it.
 */
public interface OrderNoticeRepository {

    /** Writes a notice (the caller holds the admission row lock); returns its id and creation time. */
    Created insert(UUID tenantId, UUID admissionId, UUID orderId, int orderVersion, String kind, String priority,
                   UUID createdBy);

    Optional<NoticeRow> findById(UUID tenantId, UUID noticeId);

    /** Acknowledges the open notice (via TAP); false when it was already acknowledged. */
    boolean acknowledge(UUID tenantId, UUID noticeId, UUID by);

    /** Acknowledges every open notice of the order (via DOSE); returns how many were closed. */
    int acknowledgeByOrder(UUID tenantId, UUID orderId, UUID by);

    /**
     * The notices of one admission: every open one (STAT first, then oldest first), then at most {@code ackedLimit}
     * acknowledged ones (newest first).
     */
    List<NoticeRow> findForAdmission(UUID tenantId, UUID admissionId, int ackedLimit);

    /**
     * Open notices of ADMITTED admissions, STAT first then oldest first ({@code created_at}, then id), strictly after
     * {@code after}; at most {@code limit}. {@code admissionIds} null reads every admission of the clinic.
     */
    List<NoticeRow> findOpenPage(UUID tenantId, Collection<UUID> admissionIds, Cursor after, int limit);

    /** Open notice count per admission (any status of the admission). */
    Map<UUID, Integer> countOpen(UUID tenantId, Collection<UUID> admissionIds);

    /**
     * The badge figures of the ADMITTED admissions among {@code admissionIds} (null = all of the clinic): all open
     * notices, STAT ones, and those created at or before {@code overdueCut} (STAT ones at or before
     * {@code overdueStatCut}); plus the earliest slot of an open STAT line (the ONCE version start of an open STAT
     * notice whose order is live and has no live dose yet). Admissions with no open notice are absent.
     */
    Map<UUID, Stats> stats(UUID tenantId, Collection<UUID> admissionIds, Instant overdueCut, Instant overdueStatCut);

    /**
     * SAH-76 review #6: one md5 hex text over the sorted ids of the open notices of ADMITTED admissions among {@code
     * admissionIds} (null = all), computed in the database so the 30-second poll moves no id list. md5 of the empty text
     * when none is open ({@link #EMPTY_DIGEST}). It changes whenever the open set gains or loses a notice.
     */
    String openDigest(UUID tenantId, Collection<UUID> admissionIds);

    /** The digest of no open notice: md5 of the empty text, what the database answers for an empty set. */
    String EMPTY_DIGEST = "d41d8cd98f00b204e9800998ecf8427e";

    /** Per order of the admission with any notice: an open one, and who acknowledged the latest NEW or CHANGED. */
    Map<UUID, AckState> findAckStates(UUID tenantId, UUID admissionId);

    /** The keyset position of a row of the cross-patient page: the STAT rank (0 STAT, 1 other), the time and the id. */
    record Cursor(int rank, Instant createdAt, UUID id) {
    }

    record Created(UUID id, Instant createdAt) {
    }

    record Stats(int count, int stat, int overdue, Instant earliestStatSlot) {
    }

    /** One notice with the facts a view needs. */
    record NoticeRow(UUID id, UUID admissionId, UUID orderId, int orderVersion, String kind, String priority,
                     UUID createdBy, Instant createdAt, UUID acknowledgedBy, Instant acknowledgedAt, String via,
                     String drugKey, String orderStatus, int orderCurrentVersion, String stopReason,
                     boolean hasLiveDose, UUID patientId, String bedCode, String admissionStatus) {

        public boolean open() {
            return acknowledgedAt == null;
        }

        public Cursor cursor() {
            return new Cursor("STAT".equals(priority) ? 0 : 1, createdAt, id);
        }

        @Override
        public String toString() {
            return "NoticeRow[id=" + id + ", kind=" + kind + "]";
        }
    }
}
