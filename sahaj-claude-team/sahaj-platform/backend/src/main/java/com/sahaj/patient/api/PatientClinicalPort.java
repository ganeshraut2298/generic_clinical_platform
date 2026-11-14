package com.sahaj.patient.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-31 (ADR-009 D9): what {@code ipd} needs to show an identity bar and to run the medicine safety check: UHID, name,
 * age, sex and the allergy text of the patient file (SAH-8). Implemented by {@code patient}, the owner of
 * {@code patient.patient}; {@code ipd} never reads that table. Runs in the caller's transaction (tenant already bound).
 *
 * <p>The allergy text is clinical data: it goes to the screen of the person who asked and into the safety check, and
 * is never logged, audited or put in a clinical event.
 */
public interface PatientClinicalPort {

    /**
     * {@code allergyText} is null when nothing is recorded. SAH-47-1 (ADR-020 D14) adds {@code chronicText}, the
     * patient file's existing "chronic" text for the amber "Known: ..." tag; null when nothing is recorded. It is
     * clinical data like the allergy text: screen only, never logged or audited. {@code toString} shows the id only.
     * SAH-49 to SAH-71 (ADR-023 D11) adds {@code dateOfBirth} (nullable) for a child's age in months and days and the
     * dental Child tab. The patient file holds an age in years today and no date of birth, so it is null until the patient
     * module stores one; the specialty screens then answer "not recorded" for the child's age.
     * SAH-73 (ADR-024 D1, D2): {@code age} is the effective completed years (from the date of birth as of today in
     * India when there is one, else the stored number) and {@code ageDetail} is the {@link PatientAge} that
     * {@code patient} computed with its clock (null only for a caller that builds the record by hand). The date of birth
     * is personal data: {@code opd} puts it only in the Paediatrics view, {@code ipd} in no answer at all.
     */
    record PatientClinical(UUID id, String uhid, String name, int age, String sex, String allergyText,
                           String chronicText, LocalDate dateOfBirth, PatientAge ageDetail, String factsFingerprint) {

        /**
         * The SAH-73 shape: no fingerprint. SAH-68-3 (ADR-025 D13) adds {@code factsFingerprint}, the SHA-256 of the
         * allergy and chronic texts as read ({@link PatientClinicalUpdatePort#fingerprint}); it is derived here when the
         * caller did not give one.
         */
        public PatientClinical(UUID id, String uhid, String name, int age, String sex, String allergyText,
                               String chronicText, LocalDate dateOfBirth, PatientAge ageDetail) {
            this(id, uhid, name, age, sex, allergyText, chronicText, dateOfBirth, ageDetail,
                    PatientClinicalUpdatePort.fingerprint(allergyText, chronicText));
        }

        /** The SAH-49 shape: no age detail (null). Kept so that earlier callers and tests are unchanged. */
        public PatientClinical(UUID id, String uhid, String name, int age, String sex, String allergyText,
                               String chronicText, LocalDate dateOfBirth) {
            this(id, uhid, name, age, sex, allergyText, chronicText, dateOfBirth, null);
        }

        /** The SAH-47 shape: no date of birth (null). Kept so that earlier callers and tests are unchanged. */
        public PatientClinical(UUID id, String uhid, String name, int age, String sex, String allergyText,
                               String chronicText) {
            this(id, uhid, name, age, sex, allergyText, chronicText, null, null);
        }

        /** The SAH-31 shape: no chronic text and no date of birth (null). */
        public PatientClinical(UUID id, String uhid, String name, int age, String sex, String allergyText) {
            this(id, uhid, name, age, sex, allergyText, null, null, null);
        }

        @Override
        public String toString() {
            return "PatientClinical[id=" + id + "]";
        }
    }

    /**
     * Another clinic's patient id, or any id at all, is simply absent. A merged-away record answers with the record it
     * was merged into (its id, UHID and allergy text; security review S4), so a later allergy update is never missed.
     */
    Optional<PatientClinical> find(UUID tenantId, UUID patientId);

    /**
     * Many patients, keyed by the id asked for (also for a merged-away record, which answers with the record it was
     * merged into). Unknown or other-clinic ids are absent.
     */
    Map<UUID, PatientClinical> findAll(UUID tenantId, Collection<UUID> patientIds);
}
