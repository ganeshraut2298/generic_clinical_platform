package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the Doctor EMR (docs/api/opd.yaml 0.3.0). Each body is closed: a field the contract does not
 * list is refused with 400 validation by the strict JSON module, except {@code tenantId} and {@code clinicId}, which
 * are accepted and ignored (the clinic is always the signed-in user's). Fields are nullable so that a missing field is
 * answered by the service with a field error and a wrong role is still answered 403 first.
 */
public final class EmrInputs {

    private EmrInputs() {
    }

    /** One section of a layout body: the eight properties a clinic may change. */
    public record SectionSettingsInput(String key, String part, Boolean shown, Boolean required, Boolean print,
                                       Boolean copy, Boolean collapsed, List<String> whoCanFill) {
    }

    /** PUT /emr/departments/{key}/draft and POST /emr/departments/{key}/publish. {@code origin} is EDIT or RESET. */
    public record LayoutInput(Long revision, String origin, List<SectionSettingsInput> sections) {
    }

    public record DepartmentSource(String kind, String starterKey, String copyFromDepartmentKey) {
    }

    /** POST /emr/departments (the Add department wizard). */
    public record DepartmentCreateInput(String name, String initials, String colour, DepartmentSource source,
                                        Boolean copyPrintFlags, List<SectionSettingsInput> sections,
                                        List<UUID> doctorMembershipIds, List<String> rooms) {
    }

    /** POST /emr/records/open. */
    public record OpenInput(String encounterType, UUID encounterId) {
    }

    public record RecordSectionInput(String key, JsonNode values, Boolean confirmCopy, PrefillInput prefill) {

        /** The phase 2 shape: no nurse-entry reference. */
        public RecordSectionInput(String key, JsonNode values, Boolean confirmCopy) {
            this(key, values, confirmCopy, null);
        }
    }

    /** The nurse entry the vitals were pre-filled from (ADR-025 D12). */
    public record PrefillInput(String source, java.util.UUID sourceEntryId) {
    }

    public record UiStateInput(String part, String sectionKey) {
    }

    /** POST /emr/records/{id}/surgery-booking (ADR-023 D8): the record revision the screen holds. */
    public record RevisionInput(Long revision) {
    }

    /** The Oncology decision of a draft save (ADR-023 D5). */
    public record OncDecisionInput(String choice, String reasonKey) {
    }

    /** The screen facts of a draft save: the visit mode (og, physio) and the Oncology decision (ADR-023 D2, D5). */
    public record ScreenInput(String mode, OncDecisionInput oncDecision) {
    }

    /** PUT /emr/records/{id}/draft. */
    public record RecordDraftInput(Long revision, UiStateInput uiState, List<RecordSectionInput> sections,
                                   ScreenInput screen, String saveKind) {

        /** The phase 4 shape: no save kind (MANUAL). */
        public RecordDraftInput(Long revision, UiStateInput uiState, List<RecordSectionInput> sections,
                                ScreenInput screen) {
            this(revision, uiState, sections, screen, null);
        }

        /** The phase 2 shape: no screen facts. */
        public RecordDraftInput(Long revision, UiStateInput uiState, List<RecordSectionInput> sections) {
            this(revision, uiState, sections, null, null);
        }

        /** The contract enum (opd.yaml 0.6.1): absent means MANUAL; anything but MANUAL or AUTO is a 400. */
        public boolean validSaveKind() {
            return saveKind == null || "MANUAL".equals(saveKind) || "AUTO".equals(saveKind);
        }

        public boolean auto() {
            return "AUTO".equals(saveKind);
        }
    }

    /** PUT /emr/records/{id}/department. */
    public record SwitchDepartmentInput(String departmentKey, Long revision) {
    }

    /** POST /emr/records/{id}/admission-advice. */
    public record AdmissionAdviceInput(Long revision, String referralLine) {
    }

    /** POST /emr/records/{id}/copy-items (source LAST_SIGNED_PRESCRIPTION or PAST_RECORD; what MEDICINES or TESTS). */
    public record CopyItemsInput(String source, UUID sourceRecordId, String what) {
    }

    /** PUT /emr/records/{id}/print-preferences (null = the clinic's default). */
    public record PrintPreferencesInput(String language, String paperSize) {
    }

    /** PATCH /visits/{id}/visit-type. */
    public record VisitTypeInput(String visitType, Integer triageLevel) {

        /** The SAH-52 shape: no triage level. */
        public VisitTypeInput(String visitType) {
            this(visitType, null);
        }
    }
}
