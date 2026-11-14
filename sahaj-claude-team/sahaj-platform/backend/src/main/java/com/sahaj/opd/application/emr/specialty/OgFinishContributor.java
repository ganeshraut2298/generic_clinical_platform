package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Earlier;
import com.sahaj.opd.domain.emr.specialty.AncRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SAH-61-1, SAH-61-3 (ADR-025 D7): the finish dialog's facts for Obstetrics and Gynaecology (opd.yaml 0.6.1,
 * EmrFinishOgEffects): the visit mode, the ANC visit number, the gestational age, today's blood pressure and haemoglobin, the
 * orders stamped as ordered and the doctor's next ANC week. Reads only; nothing is written at the sign. Clinical values:
 * screen only, never logged. Called only by the sign step.
 */
@Component
class OgFinishContributor implements SpecialtyFinishContributor {

    /** The labels of the five ANC orders (the due orders of AncRules; the two extra orders as the screen words them). */
    private static final Map<String, String> ORDER_LABELS = new LinkedHashMap<>();

    static {
        ORDER_LABELS.put("PIH_WORKUP", "PIH workup");
        ORDER_LABELS.put("NST", "NST");
        AncRules.DUE_ORDERS.forEach(order -> ORDER_LABELS.put(order.key(), order.label()));
    }

    private final SpecialtyRecordSupport support;

    OgFinishContributor(SpecialtyRecordSupport support) {
        this.support = support;
    }

    @Override
    public String profile() {
        return "og";
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        SpecialtyCtx specialty = support.context(ctx.tenantId(), ctx.record(), "og", ctx.membershipId(), ctx.role(),
                ctx.now(), ctx.document());
        JsonNode view = support.view("og", specialty, ctx.record());
        String mode = view.path("mode").asText("ANC");
        JsonNode og = view.path("og");
        JsonNode stored = SpecialtyRecordSupport.componentValue("og", ctx.document(), "ANC_CHECK");
        JsonNode anc = stored == null ? MissingNode.getInstance() : stored;
        ProfileEffects.GestationalAge age = og.path("gestationalAge").isObject()
                ? new ProfileEffects.GestationalAge(og.path("gestationalAge").path("weeks").asInt(),
                        og.path("gestationalAge").path("days").asInt()) : null;
        JsonNode today = anc.path("today");
        List<String> ordered = new ArrayList<>();
        for (JsonNode order : anc.path("orders")) {
            if (order.path("ordered").isObject()) {
                ordered.add(ORDER_LABELS.getOrDefault(order.path("key").asText(), order.path("key").asText()));
            }
        }
        return new ProfileEffects.Og(mode, "ANC".equals(mode) ? ancVisitNo(specialty, anc) : null, age,
                integer(today.path("bpSystolic")), integer(today.path("bpDiastolic")), number(today.path("hbGdl")),
                List.copyOf(ordered), integer(anc.path("nextAncWeeks")));
    }

    /**
     * The number of SIGNED records of the pregnancy (same {@code pregnancyCourseId} in their ANC check) plus one for this
     * visit; null without an active ANC course.
     */
    private static Integer ancVisitNo(SpecialtyCtx specialty, JsonNode anc) {
        String course = anc.path("pregnancyCourseId").asText(null);
        if (course == null) {
            return null;
        }
        int signed = 0;
        for (Earlier earlier : specialty.hooks().earlier().apply("ANC_CHECK")) {
            if (course.equals(earlier.value().path("pregnancyCourseId").asText(null))) {
                signed++;
            }
        }
        return signed + 1;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }
}
