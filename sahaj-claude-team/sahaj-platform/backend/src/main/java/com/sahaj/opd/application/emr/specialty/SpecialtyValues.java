package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.Sp.Errors;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.TariffItem;
import com.sahaj.opd.domain.emr.specialty.DentalRules;
import com.sahaj.opd.domain.emr.specialty.DentalRules.PlanRow;
import com.sahaj.opd.domain.emr.specialty.PaedsRules;
import com.sahaj.opd.domain.emr.specialty.PhysioRules;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validation and server stamping of the specialty component values (ADR-023 D2, D3; docs/api/opd.yaml 0.5.0). The client
 * sends a component value under the reserved field key {@code specialty}; the closed shape is checked by
 * {@link SpecialtyShapes}; this class adds the rules that need the stored value, the clock, the patient or a reference
 * table: server stamps ({@code recorded}, {@code given}, {@code achievedOn}, {@code doneOn}, consent and Form F stamps,
 * the estimate signature, the tariff match), duplicate checks and the fixed lists. A stamp already stored is kept; a stamp
 * sent by a client is ignored. Messages are fixed plain English and never repeat a value. Nothing here is logged.
 */
public final class SpecialtyValues {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SpecialtyValues() {
    }

    /**
     * Cleans one component value. Returns the value to store, or null after adding errors (named under {@code errors}).
     * {@code stored} is the value already stored for the same component, or null. Notes are audit entries to write
     * (ids and counts only).
     */
    public static ObjectNode normalize(String component, JsonNode input, JsonNode stored, SpecialtyCtx ctx,
                                       Errors errors, List<Note> notes) {
        Sp.Shape shape = SpecialtyShapes.shape(component);
        if (shape == null) {
            errors.add("", "This section has no editor of that kind.");
            return null;
        }
        JsonNode cleaned = shape.check(input, "", errors);
        if (cleaned == null || errors.any()) {
            return null;
        }
        ObjectNode value = (ObjectNode) cleaned;
        JsonNode old = stored != null && component.equals(stored.path("component").asText()) ? stored : null;
        switch (component) {
            case "VITALS_LOG" -> vitals(value, old, ctx, errors);
            case "TOOTH_CHART" -> toothChart(value, errors);
            case "DENTAL_PLAN" -> dentalPlan(value, old, ctx, errors, notes);
            case "LAB_WORK" -> labWork(value, old, ctx, errors);
            case "CONSENT_CHECKLIST" -> consent(value, old, ctx, errors, notes);
            case "ANC_CHECK" -> anc(value, old, ctx, errors);
            case "FORM_F" -> formF(value, old, ctx, notes);
            case "GYNAE_EXAM" -> gynae(value, errors);
            case "GROWTH" -> keep(value, old, "previous");
            case "IMMUNIZATION" -> immunization(value, old, ctx, errors);
            case "MILESTONES" -> milestones(value, ctx, errors);
            case "ROM_TABLE" -> uniqueText(value, "rows", "movement", errors);
            case "MMT_TABLE" -> uniqueText(value, "rows", "group", errors);
            case "SPECIAL_TESTS" -> uniqueText(value, "results", "test", errors);
            case "GOALS" -> goals(value, old, ctx, errors);
            case "MODALITIES" -> modalities(value, errors);
            case "OUTCOME_SCALES" -> outcomeScales(value, errors);
            case "HOME_EXERCISES" -> uniqueIds(value, "items", errors);
            case "SURGERY_FITNESS" -> uniqueText(value, "tests", "key", errors);
            case "SURGERY_PLAN" -> surgeryPlan(value, old, ctx, errors);
            case "ONC_TOX" -> uniqueText(value, "symptoms", "key", errors);
            case "ONC_EXAM" -> oncExam(value, old, ctx, errors, notes);
            case "PAIN_SCORE" -> painScore(value, errors);
            case "DENTAL_MED_HISTORY" -> dentalMedHistory(value, old, ctx, errors, notes);
            case "ONC_LABS" -> oncLabs(value, ctx, errors);
            default -> {
                // no server rule beyond the shape
            }
        }
        return errors.any() ? null : value;
    }

