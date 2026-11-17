package com.sahaj.ipd.domain.order;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-76 (ADR-027 D2): everything of one medicine order that decides its slots: the versions, the hold intervals and
 * the stop time ({@code stoppedAt} null while not stopped). Immutable and pure: built by the repository, read by
 * {@code DoseSlots}, the MAR, the Medicine round and the pharmacist rows.
 */
public record OrderTimeline(UUID orderId, UUID admissionId, OrderStatus status, List<MedicineOrderVersion> versions,
                            List<OrderHoldInterval> holds, Instant stoppedAt) {

    public OrderTimeline {
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("an order has at least one version");
        }
        versions = versions.stream().sorted(Comparator.comparingInt(MedicineOrderVersion::versionNo)).toList();
        holds = holds.stream().sorted(Comparator.comparing(OrderHoldInterval::heldAt)).toList();
    }

    /** The version with the highest number. */
    public MedicineOrderVersion current() {
        return versions.get(versions.size() - 1);
    }

    public Optional<MedicineOrderVersion> version(int versionNo) {
        return versions.stream().filter(v -> v.versionNo() == versionNo).findFirst();
    }

    public boolean isHeldAt(Instant instant) {
        return holds.stream().anyMatch(h -> h.covers(instant));
    }

    public boolean isStoppedAt(Instant instant) {
        return stoppedAt != null && !instant.isBefore(stoppedAt);
    }
}
