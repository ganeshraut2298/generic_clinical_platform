package com.sahaj.platform.web;

import com.sahaj.platform.application.TeamService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-6 Team and access. Any signed-in clinic user may read the team (the page is view-only for
 * them, AC3); every change is owner-only (AC11), checked here and again in the service.
 */
@RestController
@RequestMapping("/api/v1/platform/team")
public class TeamController {

    static final String ANY_CLINIC_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    @PreAuthorize(ANY_CLINIC_ROLE)
    public List<TeamMemberResponse> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return teamService.list(principal.tenantId(), principal.userId()).stream().map(TeamMemberResponse::from).toList();
    }

    /**
     * SAH-31: clears a member's PIN (ADR-010 D8). Open to every clinic role at the door so that a non-owner gets the
     * contract's 403 forbidden "Only the clinic owner can do this."; the service re-reads the owner's membership.
     */
    @PostMapping("/{id}/pin/reset")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public TeamMemberResponse resetPin(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
        return TeamMemberResponse.from(teamService.resetPin(principal.tenantId(), principal.userId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public TeamMemberResponse add(@Valid @RequestBody TeamMemberCreateRequest request,
                                   @AuthenticationPrincipal TenantPrincipal principal) {
        return TeamMemberResponse.from(
                teamService.add(principal.tenantId(), principal.userId(), request.toInput()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public TeamMemberResponse update(@PathVariable UUID id, @Valid @RequestBody TeamMemberUpdateRequest request,
                                      @AuthenticationPrincipal TenantPrincipal principal) {
        return TeamMemberResponse.from(
                teamService.update(principal.tenantId(), principal.userId(), id, request.toInput()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('OWNER')")
    public TeamMemberResponse deactivate(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
        return TeamMemberResponse.from(teamService.deactivate(principal.tenantId(), principal.userId(), id));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('OWNER')")
    public TeamMemberResponse reactivate(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
        return TeamMemberResponse.from(teamService.reactivate(principal.tenantId(), principal.userId(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void remove(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
        teamService.remove(principal.tenantId(), principal.userId(), id);
    }
}
