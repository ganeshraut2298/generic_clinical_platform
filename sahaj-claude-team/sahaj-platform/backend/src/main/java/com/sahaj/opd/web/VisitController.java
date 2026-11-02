package com.sahaj.opd.web;

import com.sahaj.opd.application.VisitService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-9 Queue and tokens. Any clinic role reads the board; OWNER, FRONT_DESK and NURSE issue a
 * token; a stage move is checked again inside {@link VisitService} (who owns the current stage,
 * and that a backward move is owner only). The tenant, actor and role always come from the
 * signed-in user, never from the request.
 */
@RestController
@RequestMapping("/api/v1/opd/visits")
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    @GetMapping("/today")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<VisitResponse> today(@RequestParam(defaultValue = "false") boolean mine) {
        ClinicUser user = ClinicUser.current();
        return visitService.today(user.tenantId(), user.role(), mine).stream().map(VisitResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OpdRoles.ISSUE_TOKEN)
    public VisitResponse create(@RequestBody VisitCreateRequest request) {
        ClinicUser user = ClinicUser.current();
        return VisitResponse.from(
                visitService.issueToken(user.tenantId(), user.userId(), user.role(), request.toInput()));
    }

    @PatchMapping("/{id}/stage")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public VisitResponse moveStage(@PathVariable UUID id, @RequestBody VisitStageRequest request) {
        ClinicUser user = ClinicUser.current();
        return VisitResponse.from(
                visitService.moveStage(user.tenantId(), user.userId(), user.role(), id, request.stage()));
    }
}
