package com.sahaj.ipd.web;

import com.sahaj.ipd.application.EscalationService;
import com.sahaj.ipd.application.SafetyService;
import com.sahaj.ipd.application.SafetyViews.ActionResult;
import com.sahaj.ipd.application.SafetyViews.Board;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.EventPage;
import com.sahaj.ipd.application.SafetyViews.ResponseInput;
import com.sahaj.ipd.application.SafetyViews.SeniorCallInput;
import com.sahaj.ipd.application.SafetyViews.VersionInput;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-33 Safety board, escalation actions and the clinical audit trail (contract {@code docs/api/ipd.yaml}, ADR-007).
 * The clinic, the person and the role always come from the signed-in user. The role is checked again in the service
 * (token role, then the active membership), and so are the module-off answer and every business answer. Read-only IPD
 * does not block any of these endpoints (DEC-015). The board, the actions and the audit trail carry names and clinical
 * text: they are never logged.
 */
@RestController
@RequestMapping("/api/v1/ipd")
public class SafetyController {

    /** DEC-015 OQ-33-1: who may read the board, the counts and the audit trail. */
    static final String READERS = "hasAnyRole('OWNER','DOCTOR','NURSE')";
    /** Acknowledge and Close escalation. */
    static final String DOCTORS = "hasAnyRole('OWNER','DOCTOR')";
    /** Call senior. */
    static final String NURSES = "hasRole('NURSE')";

    private final SafetyService reads;
    private final EscalationService actions;

    public SafetyController(SafetyService reads, EscalationService actions) {
        this.reads = reads;
        this.actions = actions;
    }

    @GetMapping("/safety")
    @PreAuthorize(READERS)
    public Board board() {
        ClinicUser user = ClinicUser.current();
        return reads.board(user.tenantId(), user.userId(), user.role());
    }

    @GetMapping("/safety/summary")
    @PreAuthorize(READERS)
    public Counts summary() {
        ClinicUser user = ClinicUser.current();
        return reads.summary(user.tenantId(), user.userId(), user.role());
    }

    @PostMapping("/escalations/{id}/acknowledge")
    @PreAuthorize(DOCTORS)
    public ActionResult acknowledge(@PathVariable UUID id, @RequestBody VersionInput request) {
        ClinicUser user = ClinicUser.current();
        return actions.acknowledge(user.tenantId(), user.userId(), user.role(), id, request);
    }

    @PostMapping("/escalations/{id}/respond")
    @PreAuthorize(DOCTORS)
    public ActionResult respond(@PathVariable UUID id, @RequestBody ResponseInput request) {
        ClinicUser user = ClinicUser.current();
        return actions.respond(user.tenantId(), user.userId(), user.role(), id, request);
    }

    @PostMapping("/escalations/{id}/call-senior")
    @PreAuthorize(NURSES)
    public ActionResult callSenior(@PathVariable UUID id, @RequestBody SeniorCallInput request) {
        ClinicUser user = ClinicUser.current();
        return actions.callSenior(user.tenantId(), user.userId(), user.role(), id, request);
    }

    /** The limit and the cursor are read as text so that the service gives the contract's own 400 for a bad one. */
    @GetMapping("/clinical-events")
    @PreAuthorize(READERS)
    public EventPage events(@RequestParam(name = "limit", required = false) String limit,
                            @RequestParam(name = "cursor", required = false) String cursor) {
        ClinicUser user = ClinicUser.current();
        return reads.events(user.tenantId(), user.userId(), user.role(), limit, cursor);
    }
}
