package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrComponentBinding;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-70-1, SAH-70-2 (ADR-025 D7, H9, H18): oncology. The cycle charge stays on the CHEMO course sitting (the course account);
 * no visit bill line is made. When the doctor chose no follow-up interval the follow-up falls on the next cycle date of the
 * chemotherapy plan (the plan's date, else the default date of the screen's computed view; none after Stop chemo). The finish
 * dialog's block (opd.yaml 0.6.1, EmrFinishOncoEffects) reads the same facts. The cycle record always prints, the calendar
 * when a next cycle date is set. Called only by the sign step.
 */
@Component
class OncoFinishContributor implements SpecialtyFinishContributor {

    private static final String STOP = "STOP";
    private static final String DELAY = "DELAY_1_WEEK";
    private static final int COURSE_SCAN = 50;

    private final SpecialtyRecordSupport support;
    private final TreatmentCourseRepository courses;

    OncoFinishContributor(SpecialtyRecordSupport support, TreatmentCourseRepository courses) {
        this.support = support;
        this.courses = courses;
    }

    @Override
    public String profile() {
        return "onco";
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        return compute(ctx);
    }

    @Override
    public List<FinishPrint> prints(FinishContext ctx) {
        return compute(ctx).prints();
    }

    @Override
    public ProfileResult apply(FinishContext ctx) {
        return new ProfileResult(null, 0, 0, compute(ctx).followUpDate(), null);
    }

    private ProfileEffects.Onco compute(FinishContext ctx) {
        SpecialtyCtx specialty = support.context(ctx.tenantId(), ctx.record(), "onco", ctx.membershipId(), ctx.role(),
                ctx.now(), ctx.document());
        JsonNode view = specialty == null ? null : support.view("onco", specialty, ctx.record());
        JsonNode onco = view == null ? MissingNode.getInstance() : view.path("onco");
        String decision = onco.path("decision").path("choice").isTextual() ? onco.path("decision").path("choice").asText()
                : null;
        Integer cycleNo = onco.path("cycleNo").isInt() ? onco.path("cycleNo").asInt() : null;
        LocalDate date = STOP.equals(decision) ? null : nextCycleDate(ctx, onco);
        Optional<SittingRow> sitting = date == null ? Optional.empty() : plannedSitting(ctx, date);
        boolean fromNextCycle = date != null && followUpInterval(ctx) == null;
        return new ProfileEffects.Onco(cycleNo, decision, onco.path("orderStatus").asText("NONE"),
                date == null || cycleNo == null ? null : cycleNo + 1,
                date == null ? null : DELAY.equals(decision) ? "REBOOKED_CHEMO" : "NEXT_CHEMO", date,
                sitting.map(SittingRow::id).orElse(null), sitting.map(SittingRow::chargePaise).orElse(null),
                fromNextCycle);
    }

    /** The plan's next cycle date, else the screen's default date. */
    private static LocalDate nextCycleDate(FinishContext ctx, JsonNode onco) {
        JsonNode plan = SpecialtyRecordSupport.componentValue("onco", ctx.document(), "CHEMO_PLAN");
        LocalDate chosen = plan == null ? null : date(plan.path("nextCycleDate"));
        return chosen != null ? chosen : date(onco.path("nextCycle").path("defaultDate"));
    }

    /** The planned, not yet done CHEMO course sitting of the patient on that date (SAH-70-1), if one was added. */
    private Optional<SittingRow> plannedSitting(FinishContext ctx, LocalDate date) {
        UUID patientId = ctx.record().patientId();
        if (patientId == null) {
            return Optional.empty();
        }
        for (CourseRow course : courses.forPatient(ctx.tenantId(), patientId, COURSE_SCAN)) {
            if (!"CHEMO".equals(course.kind()) || !"ACTIVE".equals(course.status())) {
                continue;
            }
            for (SittingRow sitting : courses.sittingsOf(ctx.tenantId(), List.of(course.id()))) {
                if (date.equals(sitting.plannedDate()) && sitting.doneAt() == null && sitting.missedAt() == null) {
                    return Optional.of(sitting);
                }
            }
        }
        return Optional.empty();
    }

    /** The follow-up interval the doctor chose (followup.next_visit), or null. */
    private static String followUpInterval(FinishContext ctx) {
        JsonNode value = ctx.document().values("followup").path("next_visit");
        return EmrComponentBinding.FOLLOW_UP.equals(value.path("component").asText()) && value.path("interval").isTextual()
                ? value.path("interval").asText() : null;
    }

    private static LocalDate date(JsonNode node) {
        try {
            return node.isTextual() ? LocalDate.parse(node.asText()) : null;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
