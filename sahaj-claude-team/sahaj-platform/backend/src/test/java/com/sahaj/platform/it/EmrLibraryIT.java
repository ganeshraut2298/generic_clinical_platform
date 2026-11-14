package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-53-1 and SAH-54-1 through the real endpoints: the 100-section library ({@code GET /emr/library/sections}) and the
 * 15 starter departments ({@code GET /emr/starter-departments}). Counts, order, field types, the None option, the hide
 * locks, who may read, the 403 on every write, and 404 for keys that are not there (the retired {@code gp} and
 * {@code homeo} included). The stored data itself is checked by SQL in {@link EmrGlobalTablesIT}. Test plan:
 * docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrLibraryIT extends EmrSupport {

    private static final Set<String> FIELD_TYPES = Set.of("TEXT", "RICH_TEXT", "NUMBER", "NUMBER_UNIT", "NUMBER_TREND",
            "CALCULATED", "FORMULA", "RULE", "REFERENCE", "DATE", "CALENDAR", "TOGGLE", "DROPDOWN", "DROPDOWNS",
            "SINGLE_CHOICE", "MULTI_SELECT", "CHECKLIST", "CHIPS", "SEARCH_LIST", "TABLE", "GRID", "PATTERN", "TEMPLATE",
            "DOCTOR_HOSPITAL", "CHART", "BODY_MAP", "TOOTH_PICKER", "LIBRARY", "FILE", "SIGNATURE");

    private static Clinic shared;

    /** Reads change nothing, so one clinic serves every test of this class. */
    private static synchronized Clinic clinicOf(EmrLibraryIT test) throws Exception {
        if (shared == null) {
            shared = test.newClinic();
        }
        return shared;
    }

    private JsonNode library(String role) throws Exception {
        return get(LIBRARY, clinicOf(this).as(role));
    }

    // ---------------- who may read ----------------

    @Test
    void everyClinicRoleReadsTheLibraryAndTheStartersAndNotSignedInIs401() throws Exception {
        Clinic clinic = clinicOf(this);
        for (String role : ALL_ROLES) {
            assertThat(get(LIBRARY, clinic.as(role)).size()).as("library as " + role).isEqualTo(100);
            assertThat(get(STARTERS, clinic.as(role)).size()).as("starters as " + role).isEqualTo(15);
            assertThat(get(LIBRARY + "/vitals", clinic.as(role)).get("key").asText()).isEqualTo("vitals");
            assertThat(get(STARTERS + "/gm", clinic.as(role)).get("starterKey").asText()).isEqualTo("gm");
        }
        for (String path : new String[] {LIBRARY, LIBRARY + "/vitals", STARTERS, STARTERS + "/gm"}) {
            problemOf(HttpMethod.GET, path, anonymous(), null, 401, "unauthorized");
        }
    }

    @Test
    void theLibraryAndTheStartersAreTheSameForEveryClinic() throws Exception {
        Clinic other = newClinic();
        assertThat(get(LIBRARY, other.owner())).isEqualTo(library("OWNER"));
        assertThat(get(STARTERS, other.owner())).isEqualTo(get(STARTERS, clinicOf(this).owner()));
        assertThat(get(STARTERS + "/og", other.owner())).isEqualTo(get(STARTERS + "/og", clinicOf(this).owner()));
    }

    // ---------------- the library ----------------

    @Test
    void theLibraryHoldsHundredSectionsFourteenCommonSeventyEightSpecialtyAndEightComplianceInThatOrder() throws Exception {
        JsonNode sections = library("OWNER");
        assertThat(sections.size()).isEqualTo(100);
        Map<String, Integer> kinds = new LinkedHashMap<>();
        List<String> kindOrder = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        int fields = 0;
        for (JsonNode s : sections) {
            kinds.merge(s.get("kind").asText(), 1, Integer::sum);
            if (kindOrder.isEmpty() || !kindOrder.get(kindOrder.size() - 1).equals(s.get("kind").asText())) {
                kindOrder.add(s.get("kind").asText());
            }
            assertThat(keys.add(s.get("key").asText())).as("unique key " + s.get("key")).isTrue();
            assertThat(s.get("key").asText()).matches("^[a-z][a-z0-9_]{1,39}$");
            assertThat(s.get("name").asText().length()).isBetween(1, 120);
            assertThat(s.get("description").asText()).isNotBlank();
            assertThat(s.get("fields").size()).as(s.get("key").asText() + " has a field list").isPositive();
            assertThat(s.get("usedBy").size()).as(s.get("key").asText() + " is used by a department").isPositive();
            fields += s.get("fields").size();
        }
        assertThat(kinds).containsEntry("COMMON", 14).containsEntry("SPECIALTY", 78).containsEntry("COMPLIANCE", 8);
        assertThat(kindOrder).containsExactly("COMMON", "SPECIALTY", "COMPLIANCE");
        assertThat(fields).isEqualTo(313);
        List<String> common = new ArrayList<>();
        sections.forEach(s -> {
            if ("COMMON".equals(s.get("kind").asText())) {
                common.add(s.get("name").asText());
            }
        });
        assertThat(common).containsExactly("Vitals", "Chief complaints", "History of present illness", "Past, family & personal history",
                "Allergies", "General & systemic examination", "Diagnosis", "Investigations", "Prescription (Rx)", "Advice & diet",
                "Procedures", "Follow-up", "Referral", "Attachments & images");
        List<String> compliance = new ArrayList<>();
        sections.forEach(s -> {
            if ("COMPLIANCE".equals(s.get("kind").asText())) {
                compliance.add(s.get("name").asText());
            }
        });
        assertThat(compliance).containsExactlyInAnyOrder("ABHA / ABDM link", "Consent forms", "Prescription print format",
                "PCPNDT Form F", "Scheme / insurance (PM-JAY, MJPJAY)", "Surgical consent", "WHO surgical safety checklist",
                "Insurance pre-authorisation");
    }

    @Test
    void everyFieldHasALabelAFixedTypeAMetaTextARequiredFlagAndTheValueKindTheRendererUses() throws Exception {
        Map<String, String> kindOfType = Map.of("TEXT", "TEXT", "RICH_TEXT", "TEXT", "NUMBER", "NUMBER", "NUMBER_UNIT", "NUMBER_UNIT",
                "SEARCH_LIST", "ITEM_LIST", "DROPDOWN", "CHOICE", "MULTI_SELECT", "MULTI_CHOICE", "FILE", "LATER", "CALCULATED", "DISPLAY_ONLY",
                "TABLE", "TABLE");
        Set<String> typesSeen = new TreeSet<>();
        for (JsonNode s : library("OWNER")) {
            for (JsonNode f : s.get("fields")) {
                assertThat(f.get("label").asText()).isNotBlank();
                assertThat(f.get("key").asText()).matches("^[a-z][a-z0-9_]{0,39}$");
                assertThat(FIELD_TYPES).as(s.get("key") + "." + f.get("key")).contains(f.get("type").asText());
                typesSeen.add(f.get("type").asText());
                assertThat(f.get("meta").isTextual()).isTrue();
                assertThat(f.get("required").isBoolean()).isTrue();
                assertThat(f.get("valueKind").asText()).isNotBlank();
                String expectedKind = kindOfType.get(f.get("type").asText());
                if (expectedKind != null) {
                    assertThat(f.get("valueKind").asText()).as(f.get("type") + " is stored as " + expectedKind).isEqualTo(expectedKind);
                }
                assertThat(Set.of("NONE", "FIELD", "MASTER", "SERVICES")).contains(f.get("optionsSource").asText());
                assertThat(f.get("master").isNull()).as("a master only for the MASTER source")
                        .isEqualTo(!"MASTER".equals(f.get("optionsSource").asText()));
                if ("FIELD".equals(f.get("optionsSource").asText())) {
                    assertThat(f.get("options").size()).isPositive();
                } else {
                    assertThat(f.get("options").size()).isZero();
                }
            }
        }
        assertThat(typesSeen).hasSize(30);
    }

    @Test
    void aRequiredChoiceListWithNoneOrNothingAbnormalCanBeCompletedWhenThereIsNoFinding() throws Exception {
        JsonNode modalities = get(LIBRARY + "/modalities", clinicOf(this).owner());
        JsonNode modality = field(modalities, "modality");
        assertThat(modality.get("required").asBoolean()).isTrue();
        assertThat(texts(modality.get("options"), "label")).contains("None");
        long none = 0;
        for (JsonNode o : modality.get("options")) {
            none += o.get("isNone").asBoolean() ? 1 : 0;
        }
        assertThat(none).isEqualTo(1);
        for (JsonNode s : library("OWNER")) {
            for (JsonNode f : s.get("fields")) {
                if (f.get("required").asBoolean() && Set.of("MULTI_SELECT", "CHECKLIST").contains(f.get("type").asText())
                        && f.get("options").size() > 0) {
                    long count = 0;
                    for (JsonNode o : f.get("options")) {
                        count += o.get("isNone").asBoolean() ? 1 : 0;
                    }
                    assertThat(count).as(s.get("key") + "." + f.get("key")).isEqualTo(1);
                }
            }
        }
    }

    private static JsonNode field(JsonNode section, String key) {
        for (JsonNode f : section.get("fields")) {
            if (key.equals(f.get("key").asText())) {
                return f;
            }
        }
        throw new AssertionError("no field " + key + " in " + section.get("key"));
    }

    @Test
    void oneSectionIsReadByKeyAndOnlyAllergiesCannotBeHidden() throws Exception {
        JsonNode vitals = get(LIBRARY + "/vitals", clinicOf(this).owner());
        assertThat(vitals.get("name").asText()).isEqualTo("Vitals");
        assertThat(vitals.get("kind").asText()).isEqualTo("COMMON");
        assertThat(texts(vitals.get("fields"), "label")).startsWith("BP systolic", "BP diastolic", "Pulse");
        assertThat(field(vitals, "bp_systolic").get("type").asText()).isEqualTo("NUMBER");
        assertThat(field(vitals, "bp_systolic").get("meta").asText()).isEqualTo("mmHg · alert above 140");
        assertThat(field(vitals, "bp_systolic").get("required").asBoolean()).isTrue();
        assertThat(field(vitals, "bmi").get("valueKind").asText()).isEqualTo("DISPLAY_ONLY");
        List<String> locked = new ArrayList<>();
        library("OWNER").forEach(s -> {
            if (s.get("alwaysShown").asBoolean()) {
                locked.add(s.get("key").asText());
            }
        });
        assertThat(locked).containsExactly("allergy");
    }

    @Test
    void anUnknownSectionKeyIs404SectionNotFoundAndAMalformedOneIs400() throws Exception {
        Clinic clinic = clinicOf(this);
        problemOf(HttpMethod.GET, LIBRARY + "/no_such_section", clinic.owner(), null, 404, "section-not-found");
        problemOf(HttpMethod.GET, LIBRARY + "/gp", clinic.owner(), null, 404, "section-not-found");
        problemOf(HttpMethod.GET, LIBRARY + "/Vitals", clinic.owner(), null, 400, "bad-request");
        problemOf(HttpMethod.GET, LIBRARY + "/v", clinic.owner(), null, 400, "bad-request");
        problemOf(HttpMethod.GET, LIBRARY + "/" + "a".repeat(41), clinic.owner(), null, 400, "bad-request");
    }

    // ---------------- writes are refused ----------------

    @Test
    void everyWriteToTheLibraryOrTheStartersIs403ForEveryRoleTheOwnerIncludedAndNothingChanges() throws Exception {
        Clinic clinic = clinicOf(this);
        long sections = globalCount("select count(*) from opd.emr_section");
        long fields = globalCount("select count(*) from opd.emr_section_field");
        long starters = globalCount("select count(*) from opd.emr_starter_department");
        long placements = globalCount("select count(*) from opd.emr_starter_department_section");
        String body = "{\"key\":\"qa_new\",\"name\":\"Hacked section\",\"kind\":\"COMMON\"}";
        String[] paths = {LIBRARY, LIBRARY + "/vitals", LIBRARY + "/vitals/fields", LIBRARY + "/qa_new", STARTERS, STARTERS + "/gm",
                STARTERS + "/gm/sections/vitals", STARTERS + "/qa_new"};
        for (String role : ALL_ROLES) {
            for (String path : paths) {
                for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
                    problemOf(method, path, clinic.as(role), body, 403, "forbidden");
                }
            }
        }
        assertThat(globalCount("select count(*) from opd.emr_section")).isEqualTo(sections);
        assertThat(globalCount("select count(*) from opd.emr_section_field")).isEqualTo(fields);
        assertThat(globalCount("select count(*) from opd.emr_starter_department")).isEqualTo(starters);
        assertThat(globalCount("select count(*) from opd.emr_starter_department_section")).isEqualTo(placements);
        assertThat(globalCount("select count(*) from opd.emr_section where name = 'Hacked section'")).isZero();
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            problemOf(method, LIBRARY, anonymous(), body, 401, "unauthorized");
            problemOf(method, STARTERS + "/gm", anonymous(), body, 401, "unauthorized");
        }
    }

    // ---------------- the starter departments ----------------

    @Test
    void theFifteenStartersComeInMockupOrderWithInitialsColourAndTheWizardCounts() throws Exception {
        JsonNode starters = get(STARTERS, clinicOf(this).owner());
        assertThat(texts(starters, "starterKey")).containsExactly(STARTER_KEYS);
        assertThat(texts(starters, "name")).containsExactly("General Medicine", "Dental", "Obstetrics & Gynaecology", "Paediatrics",
                "Physiotherapy", "Orthopaedics", "Ophthalmology", "ENT", "Dermatology & Cosmetology", "Diabetology", "Cardiology",
                "AYUSH (Ayurveda)", "Medical Oncology", "General Surgery", "Psychiatry & Counselling");
        Map<String, Integer> sectionCount = Map.ofEntries(Map.entry("gm", 19), Map.entry("dental", 24), Map.entry("og", 25),
                Map.entry("paeds", 23), Map.entry("physio", 27), Map.entry("ortho", 22), Map.entry("eye", 22), Map.entry("ent", 22),
                Map.entry("derm", 20), Map.entry("diab", 22), Map.entry("cardio", 21), Map.entry("ayush", 21), Map.entry("onco", 27),
                Map.entry("surgery", 26), Map.entry("psych", 20));
        // the wizard card of the mockup: the number of Specialty sections (twelve of the fifteen are drawn)
        Map<String, Integer> specialtyDrawn = Map.ofEntries(Map.entry("gm", 2), Map.entry("dental", 7), Map.entry("og", 7),
                Map.entry("paeds", 6), Map.entry("physio", 10), Map.entry("ortho", 5), Map.entry("eye", 5), Map.entry("ent", 5),
                Map.entry("derm", 3), Map.entry("diab", 5), Map.entry("cardio", 4), Map.entry("ayush", 4));
        int total = 0;
        for (JsonNode s : starters) {
            String key = s.get("starterKey").asText();
            assertThat(s.get("initials").asText()).matches("^[A-Z0-9]{1,3}$");
            assertThat(s.get("colour").asText()).matches("^#[0-9A-F]{6}$");
            assertThat(s.get("sectionCount").asInt()).as(key).isEqualTo(sectionCount.get(key));
            total += s.get("sectionCount").asInt();
            long specialty = globalCount("select count(*) from opd.emr_starter_department_section p join opd.emr_section s "
                    + "on s.section_key = p.section_key where p.starter_key = '" + key + "' and s.kind = 'SPECIALTY'");
            assertThat(s.get("specialtySectionCount").asInt()).as(key + " specialty sections").isEqualTo((int) specialty);
            if (specialtyDrawn.containsKey(key)) {
                assertThat(s.get("specialtySectionCount").asInt()).as(key + " as drawn in the wizard").isEqualTo(specialtyDrawn.get(key));
            }
            if (!"physio".equals(key)) {
                assertThat(s.get("description").isNull()).as(key + " has no wizard description").isTrue();
            }
        }
        assertThat(total).isEqualTo(341);
        JsonNode physio = starters.get(4);
        assertThat(physio.get("description").asText()).isEqualTo(
                "Built for session-based care: assessment on the first visit, then quick session notes against a package.");
        assertThat(texts(physio.get("offAtStart"), null)).contains("Prescription (Rx)", "Investigations", "General & systemic examination",
                "Procedures");
        assertThat(texts(starters.get(0).get("offAtStart"), null)).contains("Procedures", "Referral");
    }

    @Test
    void aStarterIsReadWithEverySectionHiddenOnesTooInOrderWithItsStartingSettingsAndLocks() throws Exception {
        JsonNode gm = get(STARTERS + "/gm", clinicOf(this).owner());
        assertThat(gm.get("layoutVersion").asInt()).isEqualTo(1);
        assertThat(gm.get("sections").size()).isEqualTo(19);
        assertThat(sectionKeys(gm)).startsWith("vitals", "complaints", "hpi", "history", "allergy", "exam", "diagnosis");
        assertThat(sectionKeys(gm)).endsWith("abha", "consent", "rxformat");
        // "x of y sections on": 16 of 19 shown at start, 4 required
        long shown = 0;
        long required = 0;
        for (JsonNode s : gm.get("sections")) {
            shown += s.get("shown").asBoolean() ? 1 : 0;
            required += s.get("required").asBoolean() ? 1 : 0;
            assertThat(s.get("source").asText()).isEqualTo("LIBRARY");
            assertThat(s.get("fields").size()).isPositive();
            assertThat(s.get("whoCanFill").toString()).contains("DOCTOR");
        }
        assertThat(shown).isEqualTo(16);
        assertThat(required).isEqualTo(4);
        assertThat(section(gm, "procedures").get("shown").asBoolean()).isFalse();
        assertThat(section(gm, "referral").get("shown").asBoolean()).isFalse();
        assertThat(section(gm, "consent").get("shown").asBoolean()).isFalse();
        assertThat(section(gm, "complaints").get("required").asBoolean()).isTrue();
        assertThat(section(gm, "diagnosis").get("part").asText()).isEqualTo("TREATMENT");
        assertThat(section(gm, "vitals").get("print").asBoolean()).isTrue();
        assertThat(section(gm, "history").get("copy").asBoolean()).isTrue();
        assertThat(section(gm, "vitals").get("whoCanFill").toString()).contains("NURSE");
        assertThat(section(gm, "allergy").get("hideLocked").asBoolean()).isTrue();
        assertThat(section(gm, "allergy").get("hideLockReason").asText()).isNotBlank();
        assertThat(section(gm, "vitals").get("hideLocked").asBoolean()).isFalse();
        assertThat(section(gm, "vitals").get("hideLockReason").isNull()).isTrue();
        JsonNode og = get(STARTERS + "/og", clinicOf(this).owner());
        assertThat(section(og, "pcpndt").get("hideLocked").asBoolean()).as("PCPNDT Form F is locked in Obstetrics & Gynaecology").isTrue();
        assertThat(section(og, "pcpndt").get("required").asBoolean()).isTrue();
        assertThat(section(og, "consent").get("shown").asBoolean()).isTrue();
        JsonNode physio = get(STARTERS + "/physio", clinicOf(this).owner());
        assertThat(section(physio, "rx").get("shown").asBoolean()).isFalse();
        assertThat(section(physio, "rx").get("required").asBoolean()).isFalse();
        JsonNode dental = get(STARTERS + "/dental", clinicOf(this).owner());
        assertThat(section(dental, "dental_chart").get("required").asBoolean()).isTrue();
        assertThat(section(dental, "dental_chart").get("part").asText()).isEqualTo("TREATMENT");
        assertThat(section(dental, "dental_chart").get("copy").asBoolean()).isTrue();
        for (JsonNode s : dental.get("sections")) {
            assertThat(!s.get("shown").asBoolean() && s.get("required").asBoolean()).as("a hidden section is never required").isFalse();
        }
    }

    @Test
    void aStarterKeyThatIsNotOneOfTheFifteenIs404StarterNotFoundNeverAnEmptyTemplateAndDentalAndPaedsAreValidAgain() throws Exception {
        Clinic clinic = clinicOf(this);
        for (String key : new String[] {"gp", "homeo", "ayur", "physio2", "d_abcdefghij", "xx"}) {
            problemOf(HttpMethod.GET, STARTERS + "/" + key, clinic.owner(), null, 404, "starter-not-found");
        }
        for (String malformed : new String[] {"GM", "G", "1gm", "gm-1", "a".repeat(41)}) {
            problemOf(HttpMethod.GET, STARTERS + "/" + malformed, clinic.owner(), null, 400, "bad-request");
        }
        assertThat(get(STARTERS + "/dental", clinic.owner()).get("name").asText()).isEqualTo("Dental");
        assertThat(get(STARTERS + "/paeds", clinic.owner()).get("name").asText()).isEqualTo("Paediatrics");
        // the removed old paths of the withdrawn SAH-44 answer 404, the new starter paths answer 200
        assertThat(call(HttpMethod.GET, EMR + "/templates/dental", clinic.owner(), null).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, EMR + "/templates/paeds", clinic.owner(), null).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void thereIsNoHomeopathyStarterAndNoSectionOrStarterTextHoldsPatientData() throws Exception {
        JsonNode starters = get(STARTERS, clinicOf(this).owner());
        starters.forEach(s -> assertThat(s.get("name").asText().toLowerCase()).doesNotContain("homeo"));
        String everything = library("OWNER").toString() + starters;
        assertThat(everything).doesNotContainPattern("[6-9][0-9]{9}").doesNotContain("@");
    }

    @Test
    void aPublishedVersionKeepsItsOwnCopyOfEverySectionDefinitionSoALibraryChangeCannotAlterIt() throws Exception {
        Clinic clinic = newClinic();
        publishAsIs(clinic, "gm");
        List<List<String>> copies = rows(clinic, "select s ->> 'name', s ->> 'kind', s ->> 'description', "
                + "jsonb_array_length(s -> 'fields')::text from opd.emr_department_version v, "
                + "jsonb_array_elements(v.layout -> 'sections') s where v.version_no = 1");
        assertThat(copies).hasSize(19);
        for (List<String> copy : copies) {
            assertThat(copy.get(0)).isNotBlank();
            assertThat(copy.get(1)).isIn("COMMON", "SPECIALTY", "COMPLIANCE");
            assertThat(copy.get(2)).isNotBlank();
            assertThat(Integer.parseInt(copy.get(3))).isPositive();
        }
    }
}
