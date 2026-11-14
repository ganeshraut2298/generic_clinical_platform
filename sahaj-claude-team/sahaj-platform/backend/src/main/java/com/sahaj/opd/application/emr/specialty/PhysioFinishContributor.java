package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.AppointmentService.BookedSlot;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingChange;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.finish.FinishModels.CourseOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.NextSlot;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-63-3 (ADR-025 D7): "Complete session and book next" for the physiotherapy profile. The visit's REHAB sitting is
 * marked done by the sign step (D1 step 9); this contributor moves the next planned session to the chosen slot and books it
 * (or keeps the date only when the slot was taken meanwhile or none was chosen; a past, off-grid, walk-in or leave slot is
 * 400 validation). With no planned session left the package is complete
 * and nothing is booked. Sittings are clinical plan text: never logged. Called only by the sign step.
 */
@Component
class PhysioFinishContributor implements SpecialtyFinishContributor {

    private final TreatmentCourseRepository courses;
    private final AppointmentService appointments;
    private final SpecialtyRecordSupport support;

    PhysioFinishContributor(TreatmentCourseRepository courses, AppointmentService appointments,
                            SpecialtyRecordSupport support) {
        this.courses = courses;
        this.appointments = appointments;
        this.support = support;
    }

    @Override
    public String profile() {
        return "physio";
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        JsonNode pain = SpecialtyRecordSupport.componentValue("physio", ctx.document(), "PAIN_SCORE");
        Integer painScore = pain != null && pain.path("score").isInt() ? pain.path("score").asInt() : null;
        Integer previous = previousPain(ctx);
        Optional<SittingRow> current = ctx.visit() == null ? Optional.empty()
                : courses.sittingByVisit(ctx.tenantId(), ctx.visit().id());
        if (current.isEmpty()) {
            return new ProfileEffects.Physio(null, null, null, null, false, painScore, previous);
        }
        List<SittingRow> all = courses.sittingsOf(ctx.tenantId(), List.of(current.get().courseId()));
        Optional<SittingRow> next = nextPlanned(all, current.get());
        return new ProfileEffects.Physio(current.get().courseId(), current.get().sittingNo(), all.size(),
                next.map(SittingRow::sittingNo).orElse(null), next.isEmpty(), painScore, previous);
    }

    /** The pain score of the patient's latest earlier SIGNED Physiotherapy record that has one. */
    private Integer previousPain(FinishContext ctx) {
        SpecialtyCtx specialty = support.context(ctx.tenantId(), ctx.record(), "physio", ctx.membershipId(), ctx.role(),
                ctx.now(), ctx.document());
        if (specialty == null) {
            return null;
        }
        for (SpecialtyCtx.Earlier earlier : specialty.hooks().earlier().apply("PAIN_SCORE")) {
            if (earlier.value().path("score").isInt()) {
                return earlier.value().path("score").asInt();
            }
        }
        return null;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProfileResult apply(FinishContext ctx) {
        if (ctx.visit() == null) {
            return ProfileResult.none();
        }
        Optional<SittingRow> current = courses.sittingByVisit(ctx.tenantId(), ctx.visit().id());
        if (current.isEmpty()) {
            return ProfileResult.none();
        }
        List<SittingRow> all = courses.sittingsOf(ctx.tenantId(), List.of(current.get().courseId()));
        Optional<SittingRow> next = nextPlanned(all, current.get());
        if (next.isEmpty()) {
            return new ProfileResult(new CourseOutcome(null, null, null, null, true), 0, 0, null, null);
        }
        NextSlot slot = ctx.choices() == null || ctx.choices().physio() == null ? null
                : ctx.choices().physio().nextSlot();
        if (slot == null || slot.date() == null) {
            return new ProfileResult(new CourseOutcome(null, next.get().id(), next.get().plannedDate(), null, false),
                    0, 0, null, null);
        }
        SittingRow target = next.get();
        validateNextSlot(ctx, slot);
        courses.updateSitting(ctx.tenantId(), target.id(), new SittingChange(target.encounterKind(), slot.date(),
                target.items(), target.chargePaise(), target.note()), ctx.actorUserId());
        courses.bumpRevision(ctx.tenantId(), target.courseId(), ctx.actorUserId());
        LocalTime time = parse(slot.time());
        Optional<BookedSlot> booked = time == null ? Optional.empty()
                : appointments.bookAt(ctx.tenantId(), ctx.actorUserId(), ctx.visit().patientId(),
                        ctx.visit().doctorMembershipId(), slot.date(), time, "FOLLOW_UP", "EMR");
        return new ProfileResult(new CourseOutcome(null, target.id(), slot.date(),
                booked.map(b -> b.time().toString()).orElse(null), false), 0, 0, null, null);
    }

    /**
     * The chosen date and time must be a real slot of the doctor (code review 8): not in the past, on the doctor's grid, a
     * working day, not on leave and not reserved for walk-ins. A slot someone else booked meanwhile is not refused here: the
     * sitting then keeps the date only (ADR-025 D7). A time that is not HH:mm is refused too.
     */
    private void validateNextSlot(FinishContext ctx, NextSlot slot) {
        if (slot.date().isBefore(ctx.today())) {
            throw invalidSlot();
        }
        if (slot.time() == null) {
            return;
        }
        LocalTime time = parse(slot.time());
        if (time == null || !appointments.isOpenSlot(ctx.tenantId(), ctx.visit().doctorMembershipId(), slot.date(), time)) {
            throw invalidSlot();
        }
    }

    private static InvalidFieldsException invalidSlot() {
        return new InvalidFieldsException(List.of(new FieldMessage("choices.physio.nextSlot",
                "Choose a free time from the list.")));
    }

    /** The first later session that has no visit yet and is not done. */
    private static Optional<SittingRow> nextPlanned(List<SittingRow> all, SittingRow current) {
        return all.stream().filter(s -> s.sittingNo() > current.sittingNo() && s.opdVisitId() == null
                && s.admissionId() == null && s.doneAt() == null).findFirst();
    }

    private static LocalTime parse(String value) {
        try {
            return value == null ? null : LocalTime.parse(value);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
