package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.AppointmentService.BookedSlot;
import com.sahaj.opd.application.FollowUpService;
import com.sahaj.opd.application.course.CourseKinds;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.finish.FinishModels.CourseOutcome;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBillLine;
import com.sahaj.opd.application.emr.finish.FinishModels.FreeSlot;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-69-3 (ADR-025 D7): "Complete and send to billing" for the dental profile. Plan rows done today become visit bill lines
 * (source EMR_PLAN, key {@code plan:<rowId>:<sittingNo>}, the row's charge share, tariff or typed amount); the next sitting
 * becomes a planned course sitting with the open rows and an appointment on the first free slot. Dental next sitting wins
 * over a follow-up interval (ADR-025 H7). Plan rows are clinical text: never logged. Called only by the sign step.
 */
@Component
class DentalFinishContributor implements SpecialtyFinishContributor {

    static final String DEFAULT_INTERVAL = "D7";
    private static final int MAX_LABEL = 120;
    private static final int MAX_NAME = 170;
    private static final int FREE_SLOT_DAYS = 7;
    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    private final ObjectProvider<EmrVisitChargePort> charges;
    private final TreatmentCourseRepository courses;
    private final AppointmentService appointments;

    DentalFinishContributor(ObjectProvider<EmrVisitChargePort> charges, TreatmentCourseRepository courses,
                            AppointmentService appointments) {
        this.charges = charges;
        this.courses = courses;
        this.appointments = appointments;
    }

    @Override
    public String profile() {
        return "dental";
    }

    /**
     * One plan row done today. {@code amountPaise} is null when the row has no tariff match and no typed amount (estimate
     * source NONE): the row is listed but makes no bill line (opd.yaml 0.6.1, "Not in tariff").
     */
    private record DoneRow(String sourceKey, String label, UUID rowId, String name, int sitting, int planned,
                           Long amountPaise) {
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        JsonNode plan = SpecialtyRecordSupport.componentValue("dental", ctx.document(), "DENTAL_PLAN");
        JsonNode rows = plan == null ? MissingNode.getInstance() : plan.path("rows");
        List<DoneRow> done = plan == null ? List.of() : doneToday(plan, ctx);
        List<ProfileEffects.DentalRow> shown = new ArrayList<>();
        List<FinishBillLine> lines = new ArrayList<>();
        for (DoneRow row : done) {
            shown.add(new ProfileEffects.DentalRow(row.rowId(), row.name(),
                    new ProfileEffects.Sitting(row.sitting(), row.planned()), row.amountPaise()));
            if (row.amountPaise() != null) {
                lines.add(new FinishBillLine(row.label(), 1, row.amountPaise(), row.amountPaise(), "NEW", "DENTAL_PLAN"));
            }
        }
        int carried = 0;
        for (JsonNode row : rows) {
            carried += "DONE".equals(row.path("status").asText()) ? 0 : 1;
        }
        boolean allDone = carried == 0;
        String interval = null;
        LocalDate date = null;
        FreeSlot slot = null;
        if (!allDone) {
            interval = chosenInterval(ctx);
            date = FollowUpService.dueDateFor(ctx.visitDay(), interval);
            if (date != null && ctx.visit() != null) {
                List<AppointmentService.FreeSlot> free = appointments.freeSlots(ctx.tenantId(),
                        ctx.visit().doctorMembershipId(), date, FREE_SLOT_DAYS, 1);
                slot = free == null || free.isEmpty() ? null
                        : new FreeSlot(free.get(0).date(), free.get(0).time().toString());
            }
        }
        long estimate = plan == null ? 0 : plan.path("estimate").path("totalPaise").asLong(0);
        return new ProfileEffects.Dental(List.copyOf(shown), estimate, allDone, carried, interval, date, slot,
                List.copyOf(lines));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProfileResult apply(FinishContext ctx) {
        JsonNode plan = SpecialtyRecordSupport.componentValue("dental", ctx.document(), "DENTAL_PLAN");
        if (plan == null || ctx.visit() == null) {
            return ProfileResult.none();
        }
        int lines = 0;
        long total = 0;
        EmrVisitChargePort port = charges.getIfAvailable();
        List<EmrVisitChargePort.PricedLine> priced = new ArrayList<>();
        for (DoneRow row : doneToday(plan, ctx)) {
            if (row.amountPaise() != null) {
                priced.add(new EmrVisitChargePort.PricedLine(row.sourceKey(), row.label(), row.amountPaise()));
                total += row.amountPaise();
            }
        }
        if (port != null && !priced.isEmpty()) {
            EmrVisitChargePort.Outcome outcome = port.addPricedLines(ctx.tenantId(), ctx.actorUserId(), ctx.visit().id(),
                    priced);
            if (outcome == EmrVisitChargePort.Outcome.INVALID_SERVICE) {
                // a label or an amount out of range is the plan's data, not a paid bill (code review 6)
                throw new InvalidFieldsException(List.of(new FieldMessage("dental plan",
                        "Check the treatment names and amounts done today.")));
            }
            if (outcome != EmrVisitChargePort.Outcome.SYNCED) {
                throw EmrProblemException.alreadyBilled();
            }
            lines = priced.size();
        }
        CourseOutcome course = null;
        LocalDate followUp = null;
        UUID appointmentId = null;
        if (hasOpenRows(plan)) {
            LocalDate date = nextDate(ctx);
            if (date != null) {
                followUp = date;
                Booked booked = nextSitting(ctx, plan, date);
                course = booked == null ? null : booked.course();
                appointmentId = booked == null ? null : booked.appointmentId();
            }
        }
        return new ProfileResult(course, lines, total, followUp, appointmentId);
    }

    private List<DoneRow> doneToday(JsonNode plan, FinishContext ctx) {
        List<DoneRow> out = new ArrayList<>();
        // DEC-048 H28: a row counts when it was marked done on or after the day the record was opened (IST), up to the sign
        LocalDate opened = ctx.record().createdAt().atZone(INDIA).toLocalDate();
        for (JsonNode row : plan.path("rows")) {
            if (!"DONE".equals(row.path("status").asText()) || !doneInWindow(row.path("doneOn").asText(), opened, ctx.today())) {
                continue;
            }
            int sitting = Math.max(1, row.path("currentSitting").asInt(1));
            int planned = Math.max(1, row.path("sittingsPlanned").asInt(1));
            // no amount (a tariff-less row, or no share stored for the sitting): no bill line, shown as "Not in tariff"
            JsonNode shareNode = row.path("sittingSharesPaise").path(sitting - 1);
            Long share = "NONE".equals(row.path("estimateSource").asText()) || !shareNode.isNumber()
                    || shareNode.asLong() <= 0 ? null : shareNode.asLong();
            String label = row.path("procedureText").asText() + " · tooth " + row.path("tooth").asInt();
            String name = row.path("tooth").asInt() + " · " + row.path("procedureText").asText();
            out.add(new DoneRow("plan:" + row.path("id").asText() + ":" + sitting,
                    label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label, rowId(row.path("id").asText()),
                    name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name, sitting, planned, share));
        }
        return out;
    }

    private static boolean doneInWindow(String doneOn, LocalDate opened, LocalDate signDay) {
        try {
            LocalDate day = LocalDate.parse(doneOn);
            return !day.isBefore(opened) && !day.isAfter(signDay);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static boolean hasOpenRows(JsonNode plan) {
        for (JsonNode row : plan.path("rows")) {
            if (!"DONE".equals(row.path("status").asText())) {
                return true;
            }
        }
        return false;
    }

    /** The request's next-sitting interval, D7 when absent (opd.yaml 0.6.1). */
    private static String chosenInterval(FinishContext ctx) {
        String interval = ctx.choices() == null || ctx.choices().dental() == null ? null
                : ctx.choices().dental().nextInterval();
        return interval == null ? DEFAULT_INTERVAL : interval;
    }

    private LocalDate nextDate(FinishContext ctx) {
        return FollowUpService.dueDateFor(ctx.visitDay(), chosenInterval(ctx));
    }

    /** A plan row id is a UUID; anything else (never stored) gets a stable name-based one so the answer stays valid. */
    private static UUID rowId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A planned DENTAL sitting with the open rows and an appointment on the first free slot (H6: none, date only). */
    private record Booked(CourseOutcome course, UUID appointmentId) {
    }

    private Booked nextSitting(FinishContext ctx, JsonNode plan, LocalDate date) {
        String courseText = plan.path("courseId").asText(null);
        if (courseText == null) {
            return null;
        }
        UUID courseId = UUID.fromString(courseText);
        Optional<CourseRow> course = courses.findForUpdate(ctx.tenantId(), courseId);
        if (course.isEmpty() || !"ACTIVE".equals(course.get().status())) {
            return null;
        }
        String key = "sd-" + sha(ctx.idempotencyKey() + ":" + ctx.record().id()).substring(0, 40);
        Optional<SittingRow> existing = courses.sittingByIdempotencyKey(ctx.tenantId(), key);
        SittingRow sitting = existing.orElse(null);
        if (sitting == null) {
            List<String> items = new ArrayList<>();
            long charge = 0;
            for (JsonNode row : plan.path("rows")) {
                if ("DONE".equals(row.path("status").asText())) {
                    continue;
                }
                int planned = row.path("sittingsPlanned").asInt(1);
                int next = Math.min(planned, row.path("currentSitting").asInt(1) + 1);
                charge += row.path("sittingSharesPaise").path(next - 1).asLong(0);
                String item = row.path("procedureText").asText() + " · tooth " + row.path("tooth").asInt()
                        + " · sitting " + next + " of " + planned;
                items.add(item.length() > 200 ? item.substring(0, 200) : item);
            }
            int no = courses.nextSittingNo(ctx.tenantId(), courseId);
            UUID id = courses.insertSitting(ctx.tenantId(), new NewSitting(courseId, no, CourseKinds.ENCOUNTER_OPD,
                    "opd_sitting", date, items, charge, null, key, sha(String.join("|", items) + date)),
                    ctx.actorUserId());
            courses.bumpRevision(ctx.tenantId(), courseId, ctx.actorUserId());
            sitting = courses.sitting(ctx.tenantId(), id).orElseThrow();
        }
        Optional<BookedSlot> booked = ctx.visit() == null ? Optional.empty()
                : appointments.bookFirstFree(ctx.tenantId(), ctx.actorUserId(), ctx.visit().patientId(),
                        ctx.visit().doctorMembershipId(), date, 7, "FOLLOW_UP", "EMR");
        return new Booked(new CourseOutcome(null, sitting.id(), booked.map(BookedSlot::date).orElse(date),
                booked.map(b -> b.time().toString()).orElse(null), false), booked.map(BookedSlot::appointmentId).orElse(null));
    }

    static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
