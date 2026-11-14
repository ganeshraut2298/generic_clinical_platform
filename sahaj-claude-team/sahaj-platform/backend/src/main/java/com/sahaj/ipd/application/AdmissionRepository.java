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

    /** SAH-67-3: the patient's ADMITTED admission, if any (id and IP number only). */
    Optional<AdmittedRef> findAdmittedOf(UUID tenantId, UUID patientId);

    record AdmittedRef(UUID admissionId, String ipNumber) {
    }

    /** Inserts the admission as ADMITTED with its source (DIRECT, or EMERGENCY from the emergency queue); returns the new id. */
    UUID insert(UUID tenantId, NewAdmission admission);

    /** Returns the new deposit id. */
    UUID insertDeposit(UUID tenantId, UUID admissionId, long amountPaise, String mode, UUID collectedBy);

    /**
     * SAH-58 (ADR-022 D12): the credit carried from a treatment course as a deposit with source COURSE and no mode;
     * {@code transferId} is the billing credit transfer it came from. Returns the new deposit id.
     */
    UUID insertCourseDeposit(UUID tenantId, UUID admissionId, long amountPaise, UUID transferId, UUID collectedBy);

    /** SAH-58: the treatment course and sitting an admission was made for, if any. */
    Optional<CourseLink> findCourseLink(UUID tenantId, UUID admissionId);

    /** Admitted patients, newest first, with the total of their deposits. */
    List<AdmissionRow> findAdmitted(UUID tenantId);

    /**
     * SAH-36 (ADR-013 R1): the same rows as {@link #findAdmitted} (same order) with the ward id and the name of the bed's
     * room (null when the bed has no room), from the same statement: bed, ward and room are joined by primary key.
     */
    List<AdmittedRow> findAdmittedWithPlacement(UUID tenantId);

    /** SAH-33: how many patients are Admitted now (the nurse's tile "Your patients"), counted in one query. */
    int countAdmitted(UUID tenantId);

    Optional<AdmissionRow> find(UUID tenantId, UUID admissionId);

    /** SAH-50: the patient's admissions (any status), newest first, at most {@code limit}. */
    List<AdmissionRow> findByPatient(UUID tenantId, UUID patientId, int limit);

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
                        String payer, String reason, UUID admittedBy, String source, UUID treatmentCourseId,
                        UUID treatmentCourseSittingId, UUID admissionRequestId) {

        /** An admission with a course sitting but no EMR admission request (SAH-58). */
        public NewAdmission(UUID patientId, String ipNumber, UUID bedId, UUID doctorMembershipId,
                            String admissionType, String payer, String reason, UUID admittedBy, String source,
                            UUID treatmentCourseId, UUID treatmentCourseSittingId) {
            this(patientId, ipNumber, bedId, doctorMembershipId, admissionType, payer, reason, admittedBy, source,
                    treatmentCourseId, treatmentCourseSittingId, null);
        }

        /** An admission with a source but no treatment course (SAH-28). */
        public NewAdmission(UUID patientId, String ipNumber, UUID bedId, UUID doctorMembershipId,
                            String admissionType, String payer, String reason, UUID admittedBy, String source) {
            this(patientId, ipNumber, bedId, doctorMembershipId, admissionType, payer, reason, admittedBy, source, null,
                    null);
        }

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

    /**
     * SAH-29 (AC2): the copy of a package kept on one admission (name, price in paise, included days; 0 = day-care).
     * Read from the three package columns of {@code ipd.admission}; the application never writes them.
     */
    record PackageCopy(String name, long pricePaise, int includedDays) {
    }

    /**
     * Carries the reason and the patient id: {@code toString} shows the admission id and IP number only.
     * {@code packageCopy} (SAH-29) is null when the admission has no package.
     */
    record AdmissionRow(UUID id, String ipNumber, UUID patientId, UUID bedId, String bedCode, String categoryKey,
                        String wardName, UUID doctorMembershipId, String admissionType, String payer, String reason,
                        String status, Instant admittedAt, long depositTotalPaise, Instant dischargedAt,
                        PackageCopy packageCopy) {

        /** An admission with no package. */
        public AdmissionRow(UUID id, String ipNumber, UUID patientId, UUID bedId, String bedCode, String categoryKey,
                            String wardName, UUID doctorMembershipId, String admissionType, String payer,
                            String reason, String status, Instant admittedAt, long depositTotalPaise,
                            Instant dischargedAt) {
            this(id, ipNumber, patientId, bedId, bedCode, categoryKey, wardName, doctorMembershipId, admissionType,
                    payer, reason, status, admittedAt, depositTotalPaise, dischargedAt, null);
        }

        /** An admission that has not been discharged ({@code dischargedAt} is null) and has no package. */
        public AdmissionRow(UUID id, String ipNumber, UUID patientId, UUID bedId, String bedCode, String categoryKey,
                            String wardName, UUID doctorMembershipId, String admissionType, String payer,
                            String reason, String status, Instant admittedAt, long depositTotalPaise) {
            this(id, ipNumber, patientId, bedId, bedCode, categoryKey, wardName, doctorMembershipId, admissionType,
                    payer, reason, status, admittedAt, depositTotalPaise, null, null);
        }

        @Override
        public String toString() {
            return "AdmissionRow[id=" + id + ", ipNumber=" + ipNumber + "]";
        }
    }

    /** SAH-36: an admitted row with the ward id and the room name of its bed. Ids only in {@code toString}. */
    record AdmittedRow(AdmissionRow admission, UUID wardId, String roomName) {

        @Override
        public String toString() {
            return "AdmittedRow[id=" + admission.id() + "]";
        }
    }

    /**
     * {@code mode} is null only for a COURSE deposit (SAH-58); {@code source} is DESK or COURSE.
     */
    record DepositRow(UUID id, long amountPaise, String mode, Instant collectedAt, UUID collectedBy, String source) {

        /** A deposit collected at the desk. */
        public DepositRow(UUID id, long amountPaise, String mode, Instant collectedAt, UUID collectedBy) {
            this(id, amountPaise, mode, collectedAt, collectedBy, "DESK");
        }
    }

    /** The treatment course and sitting an admission was made for (SAH-58). */
    record CourseLink(UUID courseId, UUID sittingId) {
    }
}
