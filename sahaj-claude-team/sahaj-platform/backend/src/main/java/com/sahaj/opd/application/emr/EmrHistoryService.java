package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.api.PatientIpdHistorySource;
import com.sahaj.opd.api.PatientIpdHistorySource.AdmissionHistory;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient history beside the EMR (SAH-50; ADR-022 D10): the past visits of the record's patient (OPD records and IPD
 * admissions), the details of one, the signed earlier record read-only, and the four key facts of the department. The
 * patient is always the record's patient and the access is the record's, so a history cannot be read for a patient the
 * doctor has no encounter with. Details come only from SIGNED records (until SAH-51 nothing is signed). Search runs in
 * the application over one patient's rows (at most 500 records and 200 admissions); nothing here is logged, and the
 * search text is never kept. Reads are not audited.
 */
@Service
public class EmrHistoryService {

    static final int PAGE_SIZE = 8;
    static final int MAX_RECORDS = 500;
    static final int MAX_ADMISSIONS = 200;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    public record Counts(int opd, int ipd) {
    }

    public record PastVisit(String kind, UUID id, LocalDate date, String title, String place, boolean signed) {
    }

    public record PastVisitPage(Counts counts, boolean someHidden, List<PastVisit> items, int page, int pageSize,
                                boolean hasMore) {
    }

    /** SAH-75-8 (G16-1): the heading over the medicines of a past visit. */
    public static final String MEDICINES = "MEDICINES";
    public static final String DISCHARGE_MEDICINES = "DISCHARGE_MEDICINES";
    public static final String TAKE_HOME_MEDICINES = "TAKE_HOME_MEDICINES";

    public record Line(String label, String value) {
    }

    public record DetailMedicine(String name, String frequency, int days, String when) {
    }

    public record DetailResult(String test, String value, boolean abnormal) {
    }

    public record Open(String target, UUID id) {
    }

    public record PastVisitDetail(String kind, UUID id, boolean signed, List<Line> lines,
                                  List<DetailMedicine> medicines, List<DetailResult> results, List<String> tests,
                                  boolean canCopyMedicines, boolean canRepeatTests, String copyDisabledReason,
                                  Open open, String medicinesHeading) {

        /** The 0.6.0 shape: the heading is "Medicines". */
        public PastVisitDetail(String kind, UUID id, boolean signed, List<Line> lines, List<DetailMedicine> medicines,
                               List<DetailResult> results, List<String> tests, boolean canCopyMedicines,
                               boolean canRepeatTests, String copyDisabledReason, Open open) {
            this(kind, id, signed, lines, medicines, results, tests, canCopyMedicines, canRepeatTests,
                    copyDisabledReason, open, MEDICINES);
        }
    }

    public record Fact(String key, String label, String value, boolean important) {
    }

    public record KeyFacts(String starterKey, List<Fact> facts) {
    }

    private record Row(PastVisit visit, String searchText) {
    }

    private final EmrRecordService records;
    private final EmrRecordRepository repository;
    private final PatientIpdHistorySource ipd;
    private final PatientClinicalPort patients;
    private final TeamMemberPort teamMembers;
    private final ObjectMapper mapper;
    private final EmrLayouts layouts;
    /** SAH-75-8: tells an oncology day-care visit (a SENT chemotherapy order) from another; null in a plain unit test. */
    private final com.sahaj.opd.application.emr.specialty.ChemoOrderRepository chemoOrders;

    /** Without the form layouts (a plain unit test): clinic form entries are then left out of the details. */
    public EmrHistoryService(EmrRecordService records, EmrRecordRepository repository, PatientIpdHistorySource ipd,
                             PatientClinicalPort patients, TeamMemberPort teamMembers, ObjectMapper mapper) {
        this(records, repository, ipd, patients, teamMembers, mapper, null, null);
    }

