package com.sahaj.ipd.application;

import com.sahaj.ipd.application.DoseRepository.CorrectionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.MarViews.CorrectionView;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.DoseCorrectionRules;
import com.sahaj.ipd.domain.InErrorReason;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.ipd.domain.ScanOverrideReason;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-32 and SAH-76 the shape of one recorded dose on screen (contract {@code DoseRow}): shared by the MAR slots, the
 * answers of the dose endpoints and the EMR "Last dose" line. Staff names come from one batch per answer.
 */
final class DoseViews {

    private DoseViews() {
    }

    /**
     * Who is looking and when: the flags {@code canUndo} and {@code canMarkInError} depend on them.
     *
     * @param userId          the signed-in user
     * @param canCorrectRole  token role and membership role are both NURSE or OWNER
     * @param actionable      the admission is ADMITTED and IPD is Trial or On
     */
    record Viewer(UUID userId, boolean canCorrectRole, boolean actionable, Instant asOf) {
    }

    /** Adds the people a dose names (who recorded it, the second nurse, who corrected it) to the set to look up. */
    static void collectStaff(DoseRow dose, Set<UUID> staff) {
        staff.add(dose.recordedBy());
        if (dose.secondCheckBy() != null) {
            staff.add(dose.secondCheckBy());
        }
        if (dose.correction() != null) {
            staff.add(dose.correction().correctedBy());
        }
    }

    /** A dose with no action flags (the EMR "Last dose" line). */
    static DoseView of(DoseRow dose, ActorNames names) {
        return of(dose, names, null);
    }

    /** A dose as seen by {@code viewer}; a null viewer gives every action flag false. */
    static DoseView of(DoseRow dose, ActorNames names, Viewer viewer) {
        KeyLabel reason = null;
        if (dose.reasonKey() != null) {
            NotGivenReason r = NotGivenReason.ofKey(dose.reasonKey())
                    .orElseThrow(() -> new IllegalStateException("A dose has an unknown reason"));
            reason = new KeyLabel(r.key(), r.label());
        }
        // A dose written before SAH-76 has no scan method: a built GIVEN dose was a scan.
        String method = dose.given() ? (dose.scanMethod() == null ? "SCAN" : dose.scanMethod()) : null;
        KeyLabel override = null;
        if (dose.overrideReasonKey() != null) {
            ScanOverrideReason r = ScanOverrideReason.ofKey(dose.overrideReasonKey())
                    .orElseThrow(() -> new IllegalStateException("A dose has an unknown override reason"));
            override = new KeyLabel(r.key(), r.label());
        }
        int secondsLeft = 0;
        boolean canUndo = false;
        boolean canError = false;
        if (viewer != null) {
            boolean corrected = dose.correction() != null;
            if (dose.given() && !corrected && dose.recordedBy().equals(viewer.userId())) {
                secondsLeft = DoseCorrectionRules.undoSecondsLeft(dose.undoUntil(), viewer.asOf());
            }
            canUndo = viewer.canCorrectRole() && viewer.actionable() && secondsLeft > 0;
            canError = viewer.canCorrectRole() && viewer.actionable()
                    && DoseCorrectionRules.inError(dose.given(), corrected, dose.slotAt(), viewer.asOf()) == null;
        }
        return new DoseView(dose.id(), dose.slotAt(), dose.outcome(), reason, dose.recordedAt(),
                names.of(dose.recordedBy()), dose.secondCheckBy() == null ? null : names.of(dose.secondCheckBy()),
                dose.orderVersion(), method, override, dose.beforeVerification(), correction(dose.correction(), names),
                secondsLeft, canUndo, canError);
    }

    private static CorrectionView correction(CorrectionRow row, ActorNames names) {
        if (row == null) {
            return null;
        }
        KeyLabel reason = null;
        if (row.reasonKey() != null) {
            InErrorReason r = InErrorReason.ofKey(row.reasonKey())
                    .orElseThrow(() -> new IllegalStateException("A correction has an unknown reason"));
            reason = new KeyLabel(r.key(), r.label());
        }
        return new CorrectionView(row.kind(), reason, names.of(row.correctedBy()), row.correctedAt());
    }
}
