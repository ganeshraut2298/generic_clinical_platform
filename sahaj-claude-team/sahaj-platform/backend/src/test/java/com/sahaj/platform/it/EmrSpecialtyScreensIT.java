package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The server-computed rules of the seven specialty screens (SAH-49, 60, 61, 62, 63, 64, 65, 69, 71; ADR-023 D2 to D5, D13;
 * docs/api/opd.yaml 0.5.1) through the real draft save and record read on {@code sahaj_test}: value shapes and limits (400
 * validation), the flags and results the server returns in {@code EmrRecordView.specialty} (General Medicine and oncology
 * vitals, dental estimate, ANC, paediatric growth and dose by weight, physiotherapy, surgery, the oncology assessment and the
 * decision), the visit mode and the sections it hides ({@code hiddenSectionKeys}: hidden sections are never required, Form F
 * stays visible while required, H21), the H8 rule (no length or head-circumference centile note), and the server stamps.
 * Rules that need a child's date of birth cannot be reached: the patient API has no date of birth yet (finding F-6 in the
 * test plan). Every rule value is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed".
 * Test plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class EmrSpecialtyScreensIT extends EmrSpecialtySupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String today() {
        return LocalDate.now(IST).toString();
    }

    private static String sectionStatus(JsonNode record, String key) {
        return recordSection(record, key).get("status").asText();
    }

    private static List<String> labels(JsonNode flags) {
        List<String> out = new ArrayList<>();
        flags.forEach(f -> out.add(f.get("field").asText() + ":" + f.get("label").asText()));
        return out;
    }

    private String vitals(String entryFields) {
        return "{\"entries\":[{\"id\":\"" + uuid() + "\"" + (entryFields.isEmpty() ? "" : "," + entryFields) + "}]}";
    }

    // ================= General Medicine (SAH-60) =================

    @Test
    void generalMedicineVitalsAreFlaggedByTheServerAtTheDrawnLimitsAndNoFlagIsRaisedJustInsideThem() throws Exception {
        Rec rec = specialtyRec("gm");
        assertThat(specialtyOf(rec).get("profile").asText()).isEqualTo("gm");
        assertThat(specialtyOf(rec).get("mode").isNull()).isTrue();
        assertThat(specialtyOf(rec).get("tagNote").asText()).isEqualTo(TAG);
        assertThat(hiddenKeys(rec.record())).isEmpty();
        String[][] cases = {{"\"bpSystolic\":140", "bp:High"}, {"\"bpSystolic\":139", null}, {"\"bpDiastolic\":90", "bp:High"}, {"\"bpDiastolic\":89", null},
                {"\"bpSystolic\":89", "bp:Low"}, {"\"pulse\":101", "pulse:High"}, {"\"pulse\":100", null}, {"\"pulse\":59", "pulse:Low"}, {"\"pulse\":60", null},
                {"\"spo2\":94", "spo2:Low"}, {"\"spo2\":95", null}, {"\"temperature\":{\"value\":99.5,\"unit\":\"F\"}", "temperature:Fever"},
                {"\"temperature\":{\"value\":99.4,\"unit\":\"F\"}", null}, {"\"temperature\":{\"value\":95.9,\"unit\":\"F\"}", "temperature:Low"},
                {"\"respRate\":21", "respRate:High"}, {"\"respRate\":20", null}};
        for (String[] c : cases) {
            rec = save(rec, spSection("vitals", "VITALS_LOG", vitals(c[0])));
            List<String> got = labels(specialtyOf(rec).get("vitalsFlags"));
            if (c[1] == null) {
                assertThat(got).as(c[0]).isEmpty();
            } else {
                assertThat(got).as(c[0]).contains(c[1]);
            }
        }
        rec = save(rec, spSection("vitals", "VITALS_LOG", vitals("\"bpSystolic\":150,\"bpDiastolic\":95,\"pulse\":110,\"spo2\":93,\"temperature\":{\"value\":100.4,\"unit\":\"F\"},\"respRate\":22")));
        assertThat(labels(specialtyOf(rec).get("vitalsFlags"))).contains("bp:High", "pulse:High", "spo2:Low", "temperature:Fever", "respRate:High");
        for (JsonNode flag : specialtyOf(rec).get("vitalsFlags")) {
            assertThat(flag.get("alert").asBoolean()).isTrue();
        }
    }

    @Test
    void aVitalsEntryIsStampedByTheServerIgnoresAClientStampKeepsHowTypedAndRefusesValuesOutsideTheSanityLimits() throws Exception {
        Rec rec = specialtyRec("gm");
        String id = uuid();
        Rec saved = save(rec, spSection("vitals", "VITALS_LOG", "{\"entries\":[{\"id\":\"" + id + "\",\"bpSystolic\":120,\"bpDiastolic\":80}]}"));
        JsonNode entry = valueOf(saved.record(), "vitals", "specialty").get("entries").get(0);
        assertThat(entry.get("recorded").get("byMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(entry.get("recorded").get("at").asText()).isNotBlank();
        assertThat(entry.get("how").asText()).isEqualTo("TYPED");
        assertThat(sectionStatus(saved.record(), "vitals")).isEqualTo("DONE");
        // a client-sent stamp is ignored: an entry that is already there keeps the stamp it got
        String forged = "{\"entries\":[{\"id\":\"" + id + "\",\"bpSystolic\":120,\"bpDiastolic\":80,\"recorded\":{\"at\":\"2020-01-01T00:00:00Z\",\"byMembershipId\":\"" + uuid() + "\"}}]}";
        Rec again = save(saved, spSection("vitals", "VITALS_LOG", forged));
        assertThat(valueOf(again.record(), "vitals", "specialty").get("entries").get(0).get("recorded")).isEqualTo(entry.get("recorded"));
        String[] bad = {"\"bpSystolic\":301", "\"bpSystolic\":39", "\"bpDiastolic\":19", "\"bpDiastolic\":201", "\"pulse\":19", "\"pulse\":251", "\"spo2\":49", "\"spo2\":101",
                "\"respRate\":3", "\"respRate\":81", "\"pain\":11", "\"weightKg\":0.2", "\"weightKg\":301", "\"heightCm\":29", "\"heightCm\":251",
                "\"temperature\":{\"value\":99.0,\"unit\":\"C\"}", "\"temperature\":{\"value\":84.0,\"unit\":\"F\"}", "\"temperature\":{\"value\":111.0,\"unit\":\"F\"}"};
        for (String fields : bad) {
            refuseSave(again, 400, "validation", spSection("vitals", "VITALS_LOG", vitals(fields)));
        }
        refuseSave(again, 400, "validation", spSection("vitals", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{}}"));
        refuseSave(again, 400, "validation", oneField("vitals", "bp_systolic", objectMapper.createObjectNode().put("x", 1)));
        assertThat(save(again, spSection("vitals", "VITALS_LOG", "{\"entries\":[]}")).revision()).isGreaterThan(again.revision());
    }

    @Test
    void theGeneralMedicineExamTakesTheNewGeneralSystemFirstAndAnAbnormalSystemNeedsTextToCount() throws Exception {
        Rec rec = specialtyRec("gm");
        String normal = "{\"signs\":{\"pallor\":\"ABSENT\"},\"systems\":{\"general\":{\"state\":\"NORMAL\",\"text\":\"No pallor, icterus, cyanosis, oedema\"},"
                + "\"cvs\":{\"state\":\"NORMAL\",\"text\":\"S1 S2 heard, no murmur\"},\"rs\":{\"state\":\"NORMAL\",\"text\":\"Air entry equal, clear\"},"
                + "\"pa\":{\"state\":\"NORMAL\",\"text\":\"Soft, non-tender\"},\"cns\":{\"state\":\"NORMAL\",\"text\":\"Conscious, oriented, no focal deficit\"}}}";
        Rec saved = save(rec, spSection("exam", "EXAM_GENERAL", normal));
        assertThat(valueOf(saved.record(), "exam", "specialty").get("systems").get("general").get("state").asText()).isEqualTo("NORMAL");
        assertThat(sectionStatus(saved.record(), "exam")).isEqualTo("DONE");
        Rec abnormal = save(saved, spSection("exam", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{\"general\":{\"state\":\"ABNORMAL\",\"text\":null}}}"));
        assertThat(sectionStatus(abnormal.record(), "exam")).as("an Abnormal system with no text does not count").isNotEqualTo("DONE");
        Rec described = save(abnormal, spSection("exam", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{\"general\":{\"state\":\"ABNORMAL\",\"text\":\"Mild pallor\"}}}"));
        assertThat(sectionStatus(described.record(), "exam")).isEqualTo("DONE");
        refuseSave(described, 400, "validation", spSection("exam", "EXAM_GENERAL", "{\"signs\":{\"pallor\":\"MAYBE\"},\"systems\":{}}"));
        refuseSave(described, 400, "validation", spSection("exam", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{\"liver\":{\"state\":\"NORMAL\"}}}"));
        refuseSave(described, 400, "validation", spSection("exam", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{\"general\":{\"state\":\"BAD\"}}}"));
    }

    @Test
    void aSpecialtyEditorOnlyExistsOnTheAnchorSectionOfItsProfileAndTheLayoutNamesIt() throws Exception {
        Rec gm = specialtyRec("gm");
        JsonNode layoutVitals = layoutSection(gm.record(), "vitals");
        assertThat(layoutVitals.get("specialtyEditor").get("component").asText()).isEqualTo("VITALS_LOG");
        assertThat(layoutVitals.get("specialtyEditor").get("profile").asText()).isEqualTo("gm");
        assertThat(layoutSection(gm.record(), "exam").get("specialtyEditor").get("component").asText()).isEqualTo("EXAM_GENERAL");
        assertThat(layoutSection(gm.record(), "complaints").get("specialtyEditor").isNull()).isTrue();
        // a department that is not one of the seven has no profile, and a component for the wrong section is refused
        Rec derm = switchDepartment(newRec(), "derm");
        assertThat(derm.record().get("specialty").isNull()).as("the generic renderer: no specialty view").isTrue();
        refuseSave(derm, 400, "validation", spSection("vitals", "VITALS_LOG", vitals("\"pulse\":80")));
        Rec dental = specialtyRec("dental");
        assertThat(layoutSection(dental.record(), "dental_chart").get("specialtyEditor").get("component").asText()).isEqualTo("TOOTH_CHART");
        refuseSave(dental, 400, "validation", spSection("dental_chart", "VITALS_LOG", vitals("\"pulse\":80")));
    }

    // ================= Dental (SAH-49, SAH-69) =================

    private static String chart(String teethJson, boolean allHealthy) {
        return "{\"numbering\":\"FDI\",\"dentition\":\"ADULT\",\"showNumbers\":true,\"teeth\":" + teethJson + ",\"shade\":\"A2\",\"gum\":\"PINK\",\"allHealthyConfirmed\":" + allHealthy + "}";
    }

    @Test
    void theToothChartKeepsFdiNumbersAndRefusesImpossibleTeethAndSurfacesOnTheWrongConditions() throws Exception {
        Rec rec = specialtyRec("dental");
        assertThat(sectionStatus(rec.record(), "dental_chart")).isNotEqualTo("DONE");
        Rec first = save(rec, spSection("dental_chart", "TOOTH_CHART", chart("[{\"tooth\":16,\"condition\":\"CARIES\",\"surfaces\":[\"O\",\"D\"],\"mobility\":0},"
                + "{\"tooth\":55,\"condition\":\"TO_EXTRACT\",\"surfaces\":[],\"mobility\":2}]", false)));
        assertThat(valueOf(first.record(), "dental_chart", "specialty").get("teeth")).hasSize(2);
        assertThat(sectionStatus(first.record(), "dental_chart")).as("one tooth that is not healthy").isEqualTo("DONE");
        Rec healthy = save(first, spSection("dental_chart", "TOOTH_CHART", chart("[]", true)));
        assertThat(sectionStatus(healthy.record(), "dental_chart")).as("All teeth healthy confirmed").isEqualTo("DONE");
        Rec ok = healthy;
        for (int tooth : new int[] {19, 49, 56, 90, 10, 86}) {
            refuseSave(ok, 400, "validation", spSection("dental_chart", "TOOTH_CHART", chart("[{\"tooth\":" + tooth + ",\"condition\":\"CARIES\",\"surfaces\":[],\"mobility\":0}]", false)));
        }
        refuseSave(ok, 400, "validation", spSection("dental_chart", "TOOTH_CHART", chart("[{\"tooth\":16,\"condition\":\"MISSING\",\"surfaces\":[\"O\"],\"mobility\":0}]", false)));
        refuseSave(ok, 400, "validation", spSection("dental_chart", "TOOTH_CHART", chart("[{\"tooth\":16,\"condition\":\"CARIES\",\"surfaces\":[\"X\"],\"mobility\":0}]", false)));
        refuseSave(ok, 400, "validation", spSection("dental_chart", "TOOTH_CHART", chart("[{\"tooth\":16,\"condition\":\"CARIES\",\"surfaces\":[],\"mobility\":4}]", false)));
        refuseSave(ok, 400, "validation", spSection("dental_chart", "TOOTH_CHART", chart("[{\"tooth\":16,\"condition\":\"BROKEN\",\"surfaces\":[],\"mobility\":0}]", false)));
        refuseSave(ok, 400, "validation", spSection("dental_chart", "TOOTH_CHART", chart("[]", false).replace("\"A2\"", "\"Z9\"")));
    }

    private static String planRow(String id, int tooth, String key, String text, int sittings, Long typedPaise, String status) {
        return "{\"id\":\"" + id + "\",\"tooth\":" + tooth + ",\"procedureKey\":" + (key == null ? "null" : "\"" + key + "\"") + ",\"procedureText\":\"" + text + "\",\"phase\":1,\"status\":\""
                + status + "\",\"sittingsPlanned\":" + sittings + ",\"currentSitting\":1" + (typedPaise == null ? "" : ",\"typedEstimatePaise\":" + typedPaise) + "}";
    }

    private static String plan(String rows, boolean sign) {
        return "{\"rows\":[" + rows + "]" + (sign ? ",\"signEstimate\":true" : "") + "}";
    }

    @Test
    void theEstimateComesFromTheTariffOrTheTypedAmountSplitsIntoSittingsRoundedToFiftyRupeesAndTheSignatureClearsWhenThePlanChanges() throws Exception {
        Rec rec = specialtyRec("dental");
        String rowId = uuid();
        Rec saved = save(rec, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(rowId, 16, "ROOT_CANAL", "Root canal", 3, 100000L, "PLANNED"), false)));
        JsonNode row = valueOf(saved.record(), "tx_plan", "specialty").get("rows").get(0);
        assertThat(row.get("estimateSource").asText()).isEqualTo("TYPED");
        assertThat(row.get("estimatePaise").asLong()).isEqualTo(100000L);
        long first = row.get("sittingSharesPaise").get(0).asLong();
        assertThat(row.get("sittingSharesPaise")).as("one share per planned sitting").hasSize(3);
        assertThat(first % 5000).as("rounded to Rs 50").isZero();
        assertThat(row.get("sittingSharesPaise").get(1).asLong()).isEqualTo(first);
        assertThat(row.get("sittingSharesPaise").get(2).asLong()).as("the last takes the rest").isEqualTo(100000L - 2 * first);
        assertThat(specialtyOf(saved).get("dental").get("estimateTotalPaise").asLong()).isEqualTo(100000L);
        assertThat(specialtyOf(saved).get("dental").get("estimateState").asText()).isEqualTo("NOT_SIGNED");
        // signing: stamped by the server, one audit with the total only
        Rec signed = save(saved, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(rowId, 16, "ROOT_CANAL", "Root canal", 3, 100000L, "PLANNED"), true)));
        assertThat(specialtyOf(signed).get("dental").get("estimateState").asText()).isEqualTo("SIGNED");
        assertThat(auditRows(rec.clinic(), "EMR_ESTIMATE_SIGNED")).hasSize(1);
        assertThat(auditRows(rec.clinic(), "EMR_ESTIMATE_SIGNED").get(0).get(2)).contains("100000").doesNotContain("Root canal");
        // a change of the plan's amount clears the signature
        Rec changed = save(signed, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(rowId, 16, "ROOT_CANAL", "Root canal", 3, 120000L, "PLANNED"), false)));
        assertThat(specialtyOf(changed).get("dental").get("estimateState").asText()).isEqualTo("CHANGED");
        assertThat(auditRows(rec.clinic(), "EMR_ESTIMATE_SIGN_CLEARED")).hasSize(1);
        // an amount that is not whole rupees, a duplicate (same tooth and procedure, any case), limits
        refuseSave(changed, 400, "validation", spSection("tx_plan", "DENTAL_PLAN", plan(planRow(uuid(), 16, null, "Scaling", 1, 150050L, "PLANNED"), false)));
        refuseSave(changed, 400, "duplicate-item", spSection("tx_plan", "DENTAL_PLAN",
                plan(planRow(uuid(), 16, null, "ROOT CANAL", 1, 100000L, "PLANNED") + "," + planRow(uuid(), 16, null, "root canal", 1, 100000L, "PLANNED"), false)));
        refuseSave(changed, 400, "validation", spSection("tx_plan", "DENTAL_PLAN", plan(planRow(uuid(), 16, "ROOT_CANAL", "Root canal", 7, 100000L, "PLANNED"), false)));
        refuseSave(changed, 400, "validation", spSection("tx_plan", "DENTAL_PLAN", plan(planRow(uuid(), 91, "ROOT_CANAL", "Root canal", 3, 100000L, "PLANNED"), false)));
        // signing an empty plan is ignored
        Rec empty = save(changed, spSection("tx_plan", "DENTAL_PLAN", "{\"rows\":[],\"signEstimate\":true}"));
        assertThat(auditRows(rec.clinic(), "EMR_ESTIMATE_SIGNED")).as("nothing to sign").hasSize(1);
        assertThat(specialtyOf(empty).get("dental").get("estimateTotalPaise").asLong()).isZero();
    }

    @Test
    void aProcedureInTheClinicsTariffTakesTheTariffPriceNotATypedAmount() throws Exception {
        Rec rec = specialtyRec("dental");
        post("/api/v1/opd/services", rec.clinic().owner(), "{\"name\":\"Root canal\",\"category\":\"Lab\",\"priceRupees\":3000}", 201);
        Rec saved = save(rec, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(uuid(), 16, "ROOT_CANAL", "Root canal", 3, null, "PLANNED"), false)));
        JsonNode row = valueOf(saved.record(), "tx_plan", "specialty").get("rows").get(0);
        assertThat(row.get("estimateSource").asText()).isEqualTo("TARIFF");
        assertThat(row.get("estimatePaise").asLong()).isEqualTo(300000L);
        assertThat(row.get("serviceId").asText()).isNotBlank();
        Rec sameName = save(saved, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(uuid(), 26, null, "Zzz not in tariff", 1, null, "PLANNED"), false)));
        JsonNode notInTariff = valueOf(sameName.record(), "tx_plan", "specialty").get("rows").get(0);
        assertThat(notInTariff.get("estimateSource").asText()).isEqualTo("NONE");
        assertThat(notInTariff.get("estimatePaise").isNull()).as("Not in tariff: a typed amount is needed").isTrue();
    }

    @Test
    void aRowMarkedDoneIsStampedWithThisRecordsDateAndListedAsDoneTodayAndTheConsentTicksAreAuditedByCountOnly() throws Exception {
        Rec rec = specialtyRec("dental");
        String rowId = uuid();
        Rec saved = save(rec, spSection("tx_plan", "DENTAL_PLAN", plan(planRow(rowId, 36, "EXTRACTION", "Extraction", 1, 50000L, "DONE"), false)),
                spSection("consent", "CONSENT_CHECKLIST", "{\"items\":[{\"key\":\"" + rowId + "\",\"formLabel\":\"Extraction consent\",\"confirmed\":true}]}"));
        assertThat(valueOf(saved.record(), "tx_plan", "specialty").get("rows").get(0).get("doneOn").asText()).isEqualTo(today());
        assertThat(texts(specialtyOf(saved).get("dental").get("doneTodayRowIds"), null)).containsExactly(rowId);
        assertThat(valueOf(saved.record(), "consent", "specialty").get("items").get(0).get("confirmedStamp").get("byMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(sectionStatus(saved.record(), "consent")).as("every item ticked").isEqualTo("DONE");
        assertThat(auditRows(rec.clinic(), "EMR_CONSENT_RECORDED")).hasSize(1);
        assertThat(auditRows(rec.clinic(), "EMR_CONSENT_RECORDED").get(0).get(2)).doesNotContain("Extraction");
        Rec notAll = save(saved, spSection("consent", "CONSENT_CHECKLIST", "{\"items\":[{\"key\":\"" + rowId + "\",\"formLabel\":\"Extraction consent\",\"confirmed\":false}]}"));
        assertThat(sectionStatus(notAll.record(), "consent")).isNotEqualTo("DONE");
    }

    @Test
    void dentalLabWorkRefusesADueDateInThePastAndAnUnknownItem() throws Exception {
        Rec rec = specialtyRec("dental");
        String item = "{\"items\":[{\"id\":\"" + uuid() + "\",\"planRowId\":null,\"tooth\":16,\"item\":\"CROWN\",\"labName\":\"Test lab\",\"dueDate\":\"%s\"}]}";
        Rec ok = save(rec, spSection("dental_lab", "LAB_WORK", String.format(item, LocalDate.now(IST).plusDays(7))));
        assertThat(sectionStatus(ok.record(), "dental_lab")).isEqualTo("DONE");
        JsonNode problem = refuseSave(ok, 400, "validation", spSection("dental_lab", "LAB_WORK", String.format(item, LocalDate.now(IST).minusDays(1))));
        assertThat(problem.toString()).contains("dueDate");
        refuseSave(ok, 400, "validation", spSection("dental_lab", "LAB_WORK", String.format(item, LocalDate.now(IST).plusDays(7)).replace("CROWN", "FILLING")));
    }

    // ================= Obstetrics and Gynaecology (SAH-61) =================

    private static String anc(String edd, String todayJson, String ordersJson, String scansJson) {
        return "{\"datingScanEdd\":" + (edd == null ? "null" : "\"" + edd + "\"") + ",\"today\":" + todayJson + ",\"symptoms\":[],\"orders\":" + ordersJson + ",\"scans\":" + scansJson + "}";
    }

    @Test
    void theAncResultsComeFromTheDatingScanWhichWinsTheGestationalAgeTheNotesAndTheAnaemiaAndPreEclampsiaAlerts() throws Exception {
        Rec rec = specialtyRec("og");
        String edd = LocalDate.now(IST).plusDays(140).toString();
        Rec saved = save(rec, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":62.5,\"bpSystolic\":150,\"bpDiastolic\":95,\"fundalHeightCm\":30,\"fetalHeartBpm\":100,\"hbGdl\":9.5}", "[]", "[]")));
        JsonNode og = specialtyOf(saved).get("og");
        assertThat(og.get("eddSource").asText()).isEqualTo("DATING_SCAN");
        assertThat(og.get("edd").asText()).isEqualTo(edd);
        assertThat(og.get("daysToEdd").asInt()).isEqualTo(140);
        assertThat(og.get("gestationalAge").get("weeks").asInt()).as("280 - 140 days = 20 weeks").isEqualTo(20);
        assertThat(og.get("gestationalAge").get("days").asInt()).isZero();
        List<String> alerts = texts(og.get("alerts"), null);
        assertThat(alerts).contains("POSSIBLE_PRE_ECLAMPSIA", "ANAEMIA_MODERATE");
        String notes = og.get("notes").toString();
        assertThat(notes).contains("Does not match 20 weeks").contains("110");
        assertThat(sectionStatus(saved.record(), "anc")).as("one of today's values").isEqualTo("DONE");
        // anaemia boundaries: below 11 mild, below 10 moderate, below 7 severe
        String[][] hb = {{"11.0", null}, {"10.9", "ANAEMIA_MILD"}, {"10.0", "ANAEMIA_MILD"}, {"9.9", "ANAEMIA_MODERATE"}, {"7.0", "ANAEMIA_MODERATE"}, {"6.9", "ANAEMIA_SEVERE"}};
        Rec current = saved;
        for (String[] c : hb) {
            current = save(current, spSection("anc", "ANC_CHECK", anc(edd, "{\"hbGdl\":" + c[0] + "}", "[]", "[]")));
            List<String> got = texts(specialtyOf(current).get("og").get("alerts"), null);
            assertThat(got.stream().filter(a -> a.startsWith("ANAEMIA")).toList()).as("Hb " + c[0]).isEqualTo(c[1] == null ? List.of() : List.of(c[1]));
        }
        // blood pressure: 140 systolic or 90 diastolic
        for (String[] c : new String[][] {{"139", "89", "false"}, {"140", "80", "true"}, {"120", "90", "true"}}) {
            current = save(current, spSection("anc", "ANC_CHECK", anc(edd, "{\"bpSystolic\":" + c[0] + ",\"bpDiastolic\":" + c[1] + "}", "[]", "[]")));
            assertThat(texts(specialtyOf(current).get("og").get("alerts"), null).contains("POSSIBLE_PRE_ECLAMPSIA")).as(c[0] + "/" + c[1]).isEqualTo(Boolean.parseBoolean(c[2]));
        }
        refuseSave(current, 400, "validation", spSection("anc", "ANC_CHECK", anc(edd, "{\"bpSystolic\":301}", "[]", "[]")));
        refuseSave(current, 400, "validation", spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":24}", "[]", "[]")));
        refuseSave(current, 400, "validation", spSection("anc", "ANC_CHECK", anc(edd, "{\"hbGdl\":21}", "[]", "[]")));
    }

    @Test
    void withNoLmpOrDatingScanTheGestationalAgeIsNotRecordedAndTheScanWindowsMarkDueDoneOrLater() throws Exception {
        Rec rec = specialtyRec("og");
        Rec none = save(rec, spSection("anc", "ANC_CHECK", anc(null, "{\"weightKg\":60.0}", "[]", "[]")));
        assertThat(specialtyOf(none).get("og").get("gestationalAge").isNull()).as("Not recorded").isTrue();
        assertThat(specialtyOf(none).get("og").get("eddSource").asText()).isEqualTo("NONE");
        String edd = LocalDate.now(IST).plusDays(140).toString();
        Rec at20 = save(none, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":60.0}", "[]", "[{\"key\":\"DATING\",\"done\":true,\"finding\":\"Single live fetus\"}]")));
        java.util.Map<String, String> states = new java.util.HashMap<>();
        specialtyOf(at20).get("og").get("scanStates").forEach(s -> states.put(s.get("key").asText(), s.get("state").asText()));
        assertThat(states.get("DATING")).isEqualTo("DONE");
        assertThat(states.get("ANOMALY")).as("20 weeks is inside 18 to 22").isEqualTo("DUE");
        assertThat(states.get("GROWTH")).as("28 to 32 weeks is later").isEqualTo("LATER");
        assertThat(states.get("DOPPLER_NST")).as("from 36 weeks").isEqualTo("LATER");
    }

    @Test
    void formFIsRequiredWhileAScanOrderIsInThisRecordAndNotMarkedAndMarkingItIsAuditedAndRemovingTheOrderKeepsTheForm() throws Exception {
        Rec rec = specialtyRec("og");
        String edd = LocalDate.now(IST).plusDays(140).toString();
        Rec none = save(rec, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":60.0}", "[]", "[]")));
        assertThat(specialtyOf(none).get("og").get("formFRequired").asBoolean()).isFalse();
        assertThat(neededSections(none.record())).as("Form F is not needed with no scan ordered").doesNotContain("pcpndt");
        Rec ordered = save(none, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":60.0}", "[{\"key\":\"GROWTH_SCAN_FORM_F\"}]", "[]")));
        assertThat(specialtyOf(ordered).get("og").get("formFRequired").asBoolean()).isTrue();
        assertThat(neededSections(ordered.record())).contains("pcpndt");
        assertThat(neededKeys(ordered.record())).contains("pcpndt");
        Rec marked = save(ordered, spSection("pcpndt", "FORM_F", "{\"filledAndSigned\":true}"));
        assertThat(specialtyOf(marked).get("og").get("formFRequired").asBoolean()).isFalse();
        assertThat(neededSections(marked.record())).doesNotContain("pcpndt");
        assertThat(valueOf(marked.record(), "pcpndt", "specialty").get("marked").get("byMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(auditRows(rec.clinic(), "EMR_FORM_F_MARKED")).hasSize(1);
        Rec removed = save(marked, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":60.0}", "[]", "[]")));
        assertThat(valueOf(removed.record(), "pcpndt", "specialty").get("filledAndSigned").asBoolean()).as("Scan removed. Form F kept").isTrue();
    }

    @Test
    void theVisitModeHidesTheSectionsOfTheOtherVisitTypeAndAHiddenSectionIsNeverRequiredButKeepsItsValuesAndFormFStaysVisibleWhileRequired() throws Exception {
        Rec rec = specialtyRec("og");
        assertThat(specialtyOf(rec).get("mode").asText()).as("ANC is the effective mode with none stored").isEqualTo("ANC");
        assertThat(hiddenKeys(rec.record())).containsExactlyInAnyOrder("gyn_exam", "infertility", "contraception");
        assertThat(neededSections(rec.record())).as("the required ANC check is still needed").contains("anc");
        String edd = LocalDate.now(IST).plusDays(140).toString();
        Rec withAnc = save(rec, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":60.0}", "[]", "[]")));
        Rec gynae = saveScreen(withAnc, "{\"mode\":\"GYNAE\"}");
        assertThat(specialtyOf(gynae).get("mode").asText()).isEqualTo("GYNAE");
        assertThat(hiddenKeys(gynae.record())).containsExactlyInAnyOrder("obstetric", "anc", "pcpndt", "usg");
        assertThat(valueOf(gynae.record(), "anc", "specialty").get("today").get("weightKg").asDouble()).as("a hidden section keeps its values").isEqualTo(60.0);
        // a hidden required section is never listed and never blocks
        Rec emptyGynae = saveScreen(specialtyRec("og"), "{\"mode\":\"GYNAE\"}");
        assertThat(neededSections(emptyGynae.record())).doesNotContain("anc", "pcpndt", "obstetric", "usg");
        assertThat(neededKeys(emptyGynae.record())).doesNotContain("anc", "pcpndt");
        // H21: Form F is not hidden in GYNAE while it is required
        Rec formF = save(gynae, spSection("anc", "ANC_CHECK", anc(edd, "{\"weightKg\":60.0}", "[{\"key\":\"GROWTH_SCAN_FORM_F\"}]", "[]")));
        Rec gynaeFormF = saveScreen(formF, "{\"mode\":\"GYNAE\"}");
        assertThat(hiddenKeys(gynaeFormF.record())).as("pcpndt stays visible, the legal duty").containsExactlyInAnyOrder("obstetric", "anc", "usg");
        assertThat(neededSections(gynaeFormF.record())).contains("pcpndt");
        Rec signed = save(gynaeFormF, spSection("pcpndt", "FORM_F", "{\"filledAndSigned\":true}"));
        assertThat(hiddenKeys(signed.record())).as("hidden again once marked").contains("pcpndt");
        // the other way and wrong modes
        Rec back = saveScreen(signed, "{\"mode\":\"ANC\"}");
        assertThat(hiddenKeys(back.record())).containsExactlyInAnyOrder("gyn_exam", "infertility", "contraception");
        for (String mode : new String[] {"SESSION", "FIRST_VISIT", "NOPE"}) {
            refuseScreen(back, 400, "validation", "{\"mode\":\"" + mode + "\"}");
        }
        assertThat(specialtyOf(back).get("tagNote").asText()).isEqualTo(TAG);
    }

    @Test
    void theGynaeExamNeedsAFindingAndTheChaperoneRecordedAndTheChaperoneNameIsNeverInAnAuditOrALogLine() throws Exception {
        Rec rec = saveScreen(specialtyRec("og"), "{\"mode\":\"GYNAE\"}");
        Rec finding = save(rec, spSection("gyn_exam", "GYNAE_EXAM", "{\"perAbdomen\":\"Soft\",\"chaperoneName\":null,\"chaperoneDeclined\":false}"));
        assertThat(sectionStatus(finding.record(), "gyn_exam")).as("a finding but no chaperone").isNotEqualTo("DONE");
        Rec named = save(finding, spSection("gyn_exam", "GYNAE_EXAM", "{\"perAbdomen\":\"Soft\",\"chaperoneName\":\"Zxqchaperone\",\"chaperoneDeclined\":false}"));
        assertThat(sectionStatus(named.record(), "gyn_exam")).isEqualTo("DONE");
        Rec declined = save(named, spSection("gyn_exam", "GYNAE_EXAM", "{\"perAbdomen\":\"Soft\",\"chaperoneName\":null,\"chaperoneDeclined\":true}"));
        assertThat(sectionStatus(declined.record(), "gyn_exam")).isEqualTo("DONE");
        refuseSave(declined, 400, "validation", spSection("gyn_exam", "GYNAE_EXAM", "{\"perAbdomen\":\"Soft\",\"chaperoneName\":\"Zxqchaperone\",\"chaperoneDeclined\":true}"));
        assertThat(allAuditText(rec.clinic())).doesNotContain("Zxqchaperone");
    }

    // ================= Paediatrics (SAH-62) =================

    @Test
    void thePaediatricGrowthNotesAreForWeightOnlyAndNeverForLengthOrHeadCircumferenceBecauseOfH8() throws Exception {
        Rec rec = specialtyRec("paeds");
        assertThat(specialtyOf(rec).get("paeds").get("growthNotes")).as("no weight today: no note").isEmpty();
        Rec saved = save(rec, spSection("growth", "GROWTH", "{\"weightKg\":11.0,\"lengthCm\":80.5,\"headCircCm\":47.0}"));
        JsonNode notes = specialtyOf(saved).get("paeds").get("growthNotes");
        assertThat(notes.size()).isLessThanOrEqualTo(1);
        for (JsonNode note : notes) {
            assertThat(note.get("field").asText()).as("H8: weight only").isEqualTo("weightKg");
        }
        assertThat(saved.record().toString()).doesNotContain("~40th centile").doesNotContain("~50th centile");
        assertThat(valueOf(saved.record(), "growth", "specialty").get("lengthCm").asDouble()).as("the value itself is shown and kept").isEqualTo(80.5);
        assertThat(sectionStatus(saved.record(), "growth")).isEqualTo("DONE");
        Rec lengthOnly = save(saved, spSection("growth", "GROWTH", "{\"weightKg\":null,\"lengthCm\":80.5,\"headCircCm\":47.0}"));
        assertThat(specialtyOf(lengthOnly).get("paeds").get("growthNotes")).as("no weight: not even Centile not available for length").isEmpty();
        for (String bad : new String[] {"\"weightKg\":0.2", "\"weightKg\":151", "\"lengthCm\":24", "\"lengthCm\":201", "\"headCircCm\":19", "\"headCircCm\":71", "\"weightKg\":11.05"}) {
            refuseSave(lengthOnly, 400, "validation", spSection("growth", "GROWTH", "{" + bad + "}"));
        }
    }

    private String rxDose(String rowId, String ruleKey, String medicine, double weight, int mg, double ml) {
        return "{\"id\":\"" + rowId + "\",\"medicineId\":\"" + "%s" + "\",\"name\":\"" + medicine + "\",\"form\":null,\"frequency\":null,\"days\":3,\"when\":\"AFTER_FOOD\",\"source\":\"DOSE_BY_WEIGHT\","
                + "\"doseText\":\"" + ml + " ml (" + mg + " mg)\",\"frequencyText\":\"every 6 hours if needed, at most 4 doses a day\",\"note\":\"Calculated by weight\","
                + "\"calculation\":{\"doseRuleKey\":\"" + ruleKey + "\",\"weightKg\":" + weight + ",\"mg\":" + mg + ",\"ml\":" + ml + "}}";
    }

    private com.fasterxml.jackson.databind.node.ObjectNode rxSectionOf(String rowJson, String medicineId) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode value = objectMapper.createObjectNode();
        value.put("component", "PRESCRIPTION");
        value.put("schemaVersion", 1);
        value.putArray("rows").add(objectMapper.readTree(String.format(rowJson, medicineId)));
        return oneField("rx", "drug", value);
    }

    @Test
    void aDoseByWeightIsRecomputedByTheServerAndAMismatchIs400DoseCalculationMismatchWhileACappedDoseIsAccepted() throws Exception {
        Rec rec = specialtyRec("paeds");
        String paracetamol = medicineId("Paracetamol syrup 120 mg / 5 ml");
        String name = "Paracetamol syrup 120 mg / 5 ml";
        // 11 kg: 15 mg/kg = 165 mg (below the 500 mg maximum), 165 / 24 mg per ml = 6.9 ml
        Rec saved = save(rec, spSection("growth", "GROWTH", "{\"weightKg\":11.0}"), rxSectionOf(rxDose(uuid(), "PARACETAMOL_120_5", name, 11.0, 165, 6.9), paracetamol));
        JsonNode row = valueOf(saved.record(), "rx", "drug").get("rows").get(0);
        assertThat(row.get("calculation").get("mg").asInt()).isEqualTo(165);
        assertThat(row.get("calculation").get("ml").asDouble()).isEqualTo(6.9);
        assertThat(row.get("calculation").get("capped").asBoolean()).isFalse();
        assertThat(row.get("source").asText()).isEqualTo("DOSE_BY_WEIGHT");
        assertThat(row.get("medicineId").asText()).as("a master medicine: the drug-class allergy check applies").isEqualTo(paracetamol);
        // the wrong mg or ml
        JsonNode wrongMg = refuseSave(saved, 400, "dose-calculation-mismatch", rxSectionOf(rxDose(uuid(), "PARACETAMOL_120_5", name + " x", 11.0, 170, 6.9), paracetamol));
        assertThat(wrongMg.get("title").asText()).isNotBlank();
        refuseSave(saved, 400, "dose-calculation-mismatch", rxSectionOf(rxDose(uuid(), "PARACETAMOL_120_5", name + " y", 11.0, 165, 7.0), paracetamol));
        refuseSave(saved, 400, "dose-calculation-mismatch", rxSectionOf(rxDose(uuid(), "PARACETAMOL_250_5", name + " z", 11.0, 165, 6.9), paracetamol));
        refuseSave(saved, 400, "validation", rxSectionOf(rxDose(uuid(), "ASPIRIN", name + " w", 11.0, 165, 6.9), paracetamol));
        // 40 kg: 600 mg is capped at 500 mg, 500 / 24 = 20.8 ml
        Rec capped = save(saved, rxSectionOf(rxDose(uuid(), "PARACETAMOL_120_5", name + " big", 40.0, 500, 20.8), paracetamol));
        assertThat(valueOf(capped.record(), "rx", "drug").get("rows").get(0).get("calculation").get("capped").asBoolean()).isTrue();
        // ibuprofen: 10 mg/kg, maximum 400 mg, 20 mg per ml: 11 kg is 110 mg and 5.5 ml
        String ibuprofen = medicineId("Ibuprofen syrup 100 mg / 5 ml");
        Rec ibu = save(capped, rxSectionOf(rxDose(uuid(), "IBUPROFEN_100_5", "Ibuprofen syrup 100 mg / 5 ml", 11.0, 110, 5.5), ibuprofen));
        assertThat(valueOf(ibu.record(), "rx", "drug").get("rows").get(0).get("calculation").get("mg").asInt()).isEqualTo(110);
    }

    @Test
    void aWeightThatChangesAfterTheDoseWasAddedListsTheRowAndNeverChangesItSilently() throws Exception {
        Rec rec = specialtyRec("paeds");
        String paracetamol = medicineId("Paracetamol syrup 120 mg / 5 ml");
        String rowId = uuid();
        Rec saved = save(rec, spSection("growth", "GROWTH", "{\"weightKg\":11.0}"), rxSectionOf(rxDose(rowId, "PARACETAMOL_120_5", "Paracetamol syrup 120 mg / 5 ml", 11.0, 165, 6.9), paracetamol));
        assertThat(specialtyOf(saved).get("paeds").get("doseWeightKg").asDouble()).isEqualTo(11.0);
        assertThat(specialtyOf(saved).get("paeds").get("doseWeightChangedRowIds")).isEmpty();
        Rec heavier = save(saved, spSection("growth", "GROWTH", "{\"weightKg\":12.0}"));
        assertThat(texts(specialtyOf(heavier).get("paeds").get("doseWeightChangedRowIds"), null)).containsExactly(rowId);
        assertThat(valueOf(heavier.record(), "rx", "drug").get("rows").get(0).get("calculation").get("mg").asInt()).as("never changed silently").isEqualTo(165);
        Rec noWeight = specialtyRec("paeds");
        assertThat(specialtyOf(noWeight).get("paeds").get("doseWeightKg").isNull()).as("Record today's weight first").isTrue();
    }

    @Test
    void aVaccineThatIsNotDueTodayCannotBeAddedAndMilestonesOnlyExistForTheirAgeBand() throws Exception {
        Rec rec = specialtyRec("paeds");
        // the patient has no date of birth in this build, so no vaccine is due or overdue and nothing can be added (see test plan F-6)
        for (JsonNode vaccine : specialtyOf(rec).get("paeds").get("vaccines")) {
            assertThat(vaccine.get("state").asText()).isIn("LATER", "GIVEN");
        }
        refuseSave(rec, 400, "validation", spSection("immunization", "IMMUNIZATION", "{\"givenToday\":[{\"vaccineKey\":\"bcg\"}],\"scheduleReviewed\":false}"));
        refuseSave(rec, 400, "validation", spSection("immunization", "IMMUNIZATION", "{\"givenToday\":[{\"vaccineKey\":\"no_such_vaccine\"}],\"scheduleReviewed\":false}"));
        Rec reviewed = save(rec, spSection("immunization", "IMMUNIZATION", "{\"givenToday\":[],\"scheduleReviewed\":true}"));
        assertThat(sectionStatus(reviewed.record(), "immunization")).as("Schedule reviewed").isEqualTo("DONE");
        Rec milestone = save(reviewed, spSection("milestones", "MILESTONES", "{\"ageBandMonths\":18,\"achieved\":[\"walks_alone\"],\"notYet\":[\"says_6_or_more_words\"]}"));
        assertThat(sectionStatus(milestone.record(), "milestones")).as("one milestone toggled").isEqualTo("DONE");
        refuseSave(milestone, 400, "validation", spSection("milestones", "MILESTONES", "{\"ageBandMonths\":18,\"achieved\":[\"flies\"],\"notYet\":[]}"));
    }

    // ================= Physiotherapy (SAH-63) =================

    @Test
    void thePhysiotherapyModeHidesTheSectionsOfTheOtherVisitTypeAndTheTreatmentFooterItemExistsOnlyInSessionMode() throws Exception {
        Rec rec = specialtyRec("physio");
        assertThat(specialtyOf(rec).get("mode").asText()).as("FIRST_VISIT with no linked session").isEqualTo("FIRST_VISIT");
        assertThat(hiddenKeys(rec.record())).containsExactlyInAnyOrder("modalities", "sessions");
        assertThat(neededKeys(rec.record())).doesNotContain("PHYSIO_TREATMENT_TODAY");
        Rec session = saveScreen(rec, "{\"mode\":\"SESSION\"}");
        assertThat(specialtyOf(session).get("mode").asText()).isEqualTo("SESSION");
        assertThat(hiddenKeys(session.record())).containsExactlyInAnyOrder("special_tests", "posture");
        assertThat(neededKeys(session.record())).contains("PHYSIO_TREATMENT_TODAY");
        Rec given = save(session, spSection("modalities", "MODALITIES", "{\"given\":[{\"key\":\"IFT\",\"text\":\"IFT\",\"params\":\"15 min\"}]}"));
        assertThat(neededKeys(given.record())).as("one treatment given today").doesNotContain("PHYSIO_TREATMENT_TODAY");
        assertThat(sectionStatus(given.record(), "modalities")).isEqualTo("DONE");
        Rec first = saveScreen(given, "{\"mode\":\"FIRST_VISIT\"}");
        assertThat(hiddenKeys(first.record())).containsExactlyInAnyOrder("modalities", "sessions");
        assertThat(valueOf(first.record(), "modalities", "specialty").get("given")).as("hidden but kept").hasSize(1);
        for (String mode : new String[] {"ANC", "GYNAE", "BAD"}) {
            refuseScreen(first, 400, "validation", "{\"mode\":\"" + mode + "\"}");
        }
    }

    @Test
    void theOutcomeScalesTakeOneTypedScorePerScaleWithinTheirRangesAndFeedTheChipAndDoneRule() throws Exception {
        Rec rec = specialtyRec("physio");
        assertThat(layoutSection(rec.record(), "func_scales").get("specialtyEditor").get("component").asText()).isEqualTo("OUTCOME_SCALES");
        assertThat(sectionStatus(rec.record(), "func_scales")).isNotEqualTo("DONE");
        Rec first = save(rec, spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"WOMAC\",\"score\":41},{\"scale\":\"ODI\",\"score\":null}]}"));
        assertThat(sectionStatus(first.record(), "func_scales")).as("one score").isEqualTo("DONE");
        Rec saved = first;
        JsonNode chip = null;
        for (JsonNode c : specialtyOf(first).get("physio").get("outcomeScales")) {
            if ("WOMAC".equals(c.get("scale").asText())) {
                chip = c;
            }
        }
        assertThat(chip).isNotNull();
        assertThat(chip.get("todayScore").asInt()).isEqualTo(41);
        assertThat(chip.get("previousScore").isNull()).as("no earlier signed record").isTrue();
        // the limits per scale: ODI 0-100, NDI 0-50, WOMAC 0-96, LEFS 0-80
        String[][] limits = {{"ODI", "100"}, {"NDI", "50"}, {"WOMAC", "96"}, {"LEFS", "80"}};
        for (String[] l : limits) {
            Rec atMax = save(saved, spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"" + l[0] + "\",\"score\":" + l[1] + "}]}"));
            assertThat(valueOf(atMax.record(), "func_scales", "specialty").get("rows").get(0).get("score").asInt()).as(l[0]).isEqualTo(Integer.parseInt(l[1]));
            saved = atMax;
            JsonNode problem = refuseSave(saved, 400, "validation", spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"" + l[0] + "\",\"score\":" + (Integer.parseInt(l[1]) + 1) + "}]}"));
            assertThat(problem.toString()).as(l[0]).contains("rows[0].score");
            refuseSave(saved, 400, "validation", spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"" + l[0] + "\",\"score\":-1}]}"));
        }
        refuseSave(saved, 400, "duplicate-item", spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"WOMAC\",\"score\":10},{\"scale\":\"WOMAC\",\"score\":20}]}"));
        refuseSave(saved, 400, "validation", spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"VAS\",\"score\":10}]}"));
        // an earlier SIGNED record of the same patient gives the previous score for the chip
        Rec firstVisit = specialtyRec("physio");
        Rec earlier = save(firstVisit, spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"WOMAC\",\"score\":48}]}"));
        signRecord(firstVisit.clinic(), earlier.id(), 7);
        Rec later = save(anotherVisit(firstVisit, "physio"), spSection("func_scales", "OUTCOME_SCALES", "{\"rows\":[{\"scale\":\"WOMAC\",\"score\":41}]}"));
        JsonNode womac = null;
        for (JsonNode c : specialtyOf(later).get("physio").get("outcomeScales")) {
            if ("WOMAC".equals(c.get("scale").asText())) {
                womac = c;
            }
        }
        assertThat(womac).isNotNull();
        assertThat(womac.get("previousScore").asInt()).as("the chip reads WOMAC 48 to 41").isEqualTo(48);
        assertThat(womac.get("todayScore").asInt()).isEqualTo(41);
        assertThat(womac.get("previousDate").asText()).isEqualTo(LocalDate.now(IST).minusDays(7).toString());
    }

    @Test
    void theRangeOfMotionBarsUseTheKneeReferenceAndTheOtherPhysioValuesAreRangeChecked() throws Exception {
        Rec rec = specialtyRec("physio");
        Rec rom = save(rec, spSection("rom", "ROM_TABLE", "{\"joint\":\"knee\",\"rows\":[{\"movement\":\"flexion\",\"rightDeg\":90,\"leftDeg\":120},{\"movement\":\"extension\",\"rightDeg\":0,\"leftDeg\":10}]}"));
        java.util.Map<String, Integer> bars = new java.util.HashMap<>();
        specialtyOf(rom).get("physio").get("romPercents").forEach(p -> bars.put(p.get("movement").asText(), p.get("rightPct").asInt()));
        assertThat(bars.get("flexion")).as("90 of 135 degrees").isEqualTo(67);
        assertThat(bars.get("extension")).as("extension at the normal 0 degrees is 100 %").isEqualTo(100);
        Rec rom10 = save(rom, spSection("rom", "ROM_TABLE", "{\"joint\":\"knee\",\"rows\":[{\"movement\":\"extension\",\"rightDeg\":10,\"leftDeg\":0}]}"));
        bars.clear();
        specialtyOf(rom10).get("physio").get("romPercents").forEach(p -> bars.put(p.get("movement").asText(), p.get("rightPct").asInt()));
        assertThat(bars.get("extension")).as("extension that is not 0 degrees is 90 %").isEqualTo(90);
        assertThat(sectionStatus(rom.record(), "rom")).isEqualTo("DONE");
        refuseSave(rom10, 400, "validation", spSection("rom", "ROM_TABLE", "{\"joint\":\"knee\",\"rows\":[{\"movement\":\"flexion\",\"rightDeg\":201,\"leftDeg\":0}]}"));
        refuseSave(rom10, 400, "validation", spSection("rom", "ROM_TABLE", "{\"joint\":\"knee\",\"rows\":[{\"movement\":\"flexion\",\"rightDeg\":-31,\"leftDeg\":0}]}"));
        Rec mmt = save(rom10, spSection("mmt", "MMT_TABLE", "{\"rows\":[{\"group\":\"quadriceps\",\"right\":5,\"left\":0}]}"));
        refuseSave(mmt, 400, "validation", spSection("mmt", "MMT_TABLE", "{\"rows\":[{\"group\":\"quadriceps\",\"right\":6,\"left\":0}]}"));
        refuseSave(mmt, 400, "validation", spSection("special_tests", "SPECIAL_TESTS", "{\"joint\":\"knee\",\"results\":[{\"test\":\"lachman\",\"result\":\"MAYBE\"}]}"));
        Rec tests = save(mmt, spSection("special_tests", "SPECIAL_TESTS", "{\"joint\":\"knee\",\"results\":[{\"test\":\"lachman\",\"result\":\"POSITIVE\"},{\"test\":\"mcmurray_medial\",\"result\":\"NOT_DONE\"}]}"));
        assertThat(sectionStatus(tests.record(), "special_tests")).as("a result other than Not done").isEqualTo("DONE");
        refuseSave(tests, 400, "validation", spSection("pain", "PAIN_SCORE", "{\"score\":11}"));
        Rec pain = save(tests, spSection("pain", "PAIN_SCORE", "{\"score\":6}"));
        assertThat(sectionStatus(pain.record(), "pain")).isEqualTo("DONE");
        refuseSave(pain, 400, "validation", spSection("posture", "POSTURE_GAIT", "{\"gait\":\"RUNNING\"}"));
    }

    @Test
    void aGoalIsStampedWithTheDateItBecomesAchievedAndKeepsItWhenSavedAgain() throws Exception {
        Rec rec = specialtyRec("physio");
        String goal = uuid();
        Rec open = save(rec, spSection("goals", "GOALS", "{\"goals\":[{\"id\":\"" + goal + "\",\"text\":\"Walk 500 m\",\"achieved\":false}]}"));
        assertThat(valueOf(open.record(), "goals", "specialty").get("goals").get(0).get("achievedOn").isNull()).isTrue();
        Rec achieved = save(open, spSection("goals", "GOALS", "{\"goals\":[{\"id\":\"" + goal + "\",\"text\":\"Walk 500 m\",\"achieved\":true}]}"));
        assertThat(valueOf(achieved.record(), "goals", "specialty").get("goals").get(0).get("achievedOn").asText()).isEqualTo(today());
        Rec again = save(achieved, spSection("goals", "GOALS", "{\"goals\":[{\"id\":\"" + goal + "\",\"text\":\"Walk 500 m\",\"achieved\":true,\"achievedOn\":\"2020-01-01\"}]}"));
        assertThat(valueOf(again.record(), "goals", "specialty").get("goals").get(0).get("achievedOn").asText()).as("a client date is ignored").isEqualTo(today());
    }

    // ================= General Surgery (SAH-64, SAH-71) =================

    private static String fitness(String pac, String testsJson) {
        return "{\"asa\":\"II\",\"otherIllness\":[\"NONE\"],\"bloodThinner\":\"NONE\",\"pac\":\"" + pac + "\",\"tests\":" + testsJson + "}";
    }

    @Test
    void theSurgeryFitnessSummaryFollowsTheTestsAndThePacState() throws Exception {
        Rec rec = specialtyRec("surgery");
        String[][] cases = {{"FIT", "[{\"key\":\"CBC\",\"state\":\"NORMAL\"}]", "FIT", "Fit for surgery"},
                {"FIT", "[{\"key\":\"CBC\",\"state\":\"NORMAL\"},{\"key\":\"ECG\",\"state\":\"PENDING\"}]", "TESTS_PENDING", "1 test(s) pending"},
                {"NOT_DONE", "[]", "PAC_NOT_DONE", "PAC not done"}, {"UNFIT", "[]", "NOT_FIT", "Not fit yet"},
                {"FIT", "[{\"key\":\"LFT\",\"state\":\"ABNORMAL\",\"result\":\"Raised\"}]", "NOT_FIT", "abnormal"}};
        for (String[] c : cases) {
            rec = save(rec, spSection("sx_fitness", "SURGERY_FITNESS", fitness(c[0], c[1])));
            JsonNode summary = specialtyOf(rec).get("surgery").get("fitness");
            assertThat(summary.get("state").asText()).as(c[0] + " " + c[1]).isEqualTo(c[2]);
            assertThat(summary.get("text").asText()).as(c[0] + " " + c[1]).contains(c[3]);
        }
        refuseSave(rec, 400, "validation", spSection("sx_fitness", "SURGERY_FITNESS", fitness("MAYBE", "[]")));
        refuseSave(rec, 400, "validation", spSection("sx_fitness", "SURGERY_FITNESS", fitness("FIT", "[{\"key\":\"XRAY\",\"state\":\"NORMAL\"}]")));
    }

    private static String surgeryPlan(String payer, long packagePaise, String otDate) {
        return "{\"procedureKey\":\"LAP_CHOLECYSTECTOMY\",\"procedureText\":\"Laparoscopic cholecystectomy\",\"anaesthesia\":\"GA\",\"admission\":\"IPD_1_2_DAYS\",\"otDate\":"
                + (otDate == null ? "null" : "\"" + otDate + "\"") + ",\"slotText\":\"OT 2 · 10:00\",\"payer\":\"" + payer + "\",\"typedPackagePaise\":" + packagePaise + "}";
    }

    @Test
    void theDepositIsHalfThePackageRoundedToAThousandForSelfPayAndTenThousandRupeesForOtherPayersAndTheChipSaysWhetherPreAuthIsSent() throws Exception {
        Rec rec = specialtyRec("surgery");
        Rec self = save(rec, spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("SELF", 6000000L, null)));
        assertThat(specialtyOf(self).get("surgery").get("depositPaise").asLong()).as("50 % of Rs 60,000").isEqualTo(3000000L);
        assertThat(specialtyOf(self).get("surgery").get("payerChip").asText()).isEqualTo("Self-pay");
        Rec odd = save(self, spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("SELF", 5500000L, null)));
        assertThat(specialtyOf(odd).get("surgery").get("depositPaise").asLong()).as("50 % of Rs 55,000 = 27,500, rounded to the nearest 1,000").isIn(2700000L, 2800000L);
        Rec tpa = save(odd, spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("INSURANCE_TPA", 6000000L, null)));
        assertThat(specialtyOf(tpa).get("surgery").get("depositPaise").asLong()).isEqualTo(1000000L);
        assertThat(specialtyOf(tpa).get("surgery").get("payerChip").asText()).contains("pre-auth pending");
        Rec sent = save(tpa, spSection("sx_preauth", "PREAUTH", "{\"sent\":true,\"sentOn\":\"" + today() + "\",\"reference\":\"REF-1\"}"));
        assertThat(specialtyOf(sent).get("surgery").get("payerChip").asText()).contains("pre-auth sent");
        assertThat(sectionStatus(sent.record(), "sx_preauth")).isEqualTo("DONE");
        refuseSave(sent, 400, "validation", spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("CASH", 6000000L, null)));
        refuseSave(sent, 400, "validation", spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("SELF", 6000050L, null)));
        refuseSave(sent, 400, "validation", spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("SELF", 6000000L, "next friday")));
    }

    @Test
    void theBookingStateIsNotBookedUntilADiagnosisEveryConsentItemAndPreAuthWhenThePayerIsNotSelfAreThereThenReady() throws Exception {
        Rec rec = specialtyRec("surgery");
        Rec plan = save(rec, spSection("sx_plan", "SURGERY_PLAN", surgeryPlan("INSURANCE_TPA", 6000000L, LocalDate.now(IST).plusDays(5).toString())));
        JsonNode surgery = specialtyOf(plan).get("surgery");
        assertThat(surgery.get("bookState").asText()).isEqualTo("NOT_BOOKED");
        assertThat(texts(surgery.get("bookMissing"), null)).contains("diagnosis", "pre-authorisation");
        assertThat(surgery.get("consentCount").get("ticked").asInt()).isZero();
        String items = "[\"PROCEDURE_RISKS\",\"CONVERSION_TO_OPEN\",\"ANAESTHESIA\",\"BLOOD_TRANSFUSION\",\"PHOTOS_VIDEO\"]";
        StringBuilder consent = new StringBuilder("{\"items\":[");
        for (String key : new String[] {"PROCEDURE_RISKS", "CONVERSION_TO_OPEN", "ANAESTHESIA", "BLOOD_TRANSFUSION", "PHOTOS_VIDEO"}) {
            consent.append(consent.charAt(consent.length() - 1) == '[' ? "" : ",").append("{\"key\":\"").append(key).append("\",\"confirmed\":true}");
        }
        consent.append("]}");
        assertThat(items).isNotBlank();
        Rec ready = save(plan, spSection("sx_consent", "CONSENT_CHECKLIST", consent.toString()),
                oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", typedItem("Gallstones"))),
                spSection("sx_preauth", "PREAUTH", "{\"sent\":true,\"sentOn\":\"" + today() + "\",\"reference\":null}"));
        JsonNode after = specialtyOf(ready).get("surgery");
        assertThat(after.get("bookMissing")).isEmpty();
        assertThat(after.get("bookState").asText()).isEqualTo("READY");
        assertThat(after.get("consentCount").get("ticked").asInt()).isEqualTo(5);
        assertThat(after.get("consentCount").get("total").asInt()).isEqualTo(5);
    }

    @Test
    void thePreOpSheetTakesInstructionsOnlyAndExtraLinesAreLimited() throws Exception {
        Rec rec = specialtyRec("surgery");
        Rec saved = save(rec, spSection("sx_preop", "PREOP_ORDERS", "{\"ticked\":[\"NIL_BY_MOUTH\",\"PART_PREPARATION\"],\"extraLines\":[\"Bring the old reports\"],\"blood\":\"GROUP_AND_SAVE\"}"));
        assertThat(sectionStatus(saved.record(), "sx_preop")).isEqualTo("DONE");
        refuseSave(saved, 400, "validation", spSection("sx_preop", "PREOP_ORDERS", "{\"ticked\":[\"GIVE_ANTIBIOTIC_NOW\"],\"extraLines\":[]}"));
        refuseSave(saved, 400, "validation", spSection("sx_preop", "PREOP_ORDERS", "{\"ticked\":[],\"extraLines\":[\"" + "x".repeat(201) + "\"]}"));
        refuseSave(saved, 400, "validation", spSection("sx_preop", "PREOP_ORDERS", "{\"ticked\":[],\"extraLines\":[" + String.join(",", java.util.Collections.nCopies(11, "\"a\"")) + "]}"));
    }

    // ================= Medical Oncology (SAH-65, SAH-70) =================

    private static String systems(String... keys) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keys.length; i++) {
            out.append(i == 0 ? "" : ",").append('"').append(keys[i]).append("\":{\"state\":\"NORMAL\",\"text\":\"Normal\",\"chips\":[]}");
        }
        return out.append('}').toString();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode examWithSystems(double height, double weight, String systemsJson) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode exam = oncExam(height, weight, null);
        exam.set("systems", objectMapper.readTree(systemsJson));
        return exam;
    }

    @Test
    void theOncologyExaminationCountsFiveSystemsIncludingSpineAndBonesBeforeItIsDoneAndTheFooterNamesItSystemicExam() throws Exception {
        Rec rec = specialtyRec("onco");
        assertThat(neededKeys(rec.record())).as("always listed on the oncology screen (H2)").contains("onc_bsa", "onc_tox", "onc_regimen", "ONC_DECISION");
        Rec four = save(rec, spSection("onc_bsa", examWithSystems(160, 60, systems("cvs", "rs", "pa", "cns"))));
        assertThat(sectionStatus(four.record(), "onc_bsa")).as("four systems marked: not done").isNotEqualTo("DONE");
        assertThat(neededKeys(four.record())).contains("onc_bsa");
        for (JsonNode item : four.record().get("stillNeededItems")) {
            if ("onc_bsa".equals(item.get("key").asText())) {
                assertThat(item.get("label").asText()).isEqualTo("systemic exam");
            }
        }
        Rec five = save(four, spSection("onc_bsa", examWithSystems(160, 60, systems("cvs", "rs", "pa", "cns", "bone"))));
        assertThat(sectionStatus(five.record(), "onc_bsa")).as("weight, height and five systems").isEqualTo("DONE");
        assertThat(neededKeys(five.record())).doesNotContain("onc_bsa");
        Rec noBone = save(five, spSection("onc_bsa", examWithSystems(160, 60, systems("cvs", "rs", "pa", "cns"))));
        assertThat(sectionStatus(noBone.record(), "onc_bsa")).isNotEqualTo("DONE");
        com.fasterxml.jackson.databind.node.ObjectNode chips = examWithSystems(160, 60, "{\"bone\":{\"state\":\"ABNORMAL\",\"text\":\"Pain\",\"chips\":[\"Spine tenderness\",\"Rib tenderness\"]}}");
        Rec withChips = save(noBone, spSection("onc_bsa", chips));
        assertThat(valueOf(withChips.record(), "onc_bsa", "specialty").get("systems").get("bone").get("chips")).hasSize(2);
        refuseSave(withChips, 400, "validation", spSection("onc_bsa", examWithSystems(160, 60, "{\"spine\":{\"state\":\"NORMAL\"}}")));
        refuseSave(withChips, 400, "validation", spSection("onc_bsa", examWithSystems(49, 60, "{}")));
        refuseSave(withChips, 400, "validation", spSection("onc_bsa", examWithSystems(160, 301, "{}")));
    }

    @Test
    void theBsaIsHeightAndWeightBasedCappedAtTwoSquareMetresOnRequestAndTheResponseCategoryFollowsTheLesions() throws Exception {
        Rec rec = specialtyRec("onco");
        Rec bsa = save(rec, spSection("onc_bsa", oncExam(150, 100, null)));
        assertThat(specialtyOf(bsa).get("onco").get("bsa").asDouble()).as("sqrt(150 x 100 / 3600)").isEqualTo(2.04);
        assertThat(specialtyOf(bsa).get("onco").get("capped").asBoolean()).isFalse();
        com.fasterxml.jackson.databind.node.ObjectNode capExam = oncExam(150, 100, null);
        capExam.put("capAt2", true);
        Rec capped = save(bsa, spSection("onc_bsa", capExam));
        assertThat(specialtyOf(capped).get("onco").get("capped").asBoolean()).isTrue();
        assertThat(specialtyOf(capped).get("onco").get("bsaUsed").asDouble()).isEqualTo(2.0);
        String[][] responses = {{"5.0", "3.0", "false", "PARTIAL_RESPONSE"}, {"5.0", "0.0", "false", "COMPLETE_RESPONSE"}, {"5.0", "4.0", "false", "STABLE_DISEASE"},
                {"5.0", "6.5", "false", "PROGRESSIVE_DISEASE"}, {"5.0", "5.0", "true", "PROGRESSIVE_DISEASE"}};
        Rec current = capped;
        for (String[] r : responses) {
            com.fasterxml.jackson.databind.node.ObjectNode exam = oncExam(160, 60, null);
            exam.set("lesions", objectMapper.readTree("[{\"id\":\"" + uuid() + "\",\"name\":\"Left breast lump\",\"baselineCm\":" + r[0] + ",\"todayCm\":" + r[1] + "}]"));
            exam.put("newLesionSeen", Boolean.parseBoolean(r[2]));
            current = save(current, spSection("onc_bsa", exam));
            assertThat(specialtyOf(current).get("onco").get("response").asText()).as(r[0] + " to " + r[1] + " new " + r[2]).isEqualTo(r[3]);
        }
    }

    @Test
    void theSuggestionFollowsTheDrawnOrderStopThenDelayThenChangeRegimenThenReducedThenGiveAsPlanned() throws Exception {
        Rec none = specialtyRec("onco");
        assertThat(specialtyOf(none).get("onco").get("suggestion").isNull()).as("null until ECOG is chosen").isTrue();
        String labsOk = NORMAL_LABS;
        Object[][] cases = {
                {"ECOG 3 fit or not", 3, labsOk, "{\"entries\":[]}", "{\"symptoms\":[],\"events\":[]}", "STOP", "ECOG 3 · usually not fit for chemo"},
                {"ANC below 1,500", 1, "{\"hb\":12.0,\"anc\":1000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":60}", "{\"entries\":[]}", "{\"symptoms\":[],\"events\":[]}", "DELAY_1_WEEK", "ECOG 1 · fit for chemo"},
                {"fever 38.0 C", 1, labsOk, "{\"entries\":[{\"id\":\"" + uuid() + "\",\"temperature\":{\"value\":38.0,\"unit\":\"C\"}}]}", "{\"symptoms\":[],\"events\":[]}", "DELAY_1_WEEK", null},
                {"LVEF below 50", 1, "{\"hb\":12.0,\"anc\":3000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":45}", "{\"entries\":[]}", "{\"symptoms\":[],\"events\":[]}", "CHANGE_REGIMEN", null},
                {"admitted event", 1, labsOk, "{\"entries\":[]}", "{\"symptoms\":[],\"events\":[\"ADMITTED\"]}", "GIVE_REDUCED", null},
                {"all well", 2, labsOk, "{\"entries\":[]}", "{\"symptoms\":[],\"events\":[]}", "GIVE_AS_PLANNED", "ECOG 2 · fit for chemo"}};
        for (Object[] c : cases) {
            Rec rec = specialtyRec("onco");
            Rec saved = save(rec, spSection("onc_ecog", "ONC_ECOG", "{\"grade\":" + c[1] + "}"), spSection("onc_labs", oncLabs(1, (String) c[2])),
                    spSection("vitals", "VITALS_LOG", (String) c[3]), spSection("onc_tox", "ONC_TOX", (String) c[4]));
            JsonNode onco = specialtyOf(saved).get("onco");
            assertThat(onco.get("suggestion").asText()).as((String) c[0]).isEqualTo((String) c[5]);
            if (c[6] != null) {
                assertThat(onco.get("ecogText").asText()).as((String) c[0]).isEqualTo((String) c[6]);
            }
        }
        // a confirmed grade 3 side effect suggests a reduced dose
        Rec grade3 = specialtyRec("onco");
        String symptoms = "{\"symptoms\":[{\"key\":\"NAUSEA\",\"patientGrade\":2,\"confirmedGrade\":3}],\"events\":[]}";
        Rec saved = save(grade3, spSection("onc_ecog", "ONC_ECOG", "{\"grade\":1}"), spSection("onc_labs", oncLabs(1, NORMAL_LABS)), spSection("onc_tox", "ONC_TOX", symptoms));
        assertThat(specialtyOf(saved).get("onco").get("suggestion").asText()).isEqualTo("GIVE_REDUCED");
        assertThat(specialtyOf(saved).get("onco").get("worstGrade").asInt()).isEqualTo(3);
        assertThat(texts(specialtyOf(saved).get("onco").get("alerts"), null)).contains("GRADE_3_PLUS");
    }

    @Test
    void theOncologyVitalsUseDegreesCelsiusWithTheirOwnFlagsAndGeneralMedicineUsesFahrenheit() throws Exception {
        Rec rec = specialtyRec("onco");
        Rec fever = save(rec, spSection("vitals", "VITALS_LOG", vitals("\"temperature\":{\"value\":38.5,\"unit\":\"C\"},\"bpSystolic\":165,\"pulse\":120,\"spo2\":93")));
        List<String> got = labels(specialtyOf(fever).get("vitalsFlags"));
        assertThat(got).contains("temperature:Fever · rule out infection before chemo", "bp:Check again", "pulse:Fast · check for fever, anaemia, heart", "spo2:Low · examine lungs");
        assertThat(texts(specialtyOf(fever).get("onco").get("alerts"), null)).contains("FEVER");
        refuseSave(fever, 400, "validation", spSection("vitals", "VITALS_LOG", vitals("\"temperature\":{\"value\":100.4,\"unit\":\"F\"}")));
        refuseSave(fever, 400, "validation", spSection("vitals", "VITALS_LOG", vitals("\"temperature\":{\"value\":29.9,\"unit\":\"C\"}")));
        refuseSave(fever, 400, "validation", spSection("vitals", "VITALS_LOG", vitals("\"temperature\":{\"value\":43.1,\"unit\":\"C\"}")));
    }

    @Test
    void theSideEffectGradesLabsAndEcogAreRangeCheckedAndLabsDrawnInTheFutureAreRefused() throws Exception {
        Rec rec = specialtyRec("onco");
        refuseSave(rec, 400, "validation", spSection("onc_tox", "ONC_TOX", "{\"symptoms\":[{\"key\":\"NAUSEA\",\"patientGrade\":5,\"confirmedGrade\":1}],\"events\":[]}"));
        refuseSave(rec, 400, "validation", spSection("onc_tox", "ONC_TOX", "{\"symptoms\":[{\"key\":\"HAIR_LOSS\",\"patientGrade\":1,\"confirmedGrade\":1}],\"events\":[]}"));
        refuseSave(rec, 400, "validation", spSection("onc_ecog", "ONC_ECOG", "{\"grade\":5}"));
        refuseSave(rec, 400, "validation", spSection("onc_labs", oncLabs(1, "{\"hb\":0.5}")));
        refuseSave(rec, 400, "validation", spSection("onc_labs", oncLabs(1, "{\"lvef\":4}")));
        refuseSave(rec, 400, "validation", spSection("onc_labs", oncLabs(-2, NORMAL_LABS)));
        StringBuilder all = new StringBuilder("{\"symptoms\":[");
        String[] keys = {"NAUSEA", "VOMITING", "MOUTH_SORES", "LOOSE_MOTIONS", "TINGLING_NUMBNESS", "FATIGUE", "FEVER_LOW_COUNTS", "PAIN", "LOSS_OF_APPETITE"};
        for (int i = 0; i < keys.length; i++) {
            all.append(i == 0 ? "" : ",").append("{\"key\":\"").append(keys[i]).append("\",\"patientGrade\":1,\"confirmedGrade\":").append(i < 8 ? "1" : "null").append('}');
        }
        all.append("],\"events\":[]}");
        Rec eight = save(rec, spSection("onc_tox", "ONC_TOX", all.toString()));
        assertThat(sectionStatus(eight.record(), "onc_tox")).as("nine grades must be confirmed").isNotEqualTo("DONE");
        Rec nine = save(eight, spSection("onc_tox", "ONC_TOX", all.toString().replace("\"confirmedGrade\":null", "\"confirmedGrade\":0")));
        assertThat(sectionStatus(nine.record(), "onc_tox")).isEqualTo("DONE");
        Rec labsDone = save(nine, spSection("onc_labs", oncLabs(1, NORMAL_LABS)));
        assertThat(sectionStatus(labsDone.record(), "onc_labs")).as("all seven values and the drawn time").isEqualTo("DONE");
        assertThat(sectionStatus(save(labsDone, spSection("onc_labs", oncLabs(1, "{\"hb\":12.0}"))).record(), "onc_labs")).isNotEqualTo("DONE");
    }

    // ================= the oncology decision (D5) =================

    @Test
    void aDecisionThatDiffersFromTheSuggestionNeedsAReasonIsStampedAndAuditedAndFlagsWhenTheFactsChangeLater() throws Exception {
        Rec rec = specialtyRec("onco");
        Rec ecog3 = save(rec, spSection("onc_ecog", "ONC_ECOG", "{\"grade\":3}"));
        JsonNode needed = refuseScreen(ecog3, 400, "decision-reason-required", "{\"oncDecision\":{\"choice\":\"GIVE_AS_PLANNED\",\"reasonKey\":null}}");
        assertThat(needed.get("detail").asText()).isEqualTo("Pick a reason (saved with the note).");
        assertThat(auditCount(rec.clinic(), "EMR_ONC_DECISION_RECORDED")).isZero();
        Rec decided = saveScreen(ecog3, "{\"oncDecision\":{\"choice\":\"GIVE_AS_PLANNED\",\"reasonKey\":\"CLINICAL_JUDGEMENT\"}}");
        JsonNode decision = specialtyOf(decided).get("onco").get("decision");
        assertThat(decision.get("choice").asText()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(decision.get("reasonKey").asText()).isEqualTo("CLINICAL_JUDGEMENT");
        assertThat(decision.get("suggestionAtDecision").asText()).isEqualTo("STOP");
        assertThat(decision.get("stamp").get("byMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(specialtyOf(decided).get("onco").get("factsChanged").asBoolean()).isFalse();
        assertThat(row(rec.clinic(), "select screen_state -> 'oncDecision' ->> 'choice', screen_state ->> 'profile' from opd.emr_record where id = ?::uuid", rec.id())).containsExactly("GIVE_AS_PLANNED", "onco");
        assertThat(auditRows(rec.clinic(), "EMR_ONC_DECISION_RECORDED")).hasSize(1);
        assertThat(auditRows(rec.clinic(), "EMR_ONC_DECISION_RECORDED").get(0).get(2)).contains("GIVE_AS_PLANNED").contains("CLINICAL_JUDGEMENT").contains("STOP");
        // the facts change: the recorded decision stays, the screen says so
        Rec better = save(decided, spSection("onc_ecog", "ONC_ECOG", "{\"grade\":1}"), spSection("onc_labs", oncLabs(1, NORMAL_LABS)));
        assertThat(specialtyOf(better).get("onco").get("factsChanged").asBoolean()).isTrue();
        assertThat(specialtyOf(better).get("onco").get("suggestion").asText()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(specialtyOf(better).get("onco").get("decision").get("choice").asText()).as("the recorded decision stays until confirmed again").isEqualTo("GIVE_AS_PLANNED");
        // the same as the suggestion needs no reason
        Rec agreed = saveScreen(better, "{\"oncDecision\":{\"choice\":\"GIVE_AS_PLANNED\",\"reasonKey\":null}}");
        assertThat(specialtyOf(agreed).get("onco").get("factsChanged").asBoolean()).isFalse();
        // enumerations and null
        refuseScreen(agreed, 400, "validation", "{\"oncDecision\":{\"choice\":\"MAYBE\",\"reasonKey\":null}}");
        refuseScreen(agreed, 400, "validation", "{\"oncDecision\":{\"choice\":\"STOP\",\"reasonKey\":\"BECAUSE\"}}");
        refuseScreen(agreed, 400, "validation", "{\"mode\":\"ANC\"}");
        Rec unchanged = saveScreen(agreed, "{\"oncDecision\":null}");
        assertThat(specialtyOf(unchanged).get("onco").get("decision").get("choice").asText()).as("null leaves the decision unchanged").isEqualTo("GIVE_AS_PLANNED");
    }

    @Test
    void aDecisionCannotBeRecordedOnAProfileThatHasNone() throws Exception {
        Rec gm = specialtyRec("gm");
        refuseScreen(gm, 400, "validation", "{\"oncDecision\":{\"choice\":\"STOP\",\"reasonKey\":null}}");
        refuseScreen(gm, 400, "validation", "{\"mode\":\"ANC\"}");
        Rec ok = saveScreen(gm, "{}");
        assertThat(ok.revision()).isGreaterThan(gm.revision());
    }

    // ================= take-home, referrals, Pegfilgrastim (D13) =================

    @Test
    void theChemoPlanTakesTheSixTakeHomeLinesAndEightReferralsAndRefusesOthers() throws Exception {
        Rec rec = specialtyRec("onco");
        Rec saved = save(rec, spSection("onc_regimen", "CHEMO_PLAN", "{\"takeHome\":[\"ONDANSETRON_8\",\"LOPERAMIDE_2\"],\"nextCycleDate\":\"" + LocalDate.now(IST).plusDays(21)
                + "\",\"imagingText\":null,\"referrals\":[\"SOCIAL_WORKER_SCHEME_PAPERS\"],\"cycleNote\":\"Written for you\"}"));
        JsonNode plan = valueOf(saved.record(), "onc_regimen", "specialty");
        assertThat(texts(plan.get("takeHome"), null)).containsExactly("ONDANSETRON_8", "LOPERAMIDE_2");
        assertThat(texts(plan.get("referrals"), null)).containsExactly("SOCIAL_WORKER_SCHEME_PAPERS");
        String all = "[\"RADIOTHERAPY\",\"SURGICAL_ONCOLOGY\",\"PALLIATIVE_CARE\",\"DIETITIAN\",\"PSYCHO_ONCOLOGY_COUNSELLING\",\"FERTILITY_COUNSELLING\",\"SOCIAL_WORKER_SCHEME_PAPERS\",\"CARDIOLOGY_ECHO\"]";
        Rec eight = save(saved, spSection("onc_regimen", "CHEMO_PLAN", "{\"takeHome\":[],\"referrals\":" + all + "}"));
        assertThat(valueOf(eight.record(), "onc_regimen", "specialty").get("referrals")).hasSize(8);
        refuseSave(eight, 400, "validation", spSection("onc_regimen", "CHEMO_PLAN", "{\"takeHome\":[\"MORPHINE\"],\"referrals\":[]}"));
        refuseSave(eight, 400, "validation", spSection("onc_regimen", "CHEMO_PLAN", "{\"takeHome\":[],\"referrals\":[\"ASTROLOGY\"]}"));
        refuseSave(eight, 400, "validation", spSection("onc_regimen", "CHEMO_PLAN", "{\"takeHome\":[],\"referrals\":[],\"cycleNote\":\"" + "n".repeat(4001) + "\"}"));
    }

    @Test
    void pegfilgrastimIsSuggestedForTheTcRegimenOrAFeverOrAdmissionEventAndNotOtherwise() throws Exception {
        Rec rec = oncoAssessed(specialtyRec("onco"));
        saveOrder(rec, orderBody(0, "PACLI_3W", 100, "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\""));
        assertThat(specialtyOf(get(RECORDS + "/" + rec.id(), rec.token())).get("onco").get("pegfilgrastimSuggested").asBoolean()).as("paclitaxel, no event").isFalse();
        Rec event = save(rec, spSection("onc_tox", "ONC_TOX", "{\"symptoms\":[],\"events\":[\"ADMITTED\"]}"));
        assertThat(specialtyOf(event).get("onco").get("feverOrAdmissionEvent").asBoolean()).isTrue();
        assertThat(specialtyOf(event).get("onco").get("pegfilgrastimSuggested").asBoolean()).as("an admission event").isTrue();
        Rec tc = oncoAssessed(specialtyRec("onco"));
        saveOrder(tc, orderBody(0, "TC", 100, "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\""));
        assertThat(specialtyOf(get(RECORDS + "/" + tc.id(), tc.token())).get("onco").get("pegfilgrastimSuggested").asBoolean()).as("TC").isTrue();
        Rec noOrder = specialtyRec("onco");
        assertThat(specialtyOf(noOrder).get("onco").get("pegfilgrastimSuggested").asBoolean()).isFalse();
    }

    // ================= history, server stamps and another clinic =================

    @Test
    void theSpecialtyHistoryShowsEarlierSignedVitalsNewestFirstAndAnotherPatientsRecordsNeverAppear() throws Exception {
        Rec first = specialtyRec("gm");
        Rec saved = save(first, spSection("vitals", "VITALS_LOG", vitals("\"bpSystolic\":150,\"bpDiastolic\":95,\"pulse\":88")));
        signRecord(first.clinic(), saved.id(), 3);
        Rec second = anotherVisit(first, "gm");
        Rec stranger = specialtyRec(first.clinic(), first.doctor(), newPatient(first.clinic()), "gm");
        save(stranger, spSection("vitals", "VITALS_LOG", vitals("\"bpSystolic\":199,\"bpDiastolic\":99")));
        JsonNode history = get(RECORDS + "/" + second.id() + "/specialty-history", second.token());
        assertThat(history.get("profile").asText()).isEqualTo("gm");
        assertThat(history.get("someHidden").asBoolean()).isFalse();
        assertThat(history.get("vitals")).isNotEmpty();
        assertThat(history.get("vitals").get(0).get("bp").asText()).isEqualTo("150/95");
        assertThat(history.toString()).doesNotContain("199/99");
        problemOf(HttpMethod.GET, RECORDS + "/" + second.id() + "/specialty-history", newRec().token(), null, 404, "record-not-found");
        Staff nurse = addNonDoctor(first.clinic(), "NURSE");
        problemOf(HttpMethod.GET, RECORDS + "/" + second.id() + "/specialty-history", nurse.token(first.clinic()), null, 403, "forbidden");
        Staff otherDoctor = addDoctor(first.clinic(), "General physician");
        problemOf(HttpMethod.GET, RECORDS + "/" + second.id() + "/specialty-history", otherDoctor.token(first.clinic()), null, 403, "not-own-patient");
    }

    @Test
    void aSpecialtySaveIsRefusedForAnotherClinicsRecordAndASignedRecordAndNothingClinicalGoesToTheAuditOrTheLog() throws Exception {
        Rec rec = specialtyRec("gm");
        Rec other = newRec();
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", other.token(), draftBody(0, "ASSESSMENT", null, spSection("vitals", "VITALS_LOG", vitals("\"pulse\":80"))).toString(), 404, "record-not-found");
        try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
            Rec saved = save(rec, spSection("vitals", "VITALS_LOG", vitals("\"bpSystolic\":151,\"pulse\":117")),
                    spSection("exam", "EXAM_GENERAL", "{\"signs\":{},\"systems\":{\"general\":{\"state\":\"ABNORMAL\",\"text\":\"Zxqfinding\"}}}"));
            refuseSave(saved, 400, "validation", spSection("vitals", "VITALS_LOG", vitals("\"bpSystolic\":999")));
            assertThat(logs.allText()).doesNotContain("Zxqfinding");
            signRecord(rec.clinic(), saved.id());
            problemOf(HttpMethod.PUT, RECORDS + "/" + saved.id() + "/draft", saved.token(),
                    draftBody(saved.revision(), "ASSESSMENT", null, spSection("vitals", "VITALS_LOG", vitals("\"pulse\":80"))).toString(), 409, "emr-record-signed");
        }
        assertThat(allAuditText(rec.clinic())).doesNotContain("Zxqfinding");
    }
}
