package com.sahaj.opd.application;

import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-90 (ADR-032 D1): the set-based reads of the front desk console. Each method is one SQL statement, whatever the number
 * of doctors, slots or cards. Needs {@code app.tenant_id} bound. The partial-index predicates of V2026_12_18_1000 are repeated
 * exactly in the statements that use them.
 */
public interface DeskReadRepository {

    /**
     * The most appointment or walk-in rows the board uses. {@link #appointments} and {@link #walkInVisits} fetch one more, so a
     * list longer than this means rows were cut (the board then says {@code truncated}).
     */
    int MAX_CARD_ROWS = 5000;

    /** The saved booking rules and the clinic follow-up fee rule; each is null when the clinic never saved it. */
    RulesAndFees rulesAndFees(UUID tenantId);

    /** Published EMR departments with the doctors assigned to them, by department name. */
    List<DepartmentLink> doctorDepartments(UUID tenantId);

    /** Appointments of all doctors from {@code from} to {@code to} that are not CANCELLED, each with its visit if checked in. */
    List<AppointmentCardRow> appointments(UUID tenantId, LocalDate from, LocalDate to);

    /** Visits created in {@code [from, to)} that no non-cancelled appointment of {@code today} points to (walk-ins). */
    List<VisitCardRow> walkInVisits(UUID tenantId, Instant from, Instant to, LocalDate today);

    /** The follow-ups whose source visit was created in {@code [from, to)}, with their appointment's date and time. */
    List<FollowUpCardRow> followUpsOfVisits(UUID tenantId, Instant from, Instant to);

    /** Follow-ups to book (no return, no appointment, not marked not needed, due on or before today), oldest due first. */
    ToBookPage followUpsToBook(UUID tenantId, LocalDate today, int limit);

    /** One doctor's BOOKED or ARRIVED start times from {@code from} to {@code to}, by date. */
    Map<LocalDate, Set<LocalTime>> takenSlots(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to);

    record RulesAndFees(BookingRulesRow rules, String followUpFee) {
    }

    record DepartmentLink(UUID doctorMembershipId, String key, String name, String starterKey, List<String> rooms) {
    }

    record VisitPart(UUID id, String visitType, String stage, int tokenNo, Instant createdAt, int workflowVersionNo,
                     Instant consultStartedAt, Instant consultEndedAt) {
    }

    record AppointmentCardRow(UUID id, UUID patientId, UUID doctorMembershipId, LocalDate apptDate, LocalTime apptTime,
                              String status, String visitType, long version, VisitPart visit) {
    }

    record VisitCardRow(UUID patientId, UUID doctorMembershipId, VisitPart visit) {
    }

    record FollowUpCardRow(UUID id, UUID visitId, LocalDate dueDate, UUID returnedVisitId, UUID appointmentId,
                           boolean notNeeded, LocalDate apptDate, LocalTime apptTime) {
    }

    record ToBookRow(UUID id, UUID patientId, UUID doctorMembershipId, LocalDate dueDate, String reason,
                     boolean fromDischarge) {
    }

    record ToBookPage(int total, List<ToBookRow> rows) {
    }
}