    /** Without the chemotherapy orders (a plain unit test): an oncology visit keeps the heading "Medicines". */
    public EmrHistoryService(EmrRecordService records, EmrRecordRepository repository, PatientIpdHistorySource ipd,
                             PatientClinicalPort patients, TeamMemberPort teamMembers, ObjectMapper mapper,
                             EmrLayouts layouts) {
        this(records, repository, ipd, patients, teamMembers, mapper, layouts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmrHistoryService(EmrRecordService records, EmrRecordRepository repository, PatientIpdHistorySource ipd,
                             PatientClinicalPort patients, TeamMemberPort teamMembers, ObjectMapper mapper,
                             EmrLayouts layouts,
                             com.sahaj.opd.application.emr.specialty.ChemoOrderRepository chemoOrders) {
        this.chemoOrders = chemoOrders;
        this.records = records;
        this.repository = repository;
        this.ipd = ipd;
        this.patients = patients;
        this.teamMembers = teamMembers;
        this.mapper = mapper;
        this.layouts = layouts;
    }

    // ---- list ----

    @Transactional(readOnly = true)
    public PastVisitPage list(ClinicUser user, UUID recordId, String type, String q, Integer page) {
        Authorized auth = records.authorize(user, recordId);
        String filter = type == null ? "ALL" : type;
        if (!Set.of("ALL", "OPD", "IPD").contains(filter)) {
            throw new InvalidInputException("type", "Choose All, OPD or IPD.");
        }
        String text = EmrQueryRules.searchText(q).toLowerCase(Locale.ROOT);
        int pageNo = EmrQueryRules.page(page, 200);
        if (auth.patientId() == null) {
            return new PastVisitPage(new Counts(0, 0), false, List.of(), pageNo, PAGE_SIZE, false);
        }
        UUID tenantId = user.tenantId();
        List<Row> opd = new ArrayList<>();
        List<Row> rounds = new ArrayList<>();
        List<RecordRow> mine = repository.forPatient(tenantId, auth.patientId(), MAX_RECORDS);
        Map<UUID, String> names = doctorNames(tenantId, mine);
        for (RecordRow row : mine) {
            if (row.id().equals(recordId) || "ER_ASSESSMENT".equals(row.encounterType())) {
                continue;
            }
            if ("OPD_VISIT".equals(row.encounterType())) {
                opd.add(opdRow(row, names));
            } else if (sameAdmission(auth.row(), row)) {
                rounds.add(opdRow(row, names));
            }
        }
        List<Row> admissions = new ArrayList<>();
        boolean hidden = false;
        for (AdmissionHistory admission : ipd.admissionsOf(tenantId, auth.patientId(), MAX_ADMISSIONS)) {
            if (admission.access() == PatientIpdHistorySource.Access.ALLOWED) {
                admissions.add(admissionRow(admission, tenantId));
            } else {
                hidden = true;
            }
        }
        List<Row> shown = new ArrayList<>(rounds);
        if (!"IPD".equals(filter)) {
            shown.addAll(opd);
        }
        List<Row> all = new ArrayList<>(shown);
        if (!"OPD".equals(filter)) {
            all.addAll(admissions);
        }
        all.removeIf(row -> !text.isEmpty() && !row.searchText().contains(text));
        List<Row> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparing((Row r) -> rounds.contains(r) ? 0 : 1)
                .thenComparing((Row r) -> r.visit().date(), Comparator.reverseOrder()));
        int from = Math.min(ordered.size(), pageNo * PAGE_SIZE);
        int to = Math.min(ordered.size(), from + PAGE_SIZE);
        List<PastVisit> items = ordered.subList(from, to).stream().map(Row::visit).toList();
        return new PastVisitPage(new Counts(opd.size() + rounds.size(), admissions.size()), hidden, items, pageNo,
                PAGE_SIZE, to < ordered.size());
    }

    private static boolean sameAdmission(RecordRow current, RecordRow other) {
        return "IPD_ROUND".equals(current.encounterType()) && current.ipdAdmissionId() != null
                && current.ipdAdmissionId().equals(other.ipdAdmissionId());
    }

    private Map<UUID, String> doctorNames(UUID tenantId, List<RecordRow> mine) {
        Set<UUID> ids = new HashSet<>();
        for (RecordRow row : mine) {
            ids.add(row.openedByMembershipId());
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Map.Entry<UUID, TeamMember> entry : teamMembers.findAll(tenantId, ids).entrySet()) {
            names.put(entry.getKey(), entry.getValue().name());
        }
        return names;
    }

    private Row opdRow(RecordRow row, Map<UUID, String> names) {
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        boolean signed = "SIGNED".equals(row.status());
        String title = titleOf(document);
        String place = "Dr. " + names.getOrDefault(row.openedByMembershipId(), "Doctor") + " · "
                + row.departmentName();
        LocalDate date = (signed && row.signedAt() != null ? row.signedAt() : row.createdAt()).atZone(INDIA)
                .toLocalDate();
        String kind = "IPD_ROUND".equals(row.encounterType()) ? "IPD_ROUND" : "OPD";
        StringBuilder search = new StringBuilder(title).append(' ').append(place);
        if (signed) {
            search.append(' ').append(searchableContent(document));
        }
        return new Row(new PastVisit(kind, row.id(), date, title, place, signed), search.toString().toLowerCase(Locale.ROOT));
    }

    private Row admissionRow(AdmissionHistory a, UUID tenantId) {
        String doctor = teamMembers.find(tenantId, a.doctorMembershipId()).map(TeamMember::name).orElse("Doctor");
        String title = (a.reason() == null || a.reason().isBlank() ? "Admission" : a.reason())
                + (a.dayCare() ? " · day care" : "");
        String place = "Dr. " + doctor + " · " + a.ward() + " bed " + a.bedCode();
        return new Row(new PastVisit("IPD", a.admissionId(), a.admittedOn(), title, place, true),
                (title + " " + place).toLowerCase(Locale.ROOT));
    }

    /** First active diagnosis, else first complaint, else the visit label (ADR-022 D10). */
    private String titleOf(EmrRecordDocument document) {
        String diagnosis = firstActiveItem(document, "DIAGNOSIS");
        if (diagnosis != null) {
            return diagnosis;
        }
        String complaint = firstActiveItem(document, "COMPLAINTS");
        return complaint != null ? complaint : "OPD visit";
    }

    private static String firstActiveItem(EmrRecordDocument document, String kind) {
        for (String key : document.sectionKeys()) {
            for (JsonNode value : document.values(key)) {
                if (kind.equals(value.path("kind").asText())) {
                    for (JsonNode item : value.path("items")) {
                        if (item.path("active").asBoolean(true)) {
                            return item.path("text").asText();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Complaint, diagnosis name and code, medicine and test names of a signed record (ADR-022 D10 search list). */
    private static String searchableContent(EmrRecordDocument document) {
        StringBuilder text = new StringBuilder();
        for (String key : document.sectionKeys()) {
            for (JsonNode value : document.values(key)) {
                String kind = value.path("kind").asText();
                if (Set.of("COMPLAINTS", "DIAGNOSIS", "INVESTIGATIONS").contains(kind)) {
                    for (JsonNode item : value.path("items")) {
                        text.append(' ').append(item.path("text").asText()).append(' ')
                                .append(item.path("code").asText(""));
                    }
                }
                for (JsonNode row : value.path("rows")) {
                    text.append(' ').append(row.path("name").asText());
                }
            }
        }
        return text.toString();
    }

    // ---- details ----

    @Transactional(readOnly = true)
    public PastVisitDetail detail(ClinicUser user, UUID recordId, String kind, UUID pastId) {
        Authorized auth = records.authorize(user, recordId);
        UUID tenantId = user.tenantId();
        if (!Set.of("OPD", "IPD", "IPD_ROUND").contains(kind) || auth.patientId() == null) {
            throw EmrProblemException.pastVisitNotFound();
        }
        if ("IPD".equals(kind)) {
            AdmissionHistory a = ipd.admission(tenantId, auth.patientId(), pastId)
                    .filter(h -> h.access() == PatientIpdHistorySource.Access.ALLOWED)
                    .orElseThrow(EmrProblemException::pastVisitNotFound);
            List<Line> lines = new ArrayList<>();
            lines.add(new Line("Admitted", a.admittedOn().toString()));
            if (a.dischargedOn() != null) {
                lines.add(new Line("Discharged", a.dischargedOn().toString()));
            }
            if (a.reason() != null && !a.reason().isBlank()) {
                lines.add(new Line("Reason for admission", a.reason()));
            }
            return new PastVisitDetail(kind, pastId, true, lines, List.of(), List.of(), List.of(), false, false, null,
                    new Open("ADMISSION", pastId), DISCHARGE_MEDICINES);
        }
        RecordRow past = repository.findById(tenantId, pastId).orElseThrow(EmrProblemException::pastVisitNotFound);
        if (!auth.patientId().equals(past.patientId()) || recordId.equals(pastId)
                || ("OPD".equals(kind) != "OPD_VISIT".equals(past.encounterType()))) {
            throw EmrProblemException.pastVisitNotFound();
        }
        boolean currentSigned = "SIGNED".equals(auth.row().status());
        if (!"SIGNED".equals(past.status())) {
            return new PastVisitDetail(kind, pastId, false, List.of(), List.of(), List.of(), List.of(), false, false,
                    null, new Open("RECORD", pastId), headingOf(kind, false));
        }
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, past.sections());
        List<DetailMedicine> medicines = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        List<DetailResult> results = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        // SAH-59: clinic form entries (form, version, values) of the signed record, read through the record's own
        // form references; their lists still feed the medicines and tests, their lines come from the form definition.
        Map<String, EmrModel.LayoutSection> forms = new LinkedHashMap<>();
        if (layouts != null && !EmrFormRefs.parse(mapper, past.formRefs()).isEmpty()) {
            for (EmrModel.LayoutSection section : layouts.snapshot(tenantId, past).sections()) {
                if (section.formSection() && section.form() != null) {
                    forms.put(section.key(), section);
                }
            }
        }
        for (String key : document.sectionKeys()) {
            JsonNode values = document.values(key);
            List<Line> target = forms.containsKey(key) ? new ArrayList<>() : lines;
            values.fields().forEachRemaining(entry -> collect(key, entry.getValue(), medicines, tests, results, target));
        }
        for (EmrModel.LayoutSection section : forms.values()) {
            if (document.has(section.key())) {
                for (EmrFormValues.Line line : EmrFormValues.lines(section.form(), document.values(section.key()))) {
                    lines.add(new Line(section.name() + " · " + line.label(), line.value()));
                }
            }
        }
        boolean canMedicines = !currentSigned && !medicines.isEmpty();
        boolean canTests = !currentSigned && !tests.isEmpty();
        return new PastVisitDetail(kind, pastId, true, lines, medicines, results, tests, canMedicines, canTests,
                currentSigned ? "RECORD_SIGNED" : null, new Open("RECORD", pastId),
                headingOf(kind, takeHome(tenantId, past)));
    }

    /** "Medicines"; "Discharge medicines" for an IPD round; "Take-home medicines" for a day-care chemotherapy visit. */
    static String headingOf(String kind, boolean takeHome) {
        if ("IPD".equals(kind) || "IPD_ROUND".equals(kind)) {
            return DISCHARGE_MEDICINES;
        }
        return takeHome ? TAKE_HOME_MEDICINES : MEDICINES;
    }

    /** An OPD record of the oncology profile with a SENT chemotherapy order (ADR-026 D10). */
    private boolean takeHome(UUID tenantId, RecordRow past) {
        return chemoOrders != null && "OPD_VISIT".equals(past.encounterType()) && "onco".equals(past.starterKey())
                && chemoOrders.findByRecord(tenantId, past.id()).filter(o -> "SENT".equals(o.status)).isPresent();
    }

    private void collect(String sectionKey, JsonNode value, List<DetailMedicine> medicines, List<String> tests,
                         List<DetailResult> results, List<Line> lines) {
        String component = value.path("component").asText("");
        if (EmrComponentBinding.PRESCRIPTION.equals(component)) {
            for (JsonNode row : value.path("rows")) {
                medicines.add(new DetailMedicine(row.path("name").asText(), row.path("frequency").asText(),
                        row.path("days").asInt(), row.path("when").asText()));
            }
        } else if (EmrComponentBinding.EMR_LIST.equals(component)) {
            List<String> texts = new ArrayList<>();
            for (JsonNode item : value.path("items")) {
                if (item.path("active").asBoolean(true)) {
                    texts.add(item.path("text").asText());
                }
            }
            if ("INVESTIGATIONS".equals(value.path("kind").asText())) {
                tests.addAll(texts);
            }
            if (!texts.isEmpty()) {
                lines.add(new Line(EmrCasePaperService.HEADINGS.stream().filter(h -> h.heading().equals(
                        EmrCasePaperService.headingOf(sectionKey))).map(EmrCasePaperService.Heading::en).findFirst()
                        .orElse("Findings"), String.join(", ", texts)));
            }
        } else if ("results".equals(sectionKey) || value.isArray() && value.size() > 0
                && value.get(0).has("reference_range")) {
            for (JsonNode row : value) {
                results.add(new DetailResult(row.path("test").asText("Result"),
                        (row.path("value").asText("") + " " + row.path("unit").asText("")).strip(),
                        "true".equals(row.path("abnormal").asText())));
            }
        }
    }

    // ---- open a signed earlier record ----

    /** The checks of "Open full visit": a SIGNED record of the same patient (the view itself is built by the caller). */
    @Transactional(readOnly = true)
    public RecordRow pastRecord(ClinicUser user, UUID recordId, UUID pastRecordId) {
        Authorized auth = records.authorize(user, recordId);
        RecordRow past = repository.findById(user.tenantId(), pastRecordId)
                .orElseThrow(EmrProblemException::pastVisitNotFound);
        if (!"SIGNED".equals(past.status()) || auth.patientId() == null || !auth.patientId().equals(past.patientId())) {
            throw EmrProblemException.pastVisitNotFound();
        }
        return past;
    }

    // ---- key facts ----

    record FactDef(String key, String label, boolean important, boolean patientAllergy, boolean patientChronic,
                           List<String> fields, boolean rx) {
    }

    private static FactDef allergies() {
        return new FactDef("allergies", "Allergies", true, true, false,
                List.of("allergy.drug_allergy", "allergy.food_other"), false);
    }

    private static FactDef chronic(String key, String label) {
        return new FactDef(key, label, false, false, true, List.of("history.past_illness"), false);
    }

    private static FactDef fields(String key, String label, boolean important, String... paths) {
        return new FactDef(key, label, important, false, false, List.of(paths), false);
    }

    /** Four facts per department as drawn (SAH-50-2); the other departments use the General Medicine labels. */
    static List<FactDef> definitions(String starterKey) {
        String key = starterKey == null ? "gm" : starterKey;
        return switch (key) {
            case "dental" -> List.of(allergies(), chronic("medical", "Medical"),
                    fields("habits", "Habits", true, "history.habits"), fields("past_dental", "Past dental", false));
            case "og" -> List.of(allergies(), fields("obstetric", "Obstetric", true, "obstetric.previous_deliveries"),
                    fields("blood_group", "Blood group", false), fields("risk", "Risk", true, "obstetric.previous_lscs"));
            case "paeds" -> List.of(allergies(), fields("birth", "Birth", false, "birth.findings"),
                    fields("vaccines", "Vaccines", true, "immunization.given"),
                    fields("past_illness", "Past illness", false, "history.past_illness"));
            case "physio" -> List.of(fields("diagnosis_last", "Diagnosis", false),
                    fields("referred_by", "Referred by", false), chronic("medical", "Medical"),
                    fields("package", "Package", false, "sessions.package"));
            case "onco" -> List.of(fields("diagnosis_last", "Diagnosis", true), fields("receptors", "Receptors", false),
                    allergies(), fields("scheme", "Scheme", false, "onc_scheme.findings"));
            case "surgery" -> List.of(allergies(), fields("past_surgery", "Past surgery", false,
                    "history.surgical_history"), new FactDef("medical", "Medical", true, false, true,
                    List.of("history.past_illness"), false), fields("blood_thinners", "Blood thinners", false,
                    "sx_fitness.blood_thinners"));
            default -> List.of(allergies(), chronic("long_term", "Long-term"),
                    fields("surgeries", "Surgeries", false, "history.surgical_history"),
                    new FactDef("regular_meds", "Regular meds", false, false, false, List.of(), true));
        };
    }

    @Transactional(readOnly = true)
    public KeyFacts keyFacts(ClinicUser user, UUID recordId) {
        Authorized auth = records.authorize(user, recordId);
        UUID tenantId = user.tenantId();
        String starterKey = auth.row().starterKey();
        Optional<PatientClinical> patient = auth.patientId() == null ? Optional.empty()
                : patients.find(tenantId, auth.patientId());
        List<EmrRecordDocument> signed = new ArrayList<>();
        if (auth.patientId() != null) {
            for (EmrRecordRepository.SignedCopy copy : repository.signedForPatient(tenantId, auth.patientId(), 20)) {
                signed.add(EmrRecordDocument.parse(mapper, copy.sections()));
            }
        }
        List<Fact> facts = new ArrayList<>();
        for (FactDef def : definitions(starterKey)) {
            List<String> parts = new ArrayList<>();
            if (def.patientAllergy()) {
                patient.map(PatientClinical::allergyText).map(String::strip).filter(s -> !s.isEmpty())
                        .ifPresent(parts::add);
            }
            if (def.patientChronic()) {
                patient.map(PatientClinical::chronicText).map(String::strip).filter(s -> !s.isEmpty())
                        .ifPresent(parts::add);
            }
            for (String path : def.fields()) {
                latest(signed, path).ifPresent(parts::add);
            }
            if (def.rx()) {
                latestRx(signed).ifPresent(parts::add);
            }
            if ("diagnosis_last".equals(def.key())) {
                latestDiagnosis(signed).ifPresent(parts::add);
            }
            String value = parts.isEmpty() ? null : trim(String.join("; ", parts));
            facts.add(new Fact(def.key(), def.label(), value, def.important() && value != null));
        }
        return new KeyFacts(starterKey, facts);
    }

    private static String trim(String text) {
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private Optional<String> latest(List<EmrRecordDocument> signedNewestFirst, String path) {
        String section = path.substring(0, path.indexOf('.'));
        String field = path.substring(path.indexOf('.') + 1);
        for (EmrRecordDocument document : signedNewestFirst) {
            String text = render(document.values(section).get(field));
            if (text != null) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private Optional<String> latestRx(List<EmrRecordDocument> signedNewestFirst) {
        for (EmrRecordDocument document : signedNewestFirst) {
            List<String> names = new ArrayList<>();
            for (String key : document.sectionKeys()) {
                for (JsonNode value : document.values(key)) {
                    for (JsonNode row : value.path("rows")) {
                        names.add(row.path("name").asText());
                    }
                }
            }
            if (!names.isEmpty()) {
                return Optional.of(String.join(", ", names));
            }
        }
        return Optional.empty();
    }

    private Optional<String> latestDiagnosis(List<EmrRecordDocument> signedNewestFirst) {
        for (EmrRecordDocument document : signedNewestFirst) {
            String diagnosis = firstActiveItem(document, "DIAGNOSIS");
            if (diagnosis != null) {
                return Optional.of(diagnosis);
            }
        }
        return Optional.empty();
    }

    /** A stored value as one line of text; null when empty. */
    static String render(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText().isBlank() ? null : value.asText().strip();
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "Yes" : null;
        }
        List<String> parts = new ArrayList<>();
        JsonNode list = value.has("items") ? value.get("items") : value.has("rows") ? value.get("rows") : value;
        if (list.isArray()) {
            for (JsonNode entry : list) {
                if (!entry.path("active").asBoolean(true)) {
                    continue;
                }
                String one = entry.isTextual() ? entry.asText() : entry.has("text") ? entry.path("text").asText()
                        : entry.has("name") ? entry.path("name").asText() : firstText(entry);
                if (one != null && !one.isBlank()) {
                    parts.add(one.strip());
                }
            }
        } else if (value.has("text")) {
            return render(value.get("text"));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String firstText(JsonNode object) {
        for (JsonNode cell : object) {
            if (cell.isTextual() && !cell.asText().isBlank()) {
                return cell.asText();
            }
        }
        return null;
    }
}
