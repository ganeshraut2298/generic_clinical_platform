package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.finish.FinishModels;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import com.sahaj.patient.api.PatientAge;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The EMR answers of docs/api/opd.yaml 0.3.0 as the application layer hands them to the web layer. The property names
 * are the contract's. Library and layout records are in {@link EmrModel}. These records hold setup text, and, for the
 * record views, patient data for the screen only: none of it is ever logged or audited.
 */
public final class EmrViews {

    private EmrViews() {
    }

    // ---- SAH-43 ----

    public record MasterItem(UUID id, String name, String specialtyHint, String nameMr, String nameHi) {
    }

    public record MasterPage(List<MasterItem> items, long total, int page, int pageSize) {
    }

    public record QuickOption(String itemKey, String name, String icd10Code, String form, Boolean favourite,
                              int useCount, Instant lastUsedAt, String nameMr, String nameHi) {
    }

    public record QuickOptionList(String master, List<QuickOption> items) {
    }

    /** ADR-020 D7: why this department. choiceSource is DOCTOR_SPECIALTY, DOCTOR_DEPARTMENT, CLINIC_DEFAULT or SWITCHED. */
    public record DepartmentChoice(String departmentKey, String departmentName, String starterKey,
                                   String layoutSource, Integer versionNo, String choiceSource) {
    }

    public record DepartmentRef(String departmentKey, String name) {
    }

    public record DoctorContext(UUID membershipId, String name, String role, String specialty, String degree,
                                String registrationNo, List<String> missing, DepartmentChoice department,
                                List<DepartmentRef> departments) {
    }

    // ---- SAH-53, SAH-54 ----

    public record StarterSummary(String starterKey, String name, String initials, String colour, String description,
                                 int sectionCount, int specialtySectionCount, List<String> offAtStart) {
    }

    public record StarterDetail(String starterKey, String name, String initials, String colour, String description,
                                int sectionCount, int specialtySectionCount, List<String> offAtStart,
                                int layoutVersion, List<LayoutSection> sections) {
    }

    public record DepartmentSummary(String departmentKey, String name, String initials, String colour,
                                    String starterKey, String sourceKind, String copiedFromKey, String status,
                                    int publishedVersion, boolean hasDraft, int sectionsTotal, int sectionsOn,
                                    int requiredOn, int doctorCount, List<String> rooms) {
    }

    public record DepartmentList(boolean canEdit, List<DepartmentSummary> departments) {
    }

    public record DepartmentView(String departmentKey, String name, String initials, String colour,
                                 String starterKey, String sourceKind, String copiedFromKey, String status,
                                 int publishedVersion, boolean hasDraft, int sectionsTotal, int sectionsOn,
                                 int requiredOn, int doctorCount, List<String> rooms, boolean canEdit, long revision,
                                 String layoutSource, List<LayoutSection> sections) {
    }

    public record DraftSaveResult(String outcome, DepartmentView department) {
    }

    public record PublishResult(String outcome, int publishedVersion, DepartmentView department) {
    }

    public record ResetTarget(String departmentKey, String basis, List<SectionSettings> sections) {
    }

    public record PublishedLayout(String departmentKey, String departmentName, String starterKey,
                                  String layoutSource, Integer versionNo, List<LayoutSection> sections) {
    }

    public record DepartmentVersion(String departmentKey, int versionNo, Instant publishedAt,
                                    String publishedByName, List<LayoutSection> sections) {
    }

    public record DoctorOption(UUID membershipId, String name, String role, String degree) {
    }

    // ---- SAH-46, SAH-47, SAH-52 ----

    public record DoctorOnRecord(UUID membershipId, String name, String room) {
    }

    public record AdmissionOnRecord(UUID admissionId, String ipNumber, int dayNumber, String status) {
    }

    public record EmergencyOnRecord(UUID caseId, int triageLevel, String triageLevelName, String status) {
    }

    /**
     * SAH-73 (ADR-024 D2): the age the screen shows. {@code value} and {@code unit} (YEARS, MONTHS, WEEKS or DAYS) are
     * worked out by the patient module as of today in India; it carries no date and no day count (the date of birth never
     * leaves through the header).
     */
    public record AgeDetail(int value, String unit, boolean fromDateOfBirth) {

        /** Null in, null out (an Emergency case with no patient record). */
        public static AgeDetail from(PatientAge age) {
            return age == null ? null : new AgeDetail(age.value(), age.unit().name(), age.fromDateOfBirth());
        }
    }

