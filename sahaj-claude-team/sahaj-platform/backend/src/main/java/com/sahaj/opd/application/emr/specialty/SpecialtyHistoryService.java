package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Earlier;
import com.sahaj.opd.domain.emr.specialty.VitalsRules;
import com.sahaj.patient.api.PatientAge;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /emr/records/{id}/specialty-history (ADR-023 D2, the ADR-022 D10 pattern): the earlier values the specialty screen
 * shows, read on demand from the patient's SIGNED records (at most the 500 newest are scanned) and the chemotherapy orders.
 * Nothing is copied or stored. Until SAH-51 signs records every block that reads signed records is empty; the oncology
 * cycles are read from SENT orders. Same access as the record. No audit; nothing here is logged.
 */
@Service
public class SpecialtyHistoryService {

    private static final int MAX = 100;

    private final EmrRecordService records;
    private final SpecialtyRecordSupport support;
    private final ChemoOrderRepository orders;
    private final TeamMemberPort team;
    private final ObjectMapper mapper;

    public SpecialtyHistoryService(EmrRecordService records, SpecialtyRecordSupport support,
                                   ChemoOrderRepository orders, TeamMemberPort team, ObjectMapper mapper) {
        this.records = records;
        this.support = support;
        this.orders = orders;
        this.team = team;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(ClinicUser user, UUID recordId) {
        Authorized authorized = records.authorize(user, recordId);
        RecordRow row = authorized.row();
        String profile = SpecialtyProfiles.profileOf(row.encounterType(), row.starterKey())
                .orElseThrow(EmrProblemException::notApplicable);
        UUID tenantId = user.tenantId();
        UUID patientId = authorized.patientId();
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        ObjectNode view = mapper.createObjectNode();
        view.put("profile", profile);
        view.put("someHidden", false);
        ArrayNode toothHistory = view.putArray("dentalToothHistory");
        switch (profile) {
            case "gm", "onco" -> vitals(view, tenantId, patientId, row, profile, document);
            case "og" -> ancVisits(view, tenantId, patientId, row, document);
            case "paeds" -> growth(view, tenantId, patientId, row, document);
            case "physio" -> physio(view, tenantId, patientId, row, document);
            case "dental" -> dentalToothHistory(toothHistory, tenantId, patientId, row);
            default -> {
                // no history block for another profile
            }
        }
        if ("onco".equals(profile)) {
            cycles(view, tenantId, patientId, row);
        }
        return view;
    }

    private JsonNode today(String profile, EmrRecordDocument document, String component) {
        return SpecialtyRecordSupport.componentValue(profile, document, component);
    }

    // ---- vitals (gm, onco) ----

    private void vitals(ObjectNode view, UUID tenantId, UUID patientId, RecordRow row, String profile,
                        EmrRecordDocument document) {
        List<JsonNode> entries = new ArrayList<>();
        JsonNode now = today(profile, document, "VITALS_LOG");
        if (now != null) {
            for (int i = now.path("entries").size() - 1; i >= 0; i--) {
                entries.add(now.path("entries").get(i));
            }
        }
        List<Earlier> earlier = support.earlier(tenantId, patientId, row.id(), "VITALS_LOG");
        for (Earlier e : earlier) {
            for (int i = e.value().path("entries").size() - 1; i >= 0; i--) {
                entries.add(e.value().path("entries").get(i));
            }
        }
        Set<UUID> ids = new java.util.HashSet<>();
        for (JsonNode entry : entries) {
            if (entry.path("recorded").path("byMembershipId").isTextual()) {
                ids.add(UUID.fromString(entry.path("recorded").path("byMembershipId").asText()));
            }
        }
        Map<UUID, TeamMember> names = ids.isEmpty() ? Map.of() : team.findAll(tenantId, ids);
        ArrayNode out = view.putArray("vitals");
        for (JsonNode entry : entries.subList(0, Math.min(MAX, entries.size()))) {
            ObjectNode item = out.addObject();
            item.put("recordedAt", entry.path("recorded").path("at").asText());
            String by = entry.path("recorded").path("byMembershipId").asText(null);
            TeamMember member = by == null ? null : names.get(UUID.fromString(by));
            item.put("recordedByName", member == null ? "" : member.name());
            item.put("bp", entry.path("bpSystolic").isInt() && entry.path("bpDiastolic").isInt()
                    ? entry.path("bpSystolic").asInt() + "/" + entry.path("bpDiastolic").asInt() : null);
            putInt(item, "pulse", entry.path("pulse"));
            putInt(item, "spo2", entry.path("spo2"));
            JsonNode temperature = entry.path("temperature");
            item.put("temperature", temperature.isObject() ? temperature.path("value").asText() + " °"
                    + temperature.path("unit").asText() : null);
            putInt(item, "respRate", entry.path("respRate"));
            putInt(item, "pain", entry.path("pain"));
            item.put("how", entry.path("how").asText("TYPED"));
            VitalsRules.Reading reading = new VitalsRules.Reading(intOrNull(entry.path("bpSystolic")),
                    intOrNull(entry.path("bpDiastolic")), intOrNull(entry.path("pulse")), intOrNull(entry.path("spo2")),
                    temperature.path("value").isNumber() ? temperature.path("value").asDouble() : null,
                    intOrNull(entry.path("respRate")));
            item.set("flags", SpecialtyViewBuilder.flags("onco".equals(profile) ? VitalsRules.oncology(reading)
                    : VitalsRules.generalMedicine(reading)));
        }
        ObjectNode trend = view.putObject("trend");
        ArrayNode bp = trend.putArray("bp");
        for (JsonNode entry : entries) {
            if (bp.size() >= 5) {
                break;
            }
            if (entry.path("bpSystolic").isInt() && entry.path("bpDiastolic").isInt()) {
                ObjectNode point = bp.addObject();
                point.put("date", entry.path("recorded").path("at").asText("").length() >= 10
                        ? entry.path("recorded").path("at").asText().substring(0, 10) : null);
                point.put("systolic", entry.path("bpSystolic").asInt());
                point.put("diastolic", entry.path("bpDiastolic").asInt());
            }
        }
        trend.putArray("sugar");
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static void putInt(ObjectNode node, String name, JsonNode value) {
        if (value.isNumber()) {
            node.put(name, value.asInt());
        } else {
            node.putNull(name);
        }
    }

    // ---- obstetrics ----

    private void ancVisits(ObjectNode view, UUID tenantId, UUID patientId, RecordRow row, EmrRecordDocument document) {
        ArrayNode out = view.putArray("ancVisits");
        JsonNode now = today("og", document, "ANC_CHECK");
        String course = now == null ? null : now.path("pregnancyCourseId").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (Earlier e : support.earlier(tenantId, patientId, row.id(), "ANC_CHECK")) {
            if (course != null && course.equals(e.value().path("pregnancyCourseId").asText(null))) {
                items.add(ancItem(e.value(), e.date(), false));
            }
        }
        java.util.Collections.reverse(items);
        if (now != null) {
            items.add(ancItem(now, row.createdAt().atZone(SpecialtyRecordSupport.INDIA).toLocalDate(), true));
        }
        items.stream().limit(40).forEach(out::add);
    }

    private ObjectNode ancItem(JsonNode value, LocalDate date, boolean today) {
        ObjectNode item = mapper.createObjectNode();
        item.put("date", date.toString());
        item.putNull("gestationalAge");
        JsonNode values = value.path("today");
        if (values.path("weightKg").isNumber()) {
            item.put("weightKg", values.path("weightKg").asDouble());
        } else {
            item.putNull("weightKg");
        }
        item.put("bp", values.path("bpSystolic").isInt() && values.path("bpDiastolic").isInt()
                ? values.path("bpSystolic").asInt() + "/" + values.path("bpDiastolic").asInt() : null);
        putInt(item, "fundalHeightCm", values.path("fundalHeightCm"));
        if (values.path("hbGdl").isNumber()) {
            item.put("hbGdl", values.path("hbGdl").asDouble());
        } else {
            item.putNull("hbGdl");
        }
        item.put("note", value.path("planNote").asText(null));
        item.put("today", today);
        return item;
    }

    // ---- paediatrics ----

    private void growth(ObjectNode view, UUID tenantId, UUID patientId, RecordRow row, EmrRecordDocument document) {
        ArrayNode out = view.putArray("growthPoints");
        // ADR-024 D7: the age at each point comes from the date of birth, and is null (not plotted) without one
        LocalDate dateOfBirth = patientId == null ? null : support.dateOfBirth(tenantId, patientId);
        List<Earlier> earlier = new ArrayList<>(support.earlier(tenantId, patientId, row.id(), "GROWTH"));
        java.util.Collections.reverse(earlier);
        for (Earlier e : earlier) {
            point(out, e.value(), e.date(), dateOfBirth);
        }
        JsonNode now = today("paeds", document, "GROWTH");
        if (now != null && now.path("weightKg").isNumber()) {
            point(out, now, row.createdAt().atZone(SpecialtyRecordSupport.INDIA).toLocalDate(), dateOfBirth);
        }
    }

    private void point(ArrayNode out, JsonNode value, LocalDate date, LocalDate dateOfBirth) {
        ObjectNode point = out.addObject();
        point.put("date", date.toString());
        java.math.BigDecimal months = PatientAge.monthsAt(dateOfBirth, date);
        if (months == null) {
            point.putNull("ageMonths");
        } else {
            point.put("ageMonths", months);
        }
        for (String key : List.of("weightKg", "lengthCm", "headCircCm")) {
            if (value.path(key).isNumber()) {
                point.put(key, value.path(key).asDouble());
            } else {
                point.putNull(key);
            }
        }
    }

    // ---- physiotherapy ----

    private void physio(ObjectNode view, UUID tenantId, UUID patientId, RecordRow row, EmrRecordDocument document) {
        List<Earlier> pains = support.earlier(tenantId, patientId, row.id(), "PAIN_SCORE");
        ObjectNode progress = view.putObject("physioProgress");
        if (pains.isEmpty()) {
            progress.put("sessionsDone", 0);
            progress.putNull("sessionsPlanned");
            progress.putNull("painFirst");
            progress.putNull("painLast");
            progress.putNull("romFirst");
            progress.putNull("romLast");
            progress.putArray("goalsAchieved");
            progress.putArray("painBySession");
            progress.put("onlyFirstVisit", true);
            return;
        }
        progress.put("sessionsDone", pains.size());
        progress.putNull("sessionsPlanned");
        JsonNode first = pains.get(pains.size() - 1).value();
        JsonNode last = pains.get(0).value();
        putInt(progress, "painFirst", first.path("score"));
        putInt(progress, "painLast", last.path("score"));
        progress.putNull("romFirst");
        progress.putNull("romLast");
        ArrayNode goals = progress.putArray("goalsAchieved");
        for (Earlier e : support.earlier(tenantId, patientId, row.id(), "GOALS")) {
            for (JsonNode goal : e.value().path("goals")) {
                if (goal.path("achieved").asBoolean(false) && goal.path("text").isTextual()) {
                    goals.add(goal.path("text").asText());
                }
            }
            break;
        }
        ArrayNode bySession = progress.putArray("painBySession");
        for (SpecialtyRecordSupport.SessionPain pain : support.painBySession(tenantId, patientId, row.id())) {
            ObjectNode item = bySession.addObject();
            item.put("sessionNo", pain.sessionNo());
            item.put("score", pain.score());
        }
        progress.put("onlyFirstVisit", false);
    }


    // ---- dental: earlier work per tooth (SAH-75-11 D6) ----

    /**
     * Plan rows marked done in the patient's SIGNED dental records, newest record first: {@code tooth} (FDI, as text),
     * the day it was done and the procedure as written, with "sitting n of m" when the row has more than one sitting.
     * Empty until SAH-51 signs records. Clinical text: never logged.
     */
    private void dentalToothHistory(ArrayNode out, UUID tenantId, UUID patientId, RecordRow row) {
        for (Earlier e : support.earlier(tenantId, patientId, row.id(), "DENTAL_PLAN")) {
            for (JsonNode plan : e.value().path("rows")) {
                if (out.size() >= 200) {
                    return;
                }
                if (!"DONE".equals(plan.path("status").asText()) || !plan.path("tooth").isInt()
                        || !plan.path("procedureText").isTextual()) {
                    continue;
                }
                String tooth = String.valueOf(plan.path("tooth").asInt());
                if (!tooth.matches("^[1-8][1-8]$")) {
                    continue;
                }
                int planned = plan.path("sittingsPlanned").asInt(1);
                String text = plan.path("procedureText").asText()
                        + (planned > 1 ? " · sitting " + plan.path("currentSitting").asInt(1) + " of " + planned : "");
                ObjectNode item = out.addObject();
                item.put("tooth", tooth);
                item.put("date", plan.path("doneOn").isTextual() ? plan.path("doneOn").asText() : e.date().toString());
                item.put("text", text.length() > 200 ? text.substring(0, 200) : text);
            }
        }
    }

    // ---- oncology cycles ----

    private void cycles(ObjectNode view, UUID tenantId, UUID patientId, RecordRow row) {
        ArrayNode out = view.putArray("oncologyCycles");
        if (patientId == null) {
            return;
        }
        Map<UUID, JsonNode> examByRecord = new java.util.HashMap<>();
        for (Earlier e : support.earlier(tenantId, patientId, row.id(), "ONC_EXAM")) {
            examByRecord.put(e.recordId(), e.value());
        }
        Map<UUID, JsonNode> toxByRecord = new java.util.HashMap<>();
        for (Earlier e : support.earlier(tenantId, patientId, row.id(), "ONC_TOX")) {
            toxByRecord.put(e.recordId(), e.value());
        }
        int number = 0;
        for (ChemoOrderRow order : orders.sentForPatient(tenantId, patientId)) {
            number++;
            if (out.size() >= 60) {
                break;
            }
            ObjectNode item = out.addObject();
            item.put("cycleNo", order.cycleNo == null ? number : order.cycleNo);
            item.put("date", order.sentAt.atZone(SpecialtyRecordSupport.INDIA).toLocalDate().toString());
            item.put("protocolKey", order.protocolKey);
            item.put("levelPct", order.levelPct);
            try {
                JsonNode snapshot = mapper.readTree(order.snapshot);
                if (snapshot.path("weightKg").isNumber()) {
                    item.put("weightKg", snapshot.path("weightKg").asDouble());
                } else {
                    item.putNull("weightKg");
                }
                if (snapshot.path("bsa").path("used").isNumber()) {
                    item.put("bsaUsed", snapshot.path("bsa").path("used").asDouble());
                } else {
                    item.putNull("bsaUsed");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                item.putNull("weightKg");
                item.putNull("bsaUsed");
            }
            item.putObject("labs");
            ArrayNode lesions = item.putArray("lesions");
            JsonNode exam = examByRecord.get(order.recordId);
            if (exam != null) {
                for (JsonNode lesion : exam.path("lesions")) {
                    if (lesions.size() < 10 && lesion.path("name").isTextual()) {
                        ObjectNode entry = lesions.addObject();
                        entry.put("name", lesion.path("name").asText());
                        if (lesion.path("todayCm").isNumber()) {
                            entry.put("cm", lesion.path("todayCm").asDouble());
                        } else {
                            entry.putNull("cm");
                        }
                    }
                }
            }
            JsonNode tox = toxByRecord.get(order.recordId);
            if (tox == null) {
                item.putNull("grades");
            } else {
                ObjectNode grades = item.putObject("grades");
                for (JsonNode symptom : tox.path("symptoms")) {
                    if (symptom.path("confirmedGrade").isInt()) {
                        grades.put(symptom.path("key").asText(), symptom.path("confirmedGrade").asInt());
                    }
                }
            }
        }
    }

}
