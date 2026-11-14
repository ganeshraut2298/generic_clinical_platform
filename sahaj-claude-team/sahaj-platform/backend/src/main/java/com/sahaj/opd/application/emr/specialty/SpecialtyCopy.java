package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;

/**
 * "Copy from last visit" for the specialty components (ADR-023 D3), used when a record is first opened and the section's
 * copy switch is on. The values come from the patient's latest SIGNED record (so all of this waits for SAH-51) and wait for
 * the doctor's confirmation like any copy (OQ-45-26). What is copied, per component:
 * <ul>
 *   <li>TOOTH_CHART: the teeth, the shade and the gum (not the "all teeth healthy" confirmation);</li>
 *   <li>DENTAL_PLAN: the rows still Planned or In progress (SAH-49-2 carry-over), without the estimate or its signature;</li>
 *   <li>GROWTH: into {@code previous}, for comparison only, never as today's values ("Copied from &lt;date&gt;");</li>
 *   <li>GOALS: the open and the achieved goals (an achieved goal stays achieved);</li>
 *   <li>ONC_EXAM: the lesion names and baselines, last time's size as the last size, today's sizes empty, the cap and
 *       the line device (the checks are asked again);</li>
 *   <li>ROM_TABLE, MMT_TABLE, SPECIAL_TESTS: the rows;</li>
 *   <li>every other component: nothing.</li>
 * </ul>
 * A stamp (who and when) is never copied: the server stamps it again when the doctor saves.
 */
public final class SpecialtyCopy {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SpecialtyCopy() {
    }

    /**
     * The value to pre-fill, or null when nothing is copied.
     *
     * @param previous     the earlier signed record's component value
     * @param previousDate the day that record was signed (Asia/Kolkata)
     */
    public static ObjectNode copy(String component, JsonNode previous, LocalDate previousDate) {
        if (previous == null || !previous.isObject() || !component.equals(previous.path("component").asText())) {
            return null;
        }
        return switch (component) {
            case "TOOTH_CHART" -> toothChart(previous);
            case "DENTAL_PLAN" -> dentalPlan(previous);
            case "GROWTH" -> growth(previous, previousDate);
            case "GOALS" -> goals(previous);
            case "ONC_EXAM" -> oncExam(previous);
            case "ROM_TABLE", "MMT_TABLE", "SPECIAL_TESTS" -> rowsOnly(previous);
            default -> null;
        };
    }

    private static ObjectNode header(String component) {
        ObjectNode node = NODES.objectNode();
        node.put("component", component);
        node.put("schemaVersion", 1);
        return node;
    }

    private static ObjectNode toothChart(JsonNode previous) {
        ObjectNode copy = header("TOOTH_CHART");
        copy.set("numbering", previous.path("numbering"));
        copy.set("dentition", previous.path("dentition"));
        copy.put("showNumbers", previous.path("showNumbers").asBoolean(true));
        copy.set("teeth", previous.path("teeth").deepCopy());
        copy.set("shade", previous.path("shade"));
        copy.set("gum", previous.path("gum"));
        copy.put("allHealthyConfirmed", false);
        return previous.path("teeth").isEmpty() ? null : copy;
    }

    private static ObjectNode dentalPlan(JsonNode previous) {
        ObjectNode copy = header("DENTAL_PLAN");
        ArrayNode rows = copy.putArray("rows");
        for (JsonNode row : previous.path("rows")) {
            if (!"DONE".equals(row.path("status").asText())) {
                ObjectNode kept = row.deepCopy();
                kept.putNull("doneOn");
                rows.add(kept);
            }
        }
        return rows.isEmpty() ? null : copy;
    }

    private static ObjectNode growth(JsonNode previous, LocalDate date) {
        if (!previous.path("weightKg").isNumber() && !previous.path("lengthCm").isNumber()
                && !previous.path("headCircCm").isNumber()) {
            return null;
        }
        ObjectNode copy = header("GROWTH");
        ObjectNode before = copy.putObject("previous");
        before.put("date", date.toString());
        for (String key : new String[] {"weightKg", "lengthCm", "headCircCm"}) {
            before.set(key, previous.path(key).isNumber() ? previous.path(key) : NODES.nullNode());
        }
        return copy;
    }

    private static ObjectNode goals(JsonNode previous) {
        ObjectNode copy = header("GOALS");
        copy.set("goals", previous.path("goals").deepCopy());
        return previous.path("goals").isEmpty() ? null : copy;
    }

    private static ObjectNode oncExam(JsonNode previous) {
        if (previous.path("lesions").isEmpty() && previous.path("line").path("device").isNull()) {
            return null;
        }
        ObjectNode copy = header("ONC_EXAM");
        copy.put("capAt2", previous.path("capAt2").asBoolean(true));
        copy.putObject("signs");
        ArrayNode lesions = copy.putArray("lesions");
        for (JsonNode lesion : previous.path("lesions")) {
            ObjectNode kept = lesions.addObject();
            kept.set("id", lesion.path("id"));
            kept.set("name", lesion.path("name"));
            kept.set("baselineCm", lesion.path("baselineCm"));
            kept.set("lastCm", lesion.path("todayCm").isNumber() ? lesion.path("todayCm") : NODES.nullNode());
            kept.putNull("todayCm");
        }
        copy.put("newLesionSeen", false);
        copy.putArray("breastSkin");
        copy.putObject("systems");
        ObjectNode line = copy.putObject("line");
        line.set("device", previous.path("line").path("device").isTextual() ? previous.path("line").path("device")
                : NODES.nullNode());
        line.putArray("checks");
        return copy;
    }

    private static ObjectNode rowsOnly(JsonNode previous) {
        ObjectNode copy = previous.deepCopy();
        String array = previous.has("results") ? "results" : "rows";
        return previous.path(array).isEmpty() ? null : copy;
    }
}
