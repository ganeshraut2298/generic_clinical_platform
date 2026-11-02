package com.sahaj.opd.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.service}, the clinic price list (SAH-7). Needs {@code app.tenant_id} bound. */
public interface ServiceRepository {

    /** The bound clinic's services by name; {@code active} null returns all, removed ones included. */
    List<ServiceRow> findAll(UUID tenantId, Boolean active);

    /** Another clinic's id is simply absent. */
    Optional<ServiceRow> findById(UUID tenantId, UUID id);

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when the clinic already has that name (any case). */
    void insert(UUID id, String name, String category, long pricePaise, boolean active, UUID actorUserId);

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when the new name is already used. */
    void update(UUID id, String name, String category, long pricePaise, boolean active, UUID actorUserId);

    record ServiceRow(UUID id, String name, String category, long pricePaise, boolean active) {
    }
}
