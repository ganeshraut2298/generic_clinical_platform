package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.admin.AdminIdentity;
import java.util.UUID;

/** Contract schema AdminMe. */
public record AdminMeResponse(UUID id, String name, String role, String roleLabel) {

    static AdminMeResponse of(AdminIdentity admin) {
        return new AdminMeResponse(admin.id(), admin.name(), AdminIdentity.ROLE, AdminIdentity.ROLE_LABEL);
    }
}