    public record RecordHeader(UUID patientId, String patientName, String initials, Integer age, String sex,
                               String uhid, String allergyText, String chronicText, Integer lastVisitDaysAgo,
                               Integer visitOrdinal, String visitType, DoctorOnRecord doctor,
                               AdmissionOnRecord admission, EmergencyOnRecord emergency, boolean mlc,
                               AgeDetail ageDetail) {

        /** The shape before SAH-73: no age detail. */
        public RecordHeader(UUID patientId, String patientName, String initials, Integer age, String sex,
                            String uhid, String allergyText, String chronicText, Integer lastVisitDaysAgo,
                            Integer visitOrdinal, String visitType, DoctorOnRecord doctor,
                            AdmissionOnRecord admission, EmergencyOnRecord emergency, boolean mlc) {
            this(patientId, patientName, initials, age, sex, uhid, allergyText, chronicText, lastVisitDaysAgo,
                    visitOrdinal, visitType, doctor, admission, emergency, mlc, null);
        }

        /** Patient data: the id only, so that a stray log line of the header holds no name. */
        @Override
        public String toString() {
            return "RecordHeader[patientId=" + patientId + "]";
        }
    }

    public record Snapshot(String departmentKey, String departmentName, String starterKey, String layoutSource,
                           Integer versionNo, String choiceSource, Instant takenAt, List<LayoutSection> sections) {
    }

    public record CopiedFrom(UUID recordId, LocalDate date) {
    }

    /** SAH-68-1: "Recorded by <recordedByName> · Nurse at <time>" while the values are the nurse's entry. */
    public record PrefilledFrom(String source, UUID sourceEntryId, String recordedByName, Instant recordedAt) {

        @Override
        public String toString() {
            return "PrefilledFrom[sourceEntryId=" + sourceEntryId + "]";
        }
    }

    public record RecordSection(String key, String status, JsonNode values, CopiedFrom copiedFrom,
                                boolean copyPendingConfirmation, Instant updatedAt, JsonNode legacyValues,
                                PrefilledFrom prefilledFrom) {

        /** Without a nurse entry the values came from (0.5.0 shape). */
        public RecordSection(String key, String status, JsonNode values, CopiedFrom copiedFrom,
                             boolean copyPendingConfirmation, Instant updatedAt, JsonNode legacyValues) {
            this(key, status, values, copiedFrom, copyPendingConfirmation, updatedAt, legacyValues, null);
        }

        /** Without legacy values (a section that has no specialty editor). */
        public RecordSection(String key, String status, JsonNode values, CopiedFrom copiedFrom,
                             boolean copyPendingConfirmation, Instant updatedAt) {
            this(key, status, values, copiedFrom, copyPendingConfirmation, updatedAt, null, null);
        }

        /** Clinical values: the key only. */
        @Override
        public String toString() {
            return "RecordSection[key=" + key + "]";
        }
    }

    public record PartProgress(String part, int requiredDone, int requiredTotal, boolean complete, int sectionsDone,
                               int sectionsTotal) {
    }

    public record UiState(String part, String sectionKey) {
    }

    public record IpdState(String state, boolean canSwitchOn) {
    }

