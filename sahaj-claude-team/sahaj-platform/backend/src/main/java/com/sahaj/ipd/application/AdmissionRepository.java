package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.admission}, {@code ipd.deposit} and {@code ipd.ip_sequence} (SAH-23). Every method is scoped to the
 * tenant. The database sets ids, the clinic and the admission and collection times; the application never
 * sends them.
 */
public interface AdmissionRepository {

    /** Takes the next running number of the clinic and financial year (for example {@code 26-27}). */
    int nextIpNo(UUID tenantId, String fy);

    boolean existsAdmitted(UUID tenantId, UUID patientId);

    /** Inserts the admission as ADMITTED with its source (DIRECT, or EMERGENCY from the emergency queue); returns the new id. */
    UUID insert(UUID tenantId, NewAdmission admission);

    /** Returns the new deposit id. */
    UUID insertDeposit(UUID tenantId, UUID admissionId, long amountPaise, String mode, UUID collectedBy);

    /** Admitted patients, newest first, with the total of their deposits. */
    List<AdmissionRow> findAdmitted(UUID tenantId);

    /** SAH-33: how many patients are Admitted now (the nurse's tile "Your patients"), counted in one query. */
    int countAdmitted(UUID tenantId);

    Optional<AdmissionRow> find(UUID tenantId, UUID admissionId);

    /** SAH-26: the admission row locked ({@code select ... for update of} the admission) until the transaction ends. */
    Optional<AdmissionRow> findForUpdate(UUID tenantId, UUID admissionId);

    /**
     * SAH-30: the admission row locked with {@code select ... for no key update of} the admission (ADR-006 D4). It
     * conflicts with every other row lock of the admission but not with the key-share locks that foreign keys take.
     */
    Optional<AdmissionRow> findForNoKeyUpdate(UUID tenantId, UUID admissionId);

    /** SAH-26: points the admission at another bed and bumps its version. */
    void updateBed(UUID tenantId, UUID admissionId, UUID bedId);

    /** SAH-27: ADMITTED to DISCHARGED at {@code dischargedAt}; bumps the version. False when it was not ADMITTED. */
    boolean markDischarged(UUID tenantId, UUID admissionId, Instant dischargedAt);

    /** Oldest first. */
    List<DepositRow> findDeposits(UUID tenantId, UUID admissionId);

    /** The reason is clinical text: {@code toString} leaves it out. */
    record NewAdmission(UUID patientId, String ipNumber, UUID bedId, UUID doctorMembershipId, String admissionType,
                        String payer, String reason, UUID admittedBy, String source) {

        /** An admission from the front desk (source DIRECT). */
        public NewAdmission(UUID patientId, String ipNumber, UUID bedId, UUID doctorMembershipId,
                            String admissionType, String payer, String reason, UUID admittedBy) {
            this(patientId, ipNumber, bedId, doctorMembershipId, admissionType, payer, reason, admittedBy, "DIRECT");
        }

        @Override
        public String toString() {
            return "NewAdmission[ipNumber=" + ipNumber + ", bedId=" + bedId + "]";
        }
    }

    /** Carries the reason and the patient id: {@code toString} shows the admission id and IP number only. */
    record AdmissionRow(UUID id, String ipNumber, UUID patientId, UUID bedId, String bedCode, String categoryKey,
                        String wardName, UUID doctorMembershipId, String admissionType, String payer, String reason,
                        String status, Instant admittedAt, long depositTotalPaise, Instant dischargedAt) {

        /** An admission that has not been discharged ({@code dischargedAt} is null). */
        public AdmissionRow(UUID id, String ipNumber, UUID patientId, UUID bedId, String bedCode, String categoryKey,
                            String wardName, UUID doctorMembershipId, String admissionType, String payer,
                            String reason, String status, Instant admittedAt, long depositTotalPaise) {
            this(id, ipNumber, patientId, bedId, bedCode, categoryKey, wardName, doctorMembershipId, admissionType,
                    payer, reason, status, admittedAt, depositTotalPaise, null);
        }

        @Override
        public String toString() {
            return "AdmissionRow[id=" + id + ", ipNumber=" + ipNumber + "]";
        }
    }

    record DepositRow(UUID id, long amountPaise, String mode, Instant collectedAt, UUID collectedBy) {
    }
}
