package com.sahaj.opd.application;

import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The answers of {@code GET /desk/snapshot} and {@code GET /desk/doctors/{id}/day-slots} (docs/api/opd.yaml 0.9.0, SAH-90).
 * Field names are the contract's; times are "HH:mm" strings (Asia/Kolkata). Patient names are for the screen only and are
 * never logged: {@code toString} of the card and the to-book row leaves them out.
 */
public final class DeskViews {

    private DeskViews() {
    }

    public enum TodayStatus { IN_OPD, STARTS_LATER, ON_LEAVE, FULL_TODAY, OFF_TODAY }

    public enum DepartmentSource { EMR_DEPARTMENT, SPECIALTY, NONE }

    public enum Column { BOOKED, WAITING, IN_CONSULTATION, DONE }

    public enum Kind { APPOINTMENT, WALK_IN }

    public enum Tag { NO_SHOW, DUE_NOW, DOCTOR_ON_LEAVE, VITALS }

    public enum Action { CHECK_IN, REBOOK, MOVE_TO_NEW_SLOT, START_CONSULTATION, COMPLETE_VISIT, SET_FOLLOW_UP, NONE }

    public enum FollowUpState { NONE, OPEN, BOOKED, NOT_NEEDED, RETURNED }

    public enum GroupLabel { MORNING, EVENING }

    public record Snapshot(Instant generatedAt, LocalDate today, BookingRulesRow bookingRules, String followUpFee,
                           Counters counters, Doctors doctors, Board board, ToBook followUpsToBook, DaySlots selected) {
    }

    public record Counters(int booked, int waiting, int inConsult, int done, int freeToday) {
    }

    public record Doctors(int total, List<Doctor> items) {
    }

    public record Doctor(UUID doctorMembershipId, String name, String department, DepartmentSource departmentSource,
                         String room, int slotMinutes, long consultFeePaise, long followUpFeePaise,
                         int closeBeforeHours, TodayStatus todayStatus, String startsAt, LocalDate leaveUntil,
                         int freeToday, NextFree nextFree, List<DayCount> days) {
    }

    public record NextFree(LocalDate date, int freeCount) {
    }

    public record DayCount(LocalDate date, int freeCount, boolean working, boolean onLeave) {
    }

    public record DaySlots(UUID doctorMembershipId, LocalDate date, boolean working, boolean onLeave, int slotMinutes,
                           int closeBeforeHours, int freeCount, List<SlotGroup> groups, LocalDate nextFreeDate) {
    }

    public record SlotGroup(GroupLabel label, List<Slot> slots) {
    }

    public record Slot(String time, boolean insideCutOff) {
    }

    public record Board(LocalDate date, boolean truncated, List<Card> cards) {
    }

    public record Card(UUID key, Kind kind, UUID appointmentId, Long appointmentVersion, UUID visitId, Column column,
                       String time, UUID patientId, String patientName, UUID doctorMembershipId, String doctorName,
                       String visitType, String appointmentStatus, String stage, String nextStage, Integer tokenNo,
                       Instant checkedInAt, Instant consultStartedAt, Instant consultEndedAt, Set<Tag> tags,
                       Action primaryAction, VisitFollowUp followUp) {

        /** Ids and the column only: never the patient's name. */
        @Override
        public String toString() {
            return "Card[key=" + key + ", column=" + column + "]";
        }
    }

    public record VisitFollowUp(UUID followUpId, FollowUpState state, LocalDate dueDate, UUID appointmentId,
                                LocalDate apptDate, String apptTime) {
    }

    public record ToBook(int total, List<ToBookItem> items) {
    }

    /** {@code DeskTokenPanel} (SAH-92-2). Nothing clinical; the patient name is for the screen and slip only. */
    public record TokenPanel(UUID visitId, int tokenNo, LocalDate tokenDate, String patientName,
                             UUID doctorMembershipId, String doctorName, String room, String stage,
                             Instant checkedInAt, int patientsAhead, int estimatedWaitMinutes, int slotMinutes) {

        /** Ids, token and counts only: never the patient's name. */
        @Override
        public String toString() {
            return "TokenPanel[visitId=" + visitId + ", tokenNo=" + tokenNo + ", patientsAhead=" + patientsAhead + "]";
        }
    }

    public record ToBookItem(UUID followUpId, UUID patientId, String patientName, UUID doctorMembershipId,
                             String doctorName, LocalDate dueDate, String status, int daysLate, String reason) {

        /** Ids, due date and status only: never the patient's name or the reason. */
        @Override
        public String toString() {
            return "ToBookItem[followUpId=" + followUpId + ", dueDate=" + dueDate + ", status=" + status + "]";
        }
    }
}
