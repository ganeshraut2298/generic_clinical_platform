package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrLibraryService;
import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import com.sahaj.opd.application.emr.EmrViews.StarterDetail;
import com.sahaj.opd.application.emr.EmrViews.StarterSummary;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-53-1, SAH-54-1 (ADR-020 D3, D4): the global section library and the 15 starter departments. Read only, any clinic
 * role. Write methods on these paths are refused with 403 by {@link EmrReadOnlyPathsController}.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrLibraryController implements EmrApi {

    private final EmrLibraryService service;

    public EmrLibraryController(EmrLibraryService service) {
        this.service = service;
    }

    @GetMapping("/library/sections")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<LibrarySection> sections() {
        return service.sections();
    }

    @GetMapping("/library/sections/{sectionKey}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public LibrarySection section(@PathVariable String sectionKey) {
        return service.section(sectionKey);
    }

    @GetMapping("/starter-departments")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<StarterSummary> starters() {
        return service.starters();
    }

    @GetMapping("/starter-departments/{starterKey}")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public StarterDetail starter(@PathVariable String starterKey) {
        return service.starter(starterKey);
    }
}
