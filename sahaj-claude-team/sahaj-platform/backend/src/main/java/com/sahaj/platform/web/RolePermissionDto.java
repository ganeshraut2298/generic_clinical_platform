package com.sahaj.platform.web;

import com.sahaj.platform.application.RolePermissionRepository.Cell;
import com.sahaj.platform.domain.MembershipRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code RolePermission} in {@code docs/api/platform.yaml}: one cell of "Who can do what" (SAH-6). */
public record RolePermissionDto(
        @NotNull(message = "Choose a valid option")
        MembershipRole role,

        @NotNull(message = "Choose a valid option")
        @Pattern(regexp = "^(token|vitals|collect|cancel|refund|revenue|export)$", message = "Choose a valid option")
        String permission,

        @NotNull(message = "Choose a valid option")
        Boolean allowed) {

    static RolePermissionDto from(Cell cell) {
        return new RolePermissionDto(cell.role(), cell.permission(), cell.allowed());
    }

    Cell toCell() {
        return new Cell(role, permission, allowed);
    }
}
