package com.sahaj.pharmacy.application;

import java.util.List;
import java.util.UUID;

/**
 * Port over the reason lists: the global seeded rows {@code pharmacy.reason_default} (read only), the unit's switch of a seeded
 * reason {@code pharmacy.reason_default_state} (lazy; no row means on) and the unit's own reasons {@code pharmacy.reason}.
 */
public interface ReasonRepository {

    /** A seeded reason with this unit's switch (active when the unit never switched it). */
    record SeededRow(String reasonKey, String listKey, String label, int sort, boolean active) {
    }

    /** One of the unit's own reasons. */
    record OwnRow(UUID id, String listKey, String reasonKey, String label, boolean active, int sort) {
    }

    /** All seeded reasons, by list and order. */
    List<SeededRow> seeded(UUID tenantId);

    /** All own reasons, by list and the order added. */
    List<OwnRow> own(UUID tenantId);

    /** The next position at the end of a list for an own reason. */
    int nextOwnSort(UUID tenantId, String listKey);

    UUID insertOwn(UUID tenantId, UUID unitId, String listKey, String reasonKey, String label, int sort, UUID actorUserId);

    void setOwnActive(UUID tenantId, UUID id, boolean active, UUID actorUserId);

    /** Creates or changes the unit's switch of a seeded reason. */
    void setSeededActive(UUID tenantId, UUID unitId, String reasonKey, boolean active, UUID actorUserId);
}
