package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrDepartmentRepository.DepartmentRow;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrViews.DepartmentChoice;
import com.sahaj.opd.application.emr.EmrViews.DepartmentRef;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which EMR department a doctor gets (SAH-46-3, ADR-020 D7). Order: (1) the doctor's free-text specialty, normalised
 * (trimmed, lower case, runs of spaces made one) and looked up in the alias list, which gives a starter key and so the
 * department of that key; (2) else the published departments the doctor is assigned to: one is used, with several the
 * one whose starter is the clinic default's, else the first by name; (3) else the clinic default (the clinic profile's
 * specialty, gp to gm, dental, paeds, homeo or none to gm). The doctor's specialty text is never logged. Replaces the
 * SAH-44 template resolver.
 */
@Service
public class EmrDepartmentResolver {

    public static final String DOCTOR_SPECIALTY = "DOCTOR_SPECIALTY";
    public static final String DOCTOR_DEPARTMENT = "DOCTOR_DEPARTMENT";
    public static final String CLINIC_DEFAULT = "CLINIC_DEFAULT";
    public static final String SWITCHED = "SWITCHED";

    /** The department chosen, the layout it uses now, and why. */
    public record Resolution(Department department, Published published, String choiceSource) {

        public DepartmentChoice choice() {
            return new DepartmentChoice(department.key(), department.name(), department.starterKey(),
                    published.layoutSource(), published.versionNo(), choiceSource);
        }
    }

    private final TeamMemberPort teamMembers;
    private final ClinicSpecialtyPort clinicSpecialty;
    private final EmrLibraryRepository library;
    private final EmrDepartmentRepository departments;
    private final EmrLayouts layouts;

    public EmrDepartmentResolver(TeamMemberPort teamMembers, ClinicSpecialtyPort clinicSpecialty,
                                 EmrLibraryRepository library, EmrDepartmentRepository departments,
                                 EmrLayouts layouts) {
        this.teamMembers = teamMembers;
        this.clinicSpecialty = clinicSpecialty;
        this.library = library;
        this.departments = departments;
        this.layouts = layouts;
    }

    /** The choice for this doctor, reading the doctor's specialty through {@code platform.api}. */
    @Transactional(readOnly = true)
    public Resolution choose(UUID tenantId, UUID doctorMembershipId) {
        String specialty = teamMembers.doctorDetails(tenantId, doctorMembershipId).map(DoctorDetails::specialty)
                .orElse(null);
        return chooseForSpecialty(tenantId, doctorMembershipId, specialty);
    }

    /** The same rule when the caller has already read the doctor's specialty text (may be null). */
    @Transactional(readOnly = true)
    public Resolution chooseForSpecialty(UUID tenantId, UUID doctorMembershipId, String doctorSpecialty) {
        String normalised = normalise(doctorSpecialty);
        if (normalised != null) {
            Optional<Resolution> byDoctor = library.starterForAlias(normalised)
                    .flatMap(key -> visible(tenantId, key, DOCTOR_SPECIALTY));
            if (byDoctor.isPresent()) {
                return byDoctor.get();
            }
        }
        String clinicDefault = EmrKeys.clinicDefaultStarter(clinicSpecialty.specialtyCode(tenantId).orElse(null));
        List<DepartmentRow> assigned = assigned(tenantId, doctorMembershipId);
        if (!assigned.isEmpty()) {
            DepartmentRow chosen = assigned.stream().filter(row -> clinicDefault.equals(row.starterKey())).findFirst()
                    .orElse(assigned.get(0));
            Optional<Resolution> byDepartment = visible(tenantId, chosen.key(), DOCTOR_DEPARTMENT);
            if (byDepartment.isPresent()) {
                return byDepartment.get();
            }
        }
        return visible(tenantId, clinicDefault, CLINIC_DEFAULT)
                .or(() -> visible(tenantId, EmrKeys.DEFAULT_STARTER_KEY, CLINIC_DEFAULT))
                .orElseThrow(EmrProblemException::starterNotFound);
    }

    /**
     * The department a doctor switches to (SAH-46-3): any department visible to doctors. Unknown key: 404
     * department-not-found; an added department never published: 404 department-not-published.
     */
    @Transactional(readOnly = true)
    public Resolution switchTo(UUID tenantId, String departmentKey) {
        if (!EmrKeys.isWellFormedDepartmentKey(departmentKey)) {
            throw EmrProblemException.badRequest("Choose a valid department key.");
        }
        Department department = layouts.find(tenantId, departmentKey)
                .orElseThrow(EmrProblemException::departmentNotFound);
        if (!department.visibleToDoctors()) {
            throw EmrProblemException.departmentNotPublished();
        }
        Published published = layouts.published(tenantId, department)
                .orElseThrow(EmrProblemException::departmentNotPublished);
        return new Resolution(department, published, SWITCHED);
    }

    /** The published departments this doctor is assigned to (SAH-54-3), by name. */
    @Transactional(readOnly = true)
    public List<DepartmentRef> assignedRefs(UUID tenantId, UUID doctorMembershipId) {
        return assigned(tenantId, doctorMembershipId).stream().map(row -> new DepartmentRef(row.key(), row.name()))
                .toList();
    }

    /** Every department a doctor may switch to: the 15 starters (named by the clinic if saved) and published added ones. */
    @Transactional(readOnly = true)
    public List<DepartmentRef> switchOptions(UUID tenantId) {
        java.util.Map<String, DepartmentRow> rows = new java.util.LinkedHashMap<>();
        for (DepartmentRow row : departments.findAll(tenantId)) {
            rows.put(row.key(), row);
        }
        List<DepartmentRef> options = new java.util.ArrayList<>();
        for (EmrLibraryRepository.StarterRow starter : library.starters()) {
            DepartmentRow row = rows.remove(starter.starterKey());
            options.add(new DepartmentRef(starter.starterKey(), row == null ? starter.name() : row.name()));
        }
        for (DepartmentRow row : rows.values()) {
            if (row.publishedVersion() > 0) {
                options.add(new DepartmentRef(row.key(), row.name()));
            }
        }
        return options;
    }

    private List<DepartmentRow> assigned(UUID tenantId, UUID doctorMembershipId) {
        return departments.publishedForDoctor(tenantId, doctorMembershipId);
    }

    private Optional<Resolution> visible(UUID tenantId, String departmentKey, String source) {
        return layouts.find(tenantId, departmentKey).filter(Department::visibleToDoctors).flatMap(department ->
                layouts.published(tenantId, department).map(published -> new Resolution(department, published, source)));
    }

    /** Trim, lower case, runs of white space made one space; null when nothing is left. */
    public static String normalise(String specialty) {
        if (specialty == null) {
            return null;
        }
        String text = specialty.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text;
    }
}
