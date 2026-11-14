package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientClinicalUpdatePort;
import com.sahaj.patient.api.PatientClinicalUpdatePort.FactsChange;
import com.sahaj.patient.api.PatientClinicalUpdatePort.FactsOutcome;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.platform.api.AuditLog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-68-3 (ADR-025 D13): the doctor's sign writes allergies and long-term conditions to the patient file only when the file
 * still matches what the visit read at open; a changed file is a CONFLICT and nothing is written. The audit holds field
 * names only, never the text.
 */
class PatientClinicalUpdateServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final PatientRepository patients = mock(PatientRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final PatientClinicalUpdateService service = new PatientClinicalUpdateService(patients, audit);

    private static PatientRow row(String allergy, String chronic, UUID mergedInto) {
        return new PatientRow(PATIENT, "UH1", "Asha Rao", 30, "F", "9000000000", null, null, allergy, chronic, false,
                mergedInto, Instant.parse("2026-01-01T00:00:00Z"), 1);
    }

    @Test
    void theFingerprintIsAStableSha256OfTheTwoTextsWithNullAsEmpty() {
        String first = PatientClinicalUpdatePort.fingerprint("Penicillin", "DM");

        assertThat(first).hasSize(64).isEqualTo(PatientClinicalUpdatePort.fingerprint("Penicillin", "DM"))
                .isNotEqualTo(PatientClinicalUpdatePort.fingerprint("Penicillin", "HTN"))
                .isNotEqualTo(PatientClinicalUpdatePort.fingerprint("PenicillinDM", null));
        assertThat(PatientClinicalUpdatePort.fingerprint(null, null))
                .isEqualTo(PatientClinicalUpdatePort.fingerprint("", ""));
    }

    @Test
    void aMatchingFingerprintWritesTheChangedFieldAndAuditsTheFieldNamesOnly() {
        when(patients.findByIdForUpdate(TENANT, PATIENT)).thenReturn(Optional.of(row("Dust", "DM", null)));

        FactsOutcome outcome = service.updateFacts(TENANT, ACTOR, PATIENT,
                PatientClinicalUpdatePort.fingerprint("Dust", "DM"), new FactsChange("Penicillin; Dust", null));

        assertThat(outcome).isEqualTo(FactsOutcome.UPDATED);
        verify(patients).updateClinicalFacts(PATIENT, "Penicillin; Dust", null, ACTOR);
        verify(audit).record("PATIENT_CLINICAL_FACTS_UPDATED", "PATIENT", PATIENT, null,
                Map.of("fields", List.of("allergy")));
    }

    @Test
    void aFileChangedSinceTheVisitOpenedIsAConflictAndNothingIsWritten() {
        when(patients.findByIdForUpdate(TENANT, PATIENT)).thenReturn(Optional.of(row("Dust; Pollen", "DM", null)));

        FactsOutcome outcome = service.updateFacts(TENANT, ACTOR, PATIENT,
                PatientClinicalUpdatePort.fingerprint("Dust", "DM"), new FactsChange("Penicillin", null));

        assertThat(outcome).isEqualTo(FactsOutcome.CONFLICT);
        verify(patients, never()).updateClinicalFacts(any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aSameValueIsNotChangedAndAMergedOrMissingRecordIsAConflict() {
        when(patients.findByIdForUpdate(TENANT, PATIENT)).thenReturn(Optional.of(row("Dust", null, null)));
        assertThat(service.updateFacts(TENANT, ACTOR, PATIENT, PatientClinicalUpdatePort.fingerprint("Dust", null),
                new FactsChange("Dust", null))).isEqualTo(FactsOutcome.NOT_CHANGED);
        assertThat(service.updateFacts(TENANT, ACTOR, PATIENT, "x", new FactsChange(null, null)))
                .isEqualTo(FactsOutcome.NOT_CHANGED);

        when(patients.findByIdForUpdate(TENANT, PATIENT)).thenReturn(Optional.of(row("Dust", null, UUID.randomUUID())));
        assertThat(service.updateFacts(TENANT, ACTOR, PATIENT, PatientClinicalUpdatePort.fingerprint("Dust", null),
                new FactsChange("X", null))).isEqualTo(FactsOutcome.CONFLICT);
        when(patients.findByIdForUpdate(TENANT, PATIENT)).thenReturn(Optional.empty());
        assertThat(service.updateFacts(TENANT, ACTOR, PATIENT, "x", new FactsChange("X", null)))
                .isEqualTo(FactsOutcome.CONFLICT);
        verify(patients, never()).updateClinicalFacts(any(), any(), any(), any());
    }

    @Test
    void aTextOverFourHundredCharactersIsACallerBug() {
        assertThatThrownBy(() -> service.updateFacts(TENANT, ACTOR, PATIENT, "x", new FactsChange("a".repeat(401), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FactsChange("Penicillin", "DM").toString()).doesNotContain("Penicillin").doesNotContain("DM");
    }
}
