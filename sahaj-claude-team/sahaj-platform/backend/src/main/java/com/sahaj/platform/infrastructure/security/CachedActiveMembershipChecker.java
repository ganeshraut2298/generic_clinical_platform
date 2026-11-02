package com.sahaj.platform.infrastructure.security;

import com.sahaj.platform.application.ActiveMembershipQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * "Cached at most 60 seconds" (SAH-3 technical design, matching SAH-6's "ended within one
 * minute"). A plain in-memory map is enough here: the key is per (tenant, user) and the value is
 * a single boolean, so a few thousand concurrent sessions cost a trivial amount of memory, and
 * nothing here needs to survive a restart.
 */
@Component
public class CachedActiveMembershipChecker {

    static final Duration TTL = Duration.ofSeconds(60);

    private final ActiveMembershipQueryService queryService;
    private final Clock clock;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public CachedActiveMembershipChecker(ActiveMembershipQueryService queryService, Clock clock) {
        this.queryService = queryService;
        this.clock = clock;
    }

    public boolean isActive(UUID tenantId, UUID userId) {
        Instant now = clock.instant();
        Key key = new Key(tenantId, userId);
        Entry cached = cache.get(key);
        if (cached != null && cached.checkedAt().plus(TTL).isAfter(now)) {
            return cached.active();
        }
        boolean active = queryService.isActive(tenantId, userId);
        cache.put(key, new Entry(active, now));
        return active;
    }

    private record Key(UUID tenantId, UUID userId) {
    }

    private record Entry(boolean active, Instant checkedAt) {
    }
}
