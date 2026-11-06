package com.sahaj.ipd.application;

import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.NotGivenReason;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-32 the shape of one recorded dose on screen (contract {@code DoseRow}): shared by the MAR slots, the answer of
 * the dose endpoint and the EMR "Last dose" line. Staff names come from one batch per answer.
 */
final class DoseViews {

    private DoseViews() {
    }

    /** Adds the people a dose names (who recorded it and the second nurse) to the set to look up. */
    static void collectStaff(DoseRow dose, Set<UUID> staff) {
        staff.add(dose.recordedBy());
        if (dose.secondCheckBy() != null) {
            staff.add(dose.secondCheckBy());
        }
    }

    static DoseView of(DoseRow dose, ActorNames names) {
        KeyLabel reason = null;
        if (dose.reasonKey() != null) {
            NotGivenReason r = NotGivenReason.ofKey(dose.reasonKey())
                    .orElseThrow(() -> new IllegalStateException("A dose has an unknown reason"));
            reason = new KeyLabel(r.key(), r.label());
        }
        return new DoseView(dose.id(), dose.slotAt(), dose.outcome(), reason, dose.recordedAt(),
                names.of(dose.recordedBy()), dose.secondCheckBy() == null ? null : names.of(dose.secondCheckBy()));
    }
}
