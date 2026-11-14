package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionViews.AdmittedClinicalFlags;
import com.sahaj.ipd.application.AdmissionViews.CardAllergy;
import com.sahaj.ipd.application.AdmissionViews.CardBpReading;
import com.sahaj.ipd.application.AdmissionViews.CardLatestVitals;
import com.sahaj.ipd.application.AdmissionViews.CardReading;
import com.sahaj.ipd.application.AdmissionViews.CardEscalationFlag;
import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.Banner;
import com.sahaj.ipd.application.WardScopeRepository.UnreviewedMark;
import com.sahaj.ipd.application.VitalsRepository.LatestScore;
import com.sahaj.ipd.application.VitalsViews.EwsSummary;
import com.sahaj.ipd.domain.EscalationClock;
import com.sahaj.ipd.domain.MedicineSafetyCheck;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.VitalsDue;
import com.sahaj.ipd.domain.card.CardAccessRule;
import com.sahaj.ipd.domain.card.CardCatalogue;
import com.sahaj.ipd.domain.card.VitalMarks;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * SAH-36 (ADR-013 D2) the reads and flags of the Admitted patient cards that the table does not need. Always a fixed
 * number of set queries, whatever the number of patients: one patient read through {@code patient.api} (R2), the live
 * escalations the banner already reads (R3, shared with the banner and the counts), the latest score of every admitted
 * admission (R4), and the dose reads of the banner (R5, R6, SAH-35 unchanged). No call per patient. There is no new rule:
 * the minutes come from {@link EscalationClock}, "vitals due" from {@link VitalsDue} (the function behind
 * {@code AdmissionDetail.vitalsDue}), the allergy status from {@link MedicineSafetyCheck#allergyStatus}.
 *
 * <p>The caller runs this inside the REPEATABLE READ transaction of the list. Names, UHID, allergy text, scores and
 * escalations are never logged.
 */
@Component
class AdmittedCards {

    private static final String PATIENT_FALLBACK = "Patient";

    private final PatientClinicalPort clinicalPatients;
    private final PatientLookupPort lookup;
    private final VitalsRepository vitals;
    private final SafetyService safety;

    AdmittedCards(PatientClinicalPort clinicalPatients, PatientLookupPort lookup, VitalsRepository vitals,
                  SafetyService safety) {
        this.clinicalPatients = clinicalPatients;
        this.lookup = lookup;
        this.vitals = vitals;
        this.safety = safety;
    }

    /** SAH-67-1: set by Spring; null in a plain unit test (then no card says "Round done"). */
    private RoundNoteRepository roundNotes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRoundNotes(RoundNoteRepository roundNotes) {
        this.roundNotes = roundNotes;
    }

    /** The database clock of the answer, read once: the base of every minute count and of "vitals due". */
    Instant asOf() {
        return safety.databaseNow();
    }

    /**
     * R2 (ADR-013 D15): the listed patients in one call; empty without ids. A caller with clinical access gets UHID,
     * name, age, sex and allergy text through {@link PatientClinicalPort}. Any other caller gets R2': one batch read
     * through {@link PatientLookupPort#findAllCurrent} (name, UHID, age, sex; merged records resolved the same way) and
     * {@link PatientClinicalPort} is never called, so the allergy text is not even loaded. On that path
     * {@code allergyText} is always null and is never used (the flags are not built for such a caller).
     */
    Map<UUID, PatientClinical> patients(UUID tenantId, Set<UUID> patientIds, boolean clinicalAccess) {
        return patients(tenantId, patientIds, clinicalAccess, false).byId();
    }

    /** The listed patients and, when asked for, their mobile numbers keyed by patient id (SAH-37, ADR-015 D6). */
    record Patients(Map<UUID, PatientClinical> byId, Map<UUID, String> mobiles) {

        @Override
        public String toString() {
            return "Patients[redacted]";
        }
    }

    /**
     * R2 with the mobile number (SAH-37, ADR-015 D6, O4; amends ADR-013 D15). The caller has decided that its role may
     * see the contact group and that the published setup has Mobile on (or the owner asks for every card fact). The
     * identities come as for {@link #patients(UUID, Set, boolean)}; {@code findAllCurrent} never carries a mobile. When
     * {@code wantMobile} the numbers come from the separate, explicit {@link PatientLookupPort#mobilesOf} (one more
     * batch, a merged record resolved as for the name); without it that method is never called.
     */
    Patients patients(UUID tenantId, Set<UUID> patientIds, boolean clinicalAccess, boolean wantMobile) {
        if (patientIds.isEmpty()) {
            return new Patients(Map.of(), Map.of());
        }
        Map<UUID, PatientClinical> identities;
        if (clinicalAccess) {
            identities = clinicalPatients.findAll(tenantId, patientIds);
        } else {
            identities = new HashMap<>();
            for (Map.Entry<UUID, PatientSummary> entry : lookup.findAllCurrent(tenantId, patientIds).entrySet()) {
                PatientSummary s = entry.getValue();
                identities.put(entry.getKey(),
                        new PatientClinical(s.id(), s.uhid(), s.name(), s.age(), s.sex(), null));
            }
        }
        Map<UUID, String> mobiles = wantMobile ? lookup.mobilesOf(tenantId, patientIds) : Map.of();
        return new Patients(identities, mobiles);
    }

    /**
     * SAH-37 (ADR-015 D6) which new card facts this answer carries: a fact is sent only when the role of the caller may
     * see its data group and the published setup has the field on in a section that is on (or the owner asked for
     * {@code cardFacts=all}). The default setup sends none.
     */
    record FactsPlan(boolean diagnosis, boolean bp, boolean pulse, boolean spo2, boolean temp, boolean rr,
                     boolean lastRecorded, boolean mobile) {

        static final FactsPlan NONE = new FactsPlan(false, false, false, false, false, false, false, false);

        /** At least one of the six vitals fields is wanted (the readings variant of R4 runs). */
        boolean anyVitals() {
            return bp || pulse || spo2 || temp || rr || lastRecorded;
        }

        /** {@code fields} are the keys of the fields to send (the shown ones, or every one for cardFacts=all). */
        static FactsPlan of(Set<String> fields, String tokenRole, String memberRole) {
            boolean clinical = CardAccessRule.mayView(CardCatalogue.G_CLINICAL, tokenRole, memberRole);
            boolean vitals = CardAccessRule.mayView(CardCatalogue.G_VITALS, tokenRole, memberRole);
            boolean contact = CardAccessRule.mayView(CardCatalogue.G_CONTACT, tokenRole, memberRole);
            return new FactsPlan(clinical && fields.contains(CardCatalogue.DIAGNOSIS),
                    vitals && fields.contains(CardCatalogue.BP), vitals && fields.contains(CardCatalogue.PULSE),
                    vitals && fields.contains(CardCatalogue.SPO2), vitals && fields.contains(CardCatalogue.TEMP),
                    vitals && fields.contains(CardCatalogue.RR),
                    vitals && fields.contains(CardCatalogue.LAST_RECORDED),
                    contact && fields.contains(CardCatalogue.MOBILE));
        }
    }

    /** The banner (it is {@code AdmissionList.safety}) and the clinical facts of each admission, by admission id. */
    record Clinical(Banner banner, Map<UUID, AdmittedClinicalFlags> flags) {

        static final Clinical NONE = new Clinical(null, Map.of());
    }

    /**
     * R3 to R6 for a caller who may read clinical data (the caller has checked token and membership role). The live
     * escalations are read once and feed the banner, the counts, the change token and the card flags, so they agree.
     */
    Clinical clinical(UUID tenantId, Instant asOf, List<AdmittedRow> rows, Map<UUID, PatientClinical> patientMap) {
        return clinical(tenantId, asOf, rows, patientMap, FactsPlan.NONE, ids -> Map.of());
    }

    /**
     * As above, plus the SAH-37 facts of {@code plan}. The readings variant of R4 runs only when the plan wants a
     * reading or Last recorded (one statement either way); the recorder names are one batch from {@code names}, only
     * when Last recorded is wanted.
     */
    Clinical clinical(UUID tenantId, Instant asOf, List<AdmittedRow> rows, Map<UUID, PatientClinical> patientMap,
                      FactsPlan plan, Function<Collection<UUID>, Map<UUID, String>> names) {
        return clinical(tenantId, asOf, rows, patientMap, plan, names, null, null);
    }

    /**
     * SAH-34: for a caller's scope. {@code visible} are the admission ids the caller may reach (null means every admitted
     * patient): the escalations of the banner, the counts and the card flags, and the dose counts, are those of the
     * scope. For the owner, {@code unreviewed} gives {@code emergencyAccessWaiting} and the change token line.
     */
    Clinical clinical(UUID tenantId, Instant asOf, List<AdmittedRow> rows, Map<UUID, PatientClinical> patientMap,
                      FactsPlan plan, Function<Collection<UUID>, Map<UUID, String>> names, Set<UUID> visible,
                      UnreviewedMark unreviewed) {
        List<BoardRow> live = safety.liveEscalations(tenantId, visible);
        Banner banner = safety.banner(tenantId, asOf, live, row -> name(patientMap, row.patientId()), visible,
                unreviewed);
        Map<UUID, LatestScore> latest = plan.anyVitals() ? vitals.findLatestReadingsOfAdmitted(tenantId)
                : vitals.findLatestOfAdmitted(tenantId);
        Map<UUID, String> recorders = Map.of();
        if (plan.lastRecorded()) {
            Set<UUID> ids = new HashSet<>();
            for (LatestScore score : latest.values()) {
                if (score.recordedBy() != null) {
                    ids.add(score.recordedBy());
                }
            }
            recorders = ids.isEmpty() ? Map.of() : names.apply(ids);
        }
        Map<UUID, Detail> escalations = new HashMap<>();
        for (BoardRow row : live) {
            // At most one open escalation per admission (SAH-30); should two exist, the first of the board order wins.
            escalations.putIfAbsent(row.escalation().admissionId(), row.escalation());
        }
        Map<UUID, AdmittedClinicalFlags> flags = new HashMap<>();
        // SAH-67-1 (ADR-025 D8): "Round done" from ipd's own round record, one statement for the whole set
        Set<UUID> roundDone = roundNotes == null || rows.isEmpty() ? Set.of() : roundNotes.doneOn(tenantId,
                asOf.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate(),
                rows.stream().map(r -> r.admission().id()).toList());
        for (AdmittedRow row : rows) {
            UUID admissionId = row.admission().id();
            flags.put(admissionId, flags(escalations.get(admissionId), latest.get(admissionId),
                    patientMap.get(row.admission().patientId()), asOf, plan, row.admission().reason(), recorders)
                    .withRoundDone(roundDone.contains(admissionId)));
        }
        return new Clinical(banner, flags);
    }

    /**
     * The clinical facts of one admitted patient at {@code asOf}. {@code escalation}, {@code latest} and {@code patient}
     * are null when none exists / the patient record cannot be found (then the allergy is NOT_RECORDED, the row is never
     * dropped). The allergy text is set only for RECORDED.
     */
    static AdmittedClinicalFlags flags(Detail escalation, LatestScore latest, PatientClinical patient, Instant asOf) {
        return flags(escalation, latest, patient, asOf, FactsPlan.NONE, null, Map.of());
    }

    /**
     * SAH-37: the same flags plus {@code diagnosis} (the reason for admission, "Under evaluation" when blank) and
     * {@code latestVitals} as far as {@code plan} asks for them. A reading is built only from a readings row; the mark
     * of each reading is from {@link VitalMarks}. {@code latestVitals} is {@code Optional.empty()} (JSON null) when the
     * plan wants vitals and none were recorded, and null (absent) when the plan wants none.
     */
    static AdmittedClinicalFlags flags(Detail escalation, LatestScore latest, PatientClinical patient, Instant asOf,
                                       FactsPlan plan, String reason, Map<UUID, String> recorderNames) {
        CardEscalationFlag escalationFlag = escalation == null ? null : new CardEscalationFlag(escalation.status(),
                EscalationClock.waitingMinutes(EscalationClock.start(escalation.raisedAt(), escalation.reopenedAt()),
                        asOf),
                escalation.targetMinutes(), SafetyRows.pastTarget(escalation, asOf));
        EwsSummary ews = latest == null ? null : new EwsSummary(latest.ewsScore(), latest.ewsRisk(), latest.recordedAt());
        boolean due = VitalsDue.isDue(true, latest == null ? null : latest.recordedAt(), asOf);
        String diagnosis = plan.diagnosis() ? diagnosis(reason) : null;
        Optional<CardLatestVitals> tiles = null;
        if (plan.anyVitals()) {
            tiles = Optional.ofNullable(latest == null ? null : tiles(latest, plan, recorderNames));
        }
        return new AdmittedClinicalFlags(escalationFlag, ews, due, allergy(patient), diagnosis, tiles);
    }

    /** The reason for admission is the Diagnosis of the card; "Under evaluation" when it was left blank. */
    private static String diagnosis(String reason) {
        return reason == null || reason.isBlank() ? AdmissionService.DEFAULT_REASON : reason;
    }

    private static CardLatestVitals tiles(LatestScore latest, FactsPlan plan, Map<UUID, String> recorderNames) {
        VitalsRepository.Readings r = latest.readings();
        String recordedBy = null;
        if (plan.lastRecorded()) {
            String found = latest.recordedBy() == null ? null : recorderNames.get(latest.recordedBy());
            recordedBy = found == null ? "Team member" : found;
        }
        if (r == null) {
            return new CardLatestVitals(latest.recordedAt(), recordedBy, null, null, null, null, null);
        }
        VitalMarks.Marks marks = VitalMarks.of(r.bpSystolic(), r.pulse(), r.spo2(), r.tempC(), r.rr());
        return new CardLatestVitals(latest.recordedAt(), recordedBy,
                plan.bp() ? new CardBpReading(r.bpSystolic(), r.bpDiastolic(), marks.bp()) : null,
                plan.pulse() ? new CardReading(BigDecimal.valueOf(r.pulse()), marks.pulse()) : null,
                plan.spo2() ? new CardReading(BigDecimal.valueOf(r.spo2()), marks.spo2()) : null,
                plan.temp() ? new CardReading(r.tempC(), marks.temp()) : null,
                plan.rr() ? new CardReading(BigDecimal.valueOf(r.rr()), marks.rr()) : null);
    }

    static CardAllergy allergy(PatientClinical patient) {
        String text = patient == null ? null : patient.allergyText();
        AllergyStatus status = MedicineSafetyCheck.allergyStatus(text);
        return new CardAllergy(status, status == AllergyStatus.RECORDED ? text : null);
    }

    private static String name(Map<UUID, PatientClinical> patientMap, UUID patientId) {
        PatientClinical patient = patientId == null ? null : patientMap.get(patientId);
        return patient == null ? PATIENT_FALLBACK : patient.name();
    }
}
