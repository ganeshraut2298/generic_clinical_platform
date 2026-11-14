package com.sahaj.opd.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-43-1, SAH-53-1, SAH-54-1 (ADR-019 D2, ADR-020 D2): masters, the section library, the starter departments, the allergy
 * classes and the form starters (QA finding F-1) are
 * global and change by migration only. The stories ask for 403, not the framework's 405, when any clinic role (the owner
 * included) tries to create, change or delete one, so every write method on these read-only paths ends here and is
 * refused with the platform's 403 forbidden problem. The database grant (select only) is the second layer. Nothing is
 * read from the body, so a body is never parsed. The retired SAH-44 paths (/emr/templates, /emr/settings) are no longer
 * here: they answer 404 like any unknown path.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrReadOnlyPathsController implements EmrApi {

    @RequestMapping(path = {"/masters/**", "/quick-options/**", "/library/**", "/starter-departments",
            "/starter-departments/**", "/specialty-reference/**", "/allergy-classes", "/allergy-classes/**",
            "/form-starters", "/form-starters/**"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void refuseWrite() {
        throw new AccessDeniedException(
                "Masters, the section library and the starter departments are changed by migration only.");
    }
}
