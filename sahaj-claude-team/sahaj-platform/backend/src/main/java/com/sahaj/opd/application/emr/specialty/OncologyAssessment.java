package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Facts;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.LabFail;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Labs;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Lesion;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.LesionChange;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Reason;
import com.sahaj.opd.domain.emr.specialty.OncologyRules.Response;
import com.sahaj.opd.domain.emr.specialty.VitalsRules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What the oncology assessment shows and the chemotherapy order reads (SAH-65, SAH-66; rule C-8), worked out from the
 * stored component values of one record by the pure rules of {@code com.sahaj.opd.domain.emr.specialty}. One place, so
 * that the record view and the order sheet can never disagree. Screen data: never logged.
 */
public record OncologyAssessment(Double heightCm, Double weightKg, boolean capAt2, Double bsa, Double bsaUsed,
                                 boolean capped, Double cycle1WeightKg, Double weightChangePct, boolean recalculate,
                                 List<LesionChange> lesionChanges, Response response, Labs labs, Instant drawnAt,
                                 List<LabFail> fails, Boolean labsFresh, boolean fever, Integer feverLowCountsGrade,
                                 Set<String> events, Integer worstGrade, boolean feverOrAdmission, Integer ecog,
                                 String device, Set<String> lineChecks, Boolean lineReady, boolean abnormalHeartExam,
                                 String suggestion, List<Reason> reasons, List<String> alerts) {

    /**
     * @param sibling      the stored specialty value of a component of the record (VITALS_LOG, ONC_TOX, ONC_EXAM,
     *                     ONC_LABS, ONC_ECOG), or null
     * @param cycle1Weight the weight on the patient's first SENT order, or null when there is none
     * @param cumulative   the lifetime doxorubicin including this cycle (mg/m2), or null when not worked out
     */
    public static OncologyAssessment compute(Function<String, JsonNode> sibling, Double cycle1Weight, Instant now,
                                             Double cumulative) {
        JsonNode exam = orEmpty(sibling.apply("ONC_EXAM"));
        JsonNode labsNode = orEmpty(sibling.apply("ONC_LABS"));
        JsonNode tox = orEmpty(sibling.apply("ONC_TOX"));
        JsonNode ecogNode = orEmpty(sibling.apply("ONC_ECOG"));
        JsonNode vitals = orEmpty(sibling.apply("VITALS_LOG"));

        Double height = number(exam.path("heightCm"));
        Double weight = number(exam.path("weightKg"));
        boolean cap = exam.path("capAt2").asBoolean(false);
        Double bsa = OncologyRules.bsa(height, weight);
        Double used = OncologyRules.bsaUsed(bsa, cap);
        Double cycle1 = cycle1Weight == null ? weight : cycle1Weight;
        Double change = cycle1Weight == null && weight != null ? Double.valueOf(0.0)
                : OncologyRules.weightChangePct(weight, cycle1Weight);
        boolean recalc = OncologyRules.recalculateDoses(change);

        List<Lesion> lesions = new ArrayList<>();
        for (JsonNode lesion : exam.path("lesions")) {
            lesions.add(new Lesion(lesion.path("id").asText(), lesion.path("baselineCm").asDouble(),
                    number(lesion.path("lastCm")), number(lesion.path("todayCm"))));
        }
        Response response = OncologyRules.response(lesions, exam.path("newLesionSeen").asBoolean(false));

        JsonNode values = labsNode.path("values");
        Labs labs = new Labs(number(values.path("hb")), integer(values.path("anc")), integer(values.path("plt")),
                number(values.path("cr")), number(values.path("bili")), integer(values.path("alt")),
                integer(values.path("lvef")));
        Instant drawn = labsNode.path("drawnAt").isTextual() ? Instant.parse(labsNode.path("drawnAt").asText()) : null;
        List<LabFail> fails = OncologyRules.labFails(labs);
        Boolean fresh = OncologyRules.labsFresh(drawn, now);

        Double temperature = latestTemperatureC(vitals);
        boolean fever = VitalsRules.oncologyFever(temperature);

        List<Integer> grades = new ArrayList<>();
        Integer feverGrade = null;
        for (JsonNode symptom : tox.path("symptoms")) {
            Integer grade = integer(symptom.path("confirmedGrade"));
            grades.add(grade);
            if ("FEVER_LOW_COUNTS".equals(symptom.path("key").asText())) {
                feverGrade = grade;
            }
        }
        Set<String> events = new HashSet<>();
        for (JsonNode event : tox.path("events")) {
            events.add(event.asText());
        }
        Integer worst = OncologyRules.worstGrade(grades);
        boolean feverEvent = OncologyRules.feverOrAdmissionEvent(feverGrade, events);

        Integer ecog = integer(ecogNode.path("grade"));
        String device = exam.path("line").path("device").isTextual() ? exam.path("line").path("device").asText() : null;
        Set<String> checks = new HashSet<>();
        for (JsonNode check : exam.path("line").path("checks")) {
            checks.add(check.asText());
        }
        Boolean lineReady = OncologyRules.lineReady(device, checks);
        boolean abnormalHeart = "ABNORMAL".equals(exam.path("systems").path("cvs").path("state").asText());

        Facts facts = new Facts(ecog, OncologyRules.countOrOrganFail(fails), fever,
                response.category(), OncologyRules.lvefLow(fails), worst, feverEvent, lineReady, abnormalHeart,
                labs.lvef(), fails);
        String suggestion = OncologyRules.suggestion(facts);
        List<Reason> reasons = OncologyRules.reasons(facts);
        List<String> alerts = OncologyRules.alerts(worst, !fails.isEmpty(), fever, recalc, cumulative, device, checks);
        return new OncologyAssessment(height, weight, cap, bsa, used, OncologyRules.capped(bsa, cap), cycle1, change,
                recalc, OncologyRules.lesionChanges(lesions), response, labs, drawn, fails, fresh, fever, feverGrade,
                Set.copyOf(events), worst, feverEvent, ecog, device, Set.copyOf(checks), lineReady, abnormalHeart,
                suggestion, reasons, alerts);
    }

    /** True when the weight and the height are both recorded (the BSA is worked out from today's values). */
    public boolean weightAndHeightRecorded() {
        return heightCm != null && weightKg != null;
    }

    private static JsonNode orEmpty(JsonNode node) {
        return node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    /** The newest vitals entry that has a temperature, in degrees C (an entry in degrees F is converted). */
    static Double latestTemperatureC(JsonNode vitals) {
        JsonNode entries = vitals.path("entries");
        for (int index = entries.size() - 1; index >= 0; index--) {
            JsonNode temperature = entries.get(index).path("temperature");
            if (temperature.isObject() && temperature.path("value").isNumber()) {
                double value = temperature.path("value").asDouble();
                return "F".equals(temperature.path("unit").asText()) ? (value - 32) * 5 / 9 : value;
            }
        }
        return null;
    }
}
