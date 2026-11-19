package com.sahaj.pharmacy.domain;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * First expiry, first out (ADR-029 P4, rule 17): from the AVAILABLE and unexpired rows of one product in one store, the
 * earliest expiry first, then the earliest received, then the batch id. The pick is worked out in memory from rows the
 * caller has locked; nothing here touches the database. An expired batch is never picked.
 */
public final class Fefo {

    private Fefo() {
    }

    /** One AVAILABLE row of a batch. */
    public record Source(UUID batchId, LocalDate expiryDate, Instant firstReceivedAt, int qty) {
    }

    /** A quantity taken from one batch. */
    public record Pick(UUID batchId, int quantity) {
    }

    /** What a pick found: the batches in FEFO order, what is usable, and what was short. */
    public record Result(List<Pick> picks, int available, int shortBy) {
    }

    /** The usable rows in pick order: unexpired on {@code today} (IST), quantity above 0. */
    public static List<Source> order(List<Source> rows, LocalDate today) {
        List<Source> usable = new ArrayList<>();
        for (Source row : rows) {
            if (row.qty() > 0 && !row.expiryDate().isBefore(today)) {
                usable.add(row);
            }
        }
        usable.sort(Comparator.comparing(Source::expiryDate).thenComparing(Source::firstReceivedAt)
                .thenComparing(Source::batchId));
        return usable;
    }

    /** Takes {@code quantity} from the usable rows in FEFO order, splitting across batches. */
    public static Result pick(List<Source> rows, LocalDate today, int quantity) {
        List<Source> ordered = order(rows, today);
        int available = ordered.stream().mapToInt(Source::qty).sum();
        List<Pick> picks = new ArrayList<>();
        int left = quantity;
        for (Source row : ordered) {
            if (left <= 0) {
                break;
            }
            int take = Math.min(left, row.qty());
            picks.add(new Pick(row.batchId(), take));
            left -= take;
        }
        return new Result(picks, available, Math.max(0, left));
    }
}