    /** The blood choices of the pre-op orders (docs/api/opd.yaml 0.5.2 SpPreopOrders.blood; ADR-023 D14 item 3). */
    static final Set<String> PREOP_BLOOD = Set.of("NOT_NEEDED", "GROUP_AND_SAVE", "ONE_UNIT_RESERVED");

    /**
     * Makes a stored value safe to return: a PREOP_ORDERS {@code blood} outside the three keys (written before 0.5.2) reads
     * as null, so no chip is shown selected. The stored value is not changed; {@code value} must be a copy.
     */
    public static void readable(ObjectNode value) {
        if ("PREOP_ORDERS".equals(value.path("component").asText()) && value.has("blood")
                && !(value.path("blood").isTextual() && PREOP_BLOOD.contains(value.path("blood").asText()))) {
            value.putNull("blood");
        }
    }

    // ---- helpers ----

    private static ObjectNode stamp(SpecialtyCtx ctx) {
        ObjectNode stamp = NODES.objectNode();
        stamp.put("at", ctx.now().toString());
        stamp.put("byMembershipId", ctx.membershipId().toString());
        return stamp;
    }

    private static Map<String, JsonNode> byField(JsonNode old, String array, String field) {
        Map<String, JsonNode> map = new HashMap<>();
        if (old != null) {
            for (JsonNode item : old.path(array)) {
                if (item.path(field).isTextual()) {
                    map.put(item.path(field).asText().toLowerCase(Locale.ROOT), item);
                }
            }
        }
        return map;
    }

    private static JsonNode previous(Map<String, JsonNode> before, JsonNode item, String field) {
        return before.get(item.path(field).asText().toLowerCase(Locale.ROOT));
    }

    private static void keep(ObjectNode value, JsonNode old, String property) {
        if (old != null && old.has(property) && !old.path(property).isNull()) {
            value.set(property, old.get(property));
        }
    }

