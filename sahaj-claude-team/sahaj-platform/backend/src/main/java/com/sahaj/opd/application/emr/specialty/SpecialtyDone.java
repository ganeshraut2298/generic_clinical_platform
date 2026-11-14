package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import com.sahaj.opd.domain.emr.specialty.RuleTag;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules.TestState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The Done rule of every specialty component (ADR-023 D3, extending ADR-020 D8). Computed on the server from the stored
 * value on every read and save; a copied value still needs the doctor's confirmation (the caller applies that). A
 * component that stores nothing of its own (the derived "done today" list and the dose by weight) reads its siblings.
 */
public final class SpecialtyDone {

    private SpecialtyDone() {
    }

    /**
     * What the rules need besides the value.
     *
     * @param recordDate  the day the record was opened (Asia/Kolkata)
     * @param sibling     the stored specialty value of another component of the same record, or null
     * @param orderStatus the chemotherapy order status: NONE, DRAFT, SENT or HELD
     */
    public record Ctx(LocalDate recordDate, Function<String, JsonNode> sibling, String orderStatus) {
    }

    public static boolean isDone(String component, JsonNode v, Ctx ctx) {
        JsonNode value = v == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : v;
        return switch (component) {
            case "VITALS_LOG", "LAB_WORK", "DENTAL_PLAN", "GOALS", "MODALITIES", "HOME_EXERCISES" ->
                    !value.path(listOf(component)).isEmpty() && value.path(listOf(component)).isArray();
            case "EXAM_GENERAL" -> examGeneral(value);
            case "TOOTH_CHART" -> !value.path("teeth").isEmpty() || value.path("allHealthyConfirmed").asBoolean(false);
            case "DENTAL_DONE" -> dentalDone(ctx);
            case "CONSENT_CHECKLIST" -> consent(value);
            case "ANC_CHECK" -> anyNumber(value.path("today"));
            case "FORM_F" -> !SpecialtyValues.obstetricScanOrdered(ctx.sibling().apply("ANC_CHECK"))
                    || value.path("filledAndSigned").asBoolean(false);
            case "GYNAE_EXAM" -> gynae(value);
            case "GROWTH" -> value.path("weightKg").isNumber();
            case "IMMUNIZATION" -> value.path("scheduleReviewed").asBoolean(false)
                    || !value.path("givenToday").isEmpty();
            case "MILESTONES" -> !value.path("achieved").isEmpty() || !value.path("notYet").isEmpty();
            case "PAIN_SCORE" -> value.path("score").isInt();
            case "POSTURE_GAIT" -> value.path("gait").isTextual();
            case "ROM_TABLE" -> anyRom(value);
            case "MMT_TABLE" -> anyMmt(value);
            case "SPECIAL_TESTS" -> anyTest(value);
            case "OUTCOME_SCALES" -> anyScore(value);
            case "SESSION_NOTE" -> value.path("note").isTextual() && !value.path("note").asText().isBlank();
            case "SURGICAL_EXAM" -> !value.path("abdomen").isEmpty() || value.path("murphySign").isTextual()
                    || value.path("jaundice").isTextual() || !value.path("herniaSites").isEmpty()
                    || value.path("perRectal").isTextual();
            case "SURGERY_FITNESS" -> "FIT".equals(fitness(value).state());
            case "SURGERY_PLAN" -> bookedNow(value, ctx);
            case "PREAUTH" -> value.path("sent").asBoolean(false);
            case "PREOP_ORDERS" -> !value.path("ticked").isEmpty();
            case "ONC_TOX" -> oncTox(value);
            case "ONC_EXAM" -> oncExam(value);
            case "ONC_LABS" -> oncLabs(value);
            case "ONC_ECOG" -> value.path("grade").isInt();
            case "CHEMO_PLAN" -> "SENT".equals(ctx.orderStatus()) || "HELD".equals(ctx.orderStatus());
            default -> false;
        };
    }

    private static String listOf(String component) {
        return switch (component) {
            case "VITALS_LOG" -> "entries";
            case "LAB_WORK", "HOME_EXERCISES" -> "items";
            case "DENTAL_PLAN" -> "rows";
            case "GOALS" -> "goals";
            default -> "given";
        };
    }

    private static boolean examGeneral(JsonNode value) {
        boolean marked = false;
        var signs = value.path("signs").fields();
        while (signs.hasNext()) {
            marked = marked || signs.next().getValue().isTextual();
        }
        var systems = value.path("systems").fields();
        while (systems.hasNext()) {
            JsonNode system = systems.next().getValue();
            if (system.isObject()) {
                marked = true;
                if ("ABNORMAL".equals(system.path("state").asText())
                        && (!system.path("text").isTextual() || system.path("text").asText().isBlank())) {
                    return false;
                }
            }
        }
        return marked;
    }

