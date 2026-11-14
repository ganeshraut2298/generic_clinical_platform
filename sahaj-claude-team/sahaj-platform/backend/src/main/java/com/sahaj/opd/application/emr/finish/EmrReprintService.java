package com.sahaj.opd.application.emr.finish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.FollowUpRepository;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.emr.EmrCasePaperService;
import com.sahaj.opd.application.emr.EmrCasePaperService.CasePaperContext;
import com.sahaj.opd.application.emr.EmrComponentBinding;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrViews.RecordSection;
import com.sahaj.opd.application.emr.EmrViews.Snapshot;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.finish.FinishModels.Addendum;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx;
import com.sahaj.opd.application.emr.specialty.SpecialtyProfiles;
import com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceService;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-51-6 (ADR-025 D15, H12): reprint of a signed prescription, read only. OWNER, FRONT_DESK and PHARMACIST any visit of the
 * clinic; DOCTOR and VISITING_DOCTOR their own visits; every other role is refused. The answer is what the paper needs: the
 * case-paper context, the signed values of the sections whose "Print on prescription" was on in the record's snapshot
 * (inactive list items left out), the addenda and the follow-up date. One audit entry EMR_PRESCRIPTION_REPRINTED (visit id and
 * record id). Clinical values: screen and print only, never logged. The reprint opens no other EMR access.
 */
@Service
public class EmrReprintService {

    private static final Set<String> ANY_VISIT = Set.of("OWNER", "FRONT_DESK", "PHARMACIST");
    private static final Set<String> OWN_VISIT = Set.of("DOCTOR", "VISITING_DOCTOR");

    public record PrintSection(String key, String name, String heading, JsonNode values) {

        @Override
        public String toString() {
            return "PrintSection[key=" + key + "]";
        }
    }

    /**
     * The printable signed record (opd.yaml 0.6.1). {@code layout} is the record's frozen snapshot reduced to the printed
     * sections, {@code specialty} the profile's computed view as of the signing day and {@code specialtyReference} the
     * profile's list labels; the last two are null for a record without a profile.
     */
    public record Reprint(UUID visitId, UUID recordId, boolean reprint, Instant signedAt, CasePaperContext context,
                          List<PrintSection> sections, List<Addendum> addenda, LocalDate followUpDate, Snapshot layout,
                          JsonNode specialty, JsonNode specialtyReference) {

        @Override
        public String toString() {
            return "Reprint[visitId=" + visitId + "]";
        }
    }

    private final EmrRecordService records;
    private final EmrRecordRepository recordRows;
    private final EmrCasePaperService casePaper;
    private final VisitRepository visits;
    private final FollowUpRepository followUps;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;
    private final SpecialtyRecordSupport specialty;
    private final SpecialtyReferenceService reference;
    private final ObjectMapper mapper;

    public EmrReprintService(EmrRecordService records, EmrRecordRepository recordRows,
                             EmrCasePaperService casePaper, VisitRepository visits, FollowUpRepository followUps,
                             TeamMemberPort teamMembers, AuditLog auditLog, SpecialtyRecordSupport specialty,
                             SpecialtyReferenceService reference, ObjectMapper mapper) {
        this.specialty = specialty;
        this.reference = reference;
        this.mapper = mapper;
        this.records = records;
        this.recordRows = recordRows;
        this.casePaper = casePaper;
        this.visits = visits;
        this.followUps = followUps;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
    }