    private static void uniqueIds(ObjectNode value, String array, Errors errors) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode item : value.path(array)) {
            if (!seen.add(item.path("id").asText())) {
                errors.add(array + "[" + index + "].id", "This id is used twice.");
            }
            index++;
        }
    }

    private static void uniqueText(ObjectNode value, String array, String field, Errors errors) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode item : value.path(array)) {
            if (!seen.add(item.path(field).asText().toLowerCase(Locale.ROOT))) {
                errors.add(array + "[" + index + "]." + field, "This is listed twice.");
            }
            index++;
        }
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    // ---- VITALS_LOG (SAH-60-1, SAH-65-2) ----

    private static void vitals(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors) {
        Map<String, JsonNode> before = byField(old, "entries", "id");
        Set<String> ids = new HashSet<>();
        ArrayNode entries = (ArrayNode) value.get("entries");
        for (int index = 0; index < entries.size(); index++) {
            ObjectNode entry = (ObjectNode) entries.get(index);
            String where = "entries[" + index + "]";
            if (!ids.add(entry.path("id").asText())) {
                errors.add(where + ".id", "This id is used twice.");
            }
            boolean any = false;
            for (String name : List.of("bpSystolic", "bpDiastolic", "pulse", "spo2", "temperature", "respRate", "pain",
                    "weightKg", "heightCm")) {
                any = any || (entry.has(name) && !entry.get(name).isNull());
            }
            if (!any) {
                errors.add(where, "Enter at least one reading.");
            }
            JsonNode temperature = entry.path("temperature");
            if (temperature.isObject()) {
                String unit = temperature.path("unit").asText();
                double degrees = temperature.path("value").asDouble();
                if ("gm".equals(ctx.profile()) && (!"F".equals(unit) || degrees < 85 || degrees > 110)) {
                    errors.add(where + ".temperature", "Enter 85 to 110 °F.");
                } else if ("onco".equals(ctx.profile()) && (!"C".equals(unit) || degrees < 30 || degrees > 43)) {
                    errors.add(where + ".temperature", "Enter 30 to 43 °C.");
                }
            }
            JsonNode prev = before.get(entry.path("id").asText().toLowerCase(Locale.ROOT));
            entry.put("how", "TYPED");
            entry.set("recorded", prev != null && prev.path("recorded").isObject() ? prev.get("recorded") : stamp(ctx));
        }
    }

    // ---- TOOTH_CHART (SAH-49-1) ----

    private static void toothChart(ObjectNode value, Errors errors) {
        ArrayNode teeth = (ArrayNode) value.get("teeth");
        ArrayNode kept = NODES.arrayNode();
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < teeth.size(); index++) {
            ObjectNode tooth = (ObjectNode) teeth.get(index);
            String where = "teeth[" + index + "]";
            int number = tooth.path("tooth").asInt();
            if (!DentalRules.validTooth(number)) {
                errors.add(where + ".tooth", "Use an FDI tooth number.");
                continue;
            }
            if (!seen.add(number)) {
                errors.add(where + ".tooth", "This tooth is listed twice.");
                continue;
            }
            String condition = tooth.path("condition").asText();
            if (tooth.path("surfaces").size() > 0 && !DentalRules.SURFACE_CONDITIONS.contains(condition)) {
                errors.add(where + ".surfaces", "Surfaces are only for caries, filled and crown.");
                continue;
            }
            boolean plain = "HEALTHY".equals(condition) && tooth.path("surfaces").isEmpty()
                    && tooth.path("mobility").asInt() == 0;
            if (!plain) {
                kept.add(tooth);
            }
        }
        value.set("teeth", kept);
    }

    // ---- DENTAL_PLAN (SAH-49-2, SAH-69-1) ----

    private static String serviceName(ObjectNode row) {
        String text = row.path("procedureText").asText();
        String key = row.path("procedureKey").asText(null);
        if (key != null && DentalRules.procedureLabel(key) != null) {
            return DentalRules.procedureLabel(key);
        }
        return text.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip();
    }

    private static void dentalPlan(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors, List<Note> notes) {
        Map<String, JsonNode> before = byField(old, "rows", "id");
        ArrayNode rows = (ArrayNode) value.get("rows");
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        List<PlanRow> planRows = new ArrayList<>();
        long total = 0;
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) rows.get(index);
            String where = "rows[" + index + "]";
            if (!ids.add(row.path("id").asText())) {
                errors.add(where + ".id", "This id is used twice.");
            }
            int tooth = row.path("tooth").asInt();
            if (!DentalRules.validTooth(tooth)) {
                errors.add(where + ".tooth", "Use an FDI tooth number.");
                continue;
            }
            if (!keys.add(DentalRules.dedupeKey(tooth, row.path("procedureText").asText()))) {
                throw EmrProblemException.duplicateItem();
            }
            if (row.path("currentSitting").asInt() > row.path("sittingsPlanned").asInt()) {
                errors.add(where + ".currentSitting", "The sitting cannot be after the last planned sitting.");
            }
            JsonNode prev = before.get(row.path("id").asText().toLowerCase(Locale.ROOT));
            boolean done = "DONE".equals(row.path("status").asText());
            if (done) {
                boolean wasDone = prev != null && "DONE".equals(prev.path("status").asText())
                        && prev.path("doneOn").isTextual();
                row.put("doneOn", wasDone ? prev.path("doneOn").asText() : ctx.today().toString());
            } else {
                row.putNull("doneOn");
            }
            Optional<TariffItem> match = ctx.tariff().match(serviceName(row));
            Long typed = row.path("typedEstimatePaise").isNumber() ? row.path("typedEstimatePaise").asLong() : null;
            Long estimate;
            if (match.isPresent()) {
                row.put("serviceId", match.get().serviceId().toString());
                row.put("estimateSource", "TARIFF");
                estimate = match.get().pricePaise();
                row.putNull("typedEstimatePaise");
            } else if (typed != null) {
                row.putNull("serviceId");
                row.put("estimateSource", "TYPED");
                estimate = typed;
            } else {
                row.putNull("serviceId");
                row.put("estimateSource", "NONE");
                estimate = null;
            }
            if (estimate == null) {
                row.putNull("estimatePaise");
            } else {
                row.put("estimatePaise", estimate.longValue());
            }
            long amount = estimate == null ? 0 : estimate;
            total += amount;
            ArrayNode shares = row.putArray("sittingSharesPaise");
            DentalRules.splitEstimate(amount, row.path("sittingsPlanned").asInt()).forEach(shares::add);
            planRows.add(new PlanRow(tooth, row.path("procedureText").asText(), amount,
                    row.path("sittingsPlanned").asInt()));
        }
        if (errors.any()) {
            return;
        }
        String fingerprint = DentalRules.planFingerprint(planRows);
        JsonNode prevEstimate = old == null ? NODES.missingNode() : old.path("estimate");
        boolean wasSigned = "SIGNED".equals(prevEstimate.path("state").asText());
        boolean wasChanged = "CHANGED".equals(prevEstimate.path("state").asText());
        JsonNode signRequest = value.get("signEstimate");
        value.remove("signEstimate");
        ObjectNode estimate = value.putObject("estimate");
        estimate.put("totalPaise", total);
        String state = "NOT_SIGNED";
        ObjectNode signed = null;
        if (!rows.isEmpty() && signRequest != null && signRequest.asBoolean(false)) {
            boolean same = wasSigned && fingerprint.equals(prevEstimate.path("signedFingerprint").asText());
            signed = same && prevEstimate.path("signed").isObject() ? (ObjectNode) prevEstimate.get("signed").deepCopy()
                    : stamp(ctx);
            state = "SIGNED";
            if (!same) {
                notes.add(new Note("EMR_ESTIMATE_SIGNED", mapOf("totalPaise", total)));
            }
        } else if (signRequest != null && !signRequest.asBoolean(true)) {
            if (wasSigned) {
                notes.add(new Note("EMR_ESTIMATE_SIGN_CLEARED", mapOf("totalPaise", total)));
            }
        } else if (wasSigned && fingerprint.equals(prevEstimate.path("signedFingerprint").asText())) {
            state = "SIGNED";
            signed = (ObjectNode) prevEstimate.path("signed").deepCopy();
        } else if (wasSigned || wasChanged) {
            state = "CHANGED";
            if (wasSigned) {
                notes.add(new Note("EMR_ESTIMATE_SIGN_CLEARED", mapOf("totalPaise", total)));
            }
        }
        estimate.put("state", state);
        if (signed == null) {
            estimate.putNull("signed");
            estimate.putNull("signedTotalPaise");
        } else {
            estimate.set("signed", signed);
            estimate.put("signedTotalPaise", total);
            estimate.put("signedFingerprint", fingerprint);
        }
        keep(value, old, "courseId");
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    // ---- LAB_WORK (SAH-69-2) ----

    private static void labWork(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors) {
        Map<String, JsonNode> before = byField(old, "items", "id");
        Set<String> ids = new HashSet<>();
        ArrayNode items = (ArrayNode) value.get("items");
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            String where = "items[" + index + "]";
            if (!ids.add(item.path("id").asText())) {
                errors.add(where + ".id", "This id is used twice.");
            }
            if (item.path("tooth").isInt() && !DentalRules.validTooth(item.path("tooth").asInt())) {
                errors.add(where + ".tooth", "Use an FDI tooth number.");
            }
            JsonNode prev = before.get(item.path("id").asText().toLowerCase(Locale.ROOT));
            boolean changed = prev == null || !prev.path("dueDate").equals(item.path("dueDate"));
            if (changed && item.path("dueDate").isTextual()
                    && java.time.LocalDate.parse(item.path("dueDate").asText()).isBefore(ctx.today())) {
                errors.add(where + ".dueDate", "The due date cannot be before today.");
            }
        }
    }

    // ---- CONSENT_CHECKLIST (SAH-69-2, SAH-71-2) ----

    private static void consent(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors, List<Note> notes) {
        boolean surgery = "surgery".equals(ctx.profile());
        Map<String, JsonNode> before = byField(old, "items", "key");
        Set<String> keys = new HashSet<>();
        ArrayNode items = (ArrayNode) value.get("items");
        int ticked = 0;
        for (int index = 0; index < items.size(); index++) {
            ObjectNode item = (ObjectNode) items.get(index);
            String where = "items[" + index + "]";
            String key = item.path("key").asText();
            if (!keys.add(key)) {
                errors.add(where + ".key", "This item is listed twice.");
            }
            if (surgery) {
                if (!SurgeryRules.CONSENT_ITEMS.contains(key)) {
                    errors.add(where + ".key", "Choose one of the five consent items.");
                }
                item.remove("formLabel");
            } else if (!key.matches("^[A-Za-z0-9_-]{1,40}$")) {
                errors.add(where + ".key", "Use the id of a treatment on the plan.");
            }
            JsonNode prev = before.get(key.toLowerCase(Locale.ROOT));
            if (item.path("confirmed").asBoolean()) {
                ticked++;
                item.set("confirmedStamp", prev != null && prev.path("confirmed").asBoolean()
                        && prev.path("confirmedStamp").isObject() ? prev.get("confirmedStamp") : stamp(ctx));
            } else {
                item.putNull("confirmedStamp");
            }
        }
        if (surgery && items.size() > SurgeryRules.CONSENT_ITEMS.size()) {
            errors.add("items", "Send the five consent items at most.");
        }
        int before2 = 0;
        if (old != null) {
            for (JsonNode item : old.path("items")) {
                before2 += item.path("confirmed").asBoolean() ? 1 : 0;
            }
        }
        if (ticked != before2 || (old == null && ticked > 0)) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("ticked", ticked);
            audit.put("total", items.size());
            notes.add(new Note("EMR_CONSENT_RECORDED", audit));
        }
    }

    // ---- ANC_CHECK (SAH-61-1, SAH-61-4) ----

    private static void anc(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors) {
        Map<String, JsonNode> before = byField(old, "orders", "key");
        Set<String> orderKeys = new HashSet<>();
        for (JsonNode raw : value.path("orders")) {
            ObjectNode order = (ObjectNode) raw;
            if (!orderKeys.add(order.path("key").asText())) {
                errors.add("orders", "An order is listed twice.");
            }
            JsonNode prev = before.get(order.path("key").asText().toLowerCase(Locale.ROOT));
            order.set("ordered", prev != null && prev.path("ordered").isObject() ? prev.get("ordered") : stamp(ctx));
        }
        Set<String> scanKeys = new HashSet<>();
        for (JsonNode scan : value.path("scans")) {
            if (!scanKeys.add(scan.path("key").asText())) {
                errors.add("scans", "A scan is listed twice.");
            }
        }
        if (ctx.ancCourseId() != null) {
            value.put("pregnancyCourseId", ctx.ancCourseId().toString());
        } else {
            keep(value, old, "pregnancyCourseId");
        }
    }

    // ---- FORM_F (SAH-61-2) ----

    /** True when an obstetric scan is ordered in this record's ANC check (ADR-023 H10: the due order Growth scan + Form F). */
    public static boolean obstetricScanOrdered(JsonNode ancValue) {
        if (ancValue == null) {
            return false;
        }
        for (JsonNode order : ancValue.path("orders")) {
            if ("GROWTH_SCAN_FORM_F".equals(order.path("key").asText())) {
                return true;
            }
        }
        return false;
    }

    private static void formF(ObjectNode value, JsonNode old, SpecialtyCtx ctx, List<Note> notes) {
        boolean ordered = obstetricScanOrdered(ctx.sibling().apply("ANC_CHECK"));
        String earlierKey = old != null && old.path("scanKey").isTextual() ? old.path("scanKey").asText() : null;
        if (ordered) {
            value.put("scanKey", "GROWTH_SCAN_FORM_F");
            value.put("scanRemoved", false);
        } else if (earlierKey != null) {
            value.put("scanKey", earlierKey);
            value.put("scanRemoved", true);
        } else {
            value.putNull("scanKey");
            value.put("scanRemoved", false);
        }
        boolean wasMarked = old != null && old.path("marked").isObject();
        if (value.path("filledAndSigned").asBoolean()) {
            value.set("marked", wasMarked ? old.get("marked") : stamp(ctx));
            if (!wasMarked) {
                notes.add(new Note("EMR_FORM_F_MARKED", new LinkedHashMap<>()));
            }
        } else {
            value.putNull("marked");
        }
    }

    // ---- GYNAE_EXAM (SAH-61-3) ----

    private static void gynae(ObjectNode value, Errors errors) {
        boolean declined = value.path("chaperoneDeclined").asBoolean(false);
        if (declined && value.path("chaperoneName").isTextual()) {
            errors.add("chaperoneName", "Leave the name empty when the patient declined.");
        }
    }

    // ---- IMMUNIZATION (SAH-62-2) ----

    private static void immunization(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors) {
        Map<String, JsonNode> before = byField(old, "givenToday", "vaccineKey");
        Set<String> seen = new HashSet<>();
        ArrayNode given = (ArrayNode) value.get("givenToday");
        // SAH-75-9 (ADR-026 D8): "Mark all due as given" may press twice; a vaccine sent twice is stored once (set
        // semantics by vaccineKey, the first entry is kept).
        ArrayNode unique = NODES.arrayNode();
        Set<String> sent = new HashSet<>();
        for (JsonNode entry : given) {
            if (sent.add(entry.path("vaccineKey").asText())) {
                unique.add(entry);
            }
        }
        given.removeAll();
        given.addAll(unique);
        List<PaedsRules.VaccineState> states = ctx.dateOfBirth() == null ? List.of()
                : PaedsRules.vaccineStates(ctx.ref().groups(), ctx.ref().vaccines(), Set.of(), ctx.dateOfBirth(),
                        ctx.today());
        for (int index = 0; index < given.size(); index++) {
            ObjectNode entry = (ObjectNode) given.get(index);
            String where = "givenToday[" + index + "]";
            String key = entry.path("vaccineKey").asText();
            if (ctx.ref().vaccine(key) == null) {
                errors.add(where + ".vaccineKey", "Choose a vaccine from the schedule.");
                continue;
            }
            seen.add(key);
            if (ctx.earlierVaccines().contains(key)) {
                errors.add(where + ".vaccineKey", "This vaccine was given earlier. It cannot be added here.");
                continue;
            }
            JsonNode prev = before.get(key.toLowerCase(Locale.ROOT));
            if (prev == null && ctx.dateOfBirth() == null) {
                // SAH-62-2: without a date of birth nothing is due, so no new vaccine can be added
                errors.add(where + ".vaccineKey", "This vaccine is not due yet.");
            } else if (prev == null && !states.isEmpty()) {
                String state = states.stream().filter(s -> s.vaccineKey().equals(key)).map(s -> s.state())
                        .findFirst().orElse("LATER");
                if (!PaedsRules.mayGive(state)) {
                    errors.add(where + ".vaccineKey", "This vaccine is not due yet.");
                }
            }
            ObjectNode stamp = prev != null && prev.path("given").isObject()
                    ? (ObjectNode) prev.get("given").deepCopy() : stamp(ctx);
            stamp.put("givenOn", ctx.today().toString());
            entry.set("given", stamp);
        }
    }

    // ---- MILESTONES (SAH-62-2) ----

    private static void milestones(ObjectNode value, SpecialtyCtx ctx, Errors errors) {
        int band = value.path("ageBandMonths").asInt();
        Set<String> valid = new HashSet<>();
        ctx.ref().milestonesOf(band).forEach(m -> valid.add(m.key()));
        if (valid.isEmpty()) {
            errors.add("ageBandMonths", "Milestones for this age are not set up.");
            return;
        }
        Set<String> achieved = new HashSet<>();
        for (JsonNode key : value.path("achieved")) {
            achieved.add(key.asText());
            if (!valid.contains(key.asText())) {
                errors.add("achieved", "Choose milestones from the list.");
            }
        }
        for (JsonNode key : value.path("notYet")) {
            if (!valid.contains(key.asText())) {
                errors.add("notYet", "Choose milestones from the list.");
            }
            if (achieved.contains(key.asText())) {
                errors.add("notYet", "A milestone cannot be both achieved and not yet.");
            }
        }
    }

    // ---- GOALS (SAH-63-2) ----

    private static void goals(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors) {
        uniqueIds(value, "goals", errors);
        Map<String, JsonNode> before = byField(old, "goals", "id");
        for (JsonNode raw : value.path("goals")) {
            ObjectNode goal = (ObjectNode) raw;
            JsonNode prev = before.get(goal.path("id").asText().toLowerCase(Locale.ROOT));
            if (goal.path("achieved").asBoolean()) {
                boolean was = prev != null && prev.path("achieved").asBoolean() && prev.path("achievedOn").isTextual();
                goal.put("achievedOn", was ? prev.path("achievedOn").asText() : ctx.today().toString());
            } else {
                goal.putNull("achievedOn");
            }
        }
    }

    // ---- MODALITIES (SAH-63-2) ----

    private static void modalities(ObjectNode value, Errors errors) {
        Set<String> seen = new HashSet<>();
        for (JsonNode raw : value.path("given")) {
            ObjectNode entry = (ObjectNode) raw;
            if (!seen.add(entry.path("text").asText().toLowerCase(Locale.ROOT))) {
                errors.add("given", "A treatment is listed twice.");
            }
            String key = entry.path("key").asText(null);
            if (key != null && entry.path("params").isNull()) {
                String defaults = PhysioRules.MODALITY_DEFAULTS.get(key);
                if (defaults != null) {
                    entry.put("params", defaults);
                }
            }
        }
    }

    // ---- OUTCOME_SCALES (0.5.1, ADR-023 D13, H20) ----

    /** One row per scale (a repeat is 400 duplicate-item); the score stays within the scale's range (sanity limit, H18). */
    private static void outcomeScales(ObjectNode value, Errors errors) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode row : value.path("rows")) {
            String scale = row.path("scale").asText();
            if (!seen.add(scale)) {
                throw EmrProblemException.duplicateItem();
            }
            JsonNode score = row.path("score");
            Integer max = PhysioRules.OUTCOME_SCALE_MAX.get(scale);
            if (score.isNumber() && max != null && score.asInt() > max) {
                errors.add("rows[" + index + "].score", "The score for this scale is between 0 and " + max + ".");
            }
            index++;
        }
    }

    // ---- SURGERY_PLAN (SAH-64-3, SAH-71-1) ----

    private static void surgeryPlan(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors) {
        String key = value.path("procedureKey").asText(null);
        SurgeryRules.Procedure procedure = key == null ? null : SurgeryRules.procedure(key);
        if (key != null && procedure != null && value.path("procedureText").isNull()) {
            value.put("procedureText", procedure.label());
        }
        String name = value.path("procedureText").asText(null);
        Optional<TariffItem> match = name == null ? Optional.empty() : ctx.tariff().match(name);
        if (match.isPresent()) {
            value.put("packagePaise", match.get().pricePaise());
            value.put("packageSource", "TARIFF");
        } else if (value.path("typedPackagePaise").isNumber()) {
            value.put("packagePaise", value.path("typedPackagePaise").asLong());
            value.put("packageSource", "TYPED");
        } else {
            value.putNull("packagePaise");
            value.put("packageSource", "NONE");
        }
        if (old != null && old.path("booking").isObject()) {
            value.set("booking", old.get("booking"));
        } else {
            value.putNull("booking");
        }
    }

    // ---- ONC_EXAM, ONC_LABS (SAH-65-2, SAH-65-3) ----

    private static void oncExam(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors, List<Note> notes) {
        Map<String, JsonNode> before = byField(old, "lesions", "id");
        uniqueIds(value, "lesions", errors);
        for (JsonNode raw : value.path("lesions")) {
            ObjectNode lesion = (ObjectNode) raw;
            JsonNode prev = before.get(lesion.path("id").asText().toLowerCase(Locale.ROOT));
            if (prev != null && prev.has("lastCm")) {
                lesion.set("lastCm", prev.get("lastCm"));
            } else {
                lesion.putNull("lastCm");
            }
        }
        oncBodyMap(value, old, ctx, errors, notes);
    }

    /** The shared body map rules (pin numbers, stamps, the MLC rule), run for the optional ONC_EXAM body map. */
    private static final com.sahaj.opd.application.emr.EmrComponentValues BODY_MAPS =
            new com.sahaj.opd.application.emr.EmrComponentValues(new com.fasterxml.jackson.databind.ObjectMapper());

    /**
     * SAH-75-5 (ADR-026 D5): the body map of the oncology examination follows the same rules as every body map (ADR-022
     * D8). Nothing marked is stored as null; pins removed from a map that had medico-legal pins still write the MLC audit.
     */
    private static void oncBodyMap(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors, List<Note> notes) {
        JsonNode sent = value.get("bodyMap");
        JsonNode before = old == null || !old.path("bodyMap").isObject() ? null : old.path("bodyMap");
        boolean sentMap = sent != null && sent.isObject();
        if (!sentMap && before == null) {
            value.putNull("bodyMap");
            return;
        }
        JsonNode input = sentMap ? sent : NODES.objectNode().put("component", "BODY_MAP").put("schemaVersion", 1)
                .set("pins", NODES.arrayNode());
        try {
            var result = BODY_MAPS.normalizeBodyMap(input, before,
                    new com.sahaj.opd.application.emr.EmrComponentValues.Context(ctx.now(), ctx.membershipId(), null,
                            null));
            notes.addAll(result.notes());
            if (sentMap && result.value().path("pins").size() > 0) {
                value.set("bodyMap", result.value());
            } else {
                value.putNull("bodyMap");
            }
        } catch (com.sahaj.opd.application.emr.EmrValueRules.InvalidValue e) {
            errors.add("bodyMap", e.getMessage());
        }
    }

    /** SAH-75-5: the pain marker must name one of the drawn body regions (a point outside the figure has none). */
    private static void painScore(ObjectNode value, Errors errors) {
        JsonNode marker = value.path("siteMarker");
        if (marker.isObject() && !com.sahaj.opd.application.emr.EmrComponentValues.REGIONS
                .containsKey(marker.path("region").asText())) {
            errors.add("siteMarker.region", "Mark a point on the body figure.");
        }
    }

    /**
     * SAH-75-6 (ADR-026 D6): "Medical history and alerts". NONE beside another chip of its group is refused; the review
     * stamp is the server's: stamped when the card goes to reviewed, kept while it stays reviewed, cleared when it is
     * taken back; the audit EMR_DENTAL_MEDHX_REVIEWED holds the new state only, never the chips.
     */
    private static void dentalMedHistory(ObjectNode value, JsonNode old, SpecialtyCtx ctx, Errors errors,
                                         List<Note> notes) {
        for (String group : List.of("conditions", "medicines", "allergies")) {
            List<String> chosen = new ArrayList<>();
            value.path(group).forEach(item -> chosen.add(item.asText()));
            if (DentalRules.noneWithOthers(chosen)) {
                errors.add(group, "None cannot be chosen with another option.");
            }
        }
        boolean reviewed = value.path("reviewedWithPatient").asBoolean(false);
        boolean wasReviewed = old != null && old.path("reviewedWithPatient").asBoolean(false);
        if (reviewed) {
            if (wasReviewed && old.path("review").isObject()) {
                value.set("review", old.get("review"));
            } else {
                value.set("review", stamp(ctx));
            }
        } else {
            value.putNull("review");
        }
        if (reviewed != wasReviewed) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("reviewed", reviewed);
            notes.add(new Note("EMR_DENTAL_MEDHX_REVIEWED", audit));
        }
    }

    private static void oncLabs(ObjectNode value, SpecialtyCtx ctx, Errors errors) {
        JsonNode drawn = value.path("drawnAt");
        if (drawn.isTextual()) {
            if (java.time.Instant.parse(drawn.asText()).isAfter(ctx.now().plusSeconds(60))) {
                errors.add("drawnAt", "The time cannot be in the future.");
            }
        } else {
            boolean any = false;
            for (JsonNode labValue : value.path("values")) {
                any = any || !labValue.isNull();
            }
            if (any) {
                value.put("drawnAt", ctx.now().toString());
            }
        }
        value.put("source", "TYPED");
    }

    /** Reads a number of a JSON property, or null (used by the view builders). */
    static Double num(JsonNode node, String property) {
        return number(node.path(property));
    }
}
