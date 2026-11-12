package com.sahaj.opd.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-43-1, SAH-44-1 (ADR-019 D2): masters and templates are global and change by migration only. The stories ask for
 * 403, not the framework's 405, when any clinic role (the owner included) tries to create, change or delete one, so
 * every write method on the read-only paths ends here and is refused with the platform's 403 forbidden problem. The
 * database grant (select only) is the second layer. Nothing is read from the body, so a body is never parsed.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrReadOnlyPathsController {

    @RequestMapping(path = {"/masters/**", "/quick-options/**", "/templates", "/templates/{templateKey}"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void refuseWrite() {
        throw new AccessDeniedException("Masters and templates are changed by migration only.");
    }
}
