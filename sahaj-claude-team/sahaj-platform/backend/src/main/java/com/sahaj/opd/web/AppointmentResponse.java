package com.sahaj.opd.web;

import com.sahaj.opd.application.AppointmentView;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** {@code Appointment} in {@code docs/api/opd.yaml} (SAH-11). */
public record AppointmentResponse(UUID id, UUID patientId, String patientName, Integer age, String sex,
                                   UUID doctorMembershipId, String doctorName, LocalDate apptDate, String apptTime,
                                   String status, String visitType, String source, String note, UUID visitId,
                                   Integer tokenNo) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public static AppointmentResponse from(AppointmentView view) {
        return new AppointmentResponse(view.id(), view.patientId(), view.patientName(), view.age(), view.sex(),
                view.doctorMembershipId(), view.doctorName(), view.apptDate(), view.apptTime().format(HHMM),
                view.status(), view.visitType(), view.source(), view.note(), view.visitId(), view.tokenNo());
    }
}
