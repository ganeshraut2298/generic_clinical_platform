package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SAH-71-3 (ADR-025 D7, H18, H19): the finish dialog's facts for General Surgery (opd.yaml 0.6.1, EmrFinishSurgeryEffects):
 * the OT booking state and its text, the admission type, whether a ward bed was asked for and the fitness text. Reads only;
 * the booking itself is made by the booking call, never at the sign. The admission letter and the pre-op sheet print after the
 * sign once the OT is booked; no estimate prints at the sign (H19). Clinical values: screen only, never logged. Called only by
 * the sign step.
 */
@Component
class SurgeryFinishContributor implements SpecialtyFinishContributor {

    private final SpecialtyRecordSupport support;
    private final EmrFinishRepository finish;

    SurgeryFinishContributor(SpecialtyRecordSupport support, EmrFinishRepository finish) {
        this.support = support;
        this.finish = finish;
    }

    @Override
    public String profile() {
        return "surgery";
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        SpecialtyCtx specialty = support.context(ctx.tenantId(), ctx.record(), "surgery", ctx.membershipId(), ctx.role(),
                ctx.now(), ctx.document());
        JsonNode surgery = support.view("surgery", specialty, ctx.record()).path("surgery");
        JsonNode stored = SpecialtyRecordSupport.componentValue("surgery", ctx.document(), "SURGERY_PLAN");
        JsonNode plan = stored == null ? MissingNode.getInstance() : stored;
        boolean wardBed = plan.path("booking").path("admissionRequested").asBoolean(false)
                || finish.openRequestOfRecord(ctx.tenantId(), ctx.record().id()).isPresent();
        return new ProfileEffects.Surgery(surgery.path("bookState").asText("NOT_BOOKED"),
                text(plan, "procedureText"), date(plan.path("otDate")),
                text(plan, "slotText"), text(plan, "admission"), wardBed, text(surgery.path("fitness"), "text"));
    }

    @Override
    public List<FinishPrint> prints(FinishContext ctx) {
        return preview(ctx).prints();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static LocalDate date(JsonNode node) {
        try {
            return node.isTextual() ? LocalDate.parse(node.asText()) : null;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
