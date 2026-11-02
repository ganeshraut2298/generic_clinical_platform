package com.sahaj.platform.infrastructure.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory sliding-window limiter: at most {@code maxRequests} per key inside {@code window}.
 * Phase 1 runs a single instance, so memory is enough (SAH-2 technical design); a second instance
 * would need a shared store.
 *
 * <p>A visitor the limiter has never seen is never refused outright just because the map is full:
 * the numbers chosen here are sized so that bound is reached only under sustained abuse, and even
 * then room is made for a new key by evicting the single oldest one (bounded: one entry, not a
 * scan-and-rebuild of the whole map), so a flood of new addresses thins out old ones instead of
 * locking the door. Three further limits keep that eviction itself from being a cheap way to push
 * everyone else out:
 *
 * <ul>
 *   <li>{@code maxTrackedKeys} (default 100,000) bounds memory outright.
 *   <li>A sweep of expired keys - the thing that would normally make room - runs at most once per
 *       {@code window} ({@link #sweep}), so a burst of new keys cannot force a full O(n) scan on
 *       every single admission.
 *   <li>At most {@value #MAX_V6_SUBKEYS_PER_BLOCK} distinct IPv6 {@code /64} keys are admitted per
 *       {@code /48} network (one subscriber normally owns a single {@code /64}; a block that large
 *       rotating through addresses is treated as one actor).
 *   <li>At most {@value #GLOBAL_HOURLY_NEW_KEY_CAP} brand-new keys are admitted across the whole
 *       limiter per rolling hour, regardless of configured window: a global write cap so no single
 *       flood of distinct addresses can force constant eviction of everyone else's allowance.
 * </ul>
 */
public class SlidingWindowRateLimiter {

    /** The answer to one {@link #tryAcquire} call. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /** Distinct IPv6 /64 keys admitted per /48 network before further new keys from it are refused. */
    static final int MAX_V6_SUBKEYS_PER_BLOCK = 256;

    /** Brand-new keys admitted across the whole limiter per rolling hour. */
    static final int GLOBAL_HOURLY_NEW_KEY_CAP = 20_000;

    private static final long GLOBAL_WINDOW_MILLIS = Duration.ofHours(1).toMillis();

    /** Matches a client key ending in a 16-hex-digit IPv6 /64 block, capturing its /48 prefix. */
    private static final Pattern V6_KEY = Pattern.compile("^(.*v6:)([0-9a-f]{12})[0-9a-f]{4}$");

    private final int maxRequests;
    private final Duration window;
    private final int maxTrackedKeys;
    private final Clock clock;

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> v6BlockCounts = new ConcurrentHashMap<>();
    private final Queue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final Object admissionLock = new Object();

    private volatile long lastSweepMillis = Long.MIN_VALUE;
    private volatile long globalWindowStartMillis = Long.MIN_VALUE;
    private final AtomicInteger globalNewKeysThisWindow = new AtomicInteger(0);

    public SlidingWindowRateLimiter(int maxRequests, Duration window, int maxTrackedKeys, Clock clock) {
        if (maxRequests < 1 || maxTrackedKeys < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limit settings must be positive");
        }
        this.maxRequests = maxRequests;
        this.window = window;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
    }

    /** Counts one attempt for the key and says whether it may go ahead. */
    public Decision tryAcquire(String key) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        if (!hits.containsKey(key)) {
            Decision refusal = admitNewKey(key, now, windowMillis);
            if (refusal != null) {
                return refusal;
            }
        }
        Decision[] result = new Decision[1];
        hits.compute(key, (k, deque) -> {
            Deque<Long> times = deque == null ? new ArrayDeque<>() : deque;
            while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= maxRequests) {
                long waitMillis = times.peekFirst() + windowMillis - now;
                result[0] = new Decision(false, Math.max(1, (waitMillis + 999) / 1000));
            } else {
                times.addLast(now);
                result[0] = new Decision(true, 0);
            }
            return times;
        });
        return result[0];
    }

    /**
     * Makes the new key known, applying the /48 and global caps and, if the map is full, evicting
     * one entry to make room. Returns a refusal only when a cap is hit; {@code null} means the key
     * is now tracked (or became known to another thread while waiting) and {@link #tryAcquire} may
     * proceed.
     */
    private Decision admitNewKey(String key, long now, long windowMillis) {
        synchronized (admissionLock) {
            if (hits.containsKey(key)) {
                return null;
            }
            resetGlobalWindowIfNeeded(now);
            if (globalNewKeysThisWindow.get() >= GLOBAL_HOURLY_NEW_KEY_CAP) {
                return new Decision(false, window.toSeconds());
            }
            String block48 = v6Block48(key);
            if (block48 != null) {
                int countForBlock = v6BlockCounts.computeIfAbsent(block48, b -> new AtomicInteger()).get();
                if (countForBlock >= MAX_V6_SUBKEYS_PER_BLOCK) {
                    return new Decision(false, window.toSeconds());
                }
            }
            if (hits.size() >= maxTrackedKeys) {
                sweepAtMostOncePerWindow(now, windowMillis);
                if (hits.size() >= maxTrackedKeys) {
                    evictOldest();
                }
            }
            hits.put(key, new ArrayDeque<>());
            insertionOrder.add(key);
            if (block48 != null) {
                v6BlockCounts.get(block48).incrementAndGet();
            }
            globalNewKeysThisWindow.incrementAndGet();
            return null;
        }
    }

    private void resetGlobalWindowIfNeeded(long now) {
        if (globalWindowStartMillis == Long.MIN_VALUE || now - globalWindowStartMillis >= GLOBAL_WINDOW_MILLIS) {
            globalWindowStartMillis = now;
            globalNewKeysThisWindow.set(0);
        }
    }

    /** Removes keys whose most recent hit has already left the window; at most once per window. */
    private void sweepAtMostOncePerWindow(long now, long windowMillis) {
        if (lastSweepMillis != Long.MIN_VALUE && now - lastSweepMillis < windowMillis) {
            return;
        }
        lastSweepMillis = now;
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, times) -> {
                Long newest = times.peekLast();
                if (newest == null || now - newest >= windowMillis) {
                    forget(k);
                    return null;
                }
                return times;
            });
        }
    }

    /** Evicts exactly one entry (the one admitted longest ago) to make room for a new key. */
    private void evictOldest() {
        String oldest;
        while ((oldest = insertionOrder.poll()) != null) {
            if (hits.remove(oldest) != null) {
                forgetV6Block(oldest);
                return;
            }
        }
    }

    private void forget(String key) {
        forgetV6Block(key);
    }

    private void forgetV6Block(String key) {
        String block48 = v6Block48(key);
        if (block48 != null) {
            v6BlockCounts.computeIfPresent(block48, (b, count) -> {
                int remaining = count.decrementAndGet();
                return remaining <= 0 ? null : count;
            });
        }
    }

    private static String v6Block48(String key) {
        Matcher matcher = V6_KEY.matcher(key);
        return matcher.matches() ? matcher.group(1) + matcher.group(2) : null;
    }
}
