package com.sahaj.platform.web;

import com.sahaj.platform.application.RolePermissionService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-6 AC13, "Who can do what". Any signed-in clinic user may read the matrix (view-only for
 * non-owners); only the owner may save it.
 */
@RestController
@RequestMapping("/api/v1/platform/role-permissions")
@Validated
public class RolePermissionController {

    private final RolePermissionService service;

    public RolePermissionController(RolePermissionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public List<RolePermissionDto> get() {
        return service.get().stream().map(RolePermissionDto::from).toList();
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public List<RolePermissionDto> save(
            @RequestBody @NotNull @Size(max = 200) List<@Valid @NotNull RolePermissionDto> body,
            @AuthenticationPrincipal TenantPrincipal principal) {
        return service.save(principal.tenantId(), principal.userId(),
                        body.stream().map(RolePermissionDto::toCell).toList())
                .stream().map(RolePermissionDto::from).toList();
    }
}
