package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-43-1 on the real database: search of the complaints, investigations and advice masters
 * ({@code GET /api/v1/opd/emr/masters/{master}}), the additive {@code specialty} and {@code page} parameters of
 * {@code GET /diagnoses}, the unchanged {@code GET /medicines}, the 403 on every write to a global path, and the
 * seed hygiene rules. Expected lists are worked out here from the story and from plain reads of the master tables,
 * never from the application's own query. Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrMastersIT extends EmrSupport {

    /** {master key, table}. */
    private static final String[][] MASTER_TABLES = {
            {"complaints", "opd.emr_complaint"}, {"investigations", "opd.emr_investigation"}, {"advice", "opd.emr_advice"}};

    private static Clinic shared;

    /** The masters hold no clinic data and no test here changes anything, so one clinic serves them all. */
    private static synchronized Clinic clinicOf(EmrMastersIT test) throws Exception {
        if (shared == null) {
            shared = test.newClinic();
        }
        return shared;
    }

    private record Item(String name, String hint, int sortOrder) {
    }

    private List<Item> allItems(String table) throws Exception {
        List<Item> items = new ArrayList<>();
        globalRows("select name, coalesce(specialty_hint, ''), sort_order from " + table)
                .forEach(r -> items.add(new Item(r.get(0), r.get(1), Integer.parseInt(r.get(2)))));
        return items;
    }

    /** The order the story asks for: items tagged for the specialty first, then the master's own order. */
    private static List<String> expectedOrder(List<Item> items, String specialty) {
        return items.stream()
                .sorted(Comparator.comparing((Item i) -> specialty != null && specialty.equals(i.hint()) ? 0 : 1)
                        .thenComparingInt(Item::sortOrder)
                        .thenComparing(i -> i.name().toLowerCase(Locale.ROOT)))
                .map(Item::name).toList();
    }

    private JsonNode search(String master, String query, String role) throws Exception {
        Clinic clinic = clinicOf(this);
        return get(MASTERS + master + (query == null ? "" : "?" + query), clinic.as(role));
    }

    // ---------------- who may search ----------------

    @Test
    void everyClinicRoleSearchesEveryMasterAndGetsTheContractShape() throws Exception {
        for (String[] master : MASTER_TABLES) {
            for (String role : ALL_ROLES) {
                JsonNode page = search(master[0], null, role);
                assertThat(page.get("items").size()).as(master[0] + " as " + role).isEqualTo(20);
                assertThat(page.get("page").asInt()).isZero();
                assertThat(page.get("pageSize").asInt()).isEqualTo(20);
                assertThat(page.get("total").asLong()).isEqualTo(globalCount("select count(*) from " + master[1]));
                JsonNode first = page.get("items").get(0);
                assertThat(UUID.fromString(first.get("id").asText())).isNotNull();
                assertThat(first.get("name").asText()).isNotBlank();
                assertThat(first.has("specialtyHint")).as("hint is sent, null when general").isTrue();
            }
        }
    }

    @Test
    void notSignedInAnswers401OnEveryEmrReadPath() throws Exception {
        for (String path : new String[] {MASTERS + "complaints", MASTERS + "investigations", MASTERS + "advice",
                QUICK + "complaints", QUICK + "diagnoses", ME, LIBRARY, LIBRARY + "/vitals", STARTERS, STARTERS + "/gm",
                DEPARTMENTS, DEPARTMENTS + "/gm", DEPARTMENTS + "/gm/layout", DEPARTMENTS + "/gm/reset-target",
                DEPARTMENTS + "/gm/versions/1", DOCTOR_OPTIONS, RECORDS + "/" + UUID.randomUUID(), DIAGNOSES + "?q=fev"}) {
            JsonNode problem = problemOf(HttpMethod.GET, path, anonymous(), null, 401, "unauthorized");
            assertThat(problem.get("status").asInt()).isEqualTo(401);
        }
    }

    @Test
    void anOddMasterNameIsABadRequestAndDiagnosesAndMedicinesKeepTheirOwnSearch() throws Exception {
        Clinic clinic = clinicOf(this);
        for (String master : new String[] {"diagnoses", "medicines", "zzz", "Complaints", "complaints%20x"}) {
            problemOf(HttpMethod.GET, MASTERS + master, clinic.owner(), null, 400, "bad-request");
        }
    }

    // ---------------- prefix search ----------------

    @Test
    void aSearchMatchesTheStartOfTheNameInAnyLetterCaseAndNotTheMiddle() throws Exception {
        for (String[] master : MASTER_TABLES) {
            List<Item> items = allItems(master[1]);
            String sample = items.get(0).name().toLowerCase(Locale.ROOT);
            String prefix = sample.substring(0, 3).strip();
            for (String typed : new String[] {prefix, prefix.toUpperCase(Locale.ROOT),
                    Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1), "  " + prefix + "  "}) {
                JsonNode page = search(master[0], "q=" + enc(typed), "NURSE");
                long expected = globalCount("select count(*) from " + master[1] + " where lower(name) like ? escape '\\'",
                        prefix + "%");
                assertThat(page.get("total").asLong()).as(master[0] + " '" + typed + "'").isEqualTo(expected);
                assertThat(texts(page.get("items"), "name")).isNotEmpty()
                        .allMatch(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix));
                if (expected <= 20) {
                    assertThat(texts(page.get("items"), "name")).contains(items.get(0).name());
                }
            }
        }
    }

    @Test
    void aWordInTheMiddleOfANameDoesNotMatch() throws Exception {
        // "pain" occurs inside names such as "Stomach pain"; only names that START with it may come back
        JsonNode page = search("complaints", "q=pain", "DOCTOR");
        long startsWith = globalCount("select count(*) from opd.emr_complaint where lower(name) like 'pain%'");
        long contains = globalCount("select count(*) from opd.emr_complaint where lower(name) like '%pain%'");
        assertThat(contains).as("the seed has names with pain in the middle").isGreaterThan(startsWith);
        assertThat(page.get("total").asLong()).isEqualTo(startsWith);
        assertThat(texts(page.get("items"), "name")).allMatch(n -> n.toLowerCase(Locale.ROOT).startsWith("pain"));
    }

    @Test
    void nothingMatchingIsAnEmptyListNotAnError() throws Exception {
        for (String[] master : MASTER_TABLES) {
            JsonNode page = search(master[0], "q=zzzzqqqq", "FRONT_DESK");
            assertThat(page.get("items").size()).isZero();
            assertThat(page.get("total").asLong()).isZero();
            assertThat(page.get("page").asInt()).isZero();
            assertThat(page.get("pageSize").asInt()).isEqualTo(20);
        }
    }

    // ---------------- no text, paging ----------------

    @Test
    void noTextReturnsTheFirstItemsInTheMastersOwnOrderAndPagesWalkTheWholeList() throws Exception {
        for (String[] master : MASTER_TABLES) {
            List<String> expected = expectedOrder(allItems(master[1]), null);
            List<String> walked = new ArrayList<>();
            for (int page = 0; page < 6; page++) {
                JsonNode result = search(master[0], page == 0 ? null : "page=" + page, "BILLING");
                assertThat(result.get("page").asInt()).isEqualTo(page);
                assertThat(result.get("total").asLong()).isEqualTo(expected.size());
                int from = Math.min(page * 20, expected.size());
                int to = Math.min(from + 20, expected.size());
                assertThat(texts(result.get("items"), "name")).as(master[0] + " page " + page)
                        .isEqualTo(expected.subList(from, to));
                walked.addAll(texts(result.get("items"), "name"));
            }
            assertThat(walked).as("every item once, none twice").isEqualTo(expected);
            assertThat(search(master[0], "page=500", "BILLING").get("items").size()).as("a far page is empty").isZero();
        }
    }

    @Test
    void aSpaceOnlyTextIsTreatedAsNoText() throws Exception {
        List<String> expected = expectedOrder(allItems("opd.emr_complaint"), null).subList(0, 20);
        for (String typed : new String[] {"%20", "%20%20%20%20", "%09"}) {
            assertThat(texts(search("complaints", "q=" + typed, "OWNER").get("items"), "name")).isEqualTo(expected);
        }
        assertThat(texts(search("complaints", "q=", "OWNER").get("items"), "name")).isEqualTo(expected);
    }

    @Test
    void aPageOutsideZeroToFiveHundredIsAValidationProblem() throws Exception {
        Clinic clinic = clinicOf(this);
        for (String page : new String[] {"-1", "501", "100000"}) {
            JsonNode problem = problemOf(HttpMethod.GET, MASTERS + "complaints?page=" + page, clinic.owner(), null, 400,
                    "validation");
            assertThat(fieldOf(problem, "page")).isNotNull();
        }
        assertThat(call(HttpMethod.GET, MASTERS + "complaints?page=abc", clinic.owner(), null).andReturn().getResponse()
                .getStatus()).isEqualTo(400);
    }

    // ---------------- specialty hint ----------------

    @Test
    void aSpecialtyPutsItsTaggedItemsFirstAndHidesNothing() throws Exception {
        boolean anyHintSeen = false;
        for (String[] master : MASTER_TABLES) {
            List<Item> items = allItems(master[1]);
            for (String specialty : STARTER_KEYS) {
                List<String> expected = expectedOrder(items, specialty);
                long tagged = items.stream().filter(i -> specialty.equals(i.hint())).count();
                anyHintSeen |= tagged > 0;
                List<String> walked = new ArrayList<>();
                long total = -1;
                for (int page = 0; page < 3; page++) {
                    JsonNode result = search(master[0], "specialty=" + specialty + "&page=" + page, "PHARMACIST");
                    total = result.get("total").asLong();
                    walked.addAll(texts(result.get("items"), "name"));
                }
                assertThat(total).as("nothing is hidden by a specialty").isEqualTo(items.size());
                assertThat(walked).as(master[0] + " " + specialty).isEqualTo(expected);
                List<String> hintsFirst = texts(search(master[0], "specialty=" + specialty, "OWNER").get("items"),
                        "specialtyHint");
                for (int i = 0; i < Math.min(tagged, 20); i++) {
                    assertThat(hintsFirst.get(i)).isEqualTo(specialty);
                }
            }
        }
        assertThat(anyHintSeen).as("the seed tags at least some items for a specialty, so this test is not empty").isTrue();
    }

    @Test
    void aSpecialtyOutsideTheFifteenStarterKeysIsAValidationProblem() throws Exception {
        Clinic clinic = clinicOf(this);
        // gp and homeo were the SAH-44 template codes: they are not starter keys and answer 400 like any other unknown key
        for (String specialty : new String[] {"ayur", "gp", "homeo", "GM", "Dental", "gm,dental", "physio2", "%20"}) {
            JsonNode problem = problemOf(HttpMethod.GET, MASTERS + "complaints?specialty=" + specialty, clinic.owner(),
                    null, 400, "validation");
            assertThat(fieldOf(problem, "specialty")).isNotNull();
        }
    }

    // ---------------- long and odd text ----------------

    @Test
    void textOverOneHundredCharactersIsRefusedAndNotEchoedAndExactlyOneHundredIsAnEmptyList() throws Exception {
        Clinic clinic = clinicOf(this);
        String tooLong = "q".repeat(101);
        JsonNode problem = problemOf(HttpMethod.GET, MASTERS + "complaints?q=" + tooLong, clinic.owner(), null, 400,
                "validation");
        assertThat(fieldOf(problem, "q")).isEqualTo("Search text must be 100 characters or fewer.");
        assertThat(problem.toString()).doesNotContain(tooLong);
        JsonNode edge = get(MASTERS + "complaints?q=" + "q".repeat(100), clinic.owner());
        assertThat(edge.get("items").size()).isZero();
        // 101 characters that are spaces around 100 letters count after trimming: still allowed
        assertThat(get(MASTERS + "complaints?q=" + enc("  " + "q".repeat(100) + "  "), clinic.owner()).get("total").asLong())
                .isZero();
    }

    @Test
    void percentUnderscoreBackslashAndQuoteTextNeverActsAsAWildcardOrBreaksTheQuery() throws Exception {
        Clinic clinic = clinicOf(this);
        for (String[] master : MASTER_TABLES) {
            for (String typed : new String[] {"%", "%%", "_", "__", "\\", "\\%", "c%", "C_ugh", "co_gh", "%ough", "_ever",
                    "a'b", "'; drop table opd.emr_complaint; --", "\"", "<script>", "ज्वर", "🙂"}) {
                JsonNode page = get(MASTERS + master[0] + "?q=" + enc(typed), clinic.owner());
                assertThat(page.get("total").asLong()).as(master[0] + " '" + typed + "' matches literally only").isZero();
                assertThat(page.get("items").size()).isZero();
            }
        }
        assertThat(globalCount("select count(*) from opd.emr_complaint")).as("nothing was dropped").isEqualTo(59);
    }

    @Test
    void aLiteralPercentOrUnderscoreMatchesOnlyANameThatReallyStartsWithIt() throws Exception {
        // the seed has no such name; the point is that "Co%" is NOT "Co" followed by anything
        Clinic clinic = clinicOf(this);
        assertThat(get(MASTERS + "complaints?q=" + enc("Co%"), clinic.owner()).get("total").asLong()).isZero();
        assertThat(get(MASTERS + "complaints?q=" + enc("Co"), clinic.owner()).get("total").asLong()).isPositive();
        assertThat(get(MASTERS + "complaints?q=" + enc("Fe_er"), clinic.owner()).get("total").asLong()).isZero();
    }

    @Test
    void aNulCharacterInTheTextIsNeverAServerError() throws Exception {
        Clinic clinic = clinicOf(this);
        int status = call(HttpMethod.GET, MASTERS + "complaints?q=%00", clinic.owner(), null).andReturn().getResponse()
                .getStatus();
        assertThat(status).as("a normal empty list or a bad request, never a 5xx").isIn(200, 400);
    }

    // ---------------- writes to a global path are refused ----------------

    @Test
    void everyWriteToAMasterOrTemplatePathIsRefusedWith403ForEveryRoleAndNothingChanges() throws Exception {
        Clinic clinic = clinicOf(this);
        long complaints = globalCount("select count(*) from opd.emr_complaint");
        long sections = globalCount("select count(*) from opd.emr_section");
        long starters = globalCount("select count(*) from opd.emr_starter_department");
        String body = "{\"name\":\"Hacked item\",\"label\":\"x\"}";
        String[] paths = {MASTERS + "complaints", MASTERS + "investigations", MASTERS + "advice",
                MASTERS + "complaints/" + UUID.randomUUID(), QUICK + "complaints", QUICK + "diagnoses",
                LIBRARY, LIBRARY + "/vitals", STARTERS, STARTERS + "/gm"};
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR",
                "VISITING_DOCTOR"}) {
            for (String path : paths) {
                for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                        HttpMethod.DELETE}) {
                    problemOf(method, path, clinic.as(role), body, 403, "forbidden");
                }
            }
        }
        assertThat(globalCount("select count(*) from opd.emr_complaint")).isEqualTo(complaints);
        assertThat(globalCount("select count(*) from opd.emr_section")).isEqualTo(sections);
        assertThat(globalCount("select count(*) from opd.emr_starter_department")).isEqualTo(starters);
        assertThat(globalCount("select count(*) from opd.emr_complaint where name = 'Hacked item'")).isZero();
    }

    @Test
    void aWriteWithoutASessionIs401NotAWorkingWrite() throws Exception {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            problemOf(method, MASTERS + "complaints", anonymous(), "{\"name\":\"x\"}", 401, "unauthorized");
            problemOf(method, LIBRARY + "/vitals", anonymous(), "{\"name\":\"x\"}", 401, "unauthorized");
            problemOf(method, STARTERS + "/gm", anonymous(), "{\"name\":\"x\"}", 401, "unauthorized");
        }
    }

    @Test
    void thereIsNoPublicWayToWriteUsageCounts() throws Exception {
        Clinic clinic = clinicOf(this);
        for (String path : new String[] {EMR + "/usage", EMR + "/quick-options/complaints/usage", EMR + "/usage-counts",
                EMR + "/me/usage"}) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH}) {
                int status = call(method, path, clinic.owner(), "{\"itemKey\":\"x\",\"useCount\":99}").andReturn()
                        .getResponse().getStatus();
                assertThat(status).as(method + " " + path).isBetween(400, 499);
            }
        }
        assertThat(usageRows(clinic)).isZero();
    }

    // ---------------- GET /diagnoses: additive parameters, SAH-7 behaviour kept ----------------

    private record Diagnosis(String code, String name, String specialty) {
    }

    private List<Diagnosis> allDiagnoses() throws Exception {
        List<Diagnosis> all = new ArrayList<>();
        globalRows("select code, name, coalesce(specialty, '') from opd.diagnosis order by lower(name), code")
                .forEach(r -> all.add(new Diagnosis(r.get(0), r.get(1), r.get(2))));
        return all;
    }

    private static List<String> codes(JsonNode list) {
        return texts(list, "code");
    }

    @Test
    void diagnosesWithoutTheNewParametersAreAnswerredAsInSah7() throws Exception {
        Clinic clinic = clinicOf(this);
        List<Diagnosis> all = allDiagnoses();
        JsonNode list = get(DIAGNOSES, clinic.owner());
        assertThat(list.size()).isEqualTo(20);
        assertThat(codes(list)).isEqualTo(all.stream().limit(20).map(Diagnosis::code).toList());
        assertThat(list.get(0).has("code") && list.get(0).has("name") && list.get(0).has("specialty")).isTrue();
        // the start of the name, in any case, and the start of the code
        JsonNode byName = get(DIAGNOSES + "?q=" + enc("FEVER"), clinic.owner());
        assertThat(byName.size()).isPositive();
        assertThat(texts(byName, "name")).allMatch(n -> n.toLowerCase(Locale.ROOT).startsWith("fever"));
        JsonNode byCode = get(DIAGNOSES + "?q=" + enc("j06"), clinic.owner());
        assertThat(codes(byCode)).contains("J06.9").allMatch(c -> c.startsWith("J06"));
        assertThat(get(DIAGNOSES + "?q=zzzzqqqq", clinic.owner()).size()).isZero();
        assertThat(get(DIAGNOSES + "?q=%25", clinic.owner()).size()).as("a literal percent matches nothing").isZero();
    }

    @Test
    void diagnosesPagesWalkAllFiftyThreeAndASpecialtyPutsItsTaggedRowsFirst() throws Exception {
        Clinic clinic = clinicOf(this);
        List<Diagnosis> all = allDiagnoses();
        assertThat(all).hasSize(53);
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            walked.addAll(codes(get(DIAGNOSES + "?page=" + page, clinic.owner())));
        }
        assertThat(walked).isEqualTo(all.stream().map(Diagnosis::code).toList());

        boolean anyTagged = false;
        // ADR-020 D13: the SAH-7 diagnosis tags are eight platform codes; a starter key orders by the code written here
        java.util.Map<String, String> tagOf = java.util.Map.of("gm", "gp", "og", "gynae", "derm", "derma", "ayush", "ayur",
                "dental", "dental", "paeds", "paeds", "physio", "physio");
        for (String key : STARTER_KEYS) {
            String specialty = tagOf.get(key);
            List<String> expected = new ArrayList<>(all.stream().filter(d -> specialty != null && specialty.equals(d.specialty()))
                    .map(Diagnosis::code).toList());
            anyTagged |= !expected.isEmpty();
            expected.addAll(all.stream().filter(d -> specialty == null || !specialty.equals(d.specialty())).map(Diagnosis::code).toList());
            List<String> got = new ArrayList<>();
            for (int page = 0; page < 4; page++) {
                got.addAll(codes(get(DIAGNOSES + "?specialty=" + key + "&page=" + page, clinic.owner())));
            }
            assertThat(got).as("diagnoses with starter " + key).isEqualTo(expected);
            assertThat(got).as("nothing hidden").hasSize(53);
        }
        assertThat(anyTagged).isTrue();
        assertThat(get(DIAGNOSES + "?page=3", clinic.owner()).size()).as("page 3 is past the 53 rows").isZero();
    }

    @Test
    void diagnosesRefuseASpecialtyOrPageOutOfRange() throws Exception {
        Clinic clinic = clinicOf(this);
        assertThat(fieldOf(problemOf(HttpMethod.GET, DIAGNOSES + "?specialty=ayur", clinic.owner(), null, 400, "validation"),
                "specialty")).isNotNull();
        assertThat(fieldOf(problemOf(HttpMethod.GET, DIAGNOSES + "?page=501", clinic.owner(), null, 400, "validation"),
                "page")).isNotNull();
        assertThat(fieldOf(problemOf(HttpMethod.GET, DIAGNOSES + "?page=-1", clinic.owner(), null, 400, "validation"),
                "page")).isNotNull();
    }

    // ---------------- GET /medicines unchanged; favourites per clinic ----------------

    @Test
    void medicinesStayAsInSah7AndOneClinicsFavouriteDoesNotMoveTheOthersOrder() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        JsonNode before = get(MEDICINES, a.owner());
        assertThat(before.get("total").asLong()).as("52 of SAH-7, Co-trimoxazole 960 mg of SAH-48 and 10 of SAH-49-71 (V2026_12_11_1020)").isEqualTo(63);
        assertThat(before.get("items").size()).isLessThanOrEqualTo(20);
        assertThat(before.get("items").get(0).get("favourite").asBoolean()).isFalse();
        List<String> orderB = texts(get(MEDICINES, b.owner()).get("items"), "id");
        assertThat(orderB).isEqualTo(texts(before.get("items"), "id"));

        String chosen = texts(before.get("items"), "id").get(7);
        ok(HttpMethod.PUT, "/api/v1/opd/medicine-favourites/" + chosen, a.owner(), null, 204);

        JsonNode afterA = get(MEDICINES, a.owner());
        assertThat(afterA.get("items").get(0).get("id").asText()).as("the favourite comes first for clinic A").isEqualTo(chosen);
        assertThat(afterA.get("items").get(0).get("favourite").asBoolean()).isTrue();
        assertThat(texts(get(MEDICINES, b.owner()).get("items"), "id")).as("clinic B is unchanged").isEqualTo(orderB);
        for (JsonNode m : get(MEDICINES, b.owner()).get("items")) {
            assertThat(m.get("favourite").asBoolean()).isFalse();
        }
        // the master lists are the same for both clinics
        assertThat(texts(get(MASTERS + "complaints", a.owner()).get("items"), "id"))
                .isEqualTo(texts(get(MASTERS + "complaints", b.owner()).get("items"), "id"));
        // a non-owner cannot mark a favourite (SAH-7, unchanged)
        problemOf(HttpMethod.PUT, "/api/v1/opd/medicine-favourites/" + chosen, a.as("DOCTOR"), null, 403, "forbidden");
    }

    // ---------------- the retired SAH-44 paths ----------------

    @Test
    void theRetiredTemplateAndSettingsPathsNoLongerExistAndAnswer404ForEveryRole() throws Exception {
        Clinic clinic = clinicOf(this);
        String[] paths = {EMR + "/templates", EMR + "/templates/gp", EMR + "/templates/dental", EMR + "/templates/paeds",
                EMR + "/templates/homeo", EMR + "/templates/gp/sections/vitals/override", EMR + "/templates/gp/reset",
                EMR + "/settings"};
        for (String role : new String[] {"OWNER", "DOCTOR", "NURSE"}) {
            for (String path : paths) {
                assertThat(call(HttpMethod.GET, path, clinic.as(role), null).andReturn().getResponse().getStatus())
                        .as("GET " + path + " as " + role).isEqualTo(404);
            }
        }
        for (String path : paths) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.PUT, HttpMethod.POST}) {
                assertThat(call(method, path, clinic.owner(), "{}").andReturn().getResponse().getStatus())
                        .as(method + " " + path).isEqualTo(404);
            }
        }
    }

    @Test
    void theAdviceMasterCarriesNullMarathiAndHindiNamesInTheSeedAndTheOtherMastersNeverDo() throws Exception {
        for (JsonNode item : search("advice", "page=2", "OWNER").get("items")) {
            assertThat(item.has("nameMr")).as("advice carries nameMr").isTrue();
            assertThat(item.has("nameHi")).as("advice carries nameHi").isTrue();
            assertThat(item.get("nameMr").isNull()).isTrue();
            assertThat(item.get("nameHi").isNull()).isTrue();
        }
        for (String master : new String[] {"complaints", "investigations"}) {
            for (JsonNode item : search(master, null, "OWNER").get("items")) {
                assertThat(item.path("nameMr").isNull() || item.path("nameMr").isMissingNode()).as(master).isTrue();
                assertThat(item.path("nameHi").isNull() || item.path("nameHi").isMissingNode()).as(master).isTrue();
            }
        }
    }

    // ---------------- seed hygiene (SAH-43-1, last criteria) ----------------

    @Test
    void noMasterItemLooksLikePatientDataAndEveryNameIsOneToTwoHundredCharacters() throws Exception {
        for (String[] master : MASTER_TABLES) {
            List<Item> items = allItems(master[1]);
            assertThat(items).isNotEmpty();
            List<String> lower = new ArrayList<>();
            for (Item item : items) {
                String name = item.name();
                assertThat(name.length()).as(master[1] + " name length").isBetween(1, 200);
                assertThat(name).as("no leading or trailing space").isEqualTo(name.strip());
                assertThat(name).as("no mobile number in " + name).doesNotContainPattern("[6-9][0-9]{9}");
                assertThat(name).as("no e-mail in " + name).doesNotContain("@");
                assertThat(name).as("no ABHA-like number in " + name).doesNotContainPattern("[0-9]{2}-[0-9]{4}-[0-9]{4}");
                assertThat(name).as("no person title in " + name).doesNotContainPattern("^(Mr|Mrs|Ms|Dr|Shri|Smt)\\.? ");
                lower.add(name.toLowerCase(Locale.ROOT));
            }
            assertThat(lower).as(master[1] + " names are unique ignoring letter case").doesNotHaveDuplicates();
        }
    }

    // ---------------- helpers ----------------

    private static String fieldOf(JsonNode problem, String field) {
        for (JsonNode e : problem.get("fieldErrors")) {
            if (field.equals(e.get("field").asText())) {
                return e.get("message").asText();
            }
        }
        return null;
    }
}
