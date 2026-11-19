package com.sahaj.pharmacy.domain;

import java.util.List;

/**
 * The status an indent takes after a dispatch or a receipt (docs/api/pharmacy.yaml IndentStatus 0.3.5, ADR-029 P12), worked out
 * from its lines alone. Nothing here reads or writes the database. The indent moves forward only after APPROVED (migration
 * 1720), so a result is never an earlier status than the current one.
 */
public final class IndentProgress {

    /**
     * The totals of one live line, in issue units. {@code closed} is the balance closed by the Central Store; the quantity
     * still to dispatch is {@code approved - dispatched - closed}.
     */
    public record LineFigures(int approved, int dispatched, int closed, int received, int rejected, int shortQty) {

        public int remaining() {
            return Math.max(0, approved - dispatched - closed);
        }

        /** Everything dispatched has been dealt with at the receiving store (accepted, rejected or short). */
        public boolean settledAtReceiver() {
            return received + rejected + shortQty >= dispatched;
        }
    }

    private IndentProgress() {
    }

    /** After a dispatch: PARTLY_DISPATCHED while any approved quantity is still to come (a back-order), otherwise DISPATCHED. */
    public static IndentStatus afterDispatch(List<LineFigures> lines) {
        return lines.stream().anyMatch(l -> l.remaining() > 0) ? IndentStatus.PARTLY_DISPATCHED : IndentStatus.DISPATCHED;
    }

    /**
     * After a receipt: RECEIVED only when every approved quantity is dispatched (or closed) and received, rejected or short, and no
     * Schedule X line waits for a pharmacist (0.3.4). Otherwise the status stays as it is: a back-order keeps PARTLY_DISPATCHED,
     * units still in transit keep DISPATCHED. The indent never takes a PARTLY_RECEIVED status.
     */
    public static IndentStatus afterReceipt(IndentStatus current, List<LineFigures> lines, int scheduleXWaiting) {
        boolean allDispatched = lines.stream().allMatch(l -> l.remaining() == 0);
        boolean allSettled = lines.stream().allMatch(LineFigures::settledAtReceiver);
        return allDispatched && allSettled && scheduleXWaiting == 0 ? IndentStatus.RECEIVED : current;
    }

    /** The "Received · back-order open" chip: a back-order is open on a PARTLY_DISPATCHED indent (never on RECEIVED). */
    public static boolean backOrderOpen(IndentStatus status, List<Boolean> openBackOrderPerLine) {
        return status == IndentStatus.PARTLY_DISPATCHED && openBackOrderPerLine.stream().anyMatch(Boolean::booleanValue);
    }
}
