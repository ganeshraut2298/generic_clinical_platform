package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import com.sahaj.opd.application.emr.EmrViews.StarterDetail;
import com.sahaj.opd.application.emr.EmrViews.StarterSummary;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-53-1 and SAH-54-1 (ADR-020 D3, D4): the section library and the 15 starter departments, read-only reference data
 * that is the same for every clinic. Any clinic role reads. No patient data. Starter content is not clinically
 * reviewed (OQ-43-8).
 */
@Service
public class EmrLibraryService {

    private final EmrLibraryRepository library;
    private final EmrLayouts layouts;

    public EmrLibraryService(EmrLibraryRepository library, EmrLayouts layouts) {
        this.library = library;
        this.layouts = layouts;
    }

    @Transactional(readOnly = true)
    public List<LibrarySection> sections() {
        return library.sections();
    }

    @Transactional(readOnly = true)
    public LibrarySection section(String sectionKey) {
        if (!EmrKeys.isWellFormedSectionKey(sectionKey)) {
            throw EmrProblemException.badRequest("Choose a valid section key.");
        }
        return library.section(sectionKey).orElseThrow(EmrProblemException::sectionNotFound);
    }

    @Transactional(readOnly = true)
    public List<StarterSummary> starters() {
        List<StarterSummary> result = new ArrayList<>();
        for (StarterRow starter : library.starters()) {
            StarterLayoutRow row = layouts.currentStarterRow(starter.starterKey());
            result.add(summary(starter, layouts.json().readLayout(row.layout())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public StarterDetail starter(String starterKey) {
        if (!EmrKeys.isWellFormedDepartmentKey(starterKey)) {
            throw EmrProblemException.badRequest("Choose a valid starter department key.");
        }
        StarterRow starter = library.starter(starterKey).orElseThrow(EmrProblemException::starterNotFound);
        StarterLayoutRow row = layouts.currentStarterRow(starterKey);
        List<LayoutSection> sections = layouts.json().readLayout(row.layout());
        StarterSummary summary = summary(starter, sections);
        return new StarterDetail(summary.starterKey(), summary.name(), summary.initials(), summary.colour(),
                summary.description(), summary.sectionCount(), summary.specialtySectionCount(), summary.offAtStart(),
                row.layoutVersion(), sections);
    }

    /** The wizard card: the Specialty sections count and the names "Turned off by default" (SAH-54-1). */
    static StarterSummary summary(StarterRow starter, List<LayoutSection> sections) {
        int specialty = 0;
        List<String> off = new ArrayList<>();
        for (LayoutSection section : sections) {
            if ("SPECIALTY".equals(section.kind())) {
                specialty++;
            }
            if (!section.shown()) {
                off.add(section.name());
            }
        }
        return new StarterSummary(starter.starterKey(), starter.name(), starter.initials(), starter.colour(),
                starter.description(), sections.size(), specialty, List.copyOf(off));
    }
}
