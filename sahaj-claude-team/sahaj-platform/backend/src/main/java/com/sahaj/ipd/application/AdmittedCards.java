package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionViews.AdmittedClinicalFlags;
import com.sahaj.ipd.application.AdmissionViews.CardAllergy;
import com.sahaj.ipd.application.AdmissionViews.CardEscalationFlag;
import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.Banner;
import com.sahaj.ipd.application.VitalsRepository.LatestScore;
import com.sahaj.ipd.application.VitalsViews.EwsSummary;
import com.sahaj.ipd.domain.EscalationClock;
import com.sahaj.ipd.domain.MedicineSafetyCheck;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.VitalsDue;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        if (clinicalAccess) {
            return clinicalPatients.findAll(tenantId, patientIds);
        }
        Map<UUID, PatientClinical> identities = new HashMap<>();
        for (Map.Entry<UUID, PatientSummary> entry : lookup.findAllCurrent(tenantId, patientIds).entrySet()) {
            PatientSummary s = entry.getValue();
            identities.put(entry.getKey(), new PatientClinical(s.id(), s.uhid(), s.name(), s.age(), s.sex(), null));
        }
        return identities;
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
        List<BoardRow> live = safety.liveEscalations(tenantId);
        Banner banner = safety.banner(tenantId, asOf, live, row -> name(patientMap, row.patientId()));
        Map<UUID, LatestScore> latest = vitals.findLatestOfAdmitted(tenantId);
        Map<UUID, Detail> escalations = new HashMap<>();
        for (BoardRow row : live) {
            // At most one open escalation per admission (SAH-30); should two exist, the first of the board order wins.
            escalations.putIfAbsent(row.escalation().admissionId(), row.escalation());
        }
        Map<UUID, AdmittedClinicalFlags> flags = new HashMap<>();
        for (AdmittedRow row : rows) {
            UUID admissionId = row.admission().id();
            flags.put(admissionId, flags(escalations.get(admissionId), latest.get(admissionId),
                    patientMap.get(row.admission().patientId()), asOf));
        }
        return new Clinical(banner, flags);
    }

    /**
     * The clinical facts of one admitted patient at {@code asOf}. {@code escalation}, {@code latest} and {@code patient}
     * are null when none exists / the patient record cannot be found (then the allergy is NOT_RECORDED, the row is never
     * dropped). The allergy text is set only for RECORDED.
     */
    static AdmittedClinicalFlags flags(Detail escalation, LatestScore latest, PatientClinical patient, Instant asOf) {
        CardEscalationFlag escalationFlag = escalation == null ? null : new CardEscalationFlag(escalation.status(),
                EscalationClock.waitingMinutes(EscalationClock.start(escalation.raisedAt(), escalation.reopenedAt()),
                        asOf),
                escalation.targetMinutes(), SafetyRows.pastTarget(escalation, asOf));
        EwsSummary ews = latest == null ? null : new EwsSummary(latest.ewsScore(), latest.ewsRisk(), latest.recordedAt());
        boolean due = VitalsDue.isDue(true, latest == null ? null : latest.recordedAt(), asOf);
        return new AdmittedClinicalFlags(escalationFlag, ews, due, allergy(patient));
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
