package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.emr.EmrSetupRepository.OverrideRow;
import com.sahaj.opd.application.emr.EmrTemplateComposer.Effective;
import com.sahaj.opd.application.emr.EmrTemplateRepository.SectionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.TemplateHeader;
import com.sahaj.opd.application.emr.EmrViews.SectionView;
import com.sahaj.opd.application.emr.EmrViews.TemplateSummary;
import com.sahaj.opd.application.emr.EmrViews.TemplateView;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The EMR specialty templates with this clinic's Show and Required overrides (SAH-44-1, SAH-44-2, ADR-019 D4, D5,
 * D12). Reads are open to every clinic role; changes are for the owner (capability
 * {@value EmrAccess#EMR_SETUP_EDIT}, read fresh). One revision per clinic and template guards against two owners
 * changing the same template at once. A change is all or nothing: the revision step, the override row and the audit
 * entry commit together. A request that changes nothing keeps the revision and writes no audit entry.
 */
@Service
public class EmrTemplateService {

    private static final String SETUP_CHANGED = "Someone else changed this template. Reload and try again.";

    private final EmrTemplateRepository templates;
    private final EmrSetupRepository setup;
    private final ServiceRepository services;
    private final ClinicSpecialtyPort clinicSpecialty;
    private final EmrAccess access;
    private final AuditLog auditLog;

    public EmrTemplateService(EmrTemplateRepository templates, EmrSetupRepository setup, ServiceRepository services,
                              ClinicSpecialtyPort clinicSpecialty, EmrAccess access, AuditLog auditLog) {
        this.templates = templates;
        this.setup = setup;
        this.services = services;
        this.clinicSpecialty = clinicSpecialty;
        this.access = access;
        this.auditLog = auditLog;
    }

    /** The four templates (newest version) with this clinic's counts, in the order gp, dental, paeds, homeo. */
    @Transactional(readOnly = true)
    public List<TemplateSummary> list(ClinicUser user) {
        UUID tenantId = user.tenantId();
        String clinicCode = clinicSpecialty.specialtyCode(tenantId).orElse(null);
        Map<String, List<OverrideRow>> overridesByTemplate = setup.allOverrides(tenantId);
        Map<EmrMaster, Long> masterCounts = templates.masterCounts();
        int tariffCount = tariffCount(tenantId);
        List<TemplateSummary> summaries = new ArrayList<>();
        for (TemplateHeader header : templates.newestTemplates()) {
            List<SectionRow> sections = templates.sections(header.templateKey(), header.version());
            Map<String, OverrideRow> overrides = EmrTemplateComposer.byKey(
                    overridesByTemplate.getOrDefault(header.templateKey(), List.of()));
            List<SectionView> views = EmrTemplateComposer.views(sections, List.of(), overrides, masterCounts,
                    tariffCount);
            boolean hasOverrides = views.stream().anyMatch(SectionView::overridden);
            summaries.add(new TemplateSummary(header.templateKey(), header.name(), header.version(),
                    header.templateKey().equals(clinicCode), views.size(), EmrTemplateComposer.sectionsOn(views),
                    EmrTemplateComposer.requiredOn(views), hasOverrides));
        }
        return summaries;
    }

    /** One template's sections with this clinic's overrides applied. */
    @Transactional(readOnly = true)
    public TemplateView get(ClinicUser user, String templateKey) {
        TemplateHeader header = requireTemplate(templateKey);
        return view(user, header, access.canEditSetup(user));
    }

    /** Sets Show and Required of one section for this clinic. The owner only. */
    @Transactional
    public TemplateView setOverride(ClinicUser user, String templateKey, String sectionKey, Boolean shownValue,
                                    Boolean requiredValue, Long revisionValue) {
        access.requireSetupEdit(user);
        List<InvalidFieldsException.FieldMessage> missing = new ArrayList<>();
        if (shownValue == null) {
            missing.add(new InvalidFieldsException.FieldMessage("shown", "Choose Show on or off."));
        }
        if (requiredValue == null) {
            missing.add(new InvalidFieldsException.FieldMessage("required", "Choose Required on or off."));
        }
        checkRevision(revisionValue, missing);
        if (!missing.isEmpty()) {
            throw new InvalidFieldsException(missing);
        }
        boolean shown = shownValue;
        boolean required = requiredValue;
        long revision = revisionValue;
        TemplateHeader header = requireTemplate(templateKey);
        if (!EmrKeys.isWellFormedSectionKey(sectionKey)) {
            throw EmrProblemException.badRequest("Check the section key and try again.");
        }
        SectionRow section = templates.sections(templateKey, header.version()).stream()
                .filter(row -> row.sectionKey().equals(sectionKey)).findFirst()
                .orElseThrow(EmrProblemException::sectionNotFound);
        if (!shown && required) {
            throw EmrProblemException.hiddenSectionRequired();
        }
        if (!shown && !section.hideable()) {
            throw EmrProblemException.sectionNotHideable(section.label());
        }
        UUID tenantId = user.tenantId();
        requireRevision(tenantId, templateKey, revision);

        OverrideRow current = EmrTemplateComposer.byKey(setup.overrides(tenantId, templateKey)).get(sectionKey);
        Effective before = EmrTemplateComposer.effective(section, current);
        Effective after = new Effective(shown, shown && required);
        if (before.equals(after)) {
            return view(user, header, true);
        }
        if (!setup.bumpRevision(tenantId, templateKey, revision, user.userId())) {
            throw conflict();
        }
        if (after.equals(EmrTemplateComposer.templateDefault(section))) {
            setup.deleteOverride(tenantId, templateKey, sectionKey);
        } else {
            setup.saveOverride(tenantId, templateKey, sectionKey, after.shown(), after.required(), user.userId());
        }
        auditLog.record("EMR_TEMPLATE_OVERRIDE_CHANGED", "EMR_TEMPLATE_OVERRIDE", null,
                overrideValues(templateKey, sectionKey, before), overrideValues(templateKey, sectionKey, after));
        return view(user, header, true);
    }

    /** Removes every override of this clinic for one template. The owner only. */
    @Transactional
    public TemplateView reset(ClinicUser user, String templateKey, Long revisionValue) {
        access.requireSetupEdit(user);
        List<InvalidFieldsException.FieldMessage> missing = new ArrayList<>();
        checkRevision(revisionValue, missing);
        if (!missing.isEmpty()) {
            throw new InvalidFieldsException(missing);
        }
        long revision = revisionValue;
        TemplateHeader header = requireTemplate(templateKey);
        UUID tenantId = user.tenantId();
        requireRevision(tenantId, templateKey, revision);

        List<OverrideRow> rows = setup.overrides(tenantId, templateKey);
        if (rows.isEmpty()) {
            return view(user, header, true);
        }
        List<SectionRow> sections = templates.sections(templateKey, header.version());
        Map<String, Object> before = resetValues(templateKey, sections, EmrTemplateComposer.byKey(rows), rows.size());
        Map<String, Object> after = resetValues(templateKey, sections, Map.of(), 0);
        if (!setup.bumpRevision(tenantId, templateKey, revision, user.userId())) {
            throw conflict();
        }
        setup.deleteOverrides(tenantId, templateKey);
        auditLog.record("EMR_TEMPLATE_RESET", "EMR_TEMPLATE_SETUP", null, before, after);
        return view(user, header, true);
    }

    private static void checkRevision(Long revision, List<InvalidFieldsException.FieldMessage> errors) {
        if (revision == null || revision < 0) {
            errors.add(new InvalidFieldsException.FieldMessage("revision", "Reload the page and try again."));
        }
    }

    private TemplateHeader requireTemplate(String templateKey) {
        if (!EmrKeys.isWellFormedTemplateKey(templateKey)) {
            throw EmrProblemException.badRequest("Check the template key and try again.");
        }
        return templates.newest(templateKey).orElseThrow(EmrProblemException::templateNotFound);
    }

    private void requireRevision(UUID tenantId, String templateKey, long revision) {
        if (setup.revision(tenantId, templateKey) != revision) {
            throw conflict();
        }
    }

    private static ConflictException conflict() {
        return new ConflictException("emr-setup-changed", "Someone else changed this template", SETUP_CHANGED);
    }

    private TemplateView view(ClinicUser user, TemplateHeader header, boolean canEdit) {
        UUID tenantId = user.tenantId();
        List<SectionRow> sections = templates.sections(header.templateKey(), header.version());
        List<SectionView> views = EmrTemplateComposer.views(sections,
                templates.options(header.templateKey(), header.version()),
                EmrTemplateComposer.byKey(setup.overrides(tenantId, header.templateKey())),
                templates.masterCounts(), tariffCount(tenantId));
        boolean clinicOwn = clinicSpecialty.specialtyCode(tenantId)
                .map(code -> code.equals(header.templateKey())).orElse(false);
        return new TemplateView(header.templateKey(), header.name(), header.version(), clinicOwn,
                setup.revision(tenantId, header.templateKey()), canEdit, EmrTemplateComposer.sectionsOn(views),
                EmrTemplateComposer.requiredOn(views), views);
    }

    private int tariffCount(UUID tenantId) {
        return services.findAll(tenantId, true).size();
    }

    private static Map<String, Object> overrideValues(String templateKey, String sectionKey, Effective effective) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("templateKey", templateKey);
        values.put("sectionKey", sectionKey);
        values.put("shown", effective.shown());
        values.put("required", effective.required());
        return values;
    }

    private static Map<String, Object> resetValues(String templateKey, List<SectionRow> sections,
                                                   Map<String, OverrideRow> overrides, int overrideCount) {
        int on = 0;
        int requiredOn = 0;
        for (SectionRow section : sections) {
            Effective effective = EmrTemplateComposer.effective(section, overrides.get(section.sectionKey()));
            if (effective.shown()) {
                on++;
                if (effective.required()) {
                    requiredOn++;
                }
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("templateKey", templateKey);
        values.put("sectionsOn", on);
        values.put("requiredOn", requiredOn);
        values.put("overrides", overrideCount);
        return values;
    }
}
