package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-75 database cases 7, 16 and 17 of docs/database/SAH-75.md through the real endpoints on {@code sahaj_test}: a department
 * that was published from an older starter layout version reads the new sections appended and hidden (the union rule of
 * ADR-026 D2), a never-published department reads the new starter version, the next visit's copy leaves out a Gynae value that no
 * longer fits its new definition (ADR-026 D13), and a record opened on the version 2 snapshot still saves the old shapes while one
 * opened on version 3 saves the choice keys. The "older version" is made the only way a test can: one extra department version
 * row, copied from the stored starter layout version, inserted as {@code sahaj_app} (the insert policy allows it). Throwaway
 * data only. Test plan: docs/test-plans/SAH-75.md.
 */
@OnTestDatabase
class Sah75StarterVersionsIT extends EmrPhase3Support {

    /** Publishes the department as it stands, then adds the next version holding the stored starter layout of that older version. */
    private void publishFromStarterVersion(Clinic clinic, String key, int starterVersion) throws Exception {
        publishAsIs(clinic, key);
        commit(clinic.tenantId(), "insert into opd.emr_department_version (department_id, version_no, layout, published_by) "
                + "select d.id, (select max(v.version_no) + 1 from opd.emr_department_version v where v.department_id = d.id), l.layout, ?::uuid "
                + "from opd.emr_department d join opd.emr_starter_layout l on l.starter_key = d.starter_key and l.layout_version = ? where d.department_key = ?",
                clinic.ownerUserId().toString(), starterVersion, key);
        commit(clinic.tenantId(), "update opd.emr_department d set published_version = (select max(v.version_no) from opd.emr_department_version v where v.department_id = d.id) "
                + "where d.department_key = ?", key);
    }

    /** The department as the view shows it with one harmless flag flipped, so the save is a change ("SAVED", not "NOTHING_NEW"). */
    private ObjectNode changed(JsonNode view) {
        ObjectNode body = layoutInput(view);
        ObjectNode some = sec(body, "followup");
        some.put("collapsed", !some.get("collapsed").asBoolean());
        return body;
    }

    // ================= case 7: the set rule =================

    @Test
    void aNeverPublishedGeneralMedicineDepartmentReadsLocalExamWithTheStarterSettingsAndSavesAndAnOlderPublishedOneReadsItHiddenAndOptional() throws Exception {
        Clinic clinic = newClinic();
        JsonNode view = department(clinic, "gm");
        JsonNode local = section(view, "local_exam");
        assertThat(local.get("part").asText()).isEqualTo("ASSESSMENT");
        assertThat(local.get("shown").asBoolean()).isTrue();
        assertThat(local.get("required").asBoolean()).isFalse();
        assertThat(local.get("print").asBoolean()).isTrue();
        assertThat(local.get("copy").asBoolean()).isFalse();
        assertThat(sectionKeys(view).indexOf("local_exam")).as("right after exam").isEqualTo(sectionKeys(view).indexOf("exam") + 1);
        assertThat(saveDraft(clinic, "gm", changed(view), 200).get("outcome").asText()).isEqualTo("SAVED");
        // published from layout version 1: the sections it never had are appended, hidden and not required
        Clinic older = newClinic();
        publishFromStarterVersion(older, "gm", 1);
        JsonNode oldView = department(older, "gm");
        assertThat(oldView.get("publishedVersion").asInt()).isEqualTo(2);
        JsonNode appended = section(oldView, "local_exam");
        assertThat(appended.get("shown").asBoolean()).isFalse();
        assertThat(appended.get("required").asBoolean()).isFalse();
        assertThat(sectionKeys(oldView)).as("every section of the older version is still there").containsAll(
                globalRows("select s ->> 'key' from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') s where l.starter_key = 'gm' and l.layout_version = 1")
                        .stream().map(r -> r.get(0)).toList());
        assertThat(saveDraft(older, "gm", changed(oldView), 200).get("outcome").asText()).as("the appended sections pass the set check").isEqualTo("SAVED");
        // doctors read the published version only: nothing new reaches them until the owner shows and publishes it
        JsonNode layout = get(DEPARTMENTS + "/gm/layout", older.as("DOCTOR"));
        assertThat(sectionKeys(layout)).doesNotContain("local_exam");
        assertThat(layout.get("versionNo").asInt()).isEqualTo(2);
    }

