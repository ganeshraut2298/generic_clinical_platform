package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.CasePaperRepository;
import com.sahaj.opd.application.CasePaperRepository.PaperRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrViews.PrintPreferences;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.ClinicLetterheadPort.Letterhead;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the live case paper needs besides the values on screen (SAH-55, SAH-48-3; ADR-022 D9). The paper itself is
 * composed in the browser from the values as the doctor types; this answers the letterhead, doctor, patient, the clinic
 * defaults, the ten headings in three languages, the heading of every section of the layout and whether printing is
 * blocked by an unreviewed allergy alert. Same access as reading the record. Nothing is stored, logged or sent; share is
 * always unavailable until the WhatsApp and ABHA services exist (DEC-040).
 */
@Service
public class EmrCasePaperService {

    /** The ten headings and their words (SAH-55 table; Marathi and Hindi tagged as from the mockup, not reviewed). */
    public static final List<Heading> HEADINGS = List.of(
            new Heading("CHIEF_COMPLAINTS", "Chief complaints", "मुख्य तक्रारी", "मुख्य शिकायतें"),
            new Heading("ALLERGIES", "Allergies", "ॲलर्जी", "एलर्जी"),
            new Heading("MEDICAL_HISTORY", "Medical history", "पूर्वीचे आजार", "पुरानी बीमारियाँ"),
            new Heading("EXAMINATION", "Examination", "तपासणी", "जाँच"),
            new Heading("DIAGNOSIS", "Diagnosis", "निदान", "निदान"),
            new Heading("INVESTIGATIONS", "Investigations advised", "सुचवलेल्या चाचण्या", "सुझाई गई जाँचें"),
            new Heading("MEDICINES", "Medicines ℞", "औषधे ℞", "दवाइयाँ ℞"),
            new Heading("PROCEDURES", "Procedures & services", "प्रक्रिया व सेवा", "प्रक्रिया व सेवाएँ"),
            new Heading("ADVICE", "Advice", "सल्ला", "सलाह"),
            new Heading("FOLLOW_UP", "Follow-up", "पुढील भेट", "अगली मुलाक़ात"));

    private static final Map<String, String> SECTION_HEADING = Map.ofEntries(
            Map.entry("complaints", "CHIEF_COMPLAINTS"), Map.entry("hpi", "CHIEF_COMPLAINTS"),
            Map.entry("allergy", "ALLERGIES"), Map.entry("history", "MEDICAL_HISTORY"),
            Map.entry("chronic", "MEDICAL_HISTORY"), Map.entry("vitals", "VITALS_LINE"),
            Map.entry("diagnosis", "DIAGNOSIS"), Map.entry("investigations", "INVESTIGATIONS"),
            Map.entry("rx", "MEDICINES"), Map.entry("procedures", "PROCEDURES"),
            Map.entry("dental_done", "PROCEDURES"), Map.entry("advice", "ADVICE"), Map.entry("followup", "FOLLOW_UP"),
            Map.entry("referral", "FOLLOW_UP"), Map.entry("abha", "NONE"), Map.entry("consent", "NONE"),
            Map.entry("rxformat", "NONE"), Map.entry("pcpndt", "NONE"), Map.entry("onc_scheme", "NONE"),
            Map.entry("sx_consent", "NONE"), Map.entry("sx_checklist", "NONE"), Map.entry("sx_preauth", "NONE"),
            Map.entry("attachments", "NONE"), Map.entry("therapy_notes", "NONE"),
            // ADR-023 D3: the specialty sections that print under Procedures and services, or have their own prints.
            Map.entry("immunization", "PROCEDURES"), Map.entry("modalities", "PROCEDURES"),
            Map.entry("tx_plan", "PROCEDURES"), Map.entry("wtdose", "NONE"), Map.entry("onc_regimen", "NONE"),
            Map.entry("sx_preop", "NONE"),
            // SAH-75-6 (ADR-026 D6): the Dental assessment cards. The others (dental_pain, dental_extraoral,
            // dental_intraoral) and local_exam go under Examination, the default.
            Map.entry("dental_medhx", "MEDICAL_HISTORY"), Map.entry("dental_hx", "MEDICAL_HISTORY"),
            Map.entry("dental_xray", "INVESTIGATIONS"));

    public record Heading(String heading, String en, String mr, String hi) {
    }

    public record SectionHeading(String sectionKey, String heading) {
    }

