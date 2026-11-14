package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-54 through the real endpoints: add a department from a starter, blank or a copy, with a name, initials, colour,
 * Print flags, reviewed sections, doctors and rooms ({@code POST /emr/departments}), the doctor list of step 3
 * ({@code GET /emr/department-doctor-options}), the unique name, the limit of 50, and a department that stays a draft
 * until the owner publishes it. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrAddDepartmentIT extends EmrSupport {

    private JsonNode create(Clinic clinic, String body, int expected) throws Exception {
        return post(DEPARTMENTS, clinic.owner(), body, expected);
    }

    private static String key(JsonNode view) {
        return view.get("departmentKey").asText();
    }

    private static List<Boolean> flags(JsonNode view, String flag) {
        List<Boolean> out = new ArrayList<>();
        view.get("sections").forEach(s -> out.add(s.get(flag).asBoolean()));
        return out;
    }

    // ---------------- the three sources ----------------

    @Test
    void aStarterGivesADraftDepartmentWithTheStartersSectionsPartsOrderAndSettingsAndAServerMadeKey() throws Exception {
        Clinic clinic = newClinic();
        for (String starter : new String[] {"gm", "physio", "og", "psych"}) {
            JsonNode created = create(clinic, createBody("Annexe " + starter, starterSource(starter), List.of(), List.of(), true), 201);
            assertThat(key(created)).matches("^d_[a-z0-9]{10}$");
            assertThat(created.get("name").asText()).isEqualTo("Annexe " + starter);
            assertThat(created.get("sourceKind").asText()).isEqualTo("STARTER");
            assertThat(created.get("starterKey").asText()).isEqualTo(starter);
            assertThat(created.get("copiedFromKey").isNull()).isTrue();
            assertThat(created.get("status").asText()).isEqualTo("DRAFT");
            assertThat(created.get("publishedVersion").asInt()).isZero();
            assertThat(created.get("canEdit").asBoolean()).isTrue();
            JsonNode starterView = get(STARTERS + "/" + starter, clinic.owner());
            assertThat(sectionKeys(created)).as(starter + " order").isEqualTo(sectionKeys(starterView));
            for (String flag : new String[] {"part", "shown", "required", "print", "copy", "collapsed", "whoCanFill"}) {
                for (int i = 0; i < created.get("sections").size(); i++) {
                    assertThat(created.get("sections").get(i).get(flag)).as(starter + " " + flag + " #" + i)
                            .isEqualTo(starterView.get("sections").get(i).get(flag));
                }
            }
            assertThat(created.get("sectionsTotal").asInt()).isEqualTo(starterView.get("sectionCount").asInt());
        }
        // the same fifteen starter departments are still listed as templates, and the new ones after them by name
        JsonNode list = get(DEPARTMENTS, clinic.owner());
        assertThat(list.get("departments").size()).isEqualTo(19);
        assertThat(texts(list.get("departments"), "departmentKey").subList(0, 15)).containsExactly(STARTER_KEYS);
        assertThat(texts(list.get("departments"), "name").subList(15, 19)).containsExactly("Annexe gm", "Annexe og", "Annexe physio", "Annexe psych");
        assertThat(count(clinic, "select count(*) from opd.emr_department where source_kind = 'STARTER' and department_key ~ '^d_'")).isEqualTo(4);
    }

    @Test
    void aBlankDepartmentHasTheFourteenCommonSectionsAndTheThreeComplianceSectionsAndNoStarter() throws Exception {
        Clinic clinic = newClinic();
        JsonNode blank = create(clinic, createBody("Day care", BLANK_SOURCE, List.of(), List.of(), true), 201);
        assertThat(blank.get("sourceKind").asText()).isEqualTo("BLANK");
        assertThat(blank.get("starterKey").isNull()).isTrue();
        assertThat(blank.get("sections").size()).isEqualTo(17);
        List<String> kinds = new ArrayList<>();
        blank.get("sections").forEach(s -> kinds.add(s.get("kind").asText()));
        assertThat(Collections.frequency(kinds, "COMMON")).isEqualTo(14);
        assertThat(Collections.frequency(kinds, "COMPLIANCE")).isEqualTo(3);
        assertThat(Collections.frequency(kinds, "SPECIALTY")).isZero();
        assertThat(sectionKeys(blank)).endsWith("abha", "consent", "rxformat");
        assertThat(sectionKeys(blank)).doesNotContain("pcpndt", "dental_chart");
        assertThat(section(blank, "allergy").get("hideLocked").asBoolean()).isTrue();
        assertThat(section(blank, "complaints").get("required").asBoolean()).isTrue();
        assertThat(blank.get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void aCopyEqualsTheSourceDepartmentsSavedDraftElseItsLatestVersionAndNotItsHistoryAndTheSourceMustBeSaved() throws Exception {
        Clinic clinic = newClinic();
        // a starter the clinic never saved cannot be copied
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Copy of dental", copySource("dental"), List.of(), List.of(), true),
                404, "department-not-found");
        // gm: v1 hides chronic; the draft after it hides adult_vax too
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        publish(clinic, "gm", body, 200);
        ObjectNode draft = layoutInput(department(clinic, "gm"));
        setShown(draft, "adult_vax", false, false);
        saveDraft(clinic, "gm", draft, 200);
        JsonNode copy = create(clinic, createBody("Medicine two", copySource("gm"), List.of(), List.of(), true), 201);
        assertThat(copy.get("sourceKind").asText()).isEqualTo("COPY");
        assertThat(copy.get("copiedFromKey").asText()).isEqualTo("gm");
        assertThat(copy.get("starterKey").asText()).isEqualTo("gm");
        assertThat(section(copy, "chronic").get("shown").asBoolean()).isFalse();
        assertThat(section(copy, "adult_vax").get("shown").asBoolean()).as("the source's draft is copied").isFalse();
        assertThat(copy.get("publishedVersion").asInt()).as("not its history").isZero();
        assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + key(copy) + "/versions/1", clinic.owner(), null, 404, "version-not-found");
        // with no draft the latest version is copied
        publish(clinic, "gm", layoutInput(department(clinic, "gm")), 200);
        publishOneChange(clinic, "gm", "hpi", false);
        JsonNode latest = create(clinic, createBody("Medicine three", copySource("gm"), List.of(), List.of(), true), 201);
        assertThat(section(latest, "hpi").get("shown").asBoolean()).isFalse();
        // the copy of a copy works, and an unknown or malformed source is refused
        JsonNode second = create(clinic, createBody("Medicine four", copySource(key(copy)), List.of(), List.of(), true), 201);
        assertThat(second.get("copiedFromKey").asText()).isEqualTo(key(copy));
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Medicine five", copySource("d_nothingatal"), List.of(), List.of(), true),
                404, "department-not-found");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Medicine six", copySource("GM"), List.of(), List.of(), true),
                400, "validation");
    }

    @Test
    void theSourceMustBeCompleteAndOfTheRightKind() throws Exception {
        Clinic clinic = newClinic();
        String tail = ",\"copyPrintFlags\":true,\"doctorMembershipIds\":[],\"rooms\":[]}";
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"No source\"" + tail, 400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"Source without kind\",\"source\":{}" + tail, 400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"Odd kind\",\"source\":{\"kind\":\"IMPORT\"}" + tail, 400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"Starter no key\",\"source\":{\"kind\":\"STARTER\"}" + tail, 400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"Copy no key\",\"source\":{\"kind\":\"COPY\"}" + tail, 400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"Starter bad key\",\"source\":{\"kind\":\"STARTER\",\"starterKey\":\"GP\"}" + tail,
                400, "validation");
        // the retired template codes are not starters
        for (String retired : new String[] {"gp", "homeo", "ayur"}) {
            problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("From " + retired, starterSource(retired), List.of(), List.of(), true),
                    404, "starter-not-found");
        }
        // "neither for BLANK": a starter key or a copy key on a blank source is a 400 (docs/api/opd.yaml, EmrDepartmentCreateInput)
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(),
                createBody("Blank with key", "{\"kind\":\"BLANK\",\"starterKey\":\"gm\"}", List.of(), List.of(), true), 400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"No print flag\",\"source\":{\"kind\":\"BLANK\"},\"doctorMembershipIds\":[],\"rooms\":[]}",
                400, "validation");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"name\":\"Extra\",\"source\":{\"kind\":\"BLANK\"},\"copyPrintFlags\":true,"
                + "\"doctorMembershipIds\":[],\"rooms\":[],\"label\":\"x\"}", 400, "validation");
        assertThat(count(clinic, "select count(*) from opd.emr_department")).isZero();
        assertThat(emrAudits(clinic)).isZero();
    }

    // ---------------- print flags, reviewed sections ----------------

    @Test
    void withoutTheMatchingPrintTemplateEveryPrintFlagIsClearedAndNoSeparatePrintTemplateExists() throws Exception {
        Clinic clinic = newClinic();
        JsonNode with = create(clinic, createBody("Print yes", starterSource("gm"), List.of(), List.of(), true), 201);
        JsonNode without = create(clinic, createBody("Print no", starterSource("gm"), List.of(), List.of(), false), 201);
        assertThat(flags(with, "print")).contains(true);
        assertThat(flags(without, "print")).containsOnly(false);
        assertThat(flags(without, "required")).as("only Print differs").isEqualTo(flags(with, "required"));
        assertThat(flags(without, "shown")).isEqualTo(flags(with, "shown"));
        assertThat(count(clinic, "select count(*) from information_schema.tables where table_schema = 'opd' and table_name like '%print%'")).isZero();
    }

    @Test
    void reviewedSectionsReplaceTheSourceSettingsAndFollowTheDraftRules() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode review = layoutInput(department(clinic, "gm"));
        review.remove("revision");
        review.remove("origin");
        setShown(review, "chronic", false, false);
        sec(review, "hpi").put("part", "TREATMENT");
        ObjectNode body = (ObjectNode) objectMapper.readTree(createBody("Reviewed", starterSource("gm"), List.of(), List.of(), true));
        body.set("sections", review.get("sections").deepCopy());
        JsonNode created = create(clinic, body.toString(), 201);
        assertThat(section(created, "chronic").get("shown").asBoolean()).isFalse();
        assertThat(section(created, "hpi").get("part").asText()).isEqualTo("TREATMENT");
        // the rules of Save draft apply: hidden and required, a lock, a missing section, a foreign section
        ObjectNode hiddenRequired = body.deepCopy();
        setShown((ObjectNode) hiddenRequired, "diagnosis", false, true);
        hiddenRequired.put("name", "Reviewed two");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), hiddenRequired.toString(), 400, "hidden-section-required");
        ObjectNode locked = body.deepCopy();
        locked.put("name", "Reviewed three");
        setShown(locked, "allergy", false, false);
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), locked.toString(), 400, "section-not-hideable");
        ObjectNode missing = body.deepCopy();
        missing.put("name", "Reviewed four");
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.get("sections")).remove(2);
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), missing.toString(), 400, "section-set-mismatch");
        ObjectNode foreign = body.deepCopy();
        foreign.put("name", "Reviewed five");
        ((ObjectNode) foreign.get("sections").get(0)).put("key", "dental_chart");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), foreign.toString(), 404, "section-not-found");
        assertThat(count(clinic, "select count(*) from opd.emr_department")).as("only the first one was made").isEqualTo(1);
    }

    // ---------------- name, initials, colour ----------------

    @Test
    void theNameIsUniqueInTheClinicIgnoringLetterCaseCountingTheFifteenStarterNames() throws Exception {
        Clinic clinic = newClinic();
        create(clinic, createBody("Skin and hair clinic", BLANK_SOURCE, List.of(), List.of(), true), 201);
        for (String taken : new String[] {"Skin and hair clinic", "SKIN AND HAIR CLINIC", "skin and hair clinic", "  Skin and hair clinic  ",
                "General Medicine", "general medicine", "DENTAL", "Obstetrics & Gynaecology", "ent", "AYUSH (Ayurveda)"}) {
            JsonNode problem = problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody(taken, BLANK_SOURCE, List.of(), List.of(), true),
                    400, "department-name-taken");
            assertThat(problem.get("detail").asText()).isEqualTo("A department with this name already exists.");
        }
        assertThat(count(clinic, "select count(*) from opd.emr_department")).isEqualTo(1);
        // another clinic may use the name
        Clinic other = newClinic();
        create(other, createBody("Skin and hair clinic", BLANK_SOURCE, List.of(), List.of(), true), 201);
    }

    @Test
    void anEmptyOrTooLongNameIs400AndSixtyCharactersAreAccepted() throws Exception {
        Clinic clinic = newClinic();
        for (String bad : new String[] {"", "   ", "x".repeat(61)}) {
            JsonNode problem = problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody(bad, BLANK_SOURCE, List.of(), List.of(), true),
                    400, "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("name");
        }
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{\"source\":{\"kind\":\"BLANK\"},\"copyPrintFlags\":true,\"doctorMembershipIds\":[],\"rooms\":[]}",
                400, "validation");
        JsonNode sixty = create(clinic, createBody("n".repeat(60), BLANK_SOURCE, List.of(), List.of(), true), 201);
        assertThat(sixty.get("name").asText()).hasSize(60);
        JsonNode trimmed = create(clinic, createBody("  Padded name  ", BLANK_SOURCE, List.of(), List.of(), true), 201);
        assertThat(trimmed.get("name").asText()).isEqualTo("Padded name");
    }

    @Test
    void twoRequestsWithTheSameNameAtTheSameMomentMakeOneDepartmentAndTheOtherIs400() throws Exception {
        Clinic clinic = newClinic();
        String body = createBody("Twin clinic", BLANK_SOURCE, List.of(), List.of(), true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        List<String> types = new ArrayList<>();
        try {
            List<Future<org.springframework.test.web.servlet.MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> call(HttpMethod.POST, DEPARTMENTS, clinic.owner(), body).andReturn()));
            }
            for (Future<org.springframework.test.web.servlet.MvcResult> f : futures) {
                statuses.add(f.get().getResponse().getStatus());
                types.add(f.get().getResponse().getContentAsString());
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(statuses);
        assertThat(statuses).containsExactly(201, 400);
        assertThat(types.stream().filter(t -> t.contains("department-name-taken")).count()).isEqualTo(1);
        assertThat(count(clinic, "select count(*) from opd.emr_department where lower(name) = 'twin clinic'")).isEqualTo(1);
    }

    @Test
    void initialsAndColourAreDerivedWhenNotSentAndValidatedWhenSent() throws Exception {
        Clinic clinic = newClinic();
        JsonNode derived = create(clinic, createBody("Skin and hair clinic", BLANK_SOURCE, List.of(), List.of(), true), 201);
        assertThat(derived.get("initials").asText()).isEqualTo("SAH");
        assertThat(derived.get("colour").asText()).matches("^#[0-9A-F]{6}$");
        JsonNode oneWord = create(clinic, createBody("Cardiac", BLANK_SOURCE, List.of(), List.of(), true), 201);
        assertThat(oneWord.get("initials").asText()).isEqualTo("CA");
        ObjectNode given = (ObjectNode) objectMapper.readTree(createBody("Chosen look", BLANK_SOURCE, List.of(), List.of(), true));
        given.put("initials", "CL");
        given.put("colour", "#123ABC");
        JsonNode chosen = create(clinic, given.toString(), 201);
        assertThat(chosen.get("initials").asText()).isEqualTo("CL");
        assertThat(chosen.get("colour").asText()).isEqualTo("#123ABC");
        int i = 0;
        for (String[] bad : new String[][] {{"initials", "abcd"}, {"initials", "ab"}, {"initials", ""}, {"initials", "A B"}, {"colour", "#abc"},
                {"colour", "123ABC"}, {"colour", "#12345G"}, {"colour", "#123abc"}}) {
            ObjectNode body = (ObjectNode) objectMapper.readTree(createBody("Bad look " + i++, BLANK_SOURCE, List.of(), List.of(), true));
            body.put(bad[0], bad[1]);
            JsonNode problem = problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), body.toString(), 400, "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo(bad[0]);
        }
    }

    // ---------------- rooms and doctors (step 3) ----------------

    @Test
    void roomsAreTextLabelsOfOneToFortyCharactersDifferentIgnoringCaseAndAtMostTen() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = create(clinic, createBody("Chairs", BLANK_SOURCE, List.of(), List.of("Chair 1", "Chair 2", "r".repeat(40)), true), 201);
        assertThat(texts(created.get("rooms"), null)).containsExactly("Chair 1", "Chair 2", "r".repeat(40));
        assertThat(texts(summaryOf(clinic, key(created)).get("rooms"), null)).hasSize(3);
        List<String> ten = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ten.add("Room " + i);
        }
        create(clinic, createBody("Ten rooms", BLANK_SOURCE, List.of(), ten, true), 201);
        List<String> eleven = new ArrayList<>(ten);
        eleven.add("Room 11");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Eleven rooms", BLANK_SOURCE, List.of(), eleven, true), 400, "validation");
        for (List<String> bad : List.of(List.of(""), List.of("   "), List.of("x".repeat(41)), List.of("Room 1", "ROOM 1"), List.of("Room 1", " room 1 "))) {
            problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Bad rooms", BLANK_SOURCE, List.of(), bad, true), 400, "validation");
        }
        assertThat(count(clinic, "select count(*) from opd.emr_department")).isEqualTo(2);
    }

    private JsonNode summaryOf(Clinic clinic, String key) throws Exception {
        for (JsonNode d : get(DEPARTMENTS, clinic.owner()).get("departments")) {
            if (key.equals(d.get("departmentKey").asText())) {
                return d;
            }
        }
        throw new AssertionError("no department " + key);
    }

    @Test
    void theDoctorListOffersOnlyActiveDoctorsAndVisitingDoctorsOfThisClinicByNameWithTheirDegree() throws Exception {
        Clinic clinic = newClinic();
        Clinic other = newClinic();
        Staff first = addStaff(clinic, "DOCTOR", "Dr Zed Last", "Dental", "BDS", "REG-" + shortId());
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Alpha First", "Paediatrician", "MD", "REG-" + shortId());
        Staff nurse = addNonDoctor(clinic, "NURSE");
        addNonDoctor(clinic, "BILLING");
        Staff gone = addStaff(clinic, "DOCTOR", "Dr Gone Away", "Dental", "BDS", "REG-" + shortId());
        setMembershipStatusOf(clinic, gone, "REMOVED");
        Staff foreign = addStaff(other, "DOCTOR", "Dr Other Clinic", "Dental", "BDS", "REG-" + shortId());
        JsonNode options = get(DOCTOR_OPTIONS, clinic.owner());
        List<String> ids = texts(options, "membershipId");
        assertThat(ids).contains(first.membershipId().toString(), visiting.membershipId().toString(), clinic.doctorMembershipId().toString());
        assertThat(ids).doesNotContain(nurse.membershipId().toString(), gone.membershipId().toString(), foreign.membershipId().toString());
        for (JsonNode o : options) {
            assertThat(o.get("role").asText()).isIn("DOCTOR", "VISITING_DOCTOR");
            assertThat(o.has("degree")).isTrue();
            assertThat(o.get("name").asText()).isNotBlank();
        }
        List<String> names = texts(options, "name");
        assertThat(names.indexOf("Dr Alpha First")).isLessThan(names.indexOf("Dr Zed Last"));
        assertThat(options.get(names.indexOf("Dr Zed Last")).get("degree").asText()).isEqualTo("BDS");
        // the owner is not offered unless the owner is a doctor by role; the owner's own membership never appears as OWNER
        for (JsonNode o : options) {
            assertThat(o.get("role").asText()).isNotEqualTo("OWNER");
        }
        for (String role : NON_OWNER_ROLES) {
            problemOf(HttpMethod.GET, DOCTOR_OPTIONS, clinic.as(role), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, DOCTOR_OPTIONS, anonymous(), null, 401, "unauthorized");
    }

    @Test
    void doctorsAreAssignedWhenTheyAreActiveDoctorsOfThisClinicAndAPersonWhoIsNotOneOrFromAnotherClinicIs400() throws Exception {
        Clinic clinic = newClinic();
        Clinic other = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Staff visiting = addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting One", "Dental", "BDS", "REG-" + shortId());
        Staff nurse = addNonDoctor(clinic, "NURSE");
        Staff frontDesk = addNonDoctor(clinic, "FRONT_DESK");
        Staff foreign = addDoctor(other, "Dental");
        JsonNode created = create(clinic, createBody("Two doctors", BLANK_SOURCE, List.of(doctor.membershipId(), visiting.membershipId()), List.of(), true), 201);
        assertThat(summaryOf(clinic, key(created)).get("doctorCount").asInt()).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from opd.emr_department_doctor")).isEqualTo(2);
        // a doctor may belong to more than one department
        JsonNode second = create(clinic, createBody("Doctor in two", BLANK_SOURCE, List.of(doctor.membershipId()), List.of(), true), 201);
        assertThat(summaryOf(clinic, key(second)).get("doctorCount").asInt()).isEqualTo(1);
        assertThat(count(clinic, "select count(*) from opd.emr_department_doctor where doctor_membership_id = '" + doctor.membershipId() + "'")).isEqualTo(2);
        // nobody assigned is accepted
        assertThat(summaryOf(clinic, key(create(clinic, createBody("Nobody yet", BLANK_SOURCE, List.of(), List.of(), true), 201))).get("doctorCount").asInt()).isZero();
        long departments = count(clinic, "select count(*) from opd.emr_department");
        for (UUID bad : new UUID[] {nurse.membershipId(), frontDesk.membershipId(), foreign.membershipId(), owner(clinic).membershipId(), UUID.randomUUID()}) {
            JsonNode problem = problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Bad doctor " + bad, BLANK_SOURCE, List.of(bad), List.of(), true),
                    400, "validation");
            assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo("doctorMembershipIds");
        }
        // one good and one bad: nothing at all is stored
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Half good", BLANK_SOURCE, List.of(doctor.membershipId(), foreign.membershipId()), List.of(), true),
                400, "validation");
        // the same doctor twice
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("Twice", BLANK_SOURCE, List.of(doctor.membershipId(), doctor.membershipId()), List.of(), true),
                400, "validation");
        assertThat(count(clinic, "select count(*) from opd.emr_department")).isEqualTo(departments);
        assertThat(count(other, "select count(*) from opd.emr_department_doctor")).isZero();
    }

    // ---------------- limit, audit, status ----------------

    @Test
    void aClinicCanAddFiftyDepartmentsAndTheFiftyFirstIs400DepartmentLimit() throws Exception {
        Clinic clinic = newClinic();
        for (int i = 1; i <= 50; i++) {
            create(clinic, createBody("Added department " + i, BLANK_SOURCE, List.of(), List.of(), true), 201);
        }
        JsonNode problem = problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), createBody("One too many", BLANK_SOURCE, List.of(), List.of(), true),
                400, "department-limit");
        assertThat(problem.get("detail").asText()).isEqualTo("A clinic can add up to 50 departments.");
        assertThat(count(clinic, "select count(*) from opd.emr_department")).isEqualTo(50);
        assertThat(get(DEPARTMENTS, clinic.owner()).get("departments").size()).as("15 starters and 50 added").isEqualTo(65);
        // the fifteen starters are not counted: saving one of them is still possible
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        assertThat(saveDraft(clinic, "gm", body, 200).get("outcome").asText()).isEqualTo("SAVED");
        // another clinic is not affected
        create(newClinic(), createBody("One of many", BLANK_SOURCE, List.of(), List.of(), true), 201);
    }

    @Test
    void creatingOneAuditEntryWithWhoTheNewDepartmentNameAndSourceAndNoPatientDataAndDoctorsDoNotSeeItUntilPublished() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        JsonNode created = create(clinic, createBody("Audit clinic", starterSource("physio"), List.of(doctor.membershipId()), List.of("Room 2"), true), 201);
        List<List<String>> audits = auditRows(clinic, "EMR_DEPARTMENT_CREATED");
        assertThat(audits).hasSize(1);
        JsonNode after = objectMapper.readTree(audits.get(0).get(2));
        assertThat(after.get("departmentKey").asText()).isEqualTo(key(created));
        assertThat(after.get("name").asText()).isEqualTo("Audit clinic");
        assertThat(after.get("sourceKind").asText()).isEqualTo("STARTER");
        assertThat(after.get("starterKey").asText()).isEqualTo("physio");
        assertThat(audits.get(0).get(3)).isEqualTo(clinic.ownerUserId().toString());
        // the owner reads it in the configurator as a draft; doctors cannot read a layout for it yet
        assertThat(department(clinic, key(created)).get("status").asText()).isEqualTo("DRAFT");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + key(created) + "/layout", doctor.token(clinic), null, 404, "department-not-published");
        // the first publish makes version 1 and the department Live
        JsonNode published = publishAsIs(clinic, key(created));
        assertThat(published.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(published.get("department").get("status").asText()).isEqualTo("LIVE");
        assertThat(get(DEPARTMENTS + "/" + key(created) + "/layout", doctor.token(clinic)).get("layoutSource").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void theClinicIdInTheBodyIsIgnoredAndAnotherClinicsDepartmentCannotBeCopied() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ObjectNode body = (ObjectNode) objectMapper.readTree(createBody("Mine", BLANK_SOURCE, List.of(), List.of(), true));
        body.put("tenantId", b.tenantId().toString());
        body.put("clinicId", b.tenantId().toString());
        create(a, body.toString(), 201);
        assertThat(count(a, "select count(*) from opd.emr_department")).isEqualTo(1);
        assertThat(count(b, "select count(*) from opd.emr_department")).isZero();
        JsonNode theirs = create(b, createBody("Theirs", BLANK_SOURCE, List.of(), List.of(), true), 201);
        problemOf(HttpMethod.POST, DEPARTMENTS, a.owner(), createBody("Steal", copySource(key(theirs)), List.of(), List.of(), true), 404, "department-not-found");
        assertThat(get(DEPARTMENTS, a.owner()).get("departments").size()).isEqualTo(16);
    }

    @Test
    void anEmptyOrBrokenBodyIs400AndABodyOverSixtyFourKilobytesIs413() throws Exception {
        Clinic clinic = newClinic();
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "", 400, "malformed-request");
        problemOf(HttpMethod.POST, DEPARTMENTS, clinic.owner(), "{broken", 400, "malformed-request");
        ObjectNode big = (ObjectNode) objectMapper.readTree(createBody("Big", BLANK_SOURCE, List.of(), List.of(), true));
        big.put("padding", "x".repeat(70_000));
        assertThat(call(HttpMethod.POST, DEPARTMENTS, clinic.owner(), big.toString()).andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(count(clinic, "select count(*) from opd.emr_department")).isZero();
    }
}
