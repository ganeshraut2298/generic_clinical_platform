package com.sahaj.opd.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A booking error whose problem type is one of the front desk console's own slugs (docs/api/opd.yaml 0.9.0, SAH-91).
 * The opd desk error handler turns it into an RFC 9457 problem. The detail is fixed plain English written here; it never
 * carries a patient name, a mobile number or anything that was typed.
 */
public class OpdProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String slug;
    private final String title;
    private final transient Map<String, Object> extensions = new LinkedHashMap<>();

    private OpdProblemException(int status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    /** Extra problem properties (appointmentId, closeBeforeHours): ids and numbers only. */
    public Map<String, Object> extensions() {
        return extensions;
    }

    private OpdProblemException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public static OpdProblemException doctorNotWorking() {
        return new OpdProblemException(400, "doctor-not-working", "Doctor does not work that day",
                "This doctor does not see patients on that day.");
    }

    public static OpdProblemException slotNotOnGrid() {
        return new OpdProblemException(400, "slot-not-on-grid", "Not one of the doctor's slots",
                "That time is not one of this doctor's slots. Pick a time from the list.");
    }

    public static OpdProblemException slotWalkInOnly() {
        return new OpdProblemException(400, "slot-walk-in-only", "Slot kept for walk-ins",
                "That slot is kept for walk-ins. Pick another time.");
    }

    public static OpdProblemException slotInPast() {
        return new OpdProblemException(400, "slot-in-past", "Time has passed", "That time has passed.");
    }

    public static OpdProblemException bookingClosed(int closeBeforeHours) {
        String unit = closeBeforeHours == 1 ? "hour" : "hours";
        return new OpdProblemException(400, "booking-closed", "Booking is closed for this slot",
                "Booking closes " + closeBeforeHours + " " + unit + " before the slot.")
                .with("closeBeforeHours", closeBeforeHours);
    }

    public static OpdProblemException appointmentDuplicate(UUID appointmentId) {
        return new OpdProblemException(409, "appointment-duplicate", "Already booked",
                "This patient is already booked in this slot.").with("appointmentId", appointmentId);
    }

    public static OpdProblemException appointmentChanged() {
        return new OpdProblemException(409, "appointment-changed", "Appointment changed",
                "This appointment was changed by someone else. The board now shows the latest.");
    }

    public static OpdProblemException appointmentNotReschedulable() {
        return new OpdProblemException(409, "appointment-not-reschedulable", "Appointment cannot be moved",
                "Only a booked or no-show appointment can be moved.");
    }

    // ---- SAH-92 and SAH-93 (opd.yaml 0.9.0) ----

    public static OpdProblemException appointmentAlreadyCheckedIn(UUID visitId, int tokenNo) {
        return new OpdProblemException(409, "appointment-already-checked-in", "Already checked in",
                "This patient is already checked in.").with("visitId", visitId).with("tokenNo", tokenNo);
    }

    public static OpdProblemException appointmentNotBookable() {
        return new OpdProblemException(409, "appointment-not-bookable", "This appointment cannot take a token",
                "This appointment cannot be checked in now. The board now shows the latest.");
    }

    public static OpdProblemException appointmentNotBooked() {
        return new OpdProblemException(409, "appointment-not-booked", "Appointment is not booked",
                "This appointment is no longer booked. The board now shows the latest.");
    }

    public static OpdProblemException checkInNotToday() {
        return new OpdProblemException(400, "check-in-not-today", "Not the day of the visit",
                "Check-in is only possible on the day of the visit.");
    }

    public static OpdProblemException visitStageChanged() {
        return new OpdProblemException(409, "visit-stage-changed", "Card was moved",
                "This card was moved by someone else. The board now shows the latest.");
    }

    public static OpdProblemException visitNotDone() {
        return new OpdProblemException(409, "visit-not-done", "Visit is not completed",
                "Set the follow-up after the visit is completed.");
    }

    public static OpdProblemException followUpAlreadyBooked() {
        return new OpdProblemException(409, "follow-up-already-booked", "Follow-up already booked",
                "This follow-up is already booked.");
    }

    public static OpdProblemException followUpClosed() {
        return new OpdProblemException(409, "follow-up-closed", "Follow-up closed", "This follow-up is closed.");
    }
}