    public record Doctor(String name, String qualification, String registrationNo) {
    }

    public record PaperPatient(String name, Integer age, String sex, String uhid, EmrViews.AgeDetail ageDetail) {

        /** The shape before SAH-73: no age detail. */
        public PaperPatient(String name, Integer age, String sex, String uhid) {
            this(name, age, sex, uhid, null);
        }

        /** Patient data: no name in a stray log line. */
        @Override
        public String toString() {
            return "PaperPatient[]";
        }
    }

    public record ClinicDefaults(String paperSize, String language, boolean capitals) {
    }

    public record Signature(boolean signed, String signerName, Instant signedAt) {
    }

    public record Share(boolean available, String reason) {
    }

    public record CasePaperContext(Letterhead letterhead, String departmentName, Doctor doctor, PaperPatient patient,
                                   String visitLine, ClinicDefaults clinicDefaults, PrintPreferences chosen,
                                   List<Heading> headings, List<SectionHeading> sectionHeadings, Signature signature,
                                   String printBlocked, Share share) {

        @Override
        public String toString() {
            return "CasePaperContext[]";
        }
    }

    private final EmrRecordService records;
    private final ClinicLetterheadPort letterheads;
    private final CasePaperRepository paper;
    private final TeamMemberPort teamMembers;

    public EmrCasePaperService(EmrRecordService records, ClinicLetterheadPort letterheads, CasePaperRepository paper,
                               TeamMemberPort teamMembers) {
        this.records = records;
        this.letterheads = letterheads;
        this.paper = paper;
        this.teamMembers = teamMembers;
    }

    /** The heading a section's values go under; a form section and any section not listed go under Examination. */
    public static String headingOf(String sectionKey) {
        return SECTION_HEADING.getOrDefault(sectionKey, "EXAMINATION");
    }

    @Transactional(readOnly = true)
    public CasePaperContext context(ClinicUser user, UUID recordId) {
        return contextOf(user.tenantId(), records.get(user, recordId));
    }

    /** The context of a record view already read (also for the reprint of a signed prescription, ADR-025 D15). */
    public CasePaperContext contextOf(UUID tenantId, RecordView view) {
        Letterhead letterhead = letterheads.find(tenantId).orElse(new Letterhead("Clinic", null, null));
        Optional<DoctorDetails> details = teamMembers.doctorDetails(tenantId, view.header().doctor().membershipId());
        Doctor doctor = new Doctor(view.header().doctor().name(), details.map(DoctorDetails::degree).orElse(null),
                details.map(DoctorDetails::registrationNo).orElse(null));
        Optional<PaperRow> settings = paper.find(tenantId);
        String clinicLanguage = settings.map(PaperRow::secondLanguage).map(String::toUpperCase).orElse("EN");
        ClinicDefaults defaults = new ClinicDefaults(settings.map(PaperRow::size).orElse("A4"),
                "MR".equals(clinicLanguage) || "HI".equals(clinicLanguage) ? clinicLanguage : "EN",
                settings.map(PaperRow::caps).orElse(true));
        List<SectionHeading> sections = new ArrayList<>();
        for (LayoutSection section : view.layout().sections()) {
            sections.add(new SectionHeading(section.key(), headingOf(section.key())));
        }
        String blocked = view.completeBlockers().contains("ALLERGY_ALERT") ? "ALLERGY_ALERT" : null;
        return new CasePaperContext(letterhead, view.layout().departmentName(), doctor,
                new PaperPatient(view.header().patientName(), view.header().age(), view.header().sex(),
                        view.header().uhid(), view.header().ageDetail()),
                visitLine(view), defaults, view.printPreferences(), HEADINGS, List.copyOf(sections),
                new Signature(view.signedAt() != null, view.signedByName(), view.signedAt()), blocked,
                new Share(false, "Needs the WhatsApp and ABHA services"));
    }

    static String visitLine(RecordView view) {
        return switch (view.encounterType()) {
            case "IPD_ROUND" -> "IPD · round note";
            case "ER_ASSESSMENT" -> "Emergency · assessment";
            default -> "OPD · " + switch (view.header().visitType() == null ? "NEW" : view.header().visitType()) {
                case "FOLLOW_UP" -> "follow-up visit";
                case "REVISIT" -> "revisit";
                default -> "new visit";
            };
        };
    }
}
