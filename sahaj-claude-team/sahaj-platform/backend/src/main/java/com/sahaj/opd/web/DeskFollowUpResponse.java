package com.sahaj.opd.web;

import com.sahaj.opd.application.DeskFollowUpService.Result;
import java.time.LocalDate;
import java.util.UUID;

/** {@code DeskFollowUpResult} in {@code docs/api/opd.yaml} 0.9.0 (SAH-93): the follow-up after a desk action. */
public record DeskFollowUpResponse(UUID followUpId, String state, LocalDate dueDate, AppointmentResponse appointment) {

    public static DeskFollowUpResponse from(Result result) {
        return new DeskFollowUpResponse(result.followUpId(), result.state(), result.dueDate(),
                result.appointment() == null ? null : AppointmentResponse.from(result.appointment()));
    }
}
