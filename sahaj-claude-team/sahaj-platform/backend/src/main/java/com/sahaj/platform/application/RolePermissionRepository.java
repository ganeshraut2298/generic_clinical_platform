package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import java.util.List;
import java.util.UUID;

/** Port over {@code platform.role_permission} and the defaults behind it (SAH-6). Needs {@code app.tenant_id} bound. */
public interface RolePermissionRepository {

    /** The full matrix for the bound clinic: global defaults overridden by the clinic's own changes. */
    List<Cell> effective();

    /** Saves one cell for the bound clinic. The clinic id comes from the database binding, never from a parameter. */
    void upsert(MembershipRole role, String permission, boolean allowed, UUID actorUserId);

    record Cell(MembershipRole role, String permission, boolean allowed) {
    }
}
