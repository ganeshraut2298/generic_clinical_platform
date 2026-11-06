package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ipd.ward} and {@code ipd.room} (SAH-22). Every method is scoped to the tenant. */
public interface WardRepository {

    /** Waits until no other transaction holds the clinic's IPD write lock; released at the end of the transaction. */
    void lockTenant(UUID tenantId);

    /** Wards in creation order. */
    List<WardRow> findWards(UUID tenantId);

    /** All rooms, in creation order. */
    List<RoomRow> findRooms(UUID tenantId);

    Optional<WardRow> findWard(UUID tenantId, UUID wardId);

    Optional<RoomRow> findRoom(UUID tenantId, UUID roomId);

    boolean wardNameExists(UUID tenantId, String name);

    void insertWard(UUID tenantId, UUID id, String name, UUID actorUserId, Instant now);

    void insertRoom(UUID tenantId, UUID id, UUID wardId, String name, UUID actorUserId, Instant now);

    record WardRow(UUID id, String name, String station) {
    }

    record RoomRow(UUID id, UUID wardId, String name) {
    }
}
