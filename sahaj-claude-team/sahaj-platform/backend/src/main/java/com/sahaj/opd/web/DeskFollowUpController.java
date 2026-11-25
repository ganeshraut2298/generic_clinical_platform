package com.sahaj.opd.web;

import com.sahaj.opd.application.DeskFollowUpService;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-93 follow-up panel writes (docs/api/opd.yaml 0.9.0, ADR-032 D8). OWNER and FRONT_DESK only, checked again in the
 * service. The tenant, actor and role come from the signed-in user, never from the request.
 */
@RestController
@RequestMapping("/api/v1/opd")
public class DeskFollowUpController implements DeskApi {

    private final DeskFollowUpService service;

    public DeskFollowUpController(DeskFollowUpService service) {
        this.service = service;
    }

    @PostMapping("/visits/{visitId}/follow-up/appointment")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public DeskFollowUpResponse bookForVisit(@PathVariable UUID visitId, @RequestBody VisitFollowUpBookRequest request) {
        ClinicUser user = ClinicUser.current();
        return DeskFollowUpResponse.from(
                service.bookForVisit(user.tenantId(), user.userId(), user.role(), visitId, request.toInput()));
    }

    @PostMapping("/visits/{visitId}/follow-up/not-needed")
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public DeskFollowUpResponse markNotNeeded(@PathVariable UUID visitId) {
        ClinicUser user = ClinicUser.current();
        return DeskFollowUpResponse.from(service.markNotNeeded(user.tenantId(), user.userId(), user.role(), visitId));
    }

    @PostMapping("/follow-ups/{followUpId}/appointment")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OpdRoles.OWNER_OR_FRONT_DESK)
    public DeskFollowUpResponse bookFromList(@PathVariable UUID followUpId, @RequestBody FollowUpBookRequest request) {
        ClinicUser user = ClinicUser.current();
        return DeskFollowUpResponse.from(
                service.bookFromList(user.tenantId(), user.userId(), user.role(), followUpId, request.toInput()));
    }
}
