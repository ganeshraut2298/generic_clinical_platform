package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Earlier;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.RehabInfo;
import com.sahaj.opd.domain.emr.specialty.AncRules;
import com.sahaj.opd.domain.emr.specialty.AncRules.Dating;
import com.sahaj.opd.domain.emr.specialty.AncRules.Today;
import com.sahaj.opd.domain.emr.specialty.ChemoRules;
import com.sahaj.opd.domain.emr.specialty.DentalRules;
import com.sahaj.opd.domain.emr.specialty.Flag;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import com.sahaj.opd.domain.emr.specialty.PaedsRules;
import com.sahaj.opd.domain.emr.specialty.PhysioRules;
import com.sahaj.opd.domain.emr.specialty.RuleTag;
import com.sahaj.opd.domain.emr.specialty.SurgeryRules;
import com.sahaj.opd.domain.emr.specialty.VitalsRules;
import com.sahaj.patient.api.PatientAge;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The server-computed {@code specialty} object of the record view (docs/api/opd.yaml 0.5.0, EmrSpecialtyView): flags,
 * dates, states and engine results worked out from the stored values on every read and save. The server's answer wins
 * over any live computation of the screen. Only the block of the record's profile is set. Screen data: never logged.
 */
public final class SpecialtyViewBuilder {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SpecialtyViewBuilder() {
    }

    /**
     * @param mode       the visit mode (og ANC or GYNAE; physio FIRST_VISIT or SESSION), or null
     * @param oncDecision the stored decision object of screen_state, or null
     */
    public static ObjectNode build(String profile, SpecialtyCtx ctx, String mode, JsonNode oncDecision) {
        return build(profile, ctx, mode, oncDecision, List.of());
    }

    /**
     * @param hiddenSectionKeys the sections the effective mode hides (ADR-023 D13.3), in layout order
     */
    public static ObjectNode build(String profile, SpecialtyCtx ctx, String mode, JsonNode oncDecision,
                                   List<String> hiddenSectionKeys) {
        ObjectNode view = NODES.objectNode();
        view.put("profile", profile);
        if (mode == null) {
            view.putNull("mode");
        } else {
            view.put("mode", mode);
        }
        ArrayNode hidden = view.putArray("hiddenSectionKeys");
        hiddenSectionKeys.forEach(hidden::add);
        view.put("tagNote", RuleTag.TAG);
        switch (profile) {
            case "gm" -> view.set("vitalsFlags", flags(vitalsFlags(ctx, false)));
            case "dental" -> view.set("dental", dental(ctx));
            case "og" -> view.set("og", og(ctx));
            case "paeds" -> view.set("paeds", paeds(ctx));
            case "physio" -> view.set("physio", physio(ctx));
            case "surgery" -> view.set("surgery", surgery(ctx));
            case "onco" -> {
                view.set("vitalsFlags", flags(vitalsFlags(ctx, true)));
                view.set("onco", onco(ctx, oncDecision));
            }
            default -> {
                // no block for another profile
            }
        }
        return view;
    }

    // ---- helpers ----