    private static boolean dentalDone(Ctx ctx) {
        JsonNode plan = ctx.sibling().apply("DENTAL_PLAN");
        if (plan == null) {
            return false;
        }
        for (JsonNode row : plan.path("rows")) {
            if ("DONE".equals(row.path("status").asText())
                    && ctx.recordDate().toString().equals(row.path("doneOn").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean consent(JsonNode value) {
        if (value.path("items").isEmpty()) {
            return false;
        }
        for (JsonNode item : value.path("items")) {
            if (!item.path("confirmed").asBoolean(false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyNumber(JsonNode node) {
        var fields = node.fields();
        while (fields.hasNext()) {
            if (fields.next().getValue().isNumber()) {
                return true;
            }
        }
        return false;
    }

    private static boolean gynae(JsonNode value) {
        boolean finding = text(value, "perAbdomen") || text(value, "perSpeculum") || text(value, "perVaginum");
        boolean chaperone = text(value, "chaperoneName") || value.path("chaperoneDeclined").asBoolean(false);
        return finding && chaperone;
    }

    private static boolean text(JsonNode node, String property) {
        return node.path(property).isTextual() && !node.path(property).asText().isBlank();
    }

    private static boolean anyRom(JsonNode value) {
        for (JsonNode row : value.path("rows")) {
            if (row.path("rightDeg").isInt() || row.path("leftDeg").isInt()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMmt(JsonNode value) {
        for (JsonNode row : value.path("rows")) {
            if (row.path("right").isInt() || row.path("left").isInt()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyScore(JsonNode value) {
        for (JsonNode row : value.path("rows")) {
            if (row.path("score").isInt()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyTest(JsonNode value) {
        for (JsonNode result : value.path("results")) {
            if (!"NOT_DONE".equals(result.path("result").asText())) {
                return true;
            }
        }
        return false;
    }

    /** The fitness summary of a stored SURGERY_FITNESS value (no value reads as PAC not done). */
    public static SurgeryRules.Fitness fitness(JsonNode value) {
        List<TestState> tests = new ArrayList<>();
        for (JsonNode test : value.path("tests")) {
            tests.add(new TestState(test.path("key").asText(), test.path("state").asText()));
        }
        return SurgeryRules.fitness(value.path("pac").asText("NOT_DONE"), tests);
    }

    private static boolean oncTox(JsonNode value) {
        int confirmed = 0;
        for (JsonNode symptom : value.path("symptoms")) {
            if (symptom.path("confirmedGrade").isInt()) {
                confirmed++;
            }
        }
        return confirmed == OncologyRules.SYMPTOMS.size();
    }

    /** How many of the nine side effects are still not confirmed ("n side effect grade(s)"). */
    public static int oncToxOpen(JsonNode value) {
        int confirmed = 0;
        for (JsonNode symptom : value.path("symptoms")) {
            if (symptom.path("confirmedGrade").isInt()) {
                confirmed++;
            }
        }
        return OncologyRules.SYMPTOMS.size() - confirmed;
    }

    private static boolean oncExam(JsonNode value) {
        if (!value.path("weightKg").isNumber() || !value.path("heightCm").isNumber()) {
            return false;
        }
        for (String system : OncologyRules.SYSTEM_KEYS) {
            if (!value.path("systems").path(system).isObject()) {
                return false;
            }
        }
        return true;
    }

    private static boolean oncLabs(JsonNode value) {
        if (!value.path("drawnAt").isTextual()) {
            return false;
        }
        for (String lab : List.of("hb", "anc", "plt", "cr", "bili", "alt", "lvef")) {
            if (!value.path("values").path(lab).isNumber()) {
                return false;
            }
        }
        return true;
    }

    // ---- surgery booking state ----

    /** The fingerprint of what a booking covers: the plan, the consent ticks and the pre-authorisation. */
    public static String bookingFingerprint(JsonNode plan, JsonNode consent, JsonNode preauth) {
        StringBuilder text = new StringBuilder();
        for (String key : List.of("procedureKey", "procedureText", "approach", "anaesthesia", "admission", "otDate",
                "slotText", "payer", "packagePaise")) {
            text.append(key).append('=').append(plan.path(key).isNull() || plan.path(key).isMissingNode() ? ""
                    : plan.path(key).asText()).append('\n');
        }
        List<String> ticked = new ArrayList<>();
        if (consent != null) {
            for (JsonNode item : consent.path("items")) {
                if (item.path("confirmed").asBoolean(false)) {
                    ticked.add(item.path("key").asText());
                }
            }
        }
        java.util.Collections.sort(ticked);
        text.append("consent=").append(String.join(",", ticked)).append('\n');
        text.append("preauth=").append(preauth != null && preauth.path("sent").asBoolean(false)).append('\n');
        return RuleTag.sha256(text.toString());
    }

    private static boolean bookedNow(JsonNode plan, Ctx ctx) {
        JsonNode booking = plan.path("booking");
        if (!booking.isObject()) {
            return false;
        }
        String current = bookingFingerprint(plan, ctx.sibling().apply("CONSENT_CHECKLIST"),
                ctx.sibling().apply("PREAUTH"));
        return current.equals(booking.path("planFingerprint").asText());
    }

    /** NOT_BOOKED, BOOKED or CHANGED (the view adds READY when the booking rule is met). */
    public static String bookingState(JsonNode plan, Ctx ctx) {
        if (!plan.path("booking").isObject()) {
            return "NOT_BOOKED";
        }
        return bookedNow(plan, ctx) ? "BOOKED" : "CHANGED";
    }

}
