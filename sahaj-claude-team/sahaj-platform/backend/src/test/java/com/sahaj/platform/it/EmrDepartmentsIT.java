package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-45 and SAH-53-2 to SAH-53-5 through the real endpoints: the department list and the configurator read, Save draft,
 * Publish (next version, "Nothing new to save", "Nothing to publish"), the rules on hidden, required and locked
 * sections, the order and the parts, the reset target, the published layout doctors read, the immutable versions, the
 * 409 on a stale revision, the owner-only writes and two clinics. The rows each call writes are read back by SQL as
 * {@code sahaj_app}. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrDepartmentsIT extends EmrSupport {

    private JsonNode listOf(Clinic clinic) throws Exception {
        return get(DEPARTMENTS, clinic.owner());
    }

    private static JsonNode summary(JsonNode list, String key) {
        for (JsonNode d : list.get("departments")) {
            if (key.equals(d.get("departmentKey").asText())) {
                return d;
            }
        }
        throw new AssertionError("no department " + key);
    }

    private long departmentRows(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from opd.emr_department");
    }

    private long versionRows(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from opd.emr_department_version");
    }

    private long audits(Clinic clinic, String action) throws Exception {
        return count(clinic, "select count(*) from platform.audit_event where action = '" + action + "'");
    }

    // ---------------- SAH-45-1 the list ----------------

    @Test
    void aNewClinicSeesTheFifteenStartersAsTemplateVersionZeroWithTheCountsOfShownAndRequiredSectionsOnly() throws Exception {
        Clinic clinic = newClinic();
        JsonNode list = listOf(clinic);
        assertThat(list.get("canEdit").asBoolean()).isTrue();
        assertThat(texts(list.get("departments"), "departmentKey")).containsExactly(STARTER_KEYS);
        for (JsonNode d : list.get("departments")) {
            String key = d.get("departmentKey").asText();
            assertThat(d.get("status").asText()).as(key).isEqualTo("TEMPLATE");
            assertThat(d.get("publishedVersion").asInt()).isZero();
            assertThat(d.get("hasDraft").asBoolean()).isFalse();
            assertThat(d.get("sourceKind").asText()).isEqualTo("STARTER");
            assertThat(d.get("starterKey").asText()).isEqualTo(key);
            assertThat(d.get("copiedFromKey").isNull()).isTrue();
            assertThat(d.get("doctorCount").asInt()).isZero();
            assertThat(d.get("rooms").size()).isZero();
            assertThat(d.get("initials").asText()).matches("^[A-Z0-9]{1,3}$");
            assertThat(d.get("colour").asText()).matches("^#[0-9A-F]{6}$");
            // the oracle is a plain read of the seed tables, not the application's own query
            List<String> expected = globalRows("select count(*)::text, (count(*) filter (where shown))::text, "
                    + "(count(*) filter (where shown and required))::text from opd.emr_starter_department_section where starter_key = '" + key + "'")
                    .get(0);
            assertThat(d.get("sectionsTotal").asText()).as(key + " total").isEqualTo(expected.get(0));
            assertThat(d.get("sectionsOn").asText()).as(key + " on").isEqualTo(expected.get(1));
            assertThat(d.get("requiredOn").asText()).as(key + " required").isEqualTo(expected.get(2));
        }
        JsonNode gm = summary(list, "gm");
        assertThat(gm.get("name").asText()).isEqualTo("General Medicine");
        assertThat(gm.get("sectionsTotal").asInt()).isEqualTo(19);
        assertThat(gm.get("sectionsOn").asInt()).isEqualTo(16);
        assertThat(gm.get("requiredOn").asInt()).isEqualTo(4);
        assertThat(departmentRows(clinic)).as("listing creates no row").isZero();
    }

    @Test
    void everyClinicRoleReadsTheListAndTheDepartmentButOnlyTheOwnerCanEdit() throws Exception {
        Clinic clinic = newClinic();
        java.util.Map<String, org.springframework.test.web.servlet.request.RequestPostProcessor> tokens = tokensOfEveryRole(clinic);
        for (String role : ALL_ROLES) {
            JsonNode list = get(DEPARTMENTS, tokens.get(role));
            assertThat(list.get("canEdit").asBoolean()).as(role).isEqualTo("OWNER".equals(role));
            JsonNode view = get(DEPARTMENTS + "/gm", tokens.get(role));
            assertThat(view.get("canEdit").asBoolean()).as(role).isEqualTo("OWNER".equals(role));
            assertThat(view.get("sections").size()).isEqualTo(19);
        }
        // the role comes from the membership, not from the token
        Staff doctor = addDoctor(clinic, "General physician");
        assertThat(get(DEPARTMENTS, doctor.tokenClaiming(clinic, "OWNER")).get("canEdit").asBoolean()).isFalse();
        problemOf(HttpMethod.GET, DEPARTMENTS, anonymous(), null, 401, "unauthorized");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/gm", anonymous(), null, 401, "unauthorized");
    }

    @Test
    void theDepartmentIsReadWithEverySectionHiddenOnesTooTheSixSettingsThePartTheLockAndTheReadOnlyFieldList() throws Exception {
        Clinic clinic = newClinic();
        JsonNode view = department(clinic, "gm");
        assertThat(view.get("revision").asLong()).isZero();
        assertThat(view.get("layoutSource").asText()).isEqualTo("STARTER");
        assertThat(view.get("sections").size()).isEqualTo(19);
        JsonNode complaints = section(view, "complaints");
        for (String field : new String[] {"key", "source", "name", "kind", "description", "part", "position", "shown", "required", "print",
                "copy", "collapsed", "whoCanFill", "hideLocked", "hideLockReason", "fields"}) {
            assertThat(complaints.has(field)).as(field).isTrue();
        }
        assertThat(complaints.get("fields").size()).isEqualTo(4);
        assertThat(complaints.get("fields").get(0).get("label").asText()).isEqualTo("Complaint");
        assertThat(section(view, "procedures").get("shown").asBoolean()).isFalse();
        assertThat(section(view, "allergy").get("hideLocked").asBoolean()).isTrue();
        long positionsInOrder = 0;
        for (JsonNode s : view.get("sections")) {
            assertThat(s.get("position").asInt()).isEqualTo((int) ++positionsInOrder);
        }
    }

    @Test
    void anUnknownDepartmentKeyIs404DepartmentNotFoundAndAMalformedOneIs400OnEveryDepartmentPath() throws Exception {
        Clinic clinic = newClinic();
        for (String suffix : new String[] {"", "/layout", "/reset-target", "/versions/1"}) {
            problemOf(HttpMethod.GET, DEPARTMENTS + "/zz_nothing" + suffix, clinic.owner(), null, 404, "department-not-found");
            problemOf(HttpMethod.GET, DEPARTMENTS + "/d_abcdefghij" + suffix, clinic.owner(), null, 404, "department-not-found");
            problemOf(HttpMethod.GET, DEPARTMENTS + "/GM" + suffix, clinic.owner(), null, 400, "bad-request");
            problemOf(HttpMethod.GET, DEPARTMENTS + "/g" + suffix, clinic.owner(), null, 400, "bad-request");
        }
        ObjectNode body = layoutInput(department(clinic, "gm"));
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/zz_nothing/draft", clinic.owner(), body.toString(), 404, "department-not-found");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/zz_nothing/publish", clinic.owner(), body.toString(), 404, "department-not-found");
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/GM/draft", clinic.owner(), body.toString(), 400, "bad-request");
    }

    // ---------------- SAH-53-2 the six settings, the rules ----------------

    @Test
    void saveDraftStoresTheChangeAsADraftOfThatDepartmentAndDoctorsStillSeeThePublishedLayout() throws Exception {
        Clinic clinic = newClinic();
        JsonNode before = department(clinic, "gm");
        ObjectNode body = layoutInput(before);
        setShown(body, "chronic", false, false);
        sec(body, "history").put("print", true).put("collapsed", true);
        sec(body, "vitals").set("whoCanFill", objectMapper.createArrayNode().add("DOCTOR"));
        JsonNode saved = saveDraft(clinic, "gm", body, 200);
        assertThat(saved.get("outcome").asText()).isEqualTo("SAVED");
        JsonNode view = saved.get("department");
        assertThat(view.get("status").asText()).isEqualTo("DRAFT");
        assertThat(view.get("publishedVersion").asInt()).isZero();
        assertThat(view.get("revision").asLong()).isEqualTo(1);
        assertThat(view.get("hasDraft").asBoolean()).isTrue();
        assertThat(view.get("layoutSource").asText()).isEqualTo("DRAFT");
        assertThat(view.get("sectionsOn").asInt()).as("x of y on counts shown sections only").isEqualTo(15);
        assertThat(section(view, "chronic").get("shown").asBoolean()).isFalse();
        assertThat(section(view, "history").get("collapsed").asBoolean()).isTrue();
        assertThat(section(view, "vitals").get("whoCanFill").toString()).isEqualTo("[\"DOCTOR\"]");
        // the row is made by the first Save draft; the version is still 0 and nothing was published
        assertThat(departmentRows(clinic)).isEqualTo(1);
        assertThat(versionRows(clinic)).isZero();
        assertThat(row(clinic, "select status_text from (select case when published_version = 0 then 'DRAFT' else 'LIVE' end status_text "
                + "from opd.emr_department) t")).containsExactly("DRAFT");
        // doctors read the starter layout, which still has the chronic tracker
        JsonNode layout = get(DEPARTMENTS + "/gm/layout", clinic.as("DOCTOR"));
        assertThat(layout.get("layoutSource").asText()).isEqualTo("STARTER");
        assertThat(texts(layout.get("sections"), "key")).contains("chronic");
        assertThat(layout.get("sections").size()).isEqualTo(16);
        // the owner reading again gets the draft; the list says there is a draft
        assertThat(section(department(clinic, "gm"), "chronic").get("shown").asBoolean()).isFalse();
        assertThat(summary(listOf(clinic), "gm").get("hasDraft").asBoolean()).isTrue();
        assertThat(summary(listOf(clinic), "gm").get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void aSaveWithNoChangeIsNothingNewAndWritesNothingNoAuditAndKeepsTheRevision() throws Exception {
        Clinic clinic = newClinic();
        // nothing changed on a department that was never saved: no row is created
        JsonNode first = saveDraft(clinic, "gm", layoutInput(department(clinic, "gm")), 200);
        assertThat(first.get("outcome").asText()).isEqualTo("NOTHING_NEW");
        assertThat(first.get("department").get("revision").asLong()).isZero();
        assertThat(departmentRows(clinic)).isZero();
        assertThat(audits(clinic, "EMR_DEPARTMENT_DRAFT_SAVED")).isZero();
        // after a real save, the same body again is nothing new
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        saveDraft(clinic, "gm", body, 200);
        JsonNode again = saveDraft(clinic, "gm", layoutInput(department(clinic, "gm")), 200);
        assertThat(again.get("outcome").asText()).isEqualTo("NOTHING_NEW");
        assertThat(again.get("department").get("revision").asLong()).isEqualTo(1);
        assertThat(audits(clinic, "EMR_DEPARTMENT_DRAFT_SAVED")).isEqualTo(1);
        // a switch turned off and on again is no change
        ObjectNode flip = layoutInput(department(clinic, "gm"));
        setShown(flip, "chronic", true, false);
        assertThat(saveDraft(clinic, "gm", flip, 200).get("outcome").asText()).isEqualTo("SAVED");
        assertThat(audits(clinic, "EMR_DEPARTMENT_DRAFT_SAVED")).isEqualTo(2);
    }

    @Test
    void aHiddenSectionCannotBeRequiredAndHidingARequiredSectionWithRequiredOffSavesItHiddenAndNotRequired() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "diagnosis", false, true);
        JsonNode problem = problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), body.toString(), 400, "hidden-section-required");
        assertThat(problem.get("detail").asText()).isEqualTo("A hidden section cannot be required.");
        assertThat(departmentRows(clinic)).as("nothing stored").isZero();
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.owner(), body.toString(), 400, "hidden-section-required");
        // the owner hides a required section: Required is cleared in the same change
        ObjectNode ok = layoutInput(department(clinic, "gm"));
        assertThat(sec(ok, "diagnosis").get("required").asBoolean()).isTrue();
        setShown(ok, "diagnosis", false, false);
        JsonNode view = saveDraft(clinic, "gm", ok, 200).get("department");
        assertThat(section(view, "diagnosis").get("shown").asBoolean()).isFalse();
        assertThat(section(view, "diagnosis").get("required").asBoolean()).isFalse();
        assertThat(view.get("requiredOn").asInt()).as("required counts shown sections only").isEqualTo(3);
        assertThat(view.get("sectionsOn").asInt()).isEqualTo(15);
    }

    @Test
    void onlyAllergiesAndPcpndtFormFInObstetricsCannotBeHiddenAndEveryOtherSectionIncludingPrescriptionCanBe() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode allergy = layoutInput(department(clinic, "gm"));
        setShown(allergy, "allergy", false, false);
        JsonNode problem = problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), allergy.toString(), 400, "section-not-hideable");
        assertThat(problem.get("detail").asText()).isEqualTo("Allergies cannot be hidden.");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.owner(), allergy.toString(), 400, "section-not-hideable");
        assertThat(departmentRows(clinic)).isZero();
        // Allergies cannot be hidden in any department
        for (String key : new String[] {"dental", "paeds", "physio", "psych"}) {
            ObjectNode b = layoutInput(department(clinic, key));
            setShown(b, "allergy", false, false);
            problemOf(HttpMethod.PUT, DEPARTMENTS + "/" + key + "/draft", clinic.owner(), b.toString(), 400, "section-not-hideable");
        }
        // PCPNDT Form F cannot be hidden in Obstetrics & Gynaecology
        ObjectNode pcpndt = layoutInput(department(clinic, "og"));
        setShown(pcpndt, "pcpndt", false, false);
        JsonNode lock = problemOf(HttpMethod.PUT, DEPARTMENTS + "/og/draft", clinic.owner(), pcpndt.toString(), 400, "section-not-hideable");
        assertThat(lock.get("detail").asText()).isEqualTo("PCPNDT Form F cannot be hidden.");
        // Prescription (Rx), Chief complaints, Diagnosis and the other compliance sections can be hidden
        ObjectNode others = layoutInput(department(clinic, "og"));
        for (String key : new String[] {"rx", "complaints", "diagnosis", "consent", "abha", "rxformat"}) {
            setShown(others, key, false, false);
        }
        JsonNode saved = saveDraft(clinic, "og", others, 200).get("department");
        assertThat(section(saved, "rx").get("shown").asBoolean()).isFalse();
        assertThat(section(saved, "pcpndt").get("shown").asBoolean()).isTrue();
        // a department added from Obstetrics & Gynaecology keeps the PCPNDT lock; one added from Dental has no such section
        JsonNode annexe = addDepartment(clinic, "Obstetrics annexe", starterSource("og"));
        assertThat(section(annexe, "pcpndt").get("hideLocked").asBoolean()).isTrue();
        ObjectNode annexeBody = layoutInput(annexe);
        setShown(annexeBody, "pcpndt", false, false);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/" + annexe.get("departmentKey").asText() + "/draft", clinic.owner(), annexeBody.toString(),
                400, "section-not-hideable");
    }

    @Test
    void aDepartmentWithEverySectionHiddenExceptTheLockedOneOrNothingRequiredIsAcceptedAndCanBePublished() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        for (JsonNode s : body.get("sections")) {
            if (!"allergy".equals(s.get("key").asText())) {
                ((ObjectNode) s).put("shown", false).put("required", false);
            }
        }
        JsonNode saved = saveDraft(clinic, "gm", body, 200);
        assertThat(saved.get("department").get("sectionsOn").asInt()).isEqualTo(1);
        assertThat(saved.get("department").get("requiredOn").asInt()).isZero();
        JsonNode published = publish(clinic, "gm", layoutInput(saved.get("department")), 200);
        assertThat(published.get("outcome").asText()).isEqualTo("PUBLISHED");
        assertThat(get(DEPARTMENTS + "/gm/layout", clinic.as("NURSE")).get("sections").size()).isEqualTo(1);
        // nothing required at all
        ObjectNode none = layoutInput(department(clinic, "dental"));
        for (JsonNode s : none.get("sections")) {
            ((ObjectNode) s).put("required", false);
        }
        assertThat(saveDraft(clinic, "dental", none, 200).get("department").get("requiredOn").asInt()).isZero();
    }

    @Test
    void movingASectionToTheOtherPartChangesOnlyItsPartAndASwapChangesTheOrderAndBothAreStoredInTheDraft() throws Exception {
        Clinic clinic = newClinic();
        JsonNode view = department(clinic, "gm");
        List<String> order = sectionKeys(view);
        ObjectNode body = layoutInput(view);
        assertThat(sec(body, "hpi").get("part").asText()).isEqualTo("ASSESSMENT");
        sec(body, "hpi").put("part", "TREATMENT");
        JsonNode moved = saveDraft(clinic, "gm", body, 200).get("department");
        assertThat(section(moved, "hpi").get("part").asText()).isEqualTo("TREATMENT");
        assertThat(sectionKeys(moved)).as("it keeps its place in the overall order").isEqualTo(order);
        // Up / Down swap with the next section of the same part: the page sends the array in the new order
        ObjectNode swap = layoutInput(moved);
        ArrayNode sections = (ArrayNode) swap.get("sections");
        int a = order.indexOf("history");
        JsonNode first = sections.get(a);
        sections.set(a, sections.get(a + 1));
        sections.set(a + 1, first);
        JsonNode swapped = saveDraft(clinic, "gm", swap, 200).get("department");
        List<String> expected = new ArrayList<>(order);
        Collections.swap(expected, a, a + 1);
        assertThat(sectionKeys(swapped)).isEqualTo(expected);
        assertThat(section(swapped, "history").get("position").asInt()).isEqualTo(a + 2);
        // saving the swapped order again changes nothing: the move at the top or bottom of a part is a success
        assertThat(saveDraft(clinic, "gm", layoutInput(swapped), 200).get("outcome").asText()).isEqualTo("NOTHING_NEW");
        JsonNode audit = objectMapper.readTree(auditRows(clinic, "EMR_DEPARTMENT_DRAFT_SAVED").get(1).get(2));
        assertThat(audit.get("moved").toString()).contains("history");
    }

    @Test
    void theSectionSetMustBeExactlyTheDepartmentsAndAnUnknownKeyIs404WithNothingStored() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode missing = layoutInput(department(clinic, "gm"));
        ((ArrayNode) missing.get("sections")).remove(3);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), missing.toString(), 400, "section-set-mismatch");
        ObjectNode repeated = layoutInput(department(clinic, "gm"));
        ((ArrayNode) repeated.get("sections")).remove(3);
        ((ArrayNode) repeated.get("sections")).add(repeated.get("sections").get(0).deepCopy());
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), repeated.toString(), 400, "section-set-mismatch");
        ObjectNode foreign = layoutInput(department(clinic, "gm"));
        ((ObjectNode) foreign.get("sections").get(2)).put("key", "dental_chart");
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), foreign.toString(), 404, "section-not-found");
        ObjectNode nowhere = layoutInput(department(clinic, "gm"));
        ((ObjectNode) nowhere.get("sections").get(2)).put("key", "no_such_section");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.owner(), nowhere.toString(), 404, "section-not-found");
        ObjectNode empty = layoutInput(department(clinic, "gm"));
        empty.putArray("sections");
        assertThat(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), empty.toString()).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertThat(departmentRows(clinic)).isZero();
        assertThat(audits(clinic, "EMR_DEPARTMENT_DRAFT_SAVED")).isZero();
    }

    @Test
    void aValueOutsideTheListedOnesOrAnExtraFieldIsA400AndNothingIsStored() throws Exception {
        Clinic clinic = newClinic();
        JsonNode view = department(clinic, "gm");
        String[][] mutations = {{"part", "\"MIDDLE\""}, {"part", "\"assessment\""}, {"part", "null"}, {"shown", "\"yes\""},
                {"required", "null"}, {"whoCanFill", "[\"JANITOR\"]"}, {"whoCanFill", "[\"DOCTOR\",\"DOCTOR\"]"},
                {"whoCanFill", "[\"DOCTOR\",\"NURSE\",\"RECEPTION\",\"BILLING\"]"}, {"whoCanFill", "null"},
                {"label", "\"Renamed\""}, {"kind", "\"COMMON\""}, {"description", "\"x\""}, {"fields", "[]"}, {"name", "\"x\""}};
        for (String[] m : mutations) {
            ObjectNode body = layoutInput(view);
            ObjectNode target = sec(body, "vitals");
            target.set(m[0], objectMapper.readTree(m[1]));
            JsonNode problem = problem(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), body.toString()), 400);
            assertThat(problem.get("type").asText()).as(m[0] + "=" + m[1]).isEqualTo(PROBLEM_BASE + "validation");
            assertThat(problem.toString()).as("no value is echoed").doesNotContain("JANITOR").doesNotContain("Renamed");
        }
        for (String[] top : new String[][] {{"name", "\"Renamed department\""}, {"initials", "\"ZZ\""}, {"label", "\"x\""}}) {
            ObjectNode body = layoutInput(view);
            body.set(top[0], objectMapper.readTree(top[1]));
            problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), body.toString(), 400, "validation");
        }
        for (String broken : new String[] {"{\"revision\":0,\"origin\":\"EDIT\"}", "{\"revision\":\"x\",\"origin\":\"EDIT\",\"sections\":[]}"}) {
            assertThat(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), broken).andReturn().getResponse().getStatus()).isEqualTo(400);
        }
        for (String origin : new String[] {null, "\"SOMETHING\"", "1"}) {
            ObjectNode body = layoutInput(view);
            if (origin == null) {
                body.remove("origin");
            } else {
                body.set("origin", objectMapper.readTree(origin));
            }
            assertThat(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), body.toString()).andReturn().getResponse().getStatus())
                    .as("origin " + origin).isEqualTo(400);
        }
        ObjectNode negative = layoutInput(view);
        negative.put("revision", -1);
        assertThat(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), negative.toString()).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        ObjectNode noRevision = layoutInput(view);
        noRevision.remove("revision");
        assertThat(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), noRevision.toString()).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), "", 400, "malformed-request");
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), "{not json", 400, "malformed-request");
        assertThat(departmentRows(clinic)).isZero();
        assertThat(emrAudits(clinic)).isZero();
    }

    @Test
    void aBodyOverSixtyFourKilobytesIs413AndAClinicIdInTheBodyIsIgnored() throws Exception {
        Clinic clinic = newClinic();
        Clinic other = newClinic();
        ObjectNode big = layoutInput(department(clinic, "gm"));
        big.put("padding", "x".repeat(70_000));
        assertThat(call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), big.toString()).andReturn().getResponse().getStatus())
                .isEqualTo(413);
        assertThat(departmentRows(clinic)).isZero();
        // the clinic is the signed-in user's: ids in the body are accepted and ignored
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        body.put("tenantId", other.tenantId().toString());
        body.put("clinicId", other.tenantId().toString());
        assertThat(saveDraft(clinic, "gm", body, 200).get("outcome").asText()).isEqualTo("SAVED");
        assertThat(departmentRows(clinic)).isEqualTo(1);
        assertThat(departmentRows(other)).as("the other clinic got nothing").isZero();
        assertThat(summary(listOf(other), "gm").get("status").asText()).isEqualTo("TEMPLATE");
    }

    @Test
    void aChangeThatFailsHalfWayStoresNothingAndTheRevisionAndDraftAreAsBefore() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode good = layoutInput(department(clinic, "gm"));
        setShown(good, "chronic", false, false);
        saveDraft(clinic, "gm", good, 200);
        JsonNode before = department(clinic, "gm");
        ObjectNode bad = layoutInput(before);
        setShown(bad, "hpi", false, false);
        setShown(bad, "adult_vax", false, false);
        setShown(bad, "diagnosis", false, true);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), bad.toString(), 400, "hidden-section-required");
        assertThat(department(clinic, "gm")).isEqualTo(before);
        assertThat(audits(clinic, "EMR_DEPARTMENT_DRAFT_SAVED")).isEqualTo(1);
    }

    // ---------------- 409 and concurrency ----------------

    @Test
    void aSaveOrPublishWithAnOlderRevisionIs409AndNothingIsApplied() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode first = layoutInput(department(clinic, "gm"));
        ObjectNode second = layoutInput(department(clinic, "gm"));
        setShown(first, "chronic", false, false);
        setShown(second, "adult_vax", false, false);
        saveDraft(clinic, "gm", first, 200);
        JsonNode stale = problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), second.toString(), 409, "emr-setup-changed");
        assertThat(stale.get("detail").asText()).isEqualTo("Someone else changed this department. Reload and try again.");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.owner(), second.toString(), 409, "emr-setup-changed");
        JsonNode view = department(clinic, "gm");
        assertThat(view.get("revision").asLong()).isEqualTo(1);
        assertThat(section(view, "adult_vax").get("shown").asBoolean()).isTrue();
        assertThat(versionRows(clinic)).isZero();
        // a request that would change nothing is 409 as well when its revision is old
        ObjectNode same = layoutInput(view);
        same.put("revision", 0);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), same.toString(), 409, "emr-setup-changed");
        // a revision in the future is stale too
        same.put("revision", 7);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), same.toString(), 409, "emr-setup-changed");
    }

    @Test
    void twoPublishesAtTheSameMomentMakeOneVersionAndTheOtherAnswers409() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        String json = body.toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> task = () -> call(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.owner(), json)
                        .andReturn().getResponse().getStatus();
                futures.add(pool.submit(task));
            }
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);
        assertThat(versionRows(clinic)).isEqualTo(1);
        assertThat(row(clinic, "select published_version::text from opd.emr_department where department_key = 'gm'")).containsExactly("1");
        assertThat(audits(clinic, "EMR_DEPARTMENT_PUBLISHED")).isEqualTo(1);
    }

    @Test
    void twoSavesAtTheSameMomentOneWinsAndTheOtherIs409NotAServerError() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode a = layoutInput(department(clinic, "gm"));
        ObjectNode b = layoutInput(department(clinic, "gm"));
        setShown(a, "chronic", false, false);
        setShown(b, "adult_vax", false, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (ObjectNode body : new ObjectNode[] {a, b}) {
                String json = body.toString();
                futures.add(pool.submit(() -> call(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.owner(), json)
                        .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);
        assertThat(departmentRows(clinic)).isEqualTo(1);
        assertThat(row(clinic, "select revision::text from opd.emr_department where department_key = 'gm'")).containsExactly("1");
    }

    // ---------------- SAH-53-3 publish and versions ----------------

    @Test
    void theFirstPublishIsVersionOneTheStatusBecomesLiveTheDraftIsClearedAndEachPublishIsTheNextNumber() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        JsonNode first = publish(clinic, "gm", body, 200);
        assertThat(first.get("outcome").asText()).isEqualTo("PUBLISHED");
        assertThat(first.get("publishedVersion").asInt()).isEqualTo(1);
        JsonNode view = first.get("department");
        assertThat(view.get("status").asText()).isEqualTo("LIVE");
        assertThat(view.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(view.get("hasDraft").asBoolean()).isFalse();
        assertThat(view.get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(view.get("revision").asLong()).isEqualTo(1);
        assertThat(row(clinic, "select (draft_layout is null)::text, published_version::text from opd.emr_department where department_key = 'gm'"))
                .containsExactly("true", "1");
        assertThat(summary(listOf(clinic), "gm").get("status").asText()).isEqualTo("LIVE");
        assertThat(summary(listOf(clinic), "gm").get("publishedVersion").asInt()).isEqualTo(1);
        // v1 .. v7
        for (int expected = 2; expected <= 7; expected++) {
            JsonNode next = publishOneChange(clinic, "gm", "chronic", expected % 2 == 0);
            assertThat(next.get("outcome").asText()).isEqualTo("PUBLISHED");
            assertThat(next.get("publishedVersion").asInt()).isEqualTo(expected);
        }
        assertThat(versionRows(clinic)).isEqualTo(7);
        assertThat(rows(clinic, "select version_no::text from opd.emr_department_version order by version_no").stream().map(r -> r.get(0)).toList())
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(audits(clinic, "EMR_DEPARTMENT_PUBLISHED")).isEqualTo(7);
    }

    @Test
    void publishWithNothingNewIsNothingToPublishAndWritesNoVersionNoRowAndNoAudit() throws Exception {
        Clinic clinic = newClinic();
        JsonNode fresh = publish(clinic, "gm", layoutInput(department(clinic, "gm")), 200);
        assertThat(fresh.get("outcome").asText()).isEqualTo("NOTHING_TO_PUBLISH");
        assertThat(fresh.get("publishedVersion").asInt()).isZero();
        assertThat(departmentRows(clinic)).isZero();
        assertThat(versionRows(clinic)).isZero();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        publish(clinic, "gm", body, 200);
        JsonNode again = publish(clinic, "gm", layoutInput(department(clinic, "gm")), 200);
        assertThat(again.get("outcome").asText()).isEqualTo("NOTHING_TO_PUBLISH");
        assertThat(again.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(versionRows(clinic)).isEqualTo(1);
        assertThat(audits(clinic, "EMR_DEPARTMENT_PUBLISHED")).isEqualTo(1);
        // a saved draft equal to the published layout is still a publish only when it differs from what doctors see
        assertThat(row(clinic, "select revision::text from opd.emr_department where department_key = 'gm'")).containsExactly("1");
    }

    @Test
    void aPublishedVersionIsReadExactlyAsPublishedHiddenSectionsToo_AndNeverChangesWhenLaterVersionsAreMade() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        sec(body, "hpi").put("required", true);
        publish(clinic, "gm", body, 200);
        JsonNode v1 = get(DEPARTMENTS + "/gm/versions/1", clinic.as("NURSE"));
        assertThat(v1.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.get("publishedByName").asText()).isNotBlank();
        assertThat(v1.get("publishedAt").asText()).isNotBlank();
        assertThat(v1.get("sections").size()).as("hidden sections are in the version").isEqualTo(19);
        assertThat(section(v1, "chronic").get("shown").asBoolean()).isFalse();
        assertThat(section(v1, "hpi").get("required").asBoolean()).isTrue();
        assertThat(section(v1, "complaints").get("fields").size()).isEqualTo(4);
        publishOneChange(clinic, "gm", "adult_vax", false);
        publishOneChange(clinic, "gm", "hpi", false);
        assertThat(get(DEPARTMENTS + "/gm/versions/1", clinic.owner())).as("version 1 is as it was").isEqualTo(v1);
        assertThat(section(get(DEPARTMENTS + "/gm/versions/3", clinic.owner()), "hpi").get("shown").asBoolean()).isFalse();
        problemOf(HttpMethod.GET, DEPARTMENTS + "/gm/versions/4", clinic.owner(), null, 404, "version-not-found");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/gm/versions/0", clinic.owner(), null, 400, "bad-request");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/gm/versions/100001", clinic.owner(), null, 400, "bad-request");
        // a starter-backed department the clinic never saved has no version at all
        problemOf(HttpMethod.GET, DEPARTMENTS + "/dental/versions/1", clinic.owner(), null, 404, "version-not-found");
        assertThat(call(HttpMethod.GET, DEPARTMENTS + "/gm/versions/abc", clinic.owner(), null).andReturn().getResponse().getStatus())
                .isBetween(400, 404);
    }

    @Test
    void whatDoctorsReadIsTheShownSectionsOfTheLatestVersionInOrderNeverTheDraftAndTheStarterWhenNothingIsPublished() throws Exception {
        Clinic clinic = newClinic();
        JsonNode starter = get(DEPARTMENTS + "/gm/layout", clinic.as("DOCTOR"));
        assertThat(starter.get("layoutSource").asText()).isEqualTo("STARTER");
        assertThat(starter.get("versionNo").isNull()).isTrue();
        assertThat(starter.get("starterKey").asText()).isEqualTo("gm");
        assertThat(starter.get("sections").size()).isEqualTo(16);
        for (JsonNode s : starter.get("sections")) {
            assertThat(s.get("shown").asBoolean()).isTrue();
            for (String field : new String[] {"name", "kind", "part", "required", "print", "copy", "collapsed", "whoCanFill", "fields"}) {
                assertThat(s.has(field)).as(field).isTrue();
            }
        }
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        publish(clinic, "gm", body, 200);
        JsonNode v1 = get(DEPARTMENTS + "/gm/layout", clinic.as("PHARMACIST"));
        assertThat(v1.get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.get("sections").size()).isEqualTo(15);
        assertThat(texts(v1.get("sections"), "key")).doesNotContain("chronic", "procedures", "referral", "consent");
        // a saved draft is not in what doctors read
        ObjectNode draft = layoutInput(department(clinic, "gm"));
        setShown(draft, "adult_vax", false, false);
        saveDraft(clinic, "gm", draft, 200);
        assertThat(get(DEPARTMENTS + "/gm/layout", clinic.as("DOCTOR"))).isEqualTo(v1);
        assertThat(texts(get(DEPARTMENTS + "/gm/layout", clinic.as("DOCTOR")).get("sections"), "key")).contains("adult_vax");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/gm/layout", anonymous(), null, 401, "unauthorized");
        // an added department never published is not read by doctors
        JsonNode added = addDepartment(clinic, "Walk-in clinic", BLANK_SOURCE);
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + added.get("departmentKey").asText() + "/layout", clinic.as("DOCTOR"), null, 404,
                "department-not-published");
    }

    @Test
    void theAuditEntriesCarryWhoWhatAndTheBeforeAndAfterOfEachChangedSettingAndNoPatientData() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        sec(body, "followup").put("collapsed", true);
        saveDraft(clinic, "gm", body, 200);
        JsonNode saved = objectMapper.readTree(auditRows(clinic, "EMR_DEPARTMENT_DRAFT_SAVED").get(0).get(2));
        assertThat(saved.toString()).contains("chronic").contains("followup").contains("shown").contains("collapsed");
        assertThat(saved.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(auditRows(clinic, "EMR_DEPARTMENT_DRAFT_SAVED").get(0).get(3)).as("who").isEqualTo(clinic.ownerUserId().toString());
        publish(clinic, "gm", layoutInput(department(clinic, "gm")), 200);
        List<List<String>> published = auditRows(clinic, "EMR_DEPARTMENT_PUBLISHED");
        assertThat(published).hasSize(1);
        JsonNode before = objectMapper.readTree(published.get(0).get(1));
        JsonNode after = objectMapper.readTree(published.get(0).get(2));
        assertThat(before.get("versionBefore").asInt()).isZero();
        assertThat(after.get("versionAfter").asInt()).isEqualTo(1);
        assertThat(published.get(0).get(3)).isEqualTo(clinic.ownerUserId().toString());
        for (List<String> row : rows(clinic, "select coalesce(before_json::text, ''), coalesce(after_json::text, '') from platform.audit_event "
                + "where action like 'EMR\\_DEPARTMENT\\_%'")) {
            assertThat(row.get(0) + row.get(1)).doesNotContainPattern("[6-9][0-9]{9}");
        }
    }

    // ---------------- SAH-53-4 reset ----------------

    @Test
    void theResetTargetIsTheStarterSettingsOfExactlyThisDepartmentsSectionsInStarterOrderAndReadingWritesNothing() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        sec(body, "hpi").put("part", "TREATMENT");
        publish(clinic, "gm", body, 200);
        long audits = emrAudits(clinic);
        for (String role : ALL_ROLES) {
            JsonNode target = get(DEPARTMENTS + "/gm/reset-target", clinic.as(role));
            assertThat(target.get("departmentKey").asText()).isEqualTo("gm");
            assertThat(target.get("basis").asText()).isEqualTo("STARTER");
            JsonNode starter = get(STARTERS + "/gm", clinic.as(role));
            assertThat(target.get("sections").size()).isEqualTo(19);
            for (int i = 0; i < 19; i++) {
                JsonNode t = target.get("sections").get(i);
                JsonNode s = starter.get("sections").get(i);
                assertThat(t.get("key").asText()).isEqualTo(s.get("key").asText());
                for (String flag : new String[] {"part", "shown", "required", "print", "copy", "collapsed", "whoCanFill"}) {
                    assertThat(t.get(flag)).as(t.get("key") + "." + flag).isEqualTo(s.get(flag));
                }
            }
        }
        assertThat(emrAudits(clinic)).as("reading writes nothing").isEqualTo(audits);
        // a blank department resets to the blank layout, a copy of a starter department to that starter
        JsonNode blank = addDepartment(clinic, "Blank annexe", BLANK_SOURCE);
        JsonNode blankTarget = get(DEPARTMENTS + "/" + blank.get("departmentKey").asText() + "/reset-target", clinic.owner());
        assertThat(blankTarget.get("basis").asText()).isEqualTo("BLANK");
        assertThat(blankTarget.get("sections").size()).isEqualTo(17);
        JsonNode copy = addDepartment(clinic, "Medicine copy", copySource("gm"));
        JsonNode copyTarget = get(DEPARTMENTS + "/" + copy.get("departmentKey").asText() + "/reset-target", clinic.owner());
        assertThat(copyTarget.get("basis").asText()).isEqualTo("STARTER");
        assertThat(copyTarget.get("sections").size()).isEqualTo(19);
        assertThat(sectionKeys(copy)).isEqualTo(sectionKeys(department(clinic, "gm")));
    }

    @Test
    void resettingPutsTheStarterValuesInTheDraftCountsTheChangeLeavesThePublishedVersionAndAuditsTheCounts() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        setShown(body, "diagnosis", false, false);
        sec(body, "followup").put("part", "ASSESSMENT");
        publish(clinic, "gm", body, 200);
        JsonNode changed = department(clinic, "gm");
        assertThat(changed.get("sectionsOn").asInt()).isEqualTo(14);
        // Reset to template: the page reads the target and saves it with origin RESET
        JsonNode target = get(DEPARTMENTS + "/gm/reset-target", clinic.owner());
        ObjectNode reset = objectMapper.createObjectNode();
        reset.put("revision", changed.get("revision").asLong());
        reset.put("origin", "RESET");
        reset.set("sections", target.get("sections").deepCopy());
        JsonNode saved = saveDraft(clinic, "gm", reset, 200);
        assertThat(saved.get("outcome").asText()).isEqualTo("SAVED");
        JsonNode view = saved.get("department");
        assertThat(view.get("sectionsOn").asInt()).isEqualTo(16);
        assertThat(view.get("requiredOn").asInt()).isEqualTo(4);
        assertThat(view.get("publishedVersion").asInt()).as("the published version is unchanged").isEqualTo(1);
        assertThat(view.get("hasDraft").asBoolean()).isTrue();
        assertThat(sectionKeys(view)).isEqualTo(sectionKeys(get(STARTERS + "/gm", clinic.owner())));
        assertThat(get(DEPARTMENTS + "/gm/layout", clinic.as("DOCTOR")).get("versionNo").asInt()).isEqualTo(1);
        assertThat(get(DEPARTMENTS + "/gm/layout", clinic.as("DOCTOR")).get("sections").size()).as("doctors still see v1").isEqualTo(14);
        // one audit entry with the before and after counts of shown and required sections
        List<List<String>> resets = auditRows(clinic, "EMR_DEPARTMENT_RESET");
        assertThat(resets).hasSize(1);
        JsonNode before = objectMapper.readTree(resets.get(0).get(1));
        JsonNode after = objectMapper.readTree(resets.get(0).get(2));
        assertThat(before.get("sectionsOn").asInt()).isEqualTo(14);
        assertThat(before.get("requiredOn").asInt()).isEqualTo(3);
        assertThat(after.get("sectionsOn").asInt()).isEqualTo(16);
        assertThat(after.get("requiredOn").asInt()).isEqualTo(4);
        assertThat(audits(clinic, "EMR_DEPARTMENT_DRAFT_SAVED")).as("a reset is not audited as a plain save").isZero();
        // Undo: the page sends back exactly the settings it had before; the draft returns to what it was
        ObjectNode undo = layoutInput(changed);
        undo.put("revision", view.get("revision").asLong());
        JsonNode undone = saveDraft(clinic, "gm", undo, 200);
        assertThat(undone.get("outcome").asText()).isEqualTo("SAVED");
        assertThat(undone.get("department").get("hasDraft").asBoolean()).as("equal to what doctors see again").isFalse();
        assertThat(sectionKeys(undone.get("department"))).isEqualTo(sectionKeys(changed));
        assertThat(section(undone.get("department"), "chronic").get("shown").asBoolean()).isFalse();
        // a reset saved and published makes a new version from the starter values; the old versions are kept
        ObjectNode again = objectMapper.createObjectNode();
        JsonNode current = department(clinic, "gm");
        again.put("revision", current.get("revision").asLong());
        again.put("origin", "RESET");
        again.set("sections", target.get("sections").deepCopy());
        JsonNode published = publish(clinic, "gm", again, 200);
        assertThat(published.get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(section(get(DEPARTMENTS + "/gm/versions/2", clinic.owner()), "chronic").get("shown").asBoolean()).isTrue();
        assertThat(section(get(DEPARTMENTS + "/gm/versions/1", clinic.owner()), "chronic").get("shown").asBoolean()).isFalse();
    }

    @Test
    void aDepartmentAlreadyEqualToItsStarterValuesResetsAsASuccessThatChangesNothingAndWritesNoAudit() throws Exception {
        Clinic clinic = newClinic();
        JsonNode target = get(DEPARTMENTS + "/dental/reset-target", clinic.owner());
        ObjectNode reset = objectMapper.createObjectNode();
        reset.put("revision", 0);
        reset.put("origin", "RESET");
        reset.set("sections", target.get("sections").deepCopy());
        JsonNode result = saveDraft(clinic, "dental", reset, 200);
        assertThat(result.get("outcome").asText()).isEqualTo("NOTHING_NEW");
        assertThat(departmentRows(clinic)).isZero();
        assertThat(audits(clinic, "EMR_DEPARTMENT_RESET")).isZero();
    }

    @Test
    void aResetCoversOnlyTheSelectedDepartment() throws Exception {
        Clinic clinic = newClinic();
        for (String key : new String[] {"gm", "dental"}) {
            ObjectNode body = layoutInput(department(clinic, key));
            setShown(body, "followup", false, false);
            saveDraft(clinic, key, body, 200);
        }
        JsonNode gm = department(clinic, "gm");
        ObjectNode reset = objectMapper.createObjectNode();
        reset.put("revision", gm.get("revision").asLong());
        reset.put("origin", "RESET");
        reset.set("sections", get(DEPARTMENTS + "/gm/reset-target", clinic.owner()).get("sections").deepCopy());
        saveDraft(clinic, "gm", reset, 200);
        assertThat(section(department(clinic, "gm"), "followup").get("shown").asBoolean()).isTrue();
        assertThat(section(department(clinic, "dental"), "followup").get("shown").asBoolean()).as("dental keeps its draft").isFalse();
    }

    // ---------------- who may write ----------------

    @Test
    void everyRoleButTheOwnerIs403OnSaveDraftPublishAndAddDepartmentAndNothingChanges() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode body = layoutInput(department(clinic, "gm"));
        setShown(body, "chronic", false, false);
        String create = createBody("Walk-in clinic", BLANK_SOURCE, List.of(), List.of(), true);
        for (String role : NON_OWNER_ROLES) {
            problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", clinic.as(role), body.toString(), 403, "forbidden");
            problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", clinic.as(role), body.toString(), 403, "forbidden");
            problemOf(HttpMethod.POST, DEPARTMENTS, clinic.as(role), create, 403, "forbidden");
            problemOf(HttpMethod.GET, DOCTOR_OPTIONS, clinic.as(role), null, 403, "forbidden");
        }
        // the role in the token is not trusted: a doctor whose token says OWNER is still refused
        Staff doctor = addDoctor(clinic, "General physician");
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", doctor.tokenClaiming(clinic, "OWNER"), body.toString(), 403, "forbidden");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", doctor.tokenClaiming(clinic, "OWNER"), body.toString(), 403, "forbidden");
        problemOf(HttpMethod.POST, DEPARTMENTS, doctor.tokenClaiming(clinic, "OWNER"), create, 403, "forbidden");
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/gm/draft", anonymous(), body.toString(), 401, "unauthorized");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/gm/publish", anonymous(), body.toString(), 401, "unauthorized");
        problemOf(HttpMethod.POST, DEPARTMENTS, anonymous(), create, 401, "unauthorized");
        assertThat(departmentRows(clinic)).isZero();
        assertThat(versionRows(clinic)).isZero();
        assertThat(emrAudits(clinic)).isZero();
        assertThat(department(clinic, "gm").get("revision").asLong()).isZero();
    }

    // ---------------- two clinics ----------------

    @Test
    void anotherClinicsOwnerNeverSeesOrChangesThisClinicsDraftsVersionsOrAddedDepartments() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ObjectNode body = layoutInput(department(a, "gm"));
        setShown(body, "chronic", false, false);
        publish(a, "gm", body, 200);
        JsonNode added = addDepartment(a, "Annexe of A", BLANK_SOURCE);
        String key = added.get("departmentKey").asText();
        publishAsIs(a, key);
        // B sees fifteen templates and none of A's
        JsonNode list = listOf(b);
        assertThat(list.get("departments").size()).isEqualTo(15);
        assertThat(summary(list, "gm").get("status").asText()).isEqualTo("TEMPLATE");
        assertThat(texts(list.get("departments"), "name")).doesNotContain("Annexe of A");
        // B cannot read A's added department, its versions or its layout, nor change it
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + key, b.owner(), null, 404, "department-not-found");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + key + "/versions/1", b.owner(), null, 404, "department-not-found");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + key + "/layout", b.owner(), null, 404, "department-not-found");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/" + key + "/reset-target", b.owner(), null, 404, "department-not-found");
        ObjectNode attempt = layoutInput(added);
        problemOf(HttpMethod.PUT, DEPARTMENTS + "/" + key + "/draft", b.owner(), attempt.toString(), 404, "department-not-found");
        problemOf(HttpMethod.POST, DEPARTMENTS + "/" + key + "/publish", b.owner(), attempt.toString(), 404, "department-not-found");
        problemOf(HttpMethod.POST, DEPARTMENTS, b.owner(), createBody("Copy of A", copySource(key), List.of(), List.of(), true), 404,
                "department-not-found");
        // B's own gm is separate: A's published v1 is not B's
        assertThat(get(DEPARTMENTS + "/gm/layout", b.owner()).get("layoutSource").asText()).isEqualTo("STARTER");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/gm/versions/1", b.owner(), null, 404, "version-not-found");
        assertThat(department(b, "gm").get("revision").asLong()).isZero();
        // and the same name in B is free
        addDepartment(b, "Annexe of A", BLANK_SOURCE);
        assertThat(departmentRows(a)).isEqualTo(2);
        assertThat(departmentRows(b)).isEqualTo(1);
    }

    @Test
    void theDepartmentRowsAndVersionsCannotBeReachedAcrossClinicsInTheDatabaseEither() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        publishAsIs(a, "gm");
        assertThat(count(a, "select count(*) from opd.emr_department_version")).isEqualTo(1);
        assertThat(count(b, "select count(*) from opd.emr_department_version")).isZero();
        assertThat(count(b, "select count(*) from opd.emr_department")).isZero();
        assertThat(globalCount("select count(*) from opd.emr_department_version")).as("no clinic bound").isZero();
    }
}