    public record RecordView(UUID id, String encounterType, UUID encounterId, LocalDate roundDate, String status,
                             boolean readOnly, String readOnlyReason, long revision, RecordHeader header,
                             Snapshot layout, List<RecordSection> sections, List<PartProgress> parts,
                             List<String> stillNeeded, boolean readyToComplete, UiState uiState,
                             boolean admissionAdvised, String referralLine, List<DepartmentRef> departmentOptions,
                             IpdState ipd, List<AllergyAlert> allergyAlerts, List<String> completeBlockers,
                             LastSignedPrescription lastSignedPrescription, PrintPreferences printPreferences,
                             boolean prescriptionIsOrder, List<StillNeededItem> stillNeededItems,
                             JsonNode specialty, Instant signedAt, String signedByName, FinishModels.EditHold editHold,
                             List<FinishModels.Addendum> addenda, FinishModels.AdmissionState admission,
                             boolean canAddAddendum) {

        /** The phase 4 shape: nothing of the finish and sign (docs 0.6.0 adds it). */
        public RecordView(UUID id, String encounterType, UUID encounterId, LocalDate roundDate, String status,
                          boolean readOnly, String readOnlyReason, long revision, RecordHeader header,
                          Snapshot layout, List<RecordSection> sections, List<PartProgress> parts,
                          List<String> stillNeeded, boolean readyToComplete, UiState uiState,
                          boolean admissionAdvised, String referralLine, List<DepartmentRef> departmentOptions,
                          IpdState ipd, List<AllergyAlert> allergyAlerts, List<String> completeBlockers,
                          LastSignedPrescription lastSignedPrescription, PrintPreferences printPreferences,
                          boolean prescriptionIsOrder, List<StillNeededItem> stillNeededItems, JsonNode specialty) {
            this(id, encounterType, encounterId, roundDate, status, readOnly, readOnlyReason, revision, header, layout,
                    sections, parts, stillNeeded, readyToComplete, uiState, admissionAdvised, referralLine,
                    departmentOptions, ipd, allergyAlerts, completeBlockers, lastSignedPrescription,
                    printPreferences, prescriptionIsOrder, stillNeededItems, specialty, null, null, null, List.of(),
                    FinishModels.AdmissionState.none(), false);
        }

        /** The phase 3 shape: no footer items and no specialty view (docs 0.5.0 adds both). */
        public RecordView(UUID id, String encounterType, UUID encounterId, LocalDate roundDate, String status,
                          boolean readOnly, String readOnlyReason, long revision, RecordHeader header,
                          Snapshot layout, List<RecordSection> sections, List<PartProgress> parts,
                          List<String> stillNeeded, boolean readyToComplete, UiState uiState,
                          boolean admissionAdvised, String referralLine, List<DepartmentRef> departmentOptions,
                          IpdState ipd, List<AllergyAlert> allergyAlerts, List<String> completeBlockers,
                          LastSignedPrescription lastSignedPrescription, PrintPreferences printPreferences,
                          boolean prescriptionIsOrder) {
            this(id, encounterType, encounterId, roundDate, status, readOnly, readOnlyReason, revision, header, layout,
                    sections, parts, stillNeeded, readyToComplete, uiState, admissionAdvised, referralLine,
                    departmentOptions, ipd, allergyAlerts, completeBlockers, lastSignedPrescription,
                    printPreferences, prescriptionIsOrder, List.of(), null);
        }

        /** The same record with the finish and sign facts (signature, hold, addenda, admission state). */
        public RecordView withFinish(boolean readOnlyNow, String reason, Instant signedAtNow, String signedBy,
                                     FinishModels.EditHold hold, List<FinishModels.Addendum> notes,
                                     FinishModels.AdmissionState admissionNow, boolean canAddNote,
                                     boolean orderPrescription) {
            return new RecordView(id, encounterType, encounterId, roundDate, status, readOnlyNow, reason, revision, header,
                    layout, sections, parts, stillNeeded, readyToComplete, uiState, admissionAdvised, referralLine,
                    departmentOptions, ipd, allergyAlerts, completeBlockers, lastSignedPrescription, printPreferences,
                    orderPrescription, stillNeededItems, specialty, signedAtNow, signedBy, hold, notes, admissionNow,
                    canAddNote);
        }

        /** The same record opened from Past visits: read-only, reason HISTORY (SAH-50-4). */
        public RecordView asHistory() {
            return new RecordView(id, encounterType, encounterId, roundDate, status, true, "HISTORY", revision,
                    header, layout, sections, parts, stillNeeded, readyToComplete, uiState, admissionAdvised,
                    referralLine, List.of(), ipd, allergyAlerts, completeBlockers, lastSignedPrescription,
                    printPreferences, prescriptionIsOrder, stillNeededItems, specialty, signedAt, signedByName, editHold,
                    addenda, admission, canAddAddendum);
        }

        @Override
        public String toString() {
            return "RecordView[id=" + id + ", encounterType=" + encounterType + "]";
        }
    }

    // ---- phase 3 (ADR-022) ----

    /** A drug-class clash; screen only. {@code toString} shows ids and the class, never the medicine name. */
    public record AllergyAlert(String sectionKey, String fieldKey, UUID rowId, String medicineKey,
                               String medicineName, String allergyClass, String allergyClassLabel, boolean reviewed,
                               Instant reviewedAt, String reviewedByName) {

        @Override
        public String toString() {
            return "AllergyAlert[rowId=" + rowId + ", allergyClass=" + allergyClass + ", reviewed=" + reviewed + "]";
        }
    }

    public record LastSignedPrescription(UUID recordId, LocalDate date) {
    }

    /** ADR-023 D3: one footer item. {@code key} is a section key or ONC_DECISION or PHYSIO_TREATMENT_TODAY. */
    public record StillNeededItem(String key, String sectionKey, String label, Integer count) {
    }

    public record PrintPreferences(String language, String paperSize) {
    }

    public record AllergyClass(String classKey, String label) {
    }

    /** A medicine with its allergy class and the row defaults a prescription row starts with. */
    public record MedicineOption(UUID medicineId, String name, String form, boolean favourite, String allergyClass,
                                 String defaultFrequency, int defaultDays, String defaultWhen, int useCount) {
    }

    public record MedicinePage(List<MedicineOption> items, long total, int page, int pageSize) {
    }

    public record CopyItemsResult(int added, int skipped, RecordView record) {
    }

    public record OpenResult(boolean created, String stageOutcome, RecordView record) {
    }
}
