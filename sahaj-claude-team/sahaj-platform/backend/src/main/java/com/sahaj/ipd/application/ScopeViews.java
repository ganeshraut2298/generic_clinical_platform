package com.sahaj.ipd.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-34 request and response shapes of {@code docs/api/ipd.yaml} (patient scope, emergency access, ward assignment).
 * Patient and staff names, bed codes and UHIDs are on screen only: every record that carries them hides them from
 * {@code toString}, so they cannot reach a log line by accident.
 */
public final class ScopeViews {

    private ScopeViews() {
    }

    /** {@code AdmissionScope}: the caller's scope on the Admitted list. {@code hasWard} is set for a NURSE only. */
    public record AdmissionScope(String kind, int visible, int admitted,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Boolean hasWard,
                                 boolean canOpenEmergencyAccess) {
    }

    /** {@code AdmittedEmergencyAccess}: the caller's own live grant on a row (only that row, only for her). */
    public record AdmittedEmergencyAccess(Instant expiresAt) {
    }

    /** {@code SafetyScope}: the scope of a view NURSE Safety board. */
    public record SafetyScope(String kind, int visible, int admitted, boolean hasWard) {
    }

    // ---- emergency access ----

    public record EmergencyAccessReasonView(String key, String label) {
    }

    public record EmergencyAccessPatient(UUID admissionId, String patientName, String bedCode, String uhid) {

        @Override
        public String toString() {
            return "EmergencyAccessPatient[admissionId=" + admissionId + "]";
        }
    }

    public record EmergencyAccessPicker(List<EmergencyAccessPatient> items, List<EmergencyAccessReasonView> reasons) {

        @Override
        public String toString() {
            return "EmergencyAccessPicker[items=" + items.size() + "]";
        }
    }

    /**
     * Request body of {@code POST /emergency-access}. Every field is kept as the JSON that arrived so that a missing or
     * wrong value is the contract's own field message and never a conversion error. There is no duration, start, end,
     * patient or user field.
     */
    public record EmergencyAccessInput(JsonNode admissionId, JsonNode reasonKey, JsonNode confirmed) {
    }

    /** {@code EmergencyAccessGrant}: the answer of POST /emergency-access. The name is for the toast only. */
    public record EmergencyAccessGrant(UUID id, UUID admissionId, String patientName, String bedCode, String reasonKey,
                                       String reasonLabel, Instant openedAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "EmergencyAccessGrant[id=" + id + ", admissionId=" + admissionId + "]";
        }
    }

    public record EmergencyAccessReviewRow(UUID grantId, UUID admissionId, String staffName, String patientName,
                                           String bedCode, String reasonKey, String reasonLabel, Instant openedAt,
                                           Instant expiresAt, String admissionStatus) {

        @Override
        public String toString() {
            return "EmergencyAccessReviewRow[grantId=" + grantId + "]";
        }
    }

    public record EmergencyAccessReviewPage(Instant asOf, int waiting, List<EmergencyAccessReviewRow> items,
                                            String nextCursor) {

        @Override
        public String toString() {
            return "EmergencyAccessReviewPage[waiting=" + waiting + ", items=" + items.size() + "]";
        }
    }

    public record EmergencyAccessReviewResult(UUID grantId, Instant reviewedAt, int waiting) {
    }

    // ---- ward assignment (34A data part) ----

    public record WardAssignmentNurse(UUID membershipId, String name, List<UUID> wardIds) {

        @Override
        public String toString() {
            return "WardAssignmentNurse[membershipId=" + membershipId + "]";
        }
    }

    public record WardAssignmentWard(UUID wardId, String wardName, List<UUID> nurseMembershipIds) {
    }

    public record WardAssignments(boolean wardScope, List<WardAssignmentWard> wards,
                                  List<WardAssignmentNurse> nurses, int nursesWithoutWard) {
    }

    /** Request body of {@code PUT /ward-assignments/{wardId}}; the JSON as it arrived. */
    public record WardAssignmentInput(JsonNode membershipIds) {
    }

    /** Request body of {@code PUT /ward-scope}; the JSON as it arrived. */
    public record WardScopeInput(JsonNode on) {
    }
}