    @Test
    void anOlderPublishedDentalDepartmentReadsTheSixNewCardsAppendedHiddenAndOptionalAndResetToTemplateReturnsTheVersionTwoDefaults() throws Exception {
        Clinic clinic = newClinic();
        publishFromStarterVersion(clinic, "dental", 1);
        JsonNode view = department(clinic, "dental");
        for (String card : new String[] {"dental_medhx", "dental_hx", "dental_pain", "dental_extraoral", "dental_intraoral", "dental_xray"}) {
            JsonNode s = section(view, card);
            assertThat(s.get("shown").asBoolean()).as(card + " shown").isFalse();
            assertThat(s.get("required").asBoolean()).as(card + " required").isFalse();
        }
        assertThat(saveDraft(clinic, "dental", changed(view), 200).get("outcome").asText()).isEqualTo("SAVED");
        // reset to template: the starter version 2 settings, and they pass the set check
        JsonNode target = get(DEPARTMENTS + "/dental/reset-target", clinic.owner());
        assertThat(section(target, "dental_medhx").get("shown").asBoolean()).isTrue();
        assertThat(section(target, "dental_medhx").get("required").asBoolean()).isTrue();
        assertThat(section(target, "tx_plan").get("required").asBoolean()).isTrue();
        assertThat(section(target, "intraoral").get("shown").asBoolean()).as("the old Intra-oral section is kept but hidden").isFalse();
        ObjectNode reset = objectMapper.createObjectNode();
        reset.put("revision", department(clinic, "dental").get("revision").asLong());
        reset.put("origin", "RESET");
        reset.set("sections", target.get("sections").deepCopy());
        assertThat(saveDraft(clinic, "dental", reset, 200).get("outcome").asText()).isEqualTo("SAVED");
    }

    // ================= case 16: copy-forward of the Gynae values =================

    private static final String FIT_AND_STALE = "{\"lmp\":\"2026-10-01\",\"cycle\":\"Regular, 28 days\",\"flow\":\"heavy\",\"age_at_menarche\":13}";

    /** Writes a section document into a draft record the way an older version of the application stored it. */
    private void storeSection(Clinic clinic, String recordId, String sectionKey, String valuesJson) throws Exception {
        String membership = owner(clinic).membershipId().toString();
        String doc = "{\"" + sectionKey + "\":{\"values\":" + valuesJson + ",\"copiedFrom\":null,\"copyConfirmed\":true,\"updatedAt\":\"2026-10-01T05:00:00Z\",\"updatedBy\":\""
                + membership + "\"}}";
        commit(clinic.tenantId(), "update opd.emr_record set sections = ?::jsonb where id = ?::uuid", doc, recordId);
    }

    private Rec ogVisit(Clinic clinic, Staff doctor, UUID patient) throws Exception {
        Rec rec = startRecord(clinic, doctor, patient);
        assertThat(rec.record().get("layout").get("starterKey").asText()).as("the OG doctor lands on the og layout").isEqualTo("og");
        return rec;
    }

