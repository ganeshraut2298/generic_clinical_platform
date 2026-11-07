package com.sahaj.ipd.domain;

import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.Stage;
import com.sahaj.ipd.domain.DoseSlots.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SAH-35 the Medicine round (ADR-012 D1 to D3): every dose slot of today that is DUE or LATE for the ACTIVE orders of
 * the ADMITTED admissions of a clinic. Pure Java: no Spring and no I/O. There is no second rule: every slot state comes
 * from {@link DoseSlots#slots} (the rule of the MAR tab and of the dose endpoint), so at one {@code asOf}
 * {@code due + late} equals the sum of the MAR tab counts of the admitted patients.
 *
 * <p>Order of the rows (ADR-012 D2): LATE before DUE, then the earlier slot first, then bed code, then medicine name,
 * then order id. At most {@value #MAX_ROWS} rows are returned; the counts and the change-token lines always cover every
 * listed slot. Nothing here holds a patient name.
 */
public final class MedicineRound {

    /** The cap of the panel (ADR-012 D2). */
    public static final int MAX_ROWS = 200;

    private static final Result EMPTY = new Result(List.of(), false, 0, 0, List.of());

    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> e.state() == State.LATE ? 0 : 1)
            .thenComparing(Entry::slotAt)
            .thenComparing(Entry::bedCode)
            .thenComparing(Entry::drugName)
            .thenComparing(Entry::orderId);

    private MedicineRound() {
    }

    /** An ACTIVE order of an ADMITTED admission, with what the row shows. Names of people are not part of it. */
    public record RoundOrder(UUID orderId, UUID admissionId, UUID patientId, String bedCode, String drugName,
                             String highAlert, Frequency frequency, Instant verifiedAt) {

        @Override
        public String toString() {
            return "RoundOrder[orderId=" + orderId + "]";
        }
    }

    /** A dose record of today: the outcome of one order and slot. */
    public record Recorded(UUID orderId, Instant slotAt, DoseOutcome outcome) {
    }

    /** One listed slot: DUE or LATE, no dose record. {@code lateHours} is set for LATE only (at least 1). */
    public record Entry(UUID admissionId, UUID orderId, UUID patientId, Instant slotAt, State state, Integer lateHours,
                        String drugName, String highAlert, String bedCode) {

        @Override
        public String toString() {
            return "Entry[orderId=" + orderId + ", state=" + state + "]";
        }
    }

    /**
     * The answer. {@code rows} are the first {@link #MAX_ROWS} in order; {@code due} and {@code late} count every
     * listed slot; {@code tokenLines} hold one line per listed slot (all of them) for the change token.
     */
    public record Result(List<Entry> rows, boolean truncated, int due, int late, List<String> tokenLines) {

        public Result {
            rows = List.copyOf(rows);
            tokenLines = List.copyOf(tokenLines);
        }

        @Override
        public String toString() {
            return "Result[rows=" + rows.size() + ", due=" + due + ", late=" + late + "]";
        }
    }

    /** No order, no slot: the SAH-33 token is unchanged by it. */
    public static Result empty() {
        return EMPTY;
    }

    /**
     * Computes the round.
     *
     * @param orders   ACTIVE orders of ADMITTED admissions
     * @param recorded today's dose records of those admissions (any order; matched by order id and slot instant)
     * @param asOf     the database clock of the answer
     */
    public static Result compute(Collection<RoundOrder> orders, Collection<Recorded> recorded, Instant asOf) {
        if (orders.isEmpty()) {
            return EMPTY;
        }
        Map<UUID, Map<Instant, DoseOutcome>> byOrder = new HashMap<>();
        for (Recorded r : recorded) {
            byOrder.computeIfAbsent(r.orderId(), k -> new HashMap<>()).put(r.slotAt(), r.outcome());
        }
        List<Entry> listed = new ArrayList<>();
        for (RoundOrder order : orders) {
            Map<Instant, DoseOutcome> mine = byOrder.getOrDefault(order.orderId(), Map.of());
            for (Slot slot : DoseSlots.slots(order.frequency(), Stage.ACTIVE, order.verifiedAt(), mine, true, asOf)) {
                if (slot.state().actionable()) {
                    listed.add(new Entry(order.admissionId(), order.orderId(), order.patientId(), slot.slotAt(),
                            slot.state(), slot.lateHours(), order.drugName(), order.highAlert(), order.bedCode()));
                }
            }
        }
        if (listed.isEmpty()) {
            return EMPTY;
        }
        listed.sort(ORDER);
        Map<State, Integer> counts = new EnumMap<>(State.class);
        List<String> lines = new ArrayList<>();
        for (Entry e : listed) {
            counts.merge(e.state(), 1, Integer::sum);
            lines.add(tokenLine(e));
        }
        boolean truncated = listed.size() > MAX_ROWS;
        List<Entry> rows = truncated ? listed.subList(0, MAX_ROWS) : listed;
        return new Result(rows, truncated, counts.getOrDefault(State.DUE, 0), counts.getOrDefault(State.LATE, 0),
                lines);
    }

    /**
     * ADR-012 D3: {@code dose:<order id lower case>:<slotAt as epoch seconds>:<LATE|DUE>}. No name, drug or text. The
     * prefix cannot collide with an escalation line (a UUID never contains an {@code o} or an {@code s}).
     */
    static String tokenLine(Entry e) {
        return "dose:" + e.orderId().toString().toLowerCase(Locale.ROOT) + ":" + e.slotAt().getEpochSecond() + ":"
                + e.state().name();
    }
}
