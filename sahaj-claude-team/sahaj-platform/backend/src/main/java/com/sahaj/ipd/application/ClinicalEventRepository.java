package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ipd.clinical_event} (SAH-30, SAH-33): the append-only clinical event log. The text is a fixed template of
 * numbers and fixed words only: never a patient name, a vital value or a note. The database sets the id, the order
 * number, the clinic and the time. There is no update and no delete; the log is read newest first.
 */
public interface ClinicalEventRepository {

    String KIND_INFO = "info";
    String KIND_ESC = "esc";
    /** SAH-31 (ADR-009 D7): the EMR opened, a blocked order, an order, a stop, a verification or a rejection. */
    String KIND_VIEW = "view";
    String KIND_DENY = "deny";
    String KIND_ORDER = "order";
    String KIND_STOP = "stop";
    String KIND_VERIFY = "verify";
    /** SAH-32 (ADR-011 D9): a dose given or recorded as not given. A wristband mismatch is {@link #KIND_DENY}. */
    String KIND_GIVE = "give";

    /** SAH-34: an emergency access was opened or reviewed (kind bg, written with the grant id). */
    String KIND_BG = "bg";

    void insert(UUID tenantId, UUID admissionId, String kind, String text, UUID actor);

    /** SAH-34: a `bg` entry of an emergency access grant ({@code grant_id} set). The text holds no name. */
    void insertForGrant(UUID tenantId, UUID admissionId, String text, UUID actor, UUID grantId);

    /**
     * SAH-33: one page of the log, newest first ({@code at} descending, then {@code seq} descending), strictly older
     * than {@code after} when it is set. {@code onlyForNurse} null reads every entry of the clinic; a user id reads
     * that user's own entries and the entries of ADMITTED admissions. Each row carries the admission's patient id and
     * current (or last) bed code from one join, so the caller needs no query per row. Returns at most {@code limit}
     * rows; the caller asks for one more than it shows to know whether older entries exist.
     */
    List<EventRow> findPage(UUID tenantId, UUID onlyForNurse, Position after, int limit);

    /**
     * SAH-34 (AC 11): the page of a scoped NURSE: her own entries, and the entries of ADMITTED admissions whose bed is in
     * {@code wardIds} or whose id is in {@code grantAdmissionIds} (her live emergency access). Both sets may be empty.
     */
    List<EventRow> findPageInScope(UUID tenantId, UUID nurse, Set<UUID> wardIds, Set<UUID> grantAdmissionIds,
                                   Position after, int limit);

    /** The position of an entry in the log: the keyset of the page cursor. */
    record Position(Instant at, long seq) {
    }

    /**
     * {@code grantUserId} (SAH-34) is the user who opened the emergency access grant the entry belongs to; null for an
     * entry with no grant. The trail reads the grantee name from it at display time.
     */
    record EventRow(UUID id, long seq, String kind, String text, Instant at, UUID actor, UUID admissionId,
                    UUID patientId, String bedCode, UUID grantUserId) {

        public EventRow(UUID id, long seq, String kind, String text, Instant at, UUID actor, UUID admissionId,
                        UUID patientId, String bedCode) {
            this(id, seq, kind, text, at, actor, admissionId, patientId, bedCode, null);
        }

        public Position position() {
            return new Position(at, seq);
        }
    }
}
