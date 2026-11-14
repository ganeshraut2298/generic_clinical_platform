package com.sahaj.opd.application.emr.specialty;

import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.finish.FinishModels.CourseOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBlocker;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishChoices;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The extension point of "Complete and send to billing" for the specialty screens (ADR-023 D9, ADR-025 D7). One
 * implementation per profile is called by the sign step ({@code EmrSignService}, the only caller: a convention test checks
 * it) <b>inside the sign transaction</b>, with the record, its values and the idempotency key of the sign request, so that
 * what the specialty adds at the end of a visit is written with the signature or not at all:
 * <ul>
 *   <li>dental: plan rows done today become visit bill lines through {@code EmrVisitChargePort.addPricedLines} (source key
 *       {@code plan:<rowId>:<sittingNo>}); the next sitting becomes a planned course sitting and an appointment;</li>
 *   <li>physiotherapy: the next session and the appointment in the chosen slot, or "package complete";</li>
 *   <li>oncology: the follow-up falls on the next cycle date when no interval was chosen;</li>
 *   <li>surgery, og, paeds, gm: nothing is written (previews only).</li>
 * </ul>
 * An implementation must not log or audit a clinical value (ids, keys and counts only) and must not call out of the module
 * except through an {@code api} package port. Every default does nothing, so a profile implements only what it needs.
 */
public interface SpecialtyFinishContributor {

    /** The profile served: gm, dental, og, paeds, physio, onco or surgery. */
    String profile();

    /**
     * The tools of one finish: who signs, the record and its values, the visit row, the choices of the dialog and the
     * idempotency key of the sign. Clinical values inside: {@code toString} shows ids only.
     */
    record FinishContext(UUID tenantId, UUID actorUserId, UUID membershipId, String role, RecordRow record,
                         EmrRecordDocument document, VisitRow visit, FinishChoices choices, String idempotencyKey,
                         LocalDate today, Instant now) {

        public LocalDate visitDay() {
            return visit == null ? today : visit.createdAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
        }

        @Override
        public String toString() {
            return "FinishContext[recordId=" + record.id() + "]";
        }
    }

    /** What the sign did for the profile: the course facts, the bill lines added, a follow-up date it decided. */
    record ProfileResult(CourseOutcome course, int billLines, long billTotalPaise, LocalDate followUpDate,
                         UUID appointmentId) {

        public static ProfileResult none() {
            return new ProfileResult(null, 0, 0, null, null);
        }
    }

    /** What stops the finish for this profile (for example "Pick the treatments given today first"); read only. */
    default List<FinishBlocker> blockers(FinishContext ctx) {
        return List.of();
    }

    /**
     * The profile's block of "Check before completing", the {@link ProfileEffects} record of this profile (opd.yaml 0.6.1);
     * read only (writes nothing). Null when the contributor has no block.
     */
    default ProfileEffects preview(FinishContext ctx) {
        return null;
    }

    /**
     * The prints the profile adds after the sign, in print order (oncology: the cycle record and the calendar; surgery: the
     * admission letter and pre-op sheet once booked); read only and cheaper than {@link #preview} where the block needs
     * reads the prints do not. Computed from the record each time, never stored.
     */
    default List<FinishPrint> prints(FinishContext ctx) {
        return List.of();
    }

    /**
     * The profile's writes, once per sign, inside its transaction (an exception rolls the whole sign back). Every write is
     * idempotent: a retry makes the same writes once.
     */
    default ProfileResult apply(FinishContext ctx) {
        return ProfileResult.none();
    }
}
