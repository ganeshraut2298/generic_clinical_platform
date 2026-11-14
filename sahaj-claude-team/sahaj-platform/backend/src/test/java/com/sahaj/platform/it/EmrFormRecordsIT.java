package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-59 (ADR-022 D11) in the EMR: a live form is a custom section of the departments it is used in; doctors see it at once
 * without a department publish; the record stores the form version it was opened with ({@code form_refs}) so a later version,
 * a removal or a retirement never changes it; the values of a form are saved under the form key and checked against that
 * version (limits, choices, show-if, Done); the score, the band and the "Abnormal" flag reach the history of a signed record.
 * Real database as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrFormRecordsIT extends EmrPhase3Support {

    private static ObjectNode required(ObjectNode field) {
        field.put("required", true);
        return field;
    }

    /** The "Wound review" form: a required choice, a multi choice, a number with limits and a normal range, a yes or no, a field shown by it, a score, a band, medicines, a note and a photo (later). */
    private JsonNode woundForm(Clinic clinic) throws Exception {
        ObjectNode severity = required(choiceField("sev", "SINGLE_CHOICE", "Severity", "mild:1", "moderate:2", "severe:4"));
        ObjectNode grade = choiceField("grade", "MULTI_CHOICE", "Grade", "a:1", "b:2", "c:3");
        ObjectNode pain = numberField("pain", "Pain", 0, 10);
        pain.put("normalFrom", 0);
        pain.put("normalTo", 5);
        ObjectNode infected = formField("infected", "YES_NO", "Infected");
        ObjectNode details = formField("details", "SHORT_TEXT", "Details");
        ObjectNode showIf = details.putObject("showIf");
        showIf.put("fieldKey", "infected");
        showIf.put("optionKey", "yes");
        ObjectNode total = formField("total", "SCORE", "Total");
        ObjectNode score = total.putObject("score");
        score.putArray("sourceFieldKeys").add("sev").add("grade");
        ObjectNode rule = score.putArray("rules").addObject();
        rule.put("kind", "NUMBER_BELOW");
        rule.put("fieldKey", "pain");
        rule.put("threshold", 3);
        rule.put("points", 2);
        ObjectNode band = formField("band", "SCORE_BAND", "Band");
        ObjectNode setup = band.putObject("band");
        setup.put("sourceFieldKey", "total");
        ArrayNode bands = setup.putArray("bands");
        bands.addObject().put("from", 0).put("label", "Low");
        bands.addObject().put("from", 4).put("label", "Medium");
        bands.addObject().put("from", 8).put("label", "High");
        return liveSimpleForm(clinic, "Wound review", severity, grade, pain, infected, details, total, band,
                formField("meds", "MEDICINE_LIST", "Medicines"), formField("note", "LONG_TEXT", "Note"), formField("photo", "PHOTO", "Photo"));
    }

    private static JsonNode layoutForm(JsonNode record, String formKey) {
        return layoutSection(record, formKey);
    }

    private ObjectNode formSection(String formKey, String valuesJson) throws Exception {
        return sectionValues(formKey, valuesJson);
    }

    private JsonNode refusedValues(Rec rec, String formKey, String valuesJson, int status, String slug) throws Exception {
        return refuseSave(rec, status, slug, formSection(formKey, valuesJson));
    }

    // ================= the form in layouts =================

    @Test
    void aLiveFormIsAppendedToTheWorkingLayoutOfItsDepartmentsAsACustomSectionTheOwnerCanShowHideAndOrder() throws Exception {
        Clinic clinic = newClinic();
        JsonNode form = woundForm(clinic);
        String formKey = form.get("formKey").asText();
        JsonNode gm = department(clinic, "gm");
        JsonNode section = section(gm, formKey);
        assertThat(section.get("source").asText()).isEqualTo("FORM");
        assertThat(section.get("formKey").asText()).isEqualTo(formKey);
        assertThat(section.get("name").asText()).isEqualTo("Wound review");
        assertThat(section.get("kind").asText()).isEqualTo("SPECIALTY");
        assertThat(section.get("part").asText()).isEqualTo("ASSESSMENT");
        assertThat(section.get("shown").asBoolean()).isTrue();
        assertThat(section.get("required").asBoolean()).isFalse();
        assertThat(section.get("print").asBoolean()).isTrue();
        assertThat(section.get("copy").asBoolean()).isFalse();
        assertThat(section.get("collapsed").asBoolean()).isFalse();
        assertThat(section.get("fields")).as("the definition of a form is not a list of library fields").isEmpty();
        // appended at the end of its part
        int at = sectionKeys(gm).indexOf(formKey);
        for (int i = at + 1; i < sectionKeys(gm).size(); i++) {
            assertThat(section(gm, sectionKeys(gm).get(i)).get("part").asText()).isEqualTo("TREATMENT");
        }
        assertThat(sectionKeys(department(clinic, "dental"))).as("a department the form is not used in").doesNotContain(formKey);
        // the owner hides it and publishes: a record opened later has no such section
        ObjectNode hide = layoutInput(gm);
        setShown(hide, formKey, false, false);
        publish(clinic, "gm", hide, 200);
        Staff doctor = addDoctor(clinic, "General physician");
        assertThat(layoutKeys(startRecord(clinic, doctor).record())).doesNotContain(formKey);
        // shown again and required: the record has it and it is still needed
        ObjectNode show = layoutInput(department(clinic, "gm"));
        setShown(show, formKey, true, true);
        publish(clinic, "gm", show, 200);
        Rec rec = startRecord(clinic, doctor);
        assertThat(layoutKeys(rec.record())).contains(formKey);
        assertThat(texts(rec.record().get("stillNeeded"), null)).contains(formKey);
        assertThat(layoutForm(rec.record(), formKey).get("required").asBoolean()).isTrue();
        // the set of sections is the library set plus the live forms: a page that leaves the form out is stale
        ObjectNode stale = layoutInput(department(clinic, "gm"));
        ArrayNode sections = stale.withArray("sections");
        for (int i = 0; i < sections.size(); i++) {
            if (formKey.equals(sections.get(i).get("key").asText())) {
                sections.remove(i);
                break;
            }
        }
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.owner(), stale.toString(), 400, "section-set-mismatch");
    }

    @Test
    void doctorsSeeALiveFormAtOnceWithoutADepartmentPublishAndTheRecordStoresTheVersionItWasOpenedWith() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec before = startRecord(clinic, doctor);
        JsonNode form = woundForm(clinic);
        String formKey = form.get("formKey").asText();
        assertThat(layoutKeys(current(before))).as("a record opened earlier does not get a form made later").doesNotContain(formKey);
        Rec rec = startRecord(clinic, doctor);
        JsonNode inLayout = layoutForm(rec.record(), formKey);
        assertThat(inLayout.get("source").asText()).isEqualTo("FORM");
        assertThat(inLayout.get("formVersionNo").asInt()).isEqualTo(1);
        assertThat(inLayout.get("form").get("name").asText()).isEqualTo("Wound review");
        assertThat(inLayout.get("form").get("fields")).hasSize(10);
        assertThat(recordSection(rec.record(), formKey).get("status").asText()).isEqualTo("OPTIONAL");
        assertThat(rec.record().get("layout").get("layoutSource").asText()).isEqualTo("STARTER");
        String refs = row(clinic, "select form_refs::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        JsonNode stored = objectMapper.readTree(refs);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).get("formKey").asText()).isEqualTo(formKey);
        assertThat(stored.get(0).get("versionNo").asInt()).isEqualTo(1);
        assertThat(stored.get(0).get("part").asText()).isEqualTo("ASSESSMENT");
        assertThat(refs).as("references only, no copy of the form").doesNotContain("Wound review").doesNotContain("fields");
        assertThat(row(clinic, "select form_refs::text from opd.emr_record where id = ?::uuid", before.id())).containsExactly("[]");
        // a department the form is not used in: no reference
        Rec dental = switchDepartment(startRecord(clinic, doctor), "dental");
        assertThat(layoutKeys(dental.record())).doesNotContain(formKey);
    }

    @Test
    void aRecordKeepsTheFormVersionItWasOpenedWithWhenTheOwnerPublishesVersionTwo() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        JsonNode v1 = woundForm(clinic);
        String formKey = v1.get("formKey").asText();
        Rec old = startRecord(clinic, doctor);
        ObjectNode edit = formDraftBody(v1);
        ((ObjectNode) edit.get("settings")).put("approvalRequired", false);
        ((ArrayNode) edit.get("fields")).add(formField("extra", "SHORT_TEXT", "Extra"));
        JsonNode draft = saveFormDraft(clinic, v1, edit, 200);
        formAction(clinic, draft, "publish", 200);
        JsonNode again = current(old);
        assertThat(layoutForm(again, formKey).get("formVersionNo").asInt()).isEqualTo(1);
        assertThat(layoutForm(again, formKey).get("form").get("fields")).hasSize(10);
        Rec fresh = startRecord(clinic, doctor);
        assertThat(layoutForm(fresh.record(), formKey).get("formVersionNo").asInt()).isEqualTo(2);
        assertThat(layoutForm(fresh.record(), formKey).get("form").get("fields")).hasSize(11);
        // the old record checks its values against version 1: the new field is not in it
        JsonNode problem = refusedValues(old, formKey, "{\"extra\":\"x\"}", 400, "validation");
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("sections[0].values");
        save(fresh, formSection(formKey, "{\"extra\":\"x\"}"));
        Rec saved = save(old, formSection(formKey, "{\"sev\":\"mild\"}"));
        assertThat(valueOf(saved.record(), formKey, "sev").asText()).isEqualTo("mild");
        assertThat(row(clinic, "select (form_refs -> 0 ->> 'versionNo') from opd.emr_record where id = ?::uuid", old.id())).containsExactly("1");
        assertThat(row(clinic, "select (form_refs -> 0 ->> 'versionNo') from opd.emr_record where id = ?::uuid", fresh.id())).containsExactly("2");
    }

    @Test
    void aRetiredFormOrOneRemovedFromTheDepartmentIsNotInRecordsOpenedAfterwardsAndOldRecordsKeepItAndStillSaveItsValues() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        JsonNode live = woundForm(clinic);
        String formKey = live.get("formKey").asText();
        Rec old = startRecord(clinic, doctor);
        // used in another department only: General Medicine no longer has it, Dental has it
        ObjectNode move = formDraftBody(live);
        ((ObjectNode) move.get("settings")).put("approvalRequired", false);
        ((ObjectNode) move.get("settings")).with("useIn").putArray("departmentKeys").add("dental");
        JsonNode v2 = formAction(clinic, saveFormDraft(clinic, live, move, 200), "publish", 200);
        assertThat(sectionKeys(department(clinic, "gm"))).doesNotContain(formKey);
        assertThat(sectionKeys(department(clinic, "dental"))).contains(formKey);
        assertThat(layoutKeys(startRecord(clinic, doctor).record())).doesNotContain(formKey);
        assertThat(layoutKeys(switchDepartment(startRecord(clinic, doctor), "dental").record())).contains(formKey);
        // retire: nothing new gets it
        formAction(clinic, v2, "retire", 200);
        assertThat(sectionKeys(department(clinic, "dental"))).doesNotContain(formKey);
        assertThat(layoutKeys(switchDepartment(startRecord(clinic, doctor), "dental").record())).doesNotContain(formKey);
        // the record opened before still has it, with the values saved against its version
        assertThat(layoutKeys(current(old))).contains(formKey);
        Rec saved = save(old, formSection(formKey, "{\"sev\":\"severe\",\"pain\":4}"));
        assertThat(valueOf(saved.record(), formKey, "pain").asInt()).isEqualTo(4);
        assertThat(recordSection(saved.record(), formKey).get("status").asText()).isEqualTo("DONE");
    }

    // ================= the values of a form =================

    @Test
    void aFormEntryIsSavedUnderTheFormKeyAndAFieldHiddenByShowIfIsDroppedAndNeverCounts() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String formKey = woundForm(clinic).get("formKey").asText();
        Rec rec = startRecord(clinic, doctor);
        assertThat(recordSection(rec.record(), formKey).get("status").asText()).isEqualTo("OPTIONAL");
        Rec saved = save(rec, formSection(formKey, "{\"sev\":\"severe\",\"grade\":[\"a\",\"b\"],\"pain\":4,\"infected\":true,"
                + "\"details\":\"Redness\",\"note\":\"Seen at 10 am\",\"meds\":" + rxValue(typedRx("Zinc syrup")) + "}"));
        assertThat(valueOf(saved.record(), formKey, "sev").asText()).isEqualTo("severe");
        assertThat(valueOf(saved.record(), formKey, "grade").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(valueOf(saved.record(), formKey, "pain").asInt()).isEqualTo(4);
        assertThat(valueOf(saved.record(), formKey, "infected").asBoolean()).isTrue();
        assertThat(valueOf(saved.record(), formKey, "details").asText()).isEqualTo("Redness");
        assertThat(valueOf(saved.record(), formKey, "meds").get("rows").get(0).get("name").asText()).isEqualTo("Zinc syrup");
        assertThat(recordSection(saved.record(), formKey).get("status").asText()).isEqualTo("DONE");
        // the answer that shows `details` is changed: the hidden value is dropped by the server
        Rec hidden = save(saved, formSection(formKey, "{\"sev\":\"severe\",\"infected\":false,\"details\":\"Should be dropped\"}"));
        assertThat(recordSection(hidden.record(), formKey).get("values").has("details")).isFalse();
        assertThat(recordSection(hidden.record(), formKey).get("values").has("pain")).as("a save replaces the section's values").isFalse();
        assertThat(count(clinic, "select count(*) from opd.emr_record where id = ?::uuid and sections::text like '%dropped%'", UUID.fromString(rec.id()))).isZero();
        // an empty value clears a field; with the required choice gone the section is not Done
        Rec cleared = save(hidden, formSection(formKey, "{\"sev\":null,\"note\":\"\"}"));
        assertThat(recordSection(cleared.record(), formKey).get("values").size()).isZero();
        assertThat(recordSection(cleared.record(), formKey).get("status").asText()).isEqualTo("OPTIONAL");
    }

    @Test
    void everyValueIsCheckedAgainstItsFieldAndALimitIsOutsideMinMaxWithTheLimitsInTheMessage() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String formKey = woundForm(clinic).get("formKey").asText();
        Rec rec = startRecord(clinic, doctor);
        JsonNode outside = refusedValues(rec, formKey, "{\"pain\":11}", 400, "validation");
        assertThat(outside.get("fieldErrors").get(0).get("field").asText()).isEqualTo("sections[0].values.pain");
        assertThat(outside.get("fieldErrors").get(0).get("message").asText()).isEqualTo("Outside 0–10");
        String[][] bad = {
                {"{\"pain\":-1}", "pain"}, {"{\"pain\":10.5}", "pain"}, {"{\"pain\":1.23456}", "pain"}, {"{\"pain\":\"5\"}", "pain"},
                {"{\"sev\":\"critical\"}", "sev"}, {"{\"sev\":5}", "sev"}, {"{\"grade\":[\"a\",\"a\"]}", "grade"}, {"{\"grade\":[\"z\"]}", "grade"},
                {"{\"grade\":\"a\"}", "grade"}, {"{\"infected\":\"yes\"}", "infected"}, {"{\"infected\":1}", "infected"},
                {"{\"details\":\"" + "d".repeat(201) + "\"}", "details"}, {"{\"note\":\"" + "n".repeat(4001) + "\"}", "note"},
                {"{\"total\":5}", "total"}, {"{\"band\":\"High\"}", "band"}, {"{\"photo\":\"file.jpg\"}", "photo"},
                {"{\"meds\":[{\"name\":\"Zinc\"}]}", "meds"}};
        for (String[] b : bad) {
            // `details` is shown only when infected is yes: the value of a visible field is checked, so send it with infected true
            String values = "details".equals(b[1]) ? b[0].replace("{", "{\"infected\":true,") : b[0];
            JsonNode problem = refusedValues(rec, formKey, values, 400, "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).as(b[0]).isEqualTo("sections[0].values." + b[1]);
            assertThat(problem.toString()).as("no value is echoed").doesNotContain("critical").doesNotContain("file.jpg").doesNotContain("d".repeat(50));
        }
        refusedValues(rec, formKey, "{\"zxqunknown\":\"x\"}", 400, "validation");
        refusedValues(rec, formKey, "{\"meds\":" + rxValue(typedRx("Zinc syrup"), typedRx("ZINC SYRUP")) + "}", 400, "duplicate-item");
        assertThat(current(rec).get("revision").asLong()).as("nothing was stored").isEqualTo(rec.revision());
        // the edge values are accepted
        Rec edge = save(rec, formSection(formKey, "{\"pain\":0,\"note\":\"" + "n".repeat(4000) + "\",\"infected\":true,\"details\":\"" + "d".repeat(200) + "\"}"));
        assertThat(valueOf(edge.record(), formKey, "pain").asInt()).isZero();
        Rec top = save(edge, formSection(formKey, "{\"pain\":10,\"sev\":\"mild\"}"));
        assertThat(valueOf(top.record(), formKey, "pain").asInt()).isEqualTo(10);
    }

    @Test
    void aSectionRequiredInTheLayoutIsStillNeededUntilTheFormsRequiredFieldIsFilled() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String formKey = woundForm(clinic).get("formKey").asText();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, formKey, true, true);
        publish(clinic, "gm", body, 200);
        Rec rec = startRecord(clinic, doctor);
        assertThat(texts(rec.record().get("stillNeeded"), null)).contains(formKey);
        assertThat(recordSection(rec.record(), formKey).get("status").asText()).isEqualTo("REQUIRED");
        Rec noise = save(rec, formSection(formKey, "{\"note\":\"Only a note\"}"));
        assertThat(texts(noise.record().get("stillNeeded"), null)).as("the required choice is missing").contains(formKey);
        Rec done = save(noise, formSection(formKey, "{\"sev\":\"mild\"}"));
        assertThat(texts(done.record().get("stillNeeded"), null)).doesNotContain(formKey);
        assertThat(recordSection(done.record(), formKey).get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void theAllergyCheckAlsoCoversTheMedicineListOfAForm() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String formKey = woundForm(clinic).get("formKey").asText();
        Rec rec = startRecord(clinic, doctor);
        ObjectNode row = rxRow(medicineId(SULFA_MEDICINE), SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        Rec saved = save(rec, allergySection("SULFA"), formSection(formKey, "{\"meds\":" + rxValue(row) + "}"));
        JsonNode alerts = saved.record().get("allergyAlerts");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("sectionKey").asText()).isEqualTo(formKey);
        assertThat(alerts.get(0).get("fieldKey").asText()).isEqualTo("meds");
        assertThat(saved.record().get("completeBlockers").get(0).asText()).isEqualTo("ALLERGY_ALERT");
    }

    // ================= score, band and the Abnormal flag in the history =================

    private JsonNode formLines(Clinic clinic, Staff doctor, String formKey, String valuesJson) throws Exception {
        UUID patient = newPatient(clinic);
        Rec first = startRecord(clinic, doctor, patient);
        Rec filled = save(first, formSection(formKey, valuesJson));
        Rec today = startRecord(clinic, doctor, patient);
        signRecord(clinic, filled.id(), 1);
        return get(RECORDS + "/" + today.id() + "/past-visits/OPD/" + filled.id(), today.token());
    }

    private static String lineValue(JsonNode detail, String label) {
        for (JsonNode line : detail.get("lines")) {
            if (label.equals(line.get("label").asText())) {
                return line.get("value").asText();
            }
        }
        return null;
    }

    @Test
    void theHistoryOfASignedFormShowsEachFilledFieldWithTheScoreTheBandAndTheAbnormalFlagTheServerWorksOut() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String formKey = woundForm(clinic).get("formKey").asText();
        // severe (4) + grade a and b (1 + 2) + pain below 3 (2 points) = 9: the band with the highest start not above 9 is High
        JsonNode high = formLines(clinic, doctor, formKey, "{\"sev\":\"severe\",\"grade\":[\"a\",\"b\"],\"pain\":2,\"infected\":true,\"details\":\"Redness\"}");
        assertThat(lineValue(high, "Wound review · Severity")).isEqualTo("Severe");
        assertThat(lineValue(high, "Wound review · Grade")).isEqualTo("A, B");
        assertThat(lineValue(high, "Wound review · Pain")).isEqualTo("2");
        assertThat(lineValue(high, "Wound review · Infected")).isEqualTo("Yes");
        assertThat(lineValue(high, "Wound review · Details")).isEqualTo("Redness");
        assertThat(lineValue(high, "Wound review · Total")).isEqualTo("9");
        assertThat(lineValue(high, "Wound review · Band")).isEqualTo("High");
        // moderate (2), pain 7 is not below 3 (no points), above the normal range: total 2 is Low, and Abnormal is flagged
        JsonNode low = formLines(clinic, doctor, formKey, "{\"sev\":\"moderate\",\"pain\":7,\"infected\":false}");
        assertThat(lineValue(low, "Wound review · Total")).isEqualTo("2");
        assertThat(lineValue(low, "Wound review · Band")).isEqualTo("Low");
        assertThat(lineValue(low, "Wound review · Pain")).isEqualTo("7 (Abnormal)");
        assertThat(lineValue(low, "Wound review · Details")).as("a hidden field is not shown").isNull();
        // the edges of the bands: total 4 is Medium (from 4) and total 8 is High (from 8)
        JsonNode medium = formLines(clinic, doctor, formKey, "{\"sev\":\"severe\"}");
        assertThat(lineValue(medium, "Wound review · Total")).isEqualTo("4");
        assertThat(lineValue(medium, "Wound review · Band")).isEqualTo("Medium");
        JsonNode edge = formLines(clinic, doctor, formKey, "{\"sev\":\"severe\",\"grade\":[\"a\",\"c\"]}");
        assertThat(lineValue(edge, "Wound review · Total")).isEqualTo("8");
        assertThat(lineValue(edge, "Wound review · Band")).isEqualTo("High");
        // nothing answered: the total is blank, so neither the score nor the band is shown
        JsonNode blank = formLines(clinic, doctor, formKey, "{\"note\":\"Only a note\"}");
        assertThat(lineValue(blank, "Wound review · Total")).isNull();
        assertThat(lineValue(blank, "Wound review · Band")).isNull();
        assertThat(lineValue(blank, "Wound review · Note")).isEqualTo("Only a note");
        // the normal range edge: 5 is inside the range, not flagged
        JsonNode inside = formLines(clinic, doctor, formKey, "{\"sev\":\"mild\",\"pain\":5}");
        assertThat(lineValue(inside, "Wound review · Pain")).isEqualTo("5");
    }

    @Test
    void theHistoryReadsAFormEntryThroughTheRecordsOwnVersionEvenAfterANewVersionRelabelsTheField() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        JsonNode v1 = woundForm(clinic);
        String formKey = v1.get("formKey").asText();
        UUID patient = newPatient(clinic);
        Rec first = save(startRecord(clinic, doctor, patient), formSection(formKey, "{\"sev\":\"severe\"}"));
        ObjectNode edit = formDraftBody(v1);
        ((ObjectNode) edit.get("settings")).put("approvalRequired", false);
        ((ObjectNode) edit.get("fields").get(0)).put("label", "Seriousness");
        formAction(clinic, saveFormDraft(clinic, v1, edit, 200), "publish", 200);
        Rec today = startRecord(clinic, doctor, patient);
        signRecord(clinic, first.id(), 1);
        JsonNode detail = get(RECORDS + "/" + today.id() + "/past-visits/OPD/" + first.id(), today.token());
        assertThat(lineValue(detail, "Wound review · Severity")).as("the label of the version it was filled in").isEqualTo("Severe");
        assertThat(lineValue(detail, "Wound review · Seriousness")).isNull();
    }
}
