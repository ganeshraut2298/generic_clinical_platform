package com.sahaj.ipd.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Release audit G-14: decides whether the "order skipped" warning of {@link MedicineRoundReader} is written again. The
 * Medicine round is read on every poll (every 60 seconds for each open tab), so one damaged order would repeat the
 * same warning all day. A (clinic, order) pair is let through at most once per {@link #QUIET_PERIOD}.
 *
 * <p>The memory is a small bounded map (at most {@link #MAX_ENTRIES}); when it is full the oldest entry is dropped, which
 * at worst repeats one warning early. It holds two UUIDs and a time per entry, nothing else.
 */
final class SkippedOrderWarnings {

    static final Duration QUIET_PERIOD = Duration.ofMinutes(10);
    static final int MAX_ENTRIES = 1_000;

    private record Key(UUID tenantId, UUID orderId) {
    }

    private final Clock clock;
    private final Map<Key, Instant> lastWarned = new LinkedHashMap<>();

    SkippedOrderWarnings(Clock clock) {
        this.clock = clock;
    }

    /** True when the warning for this order should be written now (and records that it was). */
    synchronized boolean shouldWarn(UUID tenantId, UUID orderId) {
        Instant now = clock.instant();
        Key key = new Key(tenantId, orderId);
        Instant last = lastWarned.get(key);
        if (last != null && now.isBefore(last.plus(QUIET_PERIOD))) {
            return false;
        }
        // Remove first so the refreshed entry moves to the end: the head is always the oldest warning.
        lastWarned.remove(key);
        lastWarned.put(key, now);
        if (lastWarned.size() > MAX_ENTRIES) {
            var oldest = lastWarned.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }
}
