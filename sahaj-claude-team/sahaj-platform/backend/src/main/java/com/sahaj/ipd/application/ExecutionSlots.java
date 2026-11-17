package com.sahaj.ipd.application;

import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.DoseSlots.LiveDose;
import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Instant;
import java.util.List;

/**
 * SAH-76-5 (ADR-027 D2; work packages "conflicts to watch"): the numbers of the execution line under an order of the EMR
 * sheet, the next due time and the late count. It owns no slot rule: it asks {@link DoseSlots#slots}, the single slot
 * function, and only picks the numbers from the answer.
 */
final class ExecutionSlots {

    private ExecutionSlots() {
    }

    /** The next DUE or LATER slot of today (null when none) and the number of LATE slots of today. */
    record Result(Instant nextDueAt, int lateCount) {
    }

    static Instant dayStart(Instant asOf) {
        return DoseSlots.dayStart(asOf);
    }

    static Instant dayEnd(Instant asOf) {
        return DoseSlots.dayEnd(asOf);
    }

    /**
     * Only slots of the current version count; a PRN order has no slots (null, 0); an order that is waiting, queried,
     * on hold or stopped has none that is DUE, LATER or LATE, so it answers (null, 0) too.
     *
     * @param todayDoses today's dose records of this order, corrected ones too: only live scheduled doses make a slot
     *                   recorded, exactly as on the MAR ({@link MarAssembler#liveScheduled})
     */
    static Result of(OrderRow order, OrderTimeline timeline, List<DoseRow> todayDoses, boolean admitted,
                     EffectiveNursingSettings settings, Instant asOf) {
        List<LiveDose> live = MarAssembler.liveScheduled(todayDoses);
        Instant next = null;
        int late = 0;
        for (Slot slot : DoseSlots.slots(timeline, order.stoppedBy(), live, admitted, settings, asOf)) {
            if (!slot.current()) {
                continue;
            }
            if (slot.state() == State.LATE) {
                late++;
            } else if (next == null && (slot.state() == State.DUE || slot.state() == State.LATER)) {
                next = slot.slotAt();
            }
        }
        return new Result(next, late);
    }
}