    static ArrayNode flags(List<Flag> flags) {
        ArrayNode array = NODES.arrayNode();
        for (Flag flag : flags) {
            ObjectNode node = array.addObject();
            node.put("field", flag.field());
            node.put("label", flag.label());
            node.put("alert", flag.alert());
        }
        return array;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static void put(ObjectNode node, String name, Double value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void put(ObjectNode node, String name, Integer value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void put(ObjectNode node, String name, Long value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void put(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void put(ObjectNode node, String name, LocalDate value) {
        put(node, name, value == null ? null : value.toString());
    }

    private static JsonNode orEmpty(JsonNode node) {
        return node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node;
    }

    // ---- vitals (gm, onco) ----

    private static List<Flag> vitalsFlags(SpecialtyCtx ctx, boolean onco) {
        JsonNode entries = orEmpty(ctx.sibling().apply("VITALS_LOG")).path("entries");
        if (entries.isEmpty()) {
            return List.of();
        }
        JsonNode last = entries.get(entries.size() - 1);
        Double temperature = last.path("temperature").path("value").isNumber()
                ? last.path("temperature").path("value").asDouble() : null;
        VitalsRules.Reading reading = new VitalsRules.Reading(integer(last.path("bpSystolic")),
                integer(last.path("bpDiastolic")), integer(last.path("pulse")), integer(last.path("spo2")),
                temperature, integer(last.path("respRate")));
        return onco ? VitalsRules.oncology(reading) : VitalsRules.generalMedicine(reading);
    }

    // ---- dental ----

    private static ObjectNode dental(SpecialtyCtx ctx) {
        JsonNode plan = orEmpty(ctx.sibling().apply("DENTAL_PLAN"));
        ObjectNode node = NODES.objectNode();
        node.put("estimateTotalPaise", plan.path("estimate").path("totalPaise").asLong(0));
        node.put("estimateState", plan.path("estimate").path("state").asText("NOT_SIGNED"));
        ArrayNode done = node.putArray("doneTodayRowIds");
        for (JsonNode row : plan.path("rows")) {
            if ("DONE".equals(row.path("status").asText()) && ctx.recordDate().toString().equals(
                    row.path("doneOn").asText())) {
                done.add(row.path("id").asText());
            }
        }
        // ADR-024 D7: under 13 by the effective years (from the date of birth when recorded, else the stored age)
        node.put("childTabDefault", DentalRules.childTabDefault(ctx.ageYears()));
        return node;
    }

    // ---- obstetrics ----

    private static LocalDate lmp(SpecialtyCtx ctx) {
        JsonNode menstrual = orEmpty(ctx.hooks().section().apply("menstrual"));
        JsonNode lmp = menstrual.path("lmp");
        try {
            return lmp.isTextual() ? LocalDate.parse(lmp.asText()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static ObjectNode og(SpecialtyCtx ctx) {
        JsonNode anc = orEmpty(ctx.sibling().apply("ANC_CHECK"));
        ObjectNode node = NODES.objectNode();
        LocalDate scanEdd = null;
        try {
            scanEdd = anc.path("datingScanEdd").isTextual() ? LocalDate.parse(anc.path("datingScanEdd").asText()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            scanEdd = null;
        }
        Dating dating = AncRules.dating(lmp(ctx), scanEdd, ctx.today());
        if (dating.ga() == null) {
            node.putNull("gestationalAge");
        } else {
            ObjectNode ga = node.putObject("gestationalAge");
            ga.put("weeks", dating.ga().weeks());
            ga.put("days", dating.ga().days());
        }
        put(node, "edd", dating.edd());
        node.put("eddSource", dating.source());
        put(node, "daysToEdd", dating.daysToEdd());
        node.putNull("obstetricScore");
        JsonNode today = anc.path("today");
        Today values = new Today(number(today.path("weightKg")), integer(today.path("bpSystolic")),
                integer(today.path("bpDiastolic")), integer(today.path("fundalHeightCm")),
                integer(today.path("fetalHeartBpm")), number(today.path("hbGdl")));
        // "+x kg since last visit": today's weight minus the weight of the pregnancy's last earlier visit
        Double change = null;
        if (values.weightKg() != null) {
            for (Earlier earlier : ctx.hooks().earlier().apply("ANC_CHECK")) {
                Double before = number(earlier.value().path("today").path("weightKg"));
                if (before != null) {
                    change = Math.round((values.weightKg() - before) * 10.0) / 10.0;
                    break;
                }
            }
        }
        put(node, "weightChangeKg", change);
        node.set("notes", flags(AncRules.notes(values, dating.ga())));
        List<String> symptoms = new ArrayList<>();
        for (JsonNode symptom : anc.path("symptoms")) {
            symptoms.add(symptom.asText());
        }
        ArrayNode alerts = node.putArray("alerts");
        AncRules.alerts(values, symptoms).forEach(alerts::add);
        boolean lscs = orEmpty(ctx.hooks().section().apply("obstetric")).path("previous_lscs").asBoolean(false);
        node.set("riskChips", flags(AncRules.riskChips(lscs)));
        Set<String> done = new HashSet<>();
        for (JsonNode scan : anc.path("scans")) {
            if (scan.path("done").asBoolean(false)) {
                done.add(scan.path("key").asText());
            }
        }
        ArrayNode states = node.putArray("scanStates");
        for (AncRules.ScanWindow window : AncRules.SCAN_WINDOWS) {
            ObjectNode state = states.addObject();
            state.put("key", window.key());
            state.put("state", AncRules.scanState(window, done.contains(window.key()), dating.ga()));
        }
        Set<String> ordered = new HashSet<>();
        for (JsonNode order : anc.path("orders")) {
            ordered.add(order.path("key").asText());
        }
        ArrayNode due = node.putArray("dueOrders");
        for (AncRules.DueOrder order : AncRules.DUE_ORDERS) {
            ObjectNode item = due.addObject();
            item.put("key", order.key());
            item.put("window", order.window());
            item.put("ordered", ordered.contains(order.key()));
        }
        boolean scanOrdered = SpecialtyValues.obstetricScanOrdered(anc);
        boolean signed = orEmpty(ctx.sibling().apply("FORM_F")).path("filledAndSigned").asBoolean(false);
        node.put("formFRequired", scanOrdered && !signed);
        node.set("formFCount", formFCount(ctx, anc, scanOrdered, signed));
        return node;
    }

    private static JsonNode formFCount(SpecialtyCtx ctx, JsonNode anc, boolean scanOrdered, boolean signed) {
        String course = anc.path("pregnancyCourseId").asText(null);
        if (course == null) {
            return NODES.nullNode();
        }
        int total = scanOrdered ? 1 : 0;
        int complete = scanOrdered && signed ? 1 : 0;
        Map<java.util.UUID, JsonNode> formByRecord = new java.util.HashMap<>();
        for (Earlier earlier : ctx.hooks().earlier().apply("FORM_F")) {
            formByRecord.put(earlier.recordId(), earlier.value());
        }
        for (Earlier earlier : ctx.hooks().earlier().apply("ANC_CHECK")) {
            if (course.equals(earlier.value().path("pregnancyCourseId").asText(null))
                    && SpecialtyValues.obstetricScanOrdered(earlier.value())) {
                total++;
                JsonNode form = formByRecord.get(earlier.recordId());
                if (form != null && form.path("filledAndSigned").asBoolean(false)) {
                    complete++;
                }
            }
        }
        ObjectNode count = NODES.objectNode();
        count.put("complete", complete);
        count.put("total", total);
        return count;
    }

    // ---- paediatrics ----

    private static ObjectNode paeds(SpecialtyCtx ctx) {
        ObjectNode node = NODES.objectNode();
        // ADR-024 D1, D7: the months and days come from PatientAge, never an estimate from the stored age
        boolean dated = ctx.dateOfBirth() != null && !PatientAge.isFuture(ctx.dateOfBirth(), ctx.today());
        Integer months = dated ? PatientAge.completedMonths(ctx.dateOfBirth(), ctx.today()) : null;
        node.put("dateOfBirthRecorded", ctx.dateOfBirth() != null);
        if (ctx.dateOfBirthVisible()) {
            put(node, "dateOfBirth", ctx.dateOfBirth());
        }
        put(node, "ageMonths", months);
        put(node, "ageDays", dated ? PatientAge.daysAfterMonths(ctx.dateOfBirth(), ctx.today()) : null);
        JsonNode growth = orEmpty(ctx.sibling().apply("GROWTH"));
        // DEC-044 (H8): weight only; a length or head circumference never gets a note
        List<Flag> notes = new ArrayList<>();
        PaedsRules.growthNote("WEIGHT", ctx.sex(), months, number(growth.path("weightKg")),
                ctx.ref().growthBands()).ifPresent(notes::add);
        node.set("growthNotes", flags(notes));

        JsonNode immunization = orEmpty(ctx.sibling().apply("IMMUNIZATION"));
        Map<String, String> givenHere = new java.util.HashMap<>();
        for (JsonNode given : immunization.path("givenToday")) {
            givenHere.put(given.path("vaccineKey").asText(), ctx.today().toString());
        }
        Set<String> given = new HashSet<>(ctx.earlierVaccines());
        given.addAll(givenHere.keySet());
        var states = PaedsRules.vaccineStates(ctx.ref().groups(), ctx.ref().vaccines(), given, ctx.dateOfBirth(),
                ctx.today());
        ArrayNode vaccines = node.putArray("vaccines");
        int dueToday = 0;
        int overdue = 0;
        String nextKey = null;
        for (PaedsRules.VaccineState state : states) {
            ObjectNode item = vaccines.addObject();
            item.put("vaccineKey", state.vaccineKey());
            item.put("state", state.state());
            boolean here = givenHere.containsKey(state.vaccineKey());
            if (here) {
                item.put("givenOn", givenHere.get(state.vaccineKey()));
            } else {
                item.putNull("givenOn");
            }
            item.put("givenHere", here);
            dueToday += "DUE_TODAY".equals(state.state()) ? 1 : 0;
            overdue += "OVERDUE".equals(state.state()) ? 1 : 0;
            if (nextKey == null && "LATER".equals(state.state())) {
                nextKey = state.vaccineKey();
            }
        }
        node.put("dueTodayCount", dueToday);
        node.put("overdueCount", overdue);
        if (nextKey == null) {
            node.putNull("nextDue");
        } else {
            PaedsRules.Vaccine vaccine = ctx.ref().vaccine(nextKey);
            String groupLabel = ctx.ref().groups().stream().filter(g -> g.key().equals(vaccine.groupKey()))
                    .map(PaedsRules.Group::label).findFirst().orElse("");
            ObjectNode next = node.putObject("nextDue");
            next.put("vaccineKey", nextKey);
            next.put("groupLabel", groupLabel);
        }
        node.put("milestonesSetUp", months != null && !ctx.ref().milestonesOf(months).isEmpty());
        Double weight = number(growth.path("weightKg"));
        put(node, "doseWeightKg", weight);
        ArrayNode changed = node.putArray("doseWeightChangedRowIds");
        for (JsonNode row : ctx.hooks().prescriptionRows().get()) {
            JsonNode calc = row.path("calculation");
            if (calc.isObject() && weight != null && calc.path("weightKg").asDouble() != weight) {
                changed.add(row.path("id").asText());
            }
        }
        return node;
    }

    // ---- physiotherapy ----

    private static ObjectNode physio(SpecialtyCtx ctx) {
        ObjectNode node = NODES.objectNode();
        RehabInfo rehab = ctx.hooks().rehab().get();
        put(node, "sessionNo", rehab == null ? null : rehab.sessionNo());
        put(node, "sessionsPlanned", rehab == null ? null : rehab.sessionsPlanned());
        put(node, "packageName", rehab == null ? null : rehab.packageName());
        ArrayNode percents = node.putArray("romPercents");
        JsonNode rom = orEmpty(ctx.sibling().apply("ROM_TABLE"));
        boolean knee = "knee".equalsIgnoreCase(rom.path("joint").asText());
        for (JsonNode row : rom.path("rows")) {
            ObjectNode item = percents.addObject();
            String movement = row.path("movement").asText();
            item.put("movement", movement);
            Integer normalMax = null;
            String normal = null;
            if (knee) {
                for (var joint : ctx.ref().joint("knee", "ROM")) {
                    if (joint.itemKey().equalsIgnoreCase(movement)) {
                        normalMax = joint.normalToDeg();
                        normal = joint.normalFromDeg() + "–" + joint.normalToDeg() + "°";
                    }
                }
            }
            put(item, "rightPct", PhysioRules.romPercent(integer(row.path("rightDeg")), normalMax));
            put(item, "normal", normal);
        }
        node.set("outcomeScales", outcomeScales(ctx));
        return node;
    }

    /**
     * The comparison chips of the outcome scales (0.5.1, D13): one entry per scale scored today or in the latest earlier
     * SIGNED Physiotherapy record that scored it, in the order ODI, NDI, WOMAC, LEFS.
     */
    private static ArrayNode outcomeScales(SpecialtyCtx ctx) {
        ArrayNode scales = NODES.arrayNode();
        JsonNode today = orEmpty(ctx.sibling().apply("OUTCOME_SCALES"));
        List<Earlier> earlier = ctx.hooks().earlier().apply("OUTCOME_SCALES");
        for (String scale : PhysioRules.OUTCOME_SCALES) {
            Integer todayScore = scoreOf(today, scale);
            Integer previous = null;
            LocalDate previousDate = null;
            for (Earlier before : earlier) {
                Integer score = scoreOf(before.value(), scale);
                if (score != null) {
                    previous = score;
                    previousDate = before.date();
                    break;
                }
            }
            if (todayScore == null && previous == null) {
                continue;
            }
            ObjectNode item = scales.addObject();
            item.put("scale", scale);
            put(item, "previousScore", previous);
            put(item, "previousDate", previousDate);
            put(item, "todayScore", todayScore);
        }
        return scales;
    }

    private static Integer scoreOf(JsonNode value, String scale) {
        for (JsonNode row : value.path("rows")) {
            if (scale.equals(row.path("scale").asText()) && row.path("score").isInt()) {
                return row.path("score").asInt();
            }
        }
        return null;
    }

    // ---- surgery ----

    private static ObjectNode surgery(SpecialtyCtx ctx) {
        ObjectNode node = NODES.objectNode();
        JsonNode fitness = orEmpty(ctx.sibling().apply("SURGERY_FITNESS"));
        var summary = SpecialtyDone.fitness(fitness);
        ObjectNode fit = node.putObject("fitness");
        fit.put("state", summary.state());
        fit.put("text", summary.text());
        JsonNode plan = orEmpty(ctx.sibling().apply("SURGERY_PLAN"));
        JsonNode consent = ctx.sibling().apply("CONSENT_CHECKLIST");
        JsonNode preauth = ctx.sibling().apply("PREAUTH");
        String payer = plan.path("payer").asText("SELF");
        Long packagePaise = plan.path("packagePaise").isNumber() ? plan.path("packagePaise").asLong() : null;
        put(node, "depositPaise", SurgeryRules.depositPaise(payer, packagePaise));
        boolean sent = orEmpty(preauth).path("sent").asBoolean(false);
        node.put("payerChip", SurgeryRules.payerChip(payer, sent));
        int ticked = 0;
        int total = 0;
        if (consent != null) {
            for (JsonNode item : consent.path("items")) {
                total++;
                ticked += item.path("confirmed").asBoolean(false) ? 1 : 0;
            }
        }
        List<String> missing = SurgeryRules.bookMissing(ctx.hooks().hasActiveDiagnosis().getAsBoolean(), ticked, total,
                payer, sent);
        SpecialtyDone.Ctx done = new SpecialtyDone.Ctx(ctx.recordDate(), ctx.sibling(), "NONE");
        String state = SpecialtyDone.bookingState(plan, done);
        if ("NOT_BOOKED".equals(state) && missing.isEmpty()) {
            state = "READY";
        }
        node.put("bookState", state);
        ArrayNode missingNode = node.putArray("bookMissing");
        missing.forEach(missingNode::add);
        ObjectNode count = node.putObject("consentCount");
        count.put("ticked", ticked);
        count.put("total", Math.max(total, SurgeryRules.CONSENT_ITEMS.size()));
        return node;
    }

    // ---- oncology ----

    private static ChemoRules.Protocol protocolOf(SpecialtyCtx ctx, ChemoInfo chemo) {
        ChemoRules.Protocol chosen = chemo.protocolKey() == null ? null : ctx.ref().protocol(chemo.protocolKey());
        if (chosen != null || ctx.ref().protocols().isEmpty()) {
            return chosen;
        }
        return ctx.ref().protocols().get(0);
    }

    /** Lifetime doxorubicin including this cycle (mg/m2), or null when the protocol has no anthracycline. */
    private static Double cumulative(ChemoInfo chemo, ChemoRules.Protocol protocol) {
        if (protocol == null || protocol.drugs().stream().noneMatch(ChemoRules.Drug::anthracycline)) {
            return null;
        }
        double outside = chemo.priorOutsideMgM2() == null ? chemo.latestOutsideMgM2() : chemo.priorOutsideMgM2();
        return ChemoRules.lifetime(outside + chemo.sentDoxorubicinMgM2(),
                ChemoRules.thisCycleMgM2(protocol, chemo.levelPct())).totalMgM2();
    }

    private static ObjectNode onco(SpecialtyCtx ctx, JsonNode decision) {
        ChemoInfo chemo = ctx.hooks().chemo().get();
        ChemoRules.Protocol protocol = protocolOf(ctx, chemo);
        Double cumulative = cumulative(chemo, protocol);
        OncologyAssessment a = OncologyAssessment.compute(ctx.sibling(), chemo.cycle1WeightKg(), ctx.now(),
                cumulative);
        ObjectNode node = NODES.objectNode();
        put(node, "bsa", a.bsa());
        put(node, "bsaUsed", a.bsaUsed());
        node.put("capped", a.capped());
        put(node, "cycle1WeightKg", a.cycle1WeightKg());
        put(node, "weightChangePct", a.weightChangePct());
        node.put("recalculateDoses", a.recalculate());
        ArrayNode lesions = node.putArray("lesionChanges");
        for (OncologyRules.LesionChange change : a.lesionChanges()) {
            ObjectNode item = lesions.addObject();
            item.put("id", change.id());
            put(item, "pctFromBaseline", change.pctFromBaseline());
            item.put("colour", change.colour());
        }
        ObjectNode sums = node.putObject("sums");
        if (a.response().sums() != null) {
            sums.put("baseline", a.response().sums().baseline());
            sums.put("previous", a.response().sums().previous());
            sums.put("today", a.response().sums().today());
            sums.put("nadir", a.response().sums().nadir());
            if (a.response().sums().pctFromBaseline() != null) {
                sums.put("pctFromBaseline", a.response().sums().pctFromBaseline());
            }
            if (a.response().sums().pctFromNadir() != null) {
                sums.put("pctFromNadir", a.response().sums().pctFromNadir());
            }
        }
        put(node, "response", a.response().category());
        ArrayNode fails = node.putArray("labFails");
        for (OncologyRules.LabFail fail : a.fails()) {
            ObjectNode item = fails.addObject();
            item.put("key", fail.key());
            item.put("direction", fail.direction());
        }
        node.put("labsBadge", OncologyRules.labsBadge(a.fails()));
        if (a.labsFresh() == null) {
            node.putNull("labsFresh");
        } else {
            node.put("labsFresh", a.labsFresh());
        }
        node.put("fever", a.fever());
        node.put("feverOrAdmissionEvent", a.feverOrAdmission());
        node.put("pegfilgrastimSuggested", "TC".equals(chemo.protocolKey()) || a.feverOrAdmission());
        put(node, "worstGrade", a.worstGrade());
        put(node, "ecogText", OncologyRules.ecogText(a.ecog()));
        if (a.lineReady() == null) {
            node.putNull("lineReady");
        } else {
            node.put("lineReady", a.lineReady());
        }
        put(node, "suggestion", a.suggestion());
        ArrayNode reasons = node.putArray("reasons");
        for (OncologyRules.Reason reason : a.reasons()) {
            ObjectNode item = reasons.addObject();
            item.put("key", reason.key());
            item.put("ok", reason.ok());
            item.put("text", reason.text());
        }
        if (decision != null && decision.isObject() && decision.has("choice")) {
            ObjectNode stored = node.putObject("decision");
            stored.set("choice", decision.get("choice"));
            stored.set("reasonKey", decision.path("reasonKey").isMissingNode() ? NODES.nullNode()
                    : decision.get("reasonKey"));
            stored.set("suggestionAtDecision", decision.path("suggestionAtDecision"));
            ObjectNode stamp = stored.putObject("stamp");
            stamp.set("at", decision.path("decidedAt"));
            stamp.set("byMembershipId", decision.path("decidedByMembershipId"));
            node.put("factsChanged", a.suggestion() != null
                    && !a.suggestion().equals(decision.path("suggestionAtDecision").asText()));
        } else {
            node.putNull("decision");
            node.put("factsChanged", false);
        }
        ArrayNode alerts = node.putArray("alerts");
        a.alerts().forEach(alerts::add);
        put(node, "cumulativeDoxorubicinMgM2", cumulative);
        put(node, "cycleNo", chemo.linkedCycleNo() == null ? Integer.valueOf(chemo.sentCount() + 1)
                : chemo.linkedCycleNo());
        boolean delayed = decision != null && "DELAY_1_WEEK".equals(decision.path("choice").asText());
        if (protocol == null) {
            node.putNull("nextCycle");
        } else {
            OncologyRules.NextCycle next = OncologyRules.nextCycle(ctx.today(), protocol.intervalDays(), delayed,
                    a.labs().lvef());
            ObjectNode cycle = node.putObject("nextCycle");
            cycle.put("defaultDate", next.defaultDate().toString());
            cycle.put("label", next.label());
            cycle.put("bloodTestDate", next.bloodTestDate().toString());
            cycle.put("echo", next.echo());
        }
        node.put("orderStatus", chemo.orderStatus());
        return node;
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