    @Transactional
    public Reprint reprint(ClinicUser user, UUID visitId) {
        UUID tenantId = user.tenantId();
        TeamMember member = teamMembers.findActiveByUserId(tenantId, user.userId())
                .orElseThrow(() -> new AccessDeniedException("Not an active member of this clinic."));
        if (!ANY_VISIT.contains(member.role()) && !OWN_VISIT.contains(member.role())) {
            throw new AccessDeniedException("You cannot reprint a prescription.");
        }
        VisitRow visit = visits.findById(tenantId, visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
        if (!ANY_VISIT.contains(member.role()) && !member.membershipId().equals(visit.doctorMembershipId())) {
            throw EmrProblemException.notOwnPatient();
        }
        // DEC-048 H32: the record's doctor and an owner get the full specialty block; the front desk and the pharmacist only
        // what the printed sections use
        boolean fullBlock = "OWNER".equals(member.role()) || OWN_VISIT.contains(member.role());
        RecordRow row = recordRows.findOpd(tenantId, visitId).orElseThrow(EmrProblemException::recordNotFound);
        if (!"SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordNotSigned();
        }
        RecordView view = records.viewForReprint(user, member, visitId);
        CasePaperContext context = casePaper.contextOf(tenantId, view);
        List<PrintSection> sections = new ArrayList<>();
        Map<String, LayoutSection> layout = new LinkedHashMap<>();
        for (LayoutSection section : view.layout().sections()) {
            layout.put(section.key(), section);
        }
        List<LayoutSection> printed = new ArrayList<>();
        for (RecordSection section : view.sections()) {
            LayoutSection definition = layout.get(section.key());
            if (definition == null || !definition.print() || !definition.shown() || section.values() == null
                    || section.values().isEmpty()) {
                continue;
            }
            printed.add(definition);
            sections.add(new PrintSection(section.key(), definition.name(), EmrCasePaperService.headingOf(section.key()),
                    activeOnly(section.values())));
        }
        Snapshot snapshot = view.layout();
        Snapshot printedLayout = new Snapshot(snapshot.departmentKey(), snapshot.departmentName(), snapshot.starterKey(),
                snapshot.layoutSource(), snapshot.versionNo(), snapshot.choiceSource(), snapshot.takenAt(),
                List.copyOf(printed));
        String profile = SpecialtyProfiles.profileOf(row.encounterType(), row.starterKey()).orElse(null);
        JsonNode specialtyView = profile == null ? null : signingDayView(tenantId, member, row, profile, view);
        if (specialtyView != null && !fullBlock) {
            specialtyView = pruneSpecialty(profile, specialtyView, printed);
        }
        JsonNode specialtyReference = profile == null ? null : reference.forProfile(tenantId, profile);
        LocalDate followUp = followUps.findBySourceVisit(tenantId, visitId).map(FollowUpRepository.VisitFollowUp::dueDate)
                .orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("recordId", row.id());
        auditLog.record("EMR_PRESCRIPTION_REPRINTED", "OPD_VISIT", visitId, null, after);
        return new Reprint(visitId, row.id(), true, row.signedAt(), context, List.copyOf(sections),
                records.addendaOf(tenantId, row.id()), followUp, printedLayout, specialtyView, specialtyReference);
    }

    /**
     * The profile's computed view with the signing day (Asia/Kolkata) as today (ADR-025 H20): ages, vaccine states, due dates
     * and the next due vaccine read as they did when the visit was signed, and only records signed before this one count as
     * earlier records. Reads only.
     */
    private JsonNode signingDayView(UUID tenantId, TeamMember member, RecordRow row, String profile, RecordView view) {
        Instant asOf = row.signedAt() == null ? row.createdAt() : row.signedAt();
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        SpecialtyCtx ctx = specialty.context(tenantId, row, profile, member.membershipId(), member.role(), asOf, document,
                asOf);
        List<String> hidden = specialty.hiddenSectionKeys(profile, ctx, row, view.layout().sections());
        return specialty.view(profile, ctx, row, hidden);
    }

    /** Marks a whole block that is not an object (the vitals flags): it follows its sections as one. */
    private static final String WHOLE = "*";
    /** The owner set of a key that always stays (the paediatrics vaccination card data). */
    private static final Set<String> ALWAYS = Set.of();

    /**
     * Profile, then block of the view, then each key of the block with the sections it is computed from (DEC-048 H32): a key
     * is kept only when one of its sections prints ("Print on prescription" on in the record's snapshot). A key not listed
     * is dropped, so a key added to a view later reaches the front desk only when this table says which section owns it.
     * Paediatrics keeps the vaccination data (and the age) for the vaccination card in any case.
     */
    private static final Map<String, Map<String, Map<String, Set<String>>>> KEY_SECTIONS = keySections();

    private static Map<String, Map<String, Map<String, Set<String>>>> keySections() {
        Map<String, Map<String, Map<String, Set<String>>>> all = new LinkedHashMap<>();
        all.put("gm", Map.of("vitalsFlags", Map.of(WHOLE, Set.of("vitals"))));
        all.put("dental", Map.of("dental", owned(
                Map.entry("estimateTotalPaise", Set.of("tx_plan")),
                Map.entry("estimateState", Set.of("tx_plan")),
                Map.entry("doneTodayRowIds", Set.of("tx_plan", "dental_done")),
                Map.entry("childTabDefault", Set.of("tx_plan", "dental_done")))));
        Set<String> anc = Set.of("anc");
        Set<String> formF = Set.of("pcpndt");
        all.put("og", Map.of("og", owned(
                Map.entry("gestationalAge", anc), Map.entry("edd", anc), Map.entry("eddSource", anc),
                Map.entry("daysToEdd", anc), Map.entry("obstetricScore", anc), Map.entry("weightChangeKg", anc),
                Map.entry("notes", anc), Map.entry("alerts", anc), Map.entry("riskChips", anc),
                Map.entry("scanStates", anc), Map.entry("dueOrders", anc),
                Map.entry("formFRequired", formF), Map.entry("formFCount", formF))));
        all.put("paeds", Map.of("paeds", owned(
                Map.entry("dateOfBirthRecorded", ALWAYS), Map.entry("dateOfBirth", ALWAYS), Map.entry("ageMonths", ALWAYS),
                Map.entry("ageDays", ALWAYS), Map.entry("vaccines", ALWAYS), Map.entry("dueTodayCount", ALWAYS),
                Map.entry("overdueCount", ALWAYS), Map.entry("nextDue", ALWAYS),
                Map.entry("growthNotes", Set.of("growth")), Map.entry("milestonesSetUp", Set.of("milestones")),
                Map.entry("doseWeightKg", Set.of("wtdose")), Map.entry("doseWeightChangedRowIds", Set.of("wtdose")))));
        all.put("physio", Map.of("physio", owned(
                Map.entry("sessionNo", Set.of("sessions")), Map.entry("sessionsPlanned", Set.of("sessions")),
                Map.entry("packageName", Set.of("sessions")), Map.entry("romPercents", Set.of("rom")),
                Map.entry("outcomeScales", Set.of("func_scales")))));
        all.put("surgery", Map.of("surgery", owned(
                Map.entry("fitness", Set.of("sx_fitness")),
                Map.entry("depositPaise", Set.of("sx_plan")),
                Map.entry("payerChip", Set.of("sx_plan", "sx_preauth")),
                Map.entry("bookState", Set.of("sx_plan")), Map.entry("bookMissing", Set.of("sx_plan")),
                Map.entry("consentCount", Set.of("sx_consent")))));
        Set<String> bsa = Set.of("onc_bsa");
        Set<String> labs = Set.of("onc_labs");
        Set<String> tox = Set.of("onc_tox");
        Set<String> regimen = Set.of("onc_regimen");
        all.put("onco", Map.of("vitalsFlags", Map.of(WHOLE, Set.of("vitals")), "onco", owned(
                Map.entry("bsa", bsa), Map.entry("bsaUsed", bsa), Map.entry("capped", bsa), Map.entry("cycle1WeightKg", bsa),
                Map.entry("weightChangePct", bsa), Map.entry("recalculateDoses", bsa), Map.entry("lesionChanges", bsa),
                Map.entry("sums", bsa), Map.entry("response", bsa),
                Map.entry("labFails", labs), Map.entry("labsBadge", labs), Map.entry("labsFresh", labs),
                Map.entry("fever", Set.of("onc_tox", "vitals")), Map.entry("feverOrAdmissionEvent", tox),
                Map.entry("worstGrade", tox), Map.entry("pegfilgrastimSuggested", Set.of("onc_tox", "onc_regimen")),
                Map.entry("ecogText", Set.of("onc_ecog")),
                Map.entry("lineReady", regimen), Map.entry("suggestion", regimen), Map.entry("reasons", regimen),
                Map.entry("decision", regimen), Map.entry("factsChanged", regimen), Map.entry("alerts", regimen),
                Map.entry("cumulativeDoxorubicinMgM2", regimen), Map.entry("cycleNo", regimen),
                Map.entry("nextCycle", regimen), Map.entry("orderStatus", regimen))));
        return all;
    }

    @SafeVarargs
    private static Map<String, Set<String>> owned(Map.Entry<String, Set<String>>... entries) {
        return Map.ofEntries(entries);
    }

    private static final Set<String> VIEW_FRAME = Set.of("profile", "mode", "hiddenSectionKeys", "tagNote");

    /**
     * The specialty view for the front desk and the pharmacist: the frame, and from each profile block only the keys whose own
     * section prints (DEC-048 H32, key level: the ANC keys follow the ANC section, the Form F keys the PCPNDT section, each
     * oncology, surgery, dental, physiotherapy and paediatrics key its own section). A block left with no key is left out.
     * The Paediatrics vaccination data (vaccines, counts, next due, age) always stays: the card prints with every paediatrics
     * prescription.
     */
    static JsonNode pruneSpecialty(String profile, JsonNode view, List<LayoutSection> printed) {
        Set<String> printedKeys = new java.util.HashSet<>();
        printed.forEach(section -> printedKeys.add(section.key()));
        ObjectNode pruned = ((ObjectNode) view).objectNode();
        Map<String, Map<String, Set<String>>> blocks = KEY_SECTIONS.getOrDefault(profile, Map.of());
        view.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            Map<String, Set<String>> keys = blocks.get(name);
            if (VIEW_FRAME.contains(name)) {
                pruned.set(name, entry.getValue().deepCopy());
            } else if (keys == null) {
                return;
            } else if (keys.containsKey(WHOLE)) {
                if (printsAny(keys.get(WHOLE), printedKeys)) {
                    pruned.set(name, entry.getValue().deepCopy());
                }
            } else if (entry.getValue().isObject()) {
                ObjectNode kept = ((ObjectNode) entry.getValue()).objectNode();
                entry.getValue().fields().forEachRemaining(field -> {
                    Set<String> owners = keys.get(field.getKey());
                    if (owners != null && (owners.isEmpty() || printsAny(owners, printedKeys))) {
                        kept.set(field.getKey(), field.getValue().deepCopy());
                    }
                });
                if (kept.size() > 0) {
                    pruned.set(name, kept);
                }
            }
        });
        return pruned;
    }

    private static boolean printsAny(Set<String> owners, Set<String> printedKeys) {
        return owners.stream().anyMatch(printedKeys::contains);
    }

    /** A copy of the section values without the inactive items of the lists (a removed item is never printed). */
    static JsonNode activeOnly(JsonNode values) {
        if (!values.isObject()) {
            return values.deepCopy();
        }
        ObjectNode copy = ((ObjectNode) values).objectNode();
        values.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject() && EmrComponentBinding.EMR_LIST.equals(value.path("component").asText())
                    && value.path("items").isArray()) {
                ObjectNode list = ((ObjectNode) value.deepCopy());
                ArrayNode kept = list.putArray("items");
                for (JsonNode item : value.path("items")) {
                    if (item.path("active").asBoolean(true)) {
                        kept.add(item.deepCopy());
                    }
                }
                copy.set(entry.getKey(), list);
            } else {
                copy.set(entry.getKey(), value.deepCopy());
            }
        });
        return copy;
    }
}
