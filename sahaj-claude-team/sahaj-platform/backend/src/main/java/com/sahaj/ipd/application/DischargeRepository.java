package com.sahaj.ipd.application;

import com.sahaj.ipd.application.RunningBill.BillLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.discharge_signoff}, {@code ipd.discharge}, {@code ipd.final_bill} and {@code ipd.final_bill_line}
 * (SAH-27). All four are append-only: the database role can select and insert, nothing more. Every method is scoped
 * to the tenant; the database sets ids, the clinic and the sign-off, completion and settlement times.
 */
public interface DischargeRepository {

    /** Appends a sign-off and returns it with the id and time the database gave it. */
    SignoffRow insertSignoff(UUID tenantId, UUID admissionId, String type, String templateKey, UUID signedBy);

    /** The latest sign-off of one admission. */
    Optional<SignoffRow> findLatestSignoff(UUID tenantId, UUID admissionId);

    /** The latest sign-off of every ADMITTED admission of the clinic in one query, keyed by admission id. */
    Map<UUID, SignoffRow> findLatestSignoffsOfAdmitted(UUID tenantId);

    /** Admissions discharged at or after {@code from} and before {@code to}. */
    int countDischargedBetween(UUID tenantId, Instant from, Instant to);

    /** Returns the new final bill id. */
    UUID insertFinalBill(UUID tenantId, NewFinalBill bill);

    /** Inserts the lines in order, numbered from 1. */
    void insertFinalBillLines(UUID tenantId, UUID finalBillId, List<BillLine> lines);

    void insertDischarge(UUID tenantId, NewDischarge discharge);

    Optional<DischargeRow> findDischarge(UUID tenantId, UUID admissionId);

    Optional<FinalBillRow> findFinalBill(UUID tenantId, UUID admissionId);

    /** In bill order. */
    List<BillLine> findFinalBillLines(UUID tenantId, UUID finalBillId);

    record SignoffRow(UUID id, UUID admissionId, String type, String templateKey, UUID signedBy, Instant signedAt) {
    }

    record NewFinalBill(UUID admissionId, long totalPaise, long depositsPaise, long balancePaise, String settleMode,
                        UUID createdBy, int billDays, String bedDayCut, String categoryKey, BigDecimal multiplier) {
    }

    /** Type, template and signature are copied from the sign-off row. */
    record NewDischarge(UUID admissionId, UUID signoffId, String type, String templateKey, UUID signedBy,
                        Instant signedAt, int followUpDays, LocalDate followUpDue, UUID completedBy) {
    }

    record DischargeRow(UUID id, UUID admissionId, UUID signoffId, String type, String templateKey, UUID signedBy,
                        Instant signedAt, int followUpDays, LocalDate followUpDue, UUID completedBy,
                        Instant completedAt) {
    }

    record FinalBillRow(UUID id, UUID admissionId, long totalPaise, long depositsPaise, long balancePaise,
                        String settleMode, UUID createdBy, Instant createdAt, int billDays, String bedDayCut,
                        String categoryKey, BigDecimal multiplier) {
    }
}
