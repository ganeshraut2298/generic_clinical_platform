package com.sahaj.opd.web;

import com.sahaj.opd.application.AppointmentService.CheckIn;
import com.sahaj.opd.application.AppointmentView;
import com.sahaj.opd.application.DeskViews.TokenPanel;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** {@code AppointmentCheckIn} in {@code docs/api/opd.yaml} 0.9.0: the Appointment plus the token panel (SAH-92-1, SAH-92-2). */
public record AppointmentCheckInResponse(UUID id, UUID patientId, String patientName, Integer age, String sex,
                                          UUID doctorMembershipId, String doctorName, LocalDate apptDate,
                                          String apptTime, String status, String visitType, String source, String note,
                                          UUID visitId, Integer tokenNo, long version, TokenPanel tokenPanel) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public static AppointmentCheckInResponse from(CheckIn checkIn) {
        AppointmentView view = checkIn.appointment();
        return new AppointmentCheckInResponse(view.id(), view.patientId(), view.patientName(), view.age(), view.sex(),
                view.doctorMembershipId(), view.doctorName(), view.apptDate(), view.apptTime().format(HHMM),
                view.status(), view.visitType(), view.source(), view.note(), view.visitId(), view.tokenNo(),
                view.version(), checkIn.tokenPanel());
    }
}
