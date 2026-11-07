package com.sahaj.platform.web;

import com.sahaj.platform.application.ColleagueTriesService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-38 (ADR-014 D8 and D9): wrong PIN tries by colleagues. The notice and its "Got it" are the signed-in person's own
 * data (any clinic role); the history of one member's PIN is for the clinic owner only (the service reads the owner's
 * membership again and answers 403 forbidden to everyone else). The clinic and the person come from the session only.
 * No PIN and no hash is ever read, logged or returned here.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class ColleagueTriesController {

    private final ColleagueTriesService colleagueTriesService;

    public ColleagueTriesController(ColleagueTriesService colleagueTriesService) {
        this.colleagueTriesService = colleagueTriesService;
    }

    @GetMapping("/me/pin/colleague-tries")
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public ColleagueTriesNoticeResponse notice(@AuthenticationPrincipal TenantPrincipal principal) {
        return ColleagueTriesNoticeResponse.from(colleagueTriesService.notice(principal.tenantId(), principal.userId()));
    }

    @PostMapping("/me/pin/colleague-tries/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public void seen(@RequestBody ColleagueTriesSeenRequest request, @AuthenticationPrincipal TenantPrincipal principal) {
        colleagueTriesService.markSeen(principal.tenantId(), principal.userId(), request.seenThrough());
    }

    /** Open to every clinic role at the door so that a non-owner gets the contract's 403 text from the service. */
    @GetMapping("/team/{id}/pin/colleague-tries")
    @PreAuthorize(TeamController.ANY_CLINIC_ROLE)
    public ColleagueTriesHistoryResponse history(@PathVariable UUID id,
                                                 @AuthenticationPrincipal TenantPrincipal principal) {
        return ColleagueTriesHistoryResponse.from(
                colleagueTriesService.history(principal.tenantId(), principal.userId(), id));
    }
}
