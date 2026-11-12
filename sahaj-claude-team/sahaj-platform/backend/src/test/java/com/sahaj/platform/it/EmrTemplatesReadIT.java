package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-44-1 and the read side of SAH-44-2 on the real database: the four specialty templates held as data, every
 * section in the order of the story table with its required default, editor type and quick-option source, the
 * clinic's effective Show and Required, the revision, {@code canEdit}, the 404 for a specialty without a template, and
 * the read access of every clinic role. The expected table below is typed from {@code docs/stories/SAH-44.md}
 * ("Notes for design"), not read from the database. Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrTemplatesReadIT extends EmrSupport {

    /** key | label | part | required by default | editor type | options source (MASTER:<master>, TEMPLATE, TARIFF, NONE). */
    static final String[][] GP = {
            {"chief_complaints", "Chief complaints", "ASSESSMENT", "true", "COMPLAINTS", "MASTER:complaints"},
            {"history_allergies", "History & allergies", "ASSESSMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"vitals", "Vitals", "ASSESSMENT", "true", "VITALS_STEPPERS", "NONE"},
            {"general_exam", "General examination", "ASSESSMENT", "true", "SIGNS", "TEMPLATE"},
            {"systemic_exam", "Systemic examination", "ASSESSMENT", "false", "SYSTEMS", "TEMPLATE"},
            {"local_exam", "Local examination", "ASSESSMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"diagnosis", "Diagnosis", "TREATMENT", "true", "DIAGNOSIS", "MASTER:diagnoses"},
            {"prescription", "Prescription", "TREATMENT", "true", "PRESCRIPTION", "MASTER:medicines"},
            {"investigations", "Investigations", "TREATMENT", "false", "CHIP_LIST", "MASTER:investigations"},
            {"services", "Procedures & services", "TREATMENT", "false", "SERVICES", "TARIFF"},
            {"advice_followup", "Advice & follow-up", "TREATMENT", "true", "ADVICE", "MASTER:advice"}};
    static final String[][] DENTAL = {
            {"chief_complaints", "Chief complaints", "ASSESSMENT", "true", "COMPLAINTS", "MASTER:complaints"},
            {"medical_alerts", "Medical alerts", "ASSESSMENT", "true", "CHIP_LIST", "TEMPLATE"},
            {"extra_oral_exam", "Extra-oral examination", "ASSESSMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"intra_oral_exam", "Intra-oral examination", "ASSESSMENT", "true", "CHIP_LIST", "TEMPLATE"},
            {"tooth_chart", "Tooth chart", "ASSESSMENT", "true", "TOOTH_CHART", "NONE"},
            {"radiographs", "Radiographs", "ASSESSMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"diagnosis", "Diagnosis", "TREATMENT", "true", "DIAGNOSIS", "MASTER:diagnoses"},
            {"treatment_plan", "Treatment plan & sittings", "TREATMENT", "true", "TREATMENT_PLAN", "NONE"},
            {"prescription", "Prescription", "TREATMENT", "false", "PRESCRIPTION", "MASTER:medicines"},
            {"services", "Procedures & services", "TREATMENT", "false", "SERVICES", "TARIFF"},
            {"advice_followup", "Advice & follow-up", "TREATMENT", "true", "ADVICE", "MASTER:advice"}};
    static final String[][] PAEDS = {
            {"chief_complaints", "Chief complaints", "ASSESSMENT", "true", "COMPLAINTS", "MASTER:complaints"},
            {"birth_immunisation", "Birth & immunisation history", "ASSESSMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"vitals_growth", "Vitals & growth", "ASSESSMENT", "true", "VITALS_STEPPERS", "NONE"},
            {"general_exam", "General examination", "ASSESSMENT", "true", "SIGNS", "TEMPLATE"},
            {"development", "Development", "ASSESSMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"diagnosis", "Diagnosis", "TREATMENT", "true", "DIAGNOSIS", "MASTER:diagnoses"},
            {"prescription", "Prescription (by weight)", "TREATMENT", "false", "PRESCRIPTION", "MASTER:medicines"},
            {"vaccines_today", "Vaccines given today", "TREATMENT", "false", "CHIP_LIST", "TEMPLATE"},
            {"advice_followup", "Advice & follow-up", "TREATMENT", "false", "ADVICE", "MASTER:advice"}};
    static final String[][] HOMEO = {
            {"chief_complaints", "Chief complaints", "ASSESSMENT", "true", "COMPLAINTS", "MASTER:complaints"},
            {"case_history", "Case history", "ASSESSMENT", "true", "CHIP_LIST", "TEMPLATE"},
            {"mentals_generals", "Mentals & generals", "ASSESSMENT", "true", "CHIP_LIST", "TEMPLATE"},
            {"vitals", "Vitals", "ASSESSMENT", "false", "VITALS_STEPPERS", "NONE"},
            {"diagnosis", "Diagnosis", "TREATMENT", "true", "DIAGNOSIS", "MASTER:diagnoses"},
            {"remedy_potency", "Remedy & potency", "TREATMENT", "true", "PRESCRIPTION", "MASTER:medicines"},
            {"advice_followup", "Advice & follow-up", "TREATMENT", "true", "ADVICE", "MASTER:advice"}};

    static final String[][] TABLE_FOR(String key) {
        return switch (key) {
            case "gp" -> GP;
            case "dental" -> DENTAL;
            case "paeds" -> PAEDS;
            default -> HOMEO;
        };
    }

    private static final String[] NAMES = {"General practice", "Dental", "Paediatrics", "Homeopathy"};
    private static final int[] REQUIRED = {6, 7, 4, 6};

    /** "template/section" of the six sections that cannot be hidden (OQ-43-14). */
    static final String[] NOT_HIDEABLE = {"gp/history_allergies", "gp/prescription", "dental/medical_alerts",
            "dental/prescription", "paeds/prescription", "homeo/remedy_potency"};

    // ---------------- the list ----------------

    @Test
    void theListHoldsTheFourTemplatesInOrderWithTheClinicsCounts() throws Exception {
        Clinic clinic = newClinic();
        JsonNode list = get(TEMPLATES, clinic.owner());
        assertThat(list.size()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            JsonNode t = list.get(i);
            assertThat(t.get("templateKey").asText()).isEqualTo(TEMPLATE_KEYS[i]);
            assertThat(t.get("name").asText()).isEqualTo(NAMES[i]);
            assertThat(t.get("version").asInt()).isEqualTo(1);
            assertThat(t.get("sectionCount").asInt()).isEqualTo(TABLE_FOR(TEMPLATE_KEYS[i]).length);
            assertThat(t.get("sectionsOn").asInt()).isEqualTo(TABLE_FOR(TEMPLATE_KEYS[i]).length);
            assertThat(t.get("requiredOn").asInt()).as(NAMES[i] + " required by default").isEqualTo(REQUIRED[i]);
            assertThat(t.get("hasOverrides").asBoolean()).isFalse();
        }
        // 11 + 11 + 9 + 7
        int total = 0;
        for (JsonNode t : list) {
            total += t.get("sectionCount").asInt();
        }
        assertThat(total).isEqualTo(38);
    }

    @Test
    void theClinicsOwnSpecialtyIsMarkedYoursAndApplyingASpecialtyInClinicSetupKeepsWorking() throws Exception {
        Clinic clinic = newClinic();
        // the wizard chose gp
        assertThat(texts(filter(get(TEMPLATES, clinic.owner()), "clinicSpecialty", true), "templateKey")).containsExactly("gp");
        // SAH-7 Clinic setup, Specialty template still applies a specialty
        ok(HttpMethod.PUT, "/api/v1/platform/clinic/specialty", clinic.owner(), "{\"code\":\"dental\"}", 200);
        assertThat(texts(filter(get(TEMPLATES, clinic.owner()), "clinicSpecialty", true), "templateKey")).containsExactly("dental");
        assertThat(template(clinic, "dental").get("clinicSpecialty").asBoolean()).isTrue();
        assertThat(template(clinic, "gp").get("clinicSpecialty").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.GET, "/api/v1/platform/specialties", clinic.owner(), null, 200).size()).isPositive();
        setClinicSpecialty(clinic, null);
        assertThat(filter(get(TEMPLATES, clinic.owner()), "clinicSpecialty", true)).isEmpty();
    }

    private static List<JsonNode> filter(JsonNode array, String field, boolean value) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(n -> {
            if (n.get(field).asBoolean() == value) {
                out.add(n);
            }
        });
        return out;
    }

    private static List<String> texts(List<JsonNode> nodes, String field) {
        return nodes.stream().map(n -> n.get(field).asText()).toList();
    }

    // ---------------- one template ----------------

    @Test
    void everyTemplateHasItsSectionsInTheOrderOfTheStoryTableWithTheirDefaults() throws Exception {
        Clinic clinic = newClinic();
        for (int i = 0; i < 4; i++) {
            String key = TEMPLATE_KEYS[i];
            JsonNode view = template(clinic, key);
            String[][] expected = TABLE_FOR(key);
            assertThat(view.get("templateKey").asText()).isEqualTo(key);
            assertThat(view.get("name").asText()).isEqualTo(NAMES[i]);
            assertThat(view.get("version").asInt()).isEqualTo(1);
            assertThat(view.get("revision").asLong()).as("before the first change").isZero();
            assertThat(view.get("sections").size()).as(key + " sections").isEqualTo(expected.length);
            assertThat(view.get("sectionsOn").asInt()).isEqualTo(expected.length);
            assertThat(view.get("requiredOn").asInt()).isEqualTo(REQUIRED[i]);
            for (int s = 0; s < expected.length; s++) {
                JsonNode section = view.get("sections").get(s);
                String[] e = expected[s];
                String where = key + "/" + e[0];
                assertThat(section.get("key").asText()).as(where).isEqualTo(e[0]);
                assertThat(section.get("label").asText()).as(where).isEqualTo(e[1]);
                assertThat(section.get("part").asText()).as(where).isEqualTo(e[2]);
                assertThat(section.get("position").asInt()).as(where).isEqualTo(s + 1);
                assertThat(section.get("templateRequired").asBoolean()).as(where).isEqualTo(Boolean.parseBoolean(e[3]));
                assertThat(section.get("editorType").asText()).as(where).isEqualTo(e[4]);
                String[] source = e[5].split(":");
                assertThat(section.get("options").get("source").asText()).as(where).isEqualTo(source[0]);
                if (source.length > 1) {
                    assertThat(section.get("options").get("master").asText()).as(where).isEqualTo(source[1]);
                } else {
                    assertThat(section.get("options").get("master").isNull()).as(where).isTrue();
                }
                assertThat(section.get("iconKey").isNull()).as("no icon until the EMR mockup shows them: " + where).isTrue();
                // no override yet: shown, required as the template says
                assertThat(section.get("shown").asBoolean()).as(where).isTrue();
                assertThat(section.get("required").asBoolean()).as(where).isEqualTo(Boolean.parseBoolean(e[3]));
                assertThat(section.get("overridden").asBoolean()).as(where).isFalse();
            }
        }
    }

    @Test
    void partsAreAssessmentThenTreatmentWithTheStoryCounts() throws Exception {
        Clinic clinic = newClinic();
        int[][] counts = {{6, 5}, {6, 5}, {5, 4}, {4, 3}};
        for (int i = 0; i < 4; i++) {
            long assessment = 0;
            long treatment = 0;
            boolean seenTreatment = false;
            for (JsonNode section : template(clinic, TEMPLATE_KEYS[i]).get("sections")) {
                if ("ASSESSMENT".equals(section.get("part").asText())) {
                    assessment++;
                    assertThat(seenTreatment).as("Assessment comes before Treatment").isFalse();
                } else {
                    treatment++;
                    seenTreatment = true;
                }
            }
            assertThat(new long[] {assessment, treatment}).as(TEMPLATE_KEYS[i]).containsExactly(counts[i][0], counts[i][1]);
        }
    }

    @Test
    void onlySixSectionsCannotBeHiddenAndAnyOfTheOthersCan() throws Exception {
        Clinic clinic = newClinic();
        List<String> fixed = new ArrayList<>();
        int hideable = 0;
        for (String key : TEMPLATE_KEYS) {
            for (JsonNode section : template(clinic, key).get("sections")) {
                if (section.get("hideable").asBoolean()) {
                    hideable++;
                } else {
                    fixed.add(key + "/" + section.get("key").asText());
                }
            }
        }
        assertThat(fixed).containsExactlyInAnyOrder(NOT_HIDEABLE);
        assertThat(hideable).isEqualTo(32);
    }

    @Test
    void quickOptionCountsAreTheMasterRowsTheChipsTheClinicsActiveServicesOrNothing() throws Exception {
        Clinic clinic = newClinic();
        long activeServices = 0;
        for (JsonNode service : ok(HttpMethod.GET, "/api/v1/opd/services", clinic.owner(), null, 200)) {
            if (service.get("active").asBoolean()) {
                activeServices++;
            }
        }
        for (String key : TEMPLATE_KEYS) {
            for (JsonNode section : template(clinic, key).get("sections")) {
                String where = key + "/" + section.get("key").asText();
                String source = section.get("options").get("source").asText();
                JsonNode count = section.get("quickOptionCount");
                switch (source) {
                    case "MASTER" -> {
                        String master = section.get("options").get("master").asText();
                        long rows = switch (master) {
                            case "complaints" -> globalCount("select count(*) from opd.emr_complaint");
                            case "diagnoses" -> globalCount("select count(*) from opd.diagnosis");
                            case "medicines" -> globalCount("select count(*) from opd.medicine");
                            case "investigations" -> globalCount("select count(*) from opd.emr_investigation");
                            default -> globalCount("select count(*) from opd.emr_advice");
                        };
                        assertThat(count.asLong()).as(where).isEqualTo(rows);
                        assertThat(section.get("templateOptions").size()).as(where).isZero();
                    }
                    case "TEMPLATE" -> {
                        assertThat(section.get("templateOptions").size()).as(where + " has its own chips, at least one").isPositive();
                        assertThat(count.asInt()).as(where).isEqualTo(section.get("templateOptions").size());
                    }
                    case "TARIFF" -> assertThat(count.asLong()).as(where).isEqualTo(activeServices);
                    default -> assertThat(count.isNull()).as(where).isTrue();
                }
            }
        }
    }

    @Test
    void theChipListsCarryTheirOptionsWithRedAlertChipsAndANoneOptionWhereTheSectionIsRequired() throws Exception {
        Clinic clinic = newClinic();
        JsonNode gp = template(clinic, "gp");
        assertThat(texts(section(gp, "general_exam").get("templateOptions"), "key")).containsExactly("pallor", "icterus",
                "cyanosis", "clubbing", "lymph_nodes", "oedema", "dehydration");
        assertThat(texts(section(gp, "systemic_exam").get("templateOptions"), "key")).containsExactly("cvs", "rs", "cns",
                "abdomen");
        JsonNode allergies = section(gp, "history_allergies").get("templateOptions");
        assertThat(allergies.size()).isGreaterThan(3);
        long alerts = 0;
        for (JsonNode option : allergies) {
            assertThat(option.get("label").asText()).isNotBlank();
            assertThat(option.get("style").asText()).isIn("NORMAL", "ALERT");
            if ("ALERT".equals(option.get("style").asText())) {
                alerts++;
            }
        }
        assertThat(alerts).as("allergy chips are red").isPositive();
        // required chip lists can be completed with no finding
        for (String[] section : new String[][] {{"dental", "medical_alerts"}, {"dental", "intra_oral_exam"},
                {"homeo", "case_history"}, {"homeo", "mentals_generals"}}) {
            JsonNode options = section(template(clinic, section[0]), section[1]).get("templateOptions");
            long none = 0;
            for (JsonNode option : options) {
                if (option.get("isNone").asBoolean()) {
                    none++;
                }
            }
            assertThat(none).as(section[0] + "/" + section[1] + " has exactly one None option").isEqualTo(1);
        }
        JsonNode alertsChips = section(template(clinic, "dental"), "medical_alerts").get("templateOptions");
        for (JsonNode option : alertsChips) {
            if (!option.get("isNone").asBoolean()) {
                assertThat(option.get("style").asText()).as("alert chips are red").isEqualTo("ALERT");
            }
        }
    }

    // ---------------- keys that are not a template ----------------

    @Test
    void aSpecialtyWithoutATemplateIsAClearRefusalNotAnEmptyTemplate() throws Exception {
        Clinic clinic = newClinic();
        for (String key : new String[] {"ayur", "physio", "derma", "gynae", "ent", "oncology", "surgery", "unknown"}) {
            JsonNode problem = problemOf(HttpMethod.GET, TEMPLATES + "/" + key, clinic.owner(), null, 404, "template-not-found");
            assertThat(problem.get("detail").asText()).isEqualTo("There is no EMR template for this specialty yet.");
        }
        assertThat(texts(get(TEMPLATES, clinic.owner()), "templateKey")).doesNotContain("ayur", "physio", "derma");
    }

    @Test
    void aMalformedTemplateKeyIsABadRequest() throws Exception {
        Clinic clinic = newClinic();
        for (String key : new String[] {"GP", "Bad-Key", "g", "1gp", "a".repeat(21), "gp%20x", "gp.x"}) {
            problemOf(HttpMethod.GET, TEMPLATES + "/" + key, clinic.owner(), null, 400, "bad-request");
        }
    }

    // ---------------- who may read ----------------

    @Test
    void everyClinicRoleReadsTheTemplatesAndOnlyTheOwnerCanEdit() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ALL_ROLES) {
            JsonNode view = get(TEMPLATES + "/gp", clinic.as(role));
            assertThat(view.get("canEdit").asBoolean()).as(role).isEqualTo("OWNER".equals(role));
            assertThat(view.get("sections").size()).isEqualTo(11);
            assertThat(get(TEMPLATES, clinic.as(role)).size()).isEqualTo(4);
        }
        // and real people, not only a role in the token
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(clinic, role);
            assertThat(get(TEMPLATES + "/dental", staff.token(clinic)).get("canEdit").asBoolean()).as(role).isFalse();
        }
        Staff doctor = addDoctor(clinic, "Dental");
        assertThat(get(TEMPLATES + "/dental", doctor.token(clinic)).get("canEdit").asBoolean()).isFalse();
        // a nurse whose token claims OWNER does not get canEdit: the membership is read again
        Staff nurse = addNonDoctor(clinic, "NURSE");
        assertThat(get(TEMPLATES + "/gp", nurse.tokenClaiming(clinic, "OWNER")).get("canEdit").asBoolean()).isFalse();
    }

    @Test
    void theDefinitionsAreTheSameForEveryClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        for (String key : TEMPLATE_KEYS) {
            JsonNode inA = template(a, key);
            JsonNode inB = template(b, key);
            assertThat(texts(inA.get("sections"), "key")).isEqualTo(texts(inB.get("sections"), "key"));
            assertThat(texts(inA.get("sections"), "label")).isEqualTo(texts(inB.get("sections"), "label"));
            assertThat(inA.get("version").asInt()).isEqualTo(inB.get("version").asInt());
        }
    }
}