    @Test
    void theNextVisitsCopyLeavesOutAFreeTextCycleKeepsTheValuesThatFitAndTheCopiedSectionSavesWithoutA400() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "OG");
        UUID patient = newPatient(clinic);
        Rec first = ogVisit(clinic, doctor, patient);
        storeSection(clinic, first.id(), "menstrual", FIT_AND_STALE);
        signRecord(clinic, first.id(), 3);
        Rec next = ogVisit(clinic, doctor, patient);
        JsonNode copied = recordSection(next.record(), "menstrual");
        assertThat(copied).as("the section is copied").isNotNull();
        JsonNode values = copied.get("values");
        assertThat(values.has("cycle")).as("\"Regular, 28 days\" does not fit the choice row").isFalse();
        assertThat(values.get("flow").asText()).as("heavy is still an option of Flow").isEqualTo("heavy");
        assertThat(values.get("lmp").asText()).isEqualTo("2026-10-01");
        assertThat(copied.get("copiedFrom").get("recordId").asText()).isEqualTo(first.id());
        // the copy saves as it stands: no 400
        Rec saved = save(next, sectionValues("menstrual", values.toString()));
        assertThat(recordSection(saved.record(), "menstrual").get("values").has("cycle")).isFalse();
    }

    @Test
    void aSectionWhereNothingFitsTheNewDefinitionIsNotCopiedAtAll() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "OG");
        UUID patient = newPatient(clinic);
        Rec first = ogVisit(clinic, doctor, patient);
        storeSection(clinic, first.id(), "menstrual", "{\"cycle\":\"Regular, 28 days\",\"dysmenorrhoea\":true,\"flow\":\"profuse\"}");
        signRecord(clinic, first.id(), 3);
        Rec next = ogVisit(clinic, doctor, patient);
        JsonNode section = next.record().get("sections") == null ? null : recordSection(next.record(), "menstrual");
        assertThat(section == null || section.get("values") == null || section.get("values").isEmpty() || section.path("copiedFrom").isNull())
                .as("nothing fits, so nothing is copied").isTrue();
        // and the draft save of that section with a fitting value passes
        Rec saved = save(next, sectionValues("menstrual", "{\"cycle\":\"regular\",\"flow\":\"scanty\"}"));
        assertThat(recordSection(saved.record(), "menstrual").get("values").get("cycle").asText()).isEqualTo("regular");
    }

    // ================= case 17: old and new records side by side =================

    @Test
    void aRecordOpenedOnTheVersionTwoSnapshotStillSavesCycleAsTextAndPainAsAToggleWhileAVersionThreeRecordSavesChoiceKeysAndRefusesTheText() throws Exception {
        Clinic old = newClinic();
        Staff oldDoctor = addDoctor(old, "OG");
        publishFromStarterVersion(old, "og", 2);
        Rec oldRec = ogVisit(old, oldDoctor, newPatient(old));
        JsonNode cycle = layoutField(oldRec, "menstrual", "cycle");
        assertThat(cycle.get("type").asText()).as("version 2 keeps the old definition").isNotEqualTo("SINGLE_CHOICE");
        Rec saved = save(oldRec, sectionValues("menstrual", "{\"cycle\":\"Regular, 28 days\",\"dysmenorrhoea\":true}"));
        assertThat(recordSection(saved.record(), "menstrual").get("values").get("cycle").asText()).isEqualTo("Regular, 28 days");
        assertThat(recordSection(saved.record(), "menstrual").get("values").get("dysmenorrhoea").asBoolean()).isTrue();
        // opened on version 3
        Clinic fresh = newClinic();
        Staff doctor = addDoctor(fresh, "OG");
        Rec newRec = ogVisit(fresh, doctor, newPatient(fresh));
        assertThat(layoutField(newRec, "menstrual", "cycle").get("type").asText()).isEqualTo("SINGLE_CHOICE");
        refuseSave(newRec, 400, "validation", sectionValues("menstrual", "{\"cycle\":\"Regular, 28 days\"}"));
        Rec ok = save(newRec, sectionValues("menstrual", "{\"cycle\":\"regular\",\"cycle_length\":\"o28_30_d\",\"flow\":\"with_clots\",\"dysmenorrhoea\":\"mild\"}"));
        assertThat(recordSection(ok.record(), "menstrual").get("values").get("dysmenorrhoea").asText()).isEqualTo("mild");
        assertThat(count(fresh, "select count(*) from opd.emr_record where id = ?::uuid and starter_layout_version = 3", newRec.id())).as("the record names the version it opened with").isEqualTo(1);
    }

    private JsonNode layoutField(Rec rec, String sectionKey, String fieldKey) {
        for (JsonNode f : layoutSection(rec.record(), sectionKey).get("fields")) {
            if (fieldKey.equals(f.get("key").asText())) {
                return f;
            }
        }
        throw new AssertionError("no field " + sectionKey + "." + fieldKey);
    }
}
