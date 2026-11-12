package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrTemplateRepository.SectionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.TemplateHeader;
import com.sahaj.opd.application.emr.EmrViews.TemplateChoice;
import com.sahaj.opd.application.emr.EmrViews.TemplateSnapshot;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which EMR template a doctor gets, and the section list a visit is opened with (SAH-44-4, ADR-019 D6).
 *
 * <p>Choice (BR15): (1) the doctor's free-text specialty, normalised (trimmed, lower case, runs of spaces made one)
 * and looked up in the alias table; (2) else the clinic's specialty code; (3) else General practice. The source of
 * the choice is returned. The doctor's specialty text is never logged.
 *
 * <p>Snapshot: the newest template version with this clinic's overrides applied, only the shown sections, in template
 * order, with the effective required flag. Template chips are not copied: a visit refers to them by template key,
 * version and section key, which is safe because template versions never change.
 */
@Service
public class EmrTemplateResolver {

    public static final String DOCTOR_SPECIALTY = "DOCTOR_SPECIALTY";
    public static final String CLINIC_SPECIALTY = "CLINIC_SPECIALTY";
    public static final String DEFAULT = "DEFAULT";
    public static final String SWITCHED = "SWITCHED";

    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final List<String> CHOICE_SOURCES = List.of(DOCTOR_SPECIALTY, CLINIC_SPECIALTY, DEFAULT, SWITCHED);

    private final TeamMemberPort teamMembers;
    private final ClinicSpecialtyPort clinicSpecialty;
    private final EmrTemplateRepository templates;
    private final EmrSetupRepository setup;
    private final Clock clock;

    public EmrTemplateResolver(TeamMemberPort teamMembers, ClinicSpecialtyPort clinicSpecialty,
                               EmrTemplateRepository templates, EmrSetupRepository setup, Clock clock) {
        this.teamMembers = teamMembers;
        this.clinicSpecialty = clinicSpecialty;
        this.templates = templates;
        this.setup = setup;
        this.clock = clock;
    }

    /** The choice for this doctor, reading the doctor's specialty through {@code platform.api}. */
    @Transactional(readOnly = true)
    public TemplateChoice choose(UUID tenantId, UUID doctorMembershipId) {
        String specialty = teamMembers.doctorDetails(tenantId, doctorMembershipId)
                .map(DoctorDetails::specialty).orElse(null);
        return chooseForSpecialty(tenantId, specialty);
    }

    /** The same rule when the caller has already read the doctor's specialty text (may be null). */
    @Transactional(readOnly = true)
    public TemplateChoice chooseForSpecialty(UUID tenantId, String doctorSpecialty) {
        String normalised = normalise(doctorSpecialty);
        if (normalised != null) {
            Optional<TemplateChoice> byDoctor = templates.templateKeyForAlias(normalised)
                    .flatMap(key -> choiceFor(key, DOCTOR_SPECIALTY));
            if (byDoctor.isPresent()) {
                return byDoctor.get();
            }
        }
        Optional<TemplateChoice> byClinic = clinicSpecialty.specialtyCode(tenantId)
                .filter(EmrKeys::isSpecialtyCode)
                .flatMap(key -> choiceFor(key, CLINIC_SPECIALTY));
        if (byClinic.isPresent()) {
            return byClinic.get();
        }
        return choiceFor(EmrKeys.DEFAULT_TEMPLATE_KEY, DEFAULT)
                .orElseThrow(EmrProblemException::templateNotFound);
    }

    /**
     * The section list a visit stores when it is opened (or switched). {@code choiceSource} is one of
     * DOCTOR_SPECIALTY, CLINIC_SPECIALTY, DEFAULT or SWITCHED. A key that is not one of the four template keys is a
     * 400 bad-request.
     */
    @Transactional(readOnly = true)
    public TemplateSnapshot snapshot(UUID tenantId, String templateKey, String choiceSource) {
        if (!EmrKeys.isSpecialtyCode(templateKey) || !CHOICE_SOURCES.contains(choiceSource)) {
            throw EmrProblemException.badRequest("Choose one of the four templates.");
        }
        TemplateHeader header = templates.newest(templateKey).orElseThrow(EmrProblemException::templateNotFound);
        List<SectionRow> sections = templates.sections(templateKey, header.version());
        var overrides = EmrTemplateComposer.byKey(setup.overrides(tenantId, templateKey));
        return new TemplateSnapshot(SNAPSHOT_SCHEMA_VERSION, templateKey, header.version(), choiceSource,
                clock.instant(), EmrTemplateComposer.snapshotSections(sections, overrides));
    }

    /** Trim, lower case, runs of white space made one space; null when nothing is left. */
    public static String normalise(String specialty) {
        if (specialty == null) {
            return null;
        }
        String text = specialty.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text;
    }

    private Optional<TemplateChoice> choiceFor(String templateKey, String source) {
        return templates.newest(templateKey)
                .map(header -> new TemplateChoice(header.templateKey(), header.version(), header.name(), source));
    }
}
