package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-44-2 on the real database: the owner shows or hides a section and makes it required or optional for the clinic,
 * resets a template, and one revision per clinic and template guards against two owners at once. Covers the
 * 400 / 403 / 404 / 409 / 413 answers, closed request bodies, the audit entries, all-or-nothing, and two clinics.
 * Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrOverridesIT extends EmrSupport {

    private static final String STALE = "Someone else changed this template. Reload and try again.";

    private long revisionRow(Clinic clinic, String template) throws Exception {
        List<String> row = row(clinic, "select revision::text from opd.emr_template_setup where template_key = ?", template);
        return row.isEmpty() ? -1 : Long.parseLong(row.get(0));
    }

    private long overrideRows(Clinic clinic, String template) throws Exception {
        return count(clinic, "select count(*) from opd.emr_template_override where template_key = ?", template);
    }

    /** Nothing at all was stored for this template: no revision row, no override row, no audit entry. */
    private void assertNothingStored(Clinic clinic, String template) throws Exception {
        assertThat(revisionRow(clinic, template)).as("no revision row").isEqualTo(-1);
        assertThat(overrideRows(clinic, template)).as("no override row").isZero();
        assertThat(emrAudits(clinic)).as("no audit entry").isZero();
    }

    // ---------------- show and hide ----------------

    @Test
    void theOwnerHidesASectionAndTheClinicsEffectiveTemplateLosesItWhileOtherClinicsAreUnchanged() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        assertThat(template(a, "gp").get("revision").asLong()).isZero();

        JsonNode view = put(overrideUrl("gp", "local_exam"), a.owner(), overrideBody(false, false, 0), 200);
        assertThat(view.get("revision").asLong()).as("the first change stores revision 1").isEqualTo(1);
        assertThat(view.get("canEdit").asBoolean()).isTrue();
        assertThat(view.get("sectionsOn").asInt()).isEqualTo(10);
        assertThat(view.get("requiredOn").asInt()).isEqualTo(6);
        JsonNode local = section(view, "local_exam");
        assertThat(local.get("shown").asBoolean()).isFalse();
        assertThat(local.get("required").asBoolean()).isFalse();
        assertThat(local.get("overridden").asBoolean()).isTrue();
        assertThat(local.get("templateRequired").asBoolean()).as("the template default is kept").isFalse();
        assertThat(view.get("sections").size()).as("a hidden section is still listed").isEqualTo(11);

        // read back by another role, and in the list
        assertThat(section(get(TEMPLATES + "/gp", a.as("NURSE")), "local_exam").get("shown").asBoolean()).isFalse();
        JsonNode summary = get(TEMPLATES, a.owner()).get(0);
        assertThat(summary.get("sectionsOn").asInt()).isEqualTo(10);
        assertThat(summary.get("hasOverrides").asBoolean()).isTrue();

        // stored as the owner, one revision row, one override row
        assertThat(revisionRow(a, "gp")).isEqualTo(1);
        List<String> stored = row(a, "select shown::text, required::text, created_by::text from opd.emr_template_override "
                + "where template_key = 'gp' and section_key = 'local_exam'");
        assertThat(stored).containsExactly("false", "false", a.ownerUserId().toString());

        // the other clinic: nothing changed
        JsonNode other = template(b, "gp");
        assertThat(section(other, "local_exam").get("shown").asBoolean()).isTrue();
        assertThat(section(other, "local_exam").get("overridden").asBoolean()).isFalse();
        assertThat(other.get("revision").asLong()).isZero();
        assertThat(other.get("sectionsOn").asInt()).isEqualTo(11);
        assertThat(get(TEMPLATES, b.owner()).get(0).get("hasOverrides").asBoolean()).isFalse();
        // and the other templates of clinic A
        assertThat(template(a, "dental").get("sectionsOn").asInt()).isEqualTo(11);
        assertThat(template(a, "dental").get("revision").asLong()).isZero();
    }

    @Test
    void theOwnerSwitchesRequiredOnAndOffAndAValueEqualToTheDefaultRemovesTheOverride() throws Exception {
        Clinic clinic = newClinic();
        JsonNode on = put(overrideUrl("gp", "systemic_exam"), clinic.owner(), overrideBody(true, true, 0), 200);
        assertThat(section(on, "systemic_exam").get("required").asBoolean()).isTrue();
        assertThat(section(on, "systemic_exam").get("shown").asBoolean()).isTrue();
        assertThat(section(on, "systemic_exam").get("overridden").asBoolean()).isTrue();
        assertThat(on.get("requiredOn").asInt()).isEqualTo(7);
        assertThat(on.get("revision").asLong()).isEqualTo(1);
        assertThat(overrideRows(clinic, "gp")).isEqualTo(1);

        JsonNode back = put(overrideUrl("gp", "systemic_exam"), clinic.owner(), overrideBody(true, false, 1), 200);
        assertThat(section(back, "systemic_exam").get("required").asBoolean()).isFalse();
        assertThat(section(back, "systemic_exam").get("overridden").asBoolean()).as("equal to the default: no override").isFalse();
        assertThat(back.get("requiredOn").asInt()).isEqualTo(6);
        assertThat(back.get("revision").asLong()).isEqualTo(2);
        assertThat(overrideRows(clinic, "gp")).isZero();
        assertThat(revisionRow(clinic, "gp")).isEqualTo(2);
        assertThat(get(TEMPLATES, clinic.owner()).get(0).get("hasOverrides").asBoolean()).isFalse();
    }

    @Test
    void makingARequiredSectionOptionalOrHidingItSavesHiddenAndNotRequiredInOneChange() throws Exception {
        Clinic clinic = newClinic();
        JsonNode optional = put(overrideUrl("gp", "chief_complaints"), clinic.owner(), overrideBody(true, false, 0), 200);
        assertThat(section(optional, "chief_complaints").get("required").asBoolean()).isFalse();
        assertThat(section(optional, "chief_complaints").get("overridden").asBoolean()).isTrue();
        assertThat(optional.get("requiredOn").asInt()).isEqualTo(5);
        assertThat(optional.get("sectionsOn").asInt()).isEqualTo(11);

        // vitals is required by default: hiding it stores hidden and not required
        JsonNode hidden = put(overrideUrl("gp", "vitals"), clinic.owner(), overrideBody(false, false, 1), 200);
        assertThat(section(hidden, "vitals").get("shown").asBoolean()).isFalse();
        assertThat(section(hidden, "vitals").get("required").asBoolean()).isFalse();
        assertThat(hidden.get("sectionsOn").asInt()).isEqualTo(10);
        assertThat(hidden.get("requiredOn").asInt()).isEqualTo(4);
        assertThat(row(clinic, "select shown::text, required::text from opd.emr_template_override where section_key = 'vitals'"))
                .containsExactly("false", "false");

        // showing it again with its default required flag removes the override
        JsonNode shownAgain = put(overrideUrl("gp", "vitals"), clinic.owner(), overrideBody(true, true, 2), 200);
        assertThat(section(shownAgain, "vitals").get("overridden").asBoolean()).isFalse();
        assertThat(section(shownAgain, "vitals").get("required").asBoolean()).isTrue();
        assertThat(shownAgain.get("sectionsOn").asInt()).isEqualTo(11);
    }

    @Test
    void aHiddenSectionCannotBeRequiredAndNothingIsStored() throws Exception {
        Clinic clinic = newClinic();
        JsonNode problem = problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(),
                overrideBody(false, true, 0), 400, "hidden-section-required");
        assertThat(problem.get("detail").asText()).isEqualTo("A hidden section cannot be required.");
        assertNothingStored(clinic, "gp");
        assertThat(template(clinic, "gp").get("sectionsOn").asInt()).isEqualTo(11);
    }

    @Test
    void theSixSectionsThatCannotBeHiddenAreRefusedWithTheirNameAndNothingChanges() throws Exception {
        Clinic clinic = newClinic();
        String[][] fixed = {{"gp", "history_allergies", "History & allergies"}, {"gp", "prescription", "Prescription"},
                {"dental", "medical_alerts", "Medical alerts"}, {"dental", "prescription", "Prescription"},
                {"paeds", "prescription", "Prescription (by weight)"}, {"homeo", "remedy_potency", "Remedy & potency"}};
        for (String[] f : fixed) {
            JsonNode problem = problemOf(HttpMethod.PUT, overrideUrl(f[0], f[1]), clinic.owner(),
                    overrideBody(false, false, 0), 400, "section-not-hideable");
            assertThat(problem.get("detail").asText()).isEqualTo(f[2] + " cannot be hidden.");
            assertThat(revisionRow(clinic, f[0])).isEqualTo(-1);
        }
        assertThat(count(clinic, "select count(*) from opd.emr_template_override")).isZero();
        assertThat(emrAudits(clinic)).isZero();
        // they can still be made optional or required (Show stays on)
        JsonNode optional = put(overrideUrl("gp", "prescription"), clinic.owner(), overrideBody(true, false, 0), 200);
        assertThat(section(optional, "prescription").get("required").asBoolean()).isFalse();
        JsonNode required = put(overrideUrl("dental", "prescription"), clinic.owner(), overrideBody(true, true, 0), 200);
        assertThat(section(required, "prescription").get("required").asBoolean()).isTrue();
        assertThat(section(required, "prescription").get("shown").asBoolean()).isTrue();
    }

    @Test
    void aRequestThatChangesNothingAnswers200WritesNoAuditAndKeepsTheRevision() throws Exception {
        Clinic clinic = newClinic();
        JsonNode same = put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(true, false, 0), 200);
        assertThat(same.get("revision").asLong()).isZero();
        JsonNode sameRequired = put(overrideUrl("gp", "chief_complaints"), clinic.owner(), overrideBody(true, true, 0), 200);
        assertThat(sameRequired.get("revision").asLong()).isZero();
        assertNothingStored(clinic, "gp");
    }

    @Test
    void anOverrideRowForASectionThatIsNotInTheTemplateIsIgnoredAndNotDeleted() throws Exception {
        Clinic clinic = newClinic();
        commit(clinic.tenantId(), "insert into opd.emr_template_override (template_key, section_key, shown, required, created_by) "
                + "values ('gp', 'retired_section', false, false, ?)", clinic.ownerUserId());
        JsonNode view = template(clinic, "gp");
        assertThat(view.get("sections").size()).isEqualTo(11);
        assertThat(texts(view.get("sections"), "key")).doesNotContain("retired_section");
        assertThat(view.get("sectionsOn").asInt()).isEqualTo(11);
        assertThat(view.get("requiredOn").asInt()).isEqualTo(6);
        assertThat(overrideRows(clinic, "gp")).as("not deleted").isEqualTo(1);
        // a database row that hides a section that cannot be hidden does not hide it
        commit(clinic.tenantId(), "insert into opd.emr_template_override (template_key, section_key, shown, required, created_by) "
                + "values ('gp', 'prescription', false, false, ?)", clinic.ownerUserId());
        JsonNode again = template(clinic, "gp");
        assertThat(section(again, "prescription").get("shown").asBoolean()).isTrue();
        assertThat(again.get("sectionsOn").asInt()).isEqualTo(11);
    }

    // ---------------- reset ----------------

    @Test
    void resetRemovesEveryOverrideOfThatTemplateAndAnswersTheNewCounts() throws Exception {
        Clinic clinic = newClinic();
        put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 200);
        put(overrideUrl("gp", "investigations"), clinic.owner(), overrideBody(false, false, 1), 200);
        put(overrideUrl("gp", "systemic_exam"), clinic.owner(), overrideBody(true, true, 2), 200);
        put(overrideUrl("dental", "radiographs"), clinic.owner(), overrideBody(false, false, 0), 200);
        JsonNode before = template(clinic, "gp");
        assertThat(before.get("revision").asLong()).isEqualTo(3);
        assertThat(before.get("sectionsOn").asInt()).isEqualTo(9);
        assertThat(before.get("requiredOn").asInt()).isEqualTo(7);

        JsonNode view = post(resetUrl("gp"), clinic.owner(), "{\"revision\":3}", 200);
        assertThat(view.get("sectionsOn").asInt()).as("n sections on").isEqualTo(11);
        assertThat(view.get("requiredOn").asInt()).as("m required").isEqualTo(6);
        assertThat(view.get("revision").asLong()).isEqualTo(4);
        for (JsonNode s : view.get("sections")) {
            assertThat(s.get("overridden").asBoolean()).isFalse();
            assertThat(s.get("shown").asBoolean()).isTrue();
            assertThat(s.get("required").asBoolean()).isEqualTo(s.get("templateRequired").asBoolean());
        }
        assertThat(overrideRows(clinic, "gp")).isZero();
        assertThat(overrideRows(clinic, "dental")).as("another template of the clinic is untouched").isEqualTo(1);
        assertThat(template(clinic, "dental").get("sectionsOn").asInt()).isEqualTo(10);

        // one audit entry for the reset, with before and after counts
        List<List<String>> audit = auditRows(clinic, "EMR_TEMPLATE_RESET");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(0)).isEqualTo("EMR_TEMPLATE_SETUP");
        JsonNode beforeJson = objectMapper.readTree(audit.get(0).get(1));
        JsonNode afterJson = objectMapper.readTree(audit.get(0).get(2));
        assertThat(beforeJson.get("templateKey").asText()).isEqualTo("gp");
        assertThat(beforeJson.get("sectionsOn").asInt()).isEqualTo(9);
        assertThat(beforeJson.get("requiredOn").asInt()).isEqualTo(7);
        assertThat(beforeJson.get("overrides").asInt()).isEqualTo(3);
        assertThat(afterJson.get("sectionsOn").asInt()).isEqualTo(11);
        assertThat(afterJson.get("requiredOn").asInt()).isEqualTo(6);
        assertThat(afterJson.get("overrides").asInt()).isZero();
        assertThat(audit.get(0).get(3)).isEqualTo(clinic.ownerUserId().toString());

        // the next change uses the new revision
        put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 4), 200);
        problemOf(HttpMethod.PUT, overrideUrl("gp", "services"), clinic.owner(), overrideBody(false, false, 4), 409, "emr-setup-changed");
    }

    @Test
    void resettingATemplateWithNoOverridesIsASuccessThatChangesNothingAndWritesNoAudit() throws Exception {
        Clinic clinic = newClinic();
        JsonNode view = post(resetUrl("paeds"), clinic.owner(), "{\"revision\":0}", 200);
        assertThat(view.get("sectionsOn").asInt()).isEqualTo(9);
        assertThat(view.get("revision").asLong()).isZero();
        assertNothingStored(clinic, "paeds");
        // after a change that was undone by hand there is no override either: still no audit entry for the reset
        put(overrideUrl("paeds", "development"), clinic.owner(), overrideBody(false, false, 0), 200);
        put(overrideUrl("paeds", "development"), clinic.owner(), overrideBody(true, false, 1), 200);
        long audits = emrAudits(clinic);
        JsonNode again = post(resetUrl("paeds"), clinic.owner(), "{\"revision\":2}", 200);
        assertThat(again.get("revision").asLong()).isEqualTo(2);
        assertThat(emrAudits(clinic)).isEqualTo(audits);
    }

    @Test
    void aStaleRevisionOnResetIs409AndKeepsTheOverrides() throws Exception {
        Clinic clinic = newClinic();
        put(overrideUrl("homeo", "vitals"), clinic.owner(), overrideBody(false, false, 0), 200);
        JsonNode problem = problemOf(HttpMethod.POST, resetUrl("homeo"), clinic.owner(), "{\"revision\":0}", 409,
                "emr-setup-changed");
        assertThat(problem.get("detail").asText()).isEqualTo(STALE);
        assertThat(overrideRows(clinic, "homeo")).isEqualTo(1);
        assertThat(revisionRow(clinic, "homeo")).isEqualTo(1);
        assertThat(auditRows(clinic, "EMR_TEMPLATE_RESET")).isEmpty();
    }

    // ---------------- 409: two owners at once ----------------

    @Test
    void aStaleRevisionIs409EvenWhenTheRequestWouldChangeNothingAndNothingIsApplied() throws Exception {
        Clinic clinic = newClinic();
        JsonNode problem = problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(),
                overrideBody(false, false, 5), 409, "emr-setup-changed");
        assertThat(problem.get("detail").asText()).isEqualTo(STALE);
        assertNothingStored(clinic, "gp");
        // a request equal to the default but with a stale revision: still 409 (the revision is checked first)
        problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(true, false, 7), 409,
                "emr-setup-changed");
        assertNothingStored(clinic, "gp");

        put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 200);
        problemOf(HttpMethod.PUT, overrideUrl("gp", "services"), clinic.owner(), overrideBody(false, false, 0), 409, "emr-setup-changed");
        problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 409, "emr-setup-changed");
        assertThat(overrideRows(clinic, "gp")).isEqualTo(1);
        assertThat(revisionRow(clinic, "gp")).isEqualTo(1);
        assertThat(auditRows(clinic, "EMR_TEMPLATE_OVERRIDE_CHANGED")).hasSize(1);
    }

    @Test
    void theRevisionIsPerTemplateSoAChangeToOneTemplateDoesNotStaleAnother() throws Exception {
        Clinic clinic = newClinic();
        put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 200);
        JsonNode dental = put(overrideUrl("dental", "radiographs"), clinic.owner(), overrideBody(false, false, 0), 200);
        assertThat(dental.get("revision").asLong()).isEqualTo(1);
        assertThat(revisionRow(clinic, "gp")).isEqualTo(1);
        assertThat(revisionRow(clinic, "dental")).isEqualTo(1);
    }

    @Test
    void twoOwnersAtTheSameMomentOneWinsAndTheOtherGets409() throws Exception {
        Clinic clinic = newClinic();
        for (String key : new String[] {"gp", "dental", "paeds", "homeo"}) {
            String first = TemplateSections.optional(key, 0);
            String second = TemplateSections.optional(key, 1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String section : new String[] {first, second}) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call(HttpMethod.PUT, overrideUrl(key, section), clinic.owner(), overrideBody(false, false, 0))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            pool.shutdown();
            assertThat(statuses).as(key + " statuses").containsExactlyInAnyOrder(200, 409);
            assertThat(overrideRows(clinic, key)).as(key + ": only the winner's change is stored").isEqualTo(1);
            assertThat(revisionRow(clinic, key)).isEqualTo(1);
        }
        assertThat(auditRows(clinic, "EMR_TEMPLATE_OVERRIDE_CHANGED")).as("one audit entry per applied change").hasSize(4);
    }

    /** Two sections of each template that may be hidden (the first two that are not fixed on). */
    private static final class TemplateSections {
        static String optional(String template, int index) {
            String[][] table = EmrTemplatesReadIT.TABLE_FOR(template);
            int seen = 0;
            for (String[] row : table) {
                boolean fixed = List.of(EmrTemplatesReadIT.NOT_HIDEABLE).contains(template + "/" + row[0]);
                if (!fixed) {
                    if (seen == index) {
                        return row[0];
                    }
                    seen++;
                }
            }
            throw new AssertionError("no optional section " + index + " in " + template);
        }
    }

    // ---------------- audit ----------------

    @Test
    void eachAppliedChangeWritesOneAuditEntryWithWhoAndTheBeforeAndAfterOfShowAndRequired() throws Exception {
        Clinic clinic = newClinic();
        put(overrideUrl("gp", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 200);
        put(overrideUrl("gp", "vitals"), clinic.owner(), overrideBody(true, false, 1), 200);
        List<List<String>> audit = auditRows(clinic, "EMR_TEMPLATE_OVERRIDE_CHANGED");
        assertThat(audit).hasSize(2);
        JsonNode firstBefore = objectMapper.readTree(audit.get(0).get(1));
        JsonNode firstAfter = objectMapper.readTree(audit.get(0).get(2));
        assertThat(audit.get(0).get(0)).isEqualTo("EMR_TEMPLATE_OVERRIDE");
        assertThat(firstBefore.get("templateKey").asText()).isEqualTo("gp");
        assertThat(firstBefore.get("sectionKey").asText()).isEqualTo("local_exam");
        assertThat(firstBefore.get("shown").asBoolean()).isTrue();
        assertThat(firstBefore.get("required").asBoolean()).isFalse();
        assertThat(firstAfter.get("shown").asBoolean()).isFalse();
        assertThat(firstAfter.get("required").asBoolean()).isFalse();
        JsonNode secondBefore = objectMapper.readTree(audit.get(1).get(1));
        JsonNode secondAfter = objectMapper.readTree(audit.get(1).get(2));
        assertThat(secondBefore.get("required").asBoolean()).isTrue();
        assertThat(secondAfter.get("required").asBoolean()).isFalse();
        assertThat(audit.get(0).get(3)).isEqualTo(clinic.ownerUserId().toString());
        // setup values only: nothing but the four keys
        assertThat(firstBefore.size()).isEqualTo(4);
        assertThat(firstAfter.size()).isEqualTo(4);
        // refused and unchanged requests wrote nothing more
        problemOf(HttpMethod.PUT, overrideUrl("gp", "prescription"), clinic.owner(), overrideBody(false, false, 2), 400, "section-not-hideable");
        problemOf(HttpMethod.PUT, overrideUrl("gp", "services"), clinic.owner(), overrideBody(false, false, 0), 409, "emr-setup-changed");
        put(overrideUrl("gp", "services"), clinic.owner(), overrideBody(true, false, 2), 200);
        assertThat(auditRows(clinic, "EMR_TEMPLATE_OVERRIDE_CHANGED")).hasSize(2);
    }

    // ---------------- request bodies ----------------

    @Test
    void theBodiesAreClosedAnUnknownFieldIsARefusalWhetherItIsALabelAPartAClinicIdOrAnyOtherName() throws Exception {
        Clinic clinic = newClinic();
        Clinic other = newClinic();
        for (String extra : new String[] {"\"label\":\"Renamed\"", "\"part\":\"TREATMENT\"", "\"icon\":\"x\"",
                "\"tenantId\":\"" + other.tenantId() + "\"", "\"clinicId\":\"" + other.tenantId() + "\"",
                "\"zxqvfield\":1", "\"defaultPart\":\"ASSESSMENT\""}) {
            String body = "{\"shown\":false,\"required\":false,\"revision\":0," + extra + "}";
            JsonNode problem = problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(), body, 400, "validation");
            assertThat(problem.get("fieldErrors").size()).isPositive();
            assertThat(problem.toString()).doesNotContain("zxqvfield");
            String resetBody = "{\"revision\":0," + extra + "}";
            problemOf(HttpMethod.POST, resetUrl("gp"), clinic.owner(), resetBody, 400, "validation");
        }
        assertNothingStored(clinic, "gp");
        assertNothingStored(other, "gp");
    }

    @Test
    void aMissingOrWrongTypedFieldIsAValidationProblemNamingTheFieldAndNothingIsStored() throws Exception {
        Clinic clinic = newClinic();
        String url = overrideUrl("gp", "local_exam");
        JsonNode empty = problemOf(HttpMethod.PUT, url, clinic.owner(), "{}", 400, "validation");
        assertThat(texts(empty.get("fieldErrors"), "field")).contains("shown", "required", "revision");
        assertThat(texts(problemOf(HttpMethod.PUT, url, clinic.owner(), "{\"shown\":true,\"required\":false}", 400,
                "validation").get("fieldErrors"), "field")).containsExactly("revision");
        assertThat(texts(problemOf(HttpMethod.PUT, url, clinic.owner(), "{\"required\":false,\"revision\":0}", 400,
                "validation").get("fieldErrors"), "field")).containsExactly("shown");
        assertThat(texts(problemOf(HttpMethod.PUT, url, clinic.owner(), overrideBody(false, false, -1), 400,
                "validation").get("fieldErrors"), "field")).containsExactly("revision");
        assertThat(texts(problemOf(HttpMethod.PUT, url, clinic.owner(), "{\"shown\":\"yes\",\"required\":false,\"revision\":0}",
                400, "validation").get("fieldErrors"), "field")).containsExactly("shown");
        assertThat(texts(problemOf(HttpMethod.PUT, url, clinic.owner(), "{\"shown\":true,\"required\":false,\"revision\":\"abc\"}",
                400, "validation").get("fieldErrors"), "field")).containsExactly("revision");
        problemOf(HttpMethod.POST, resetUrl("gp"), clinic.owner(), "{}", 400, "validation");
        problemOf(HttpMethod.POST, resetUrl("gp"), clinic.owner(), "{\"revision\":-3}", 400, "validation");
        assertNothingStored(clinic, "gp");
    }

    @Test
    void anEmptyOrBrokenBodyIsAMalformedRequestAndNotAServerError() throws Exception {
        Clinic clinic = newClinic();
        for (String body : new String[] {"", "{", "not json", "[1,2]"}) {
            int status = call(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(), body).andReturn()
                    .getResponse().getStatus();
            assertThat(status).as("body '" + body + "'").isEqualTo(400);
        }
        assertNothingStored(clinic, "gp");
    }

    @Test
    void aBodyOverSixtyFourKilobytesIs413() throws Exception {
        Clinic clinic = newClinic();
        String big = "{\"shown\":false,\"required\":false,\"revision\":0,\"pad\":\"" + "x".repeat(70_000) + "\"}";
        problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.owner(), big, 413, "payload-too-large");
        problemOf(HttpMethod.POST, resetUrl("gp"), clinic.owner(), big, 413, "payload-too-large");
        assertNothingStored(clinic, "gp");
    }

    // ---------------- unknown keys ----------------

    @Test
    void anUnknownTemplateOrSectionIs404AndAMalformedKeyIs400AndNothingIsStored() throws Exception {
        Clinic clinic = newClinic();
        for (String key : new String[] {"ayur", "physio", "derma", "gynae", "ent", "oncology", "surgery", "unknown"}) {
            problemOf(HttpMethod.PUT, overrideUrl(key, "local_exam"), clinic.owner(), overrideBody(false, false, 0), 404, "template-not-found");
            problemOf(HttpMethod.POST, resetUrl(key), clinic.owner(), "{\"revision\":0}", 404, "template-not-found");
        }
        problemOf(HttpMethod.PUT, overrideUrl("gp", "nope_section"), clinic.owner(), overrideBody(false, false, 0), 404, "section-not-found");
        // a section of another template is not a section of this one
        problemOf(HttpMethod.PUT, overrideUrl("gp", "tooth_chart"), clinic.owner(), overrideBody(false, false, 0), 404, "section-not-found");
        problemOf(HttpMethod.PUT, overrideUrl("homeo", "local_exam"), clinic.owner(), overrideBody(false, false, 0), 404, "section-not-found");
        for (String bad : new String[] {"Bad-Key", "GP", "g", "1gp"}) {
            problemOf(HttpMethod.PUT, overrideUrl(bad, "local_exam"), clinic.owner(), overrideBody(false, false, 0), 400, "bad-request");
            problemOf(HttpMethod.POST, resetUrl(bad), clinic.owner(), "{\"revision\":0}", 400, "bad-request");
        }
        for (String bad : new String[] {"Bad%20Key", "A", "x", "local-exam", "Local_exam"}) {
            problemOf(HttpMethod.PUT, overrideUrl("gp", bad), clinic.owner(), overrideBody(false, false, 0), 400, "bad-request");
        }
        for (String key : TEMPLATE_KEYS) {
            assertThat(revisionRow(clinic, key)).isEqualTo(-1);
        }
        assertThat(count(clinic, "select count(*) from opd.emr_template_override")).isZero();
        assertThat(emrAudits(clinic)).isZero();
    }

    // ---------------- who may change ----------------

    @Test
    void everyRoleButTheOwnerIsRefusedWith403OnOverrideAndResetAndNothingChanges() throws Exception {
        Clinic clinic = newClinic();
        List<Staff> people = new ArrayList<>();
        people.add(addDoctor(clinic, "General practice"));
        people.add(addStaff(clinic, "VISITING_DOCTOR", "Dr Visiting", "Dental", "BDS", "REG-" + shortId()));
        for (String role : NOT_DOCTOR_ROLES) {
            people.add(addNonDoctor(clinic, role));
        }
        for (Staff who : people) {
            problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), who.token(clinic), overrideBody(false, false, 0), 403, "forbidden");
            problemOf(HttpMethod.POST, resetUrl("gp"), who.token(clinic), "{\"revision\":0}", 403, "forbidden");
            // a token that says OWNER for a person who is not one is refused too: the membership is read again
            problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), who.tokenClaiming(clinic, "OWNER"), overrideBody(false, false, 0), 403, "forbidden");
            problemOf(HttpMethod.POST, resetUrl("gp"), who.tokenClaiming(clinic, "OWNER"), "{\"revision\":0}", 403, "forbidden");
        }
        for (String role : NON_OWNER_ROLES) {
            problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), clinic.as(role), overrideBody(false, false, 0), 403, "forbidden");
        }
        problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), anonymous(), overrideBody(false, false, 0), 401, "unauthorized");
        problemOf(HttpMethod.POST, resetUrl("gp"), anonymous(), "{\"revision\":0}", 401, "unauthorized");
        assertNothingStored(clinic, "gp");
    }

    // ---------------- two clinics ----------------

    @Test
    void anotherClinicsOwnerCannotSeeChangeOrResetThisClinicsOverrides() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        put(overrideUrl("gp", "local_exam"), a.owner(), overrideBody(false, false, 0), 200);
        // B's revision is its own (0): A's revision 1 is stale for B
        problemOf(HttpMethod.PUT, overrideUrl("gp", "local_exam"), b.owner(), overrideBody(false, false, 1), 409, "emr-setup-changed");
        problemOf(HttpMethod.POST, resetUrl("gp"), b.owner(), "{\"revision\":1}", 409, "emr-setup-changed");
        // a reset by B with B's revision resets nothing of A's
        JsonNode reset = post(resetUrl("gp"), b.owner(), "{\"revision\":0}", 200);
        assertThat(reset.get("sectionsOn").asInt()).isEqualTo(11);
        assertThat(section(template(a, "gp"), "local_exam").get("shown").asBoolean()).isFalse();
        // B's own change does not touch A's rows either
        put(overrideUrl("gp", "services"), b.owner(), overrideBody(false, false, 0), 200);
        assertThat(section(template(a, "gp"), "services").get("shown").asBoolean()).isTrue();
        assertThat(section(template(b, "gp"), "local_exam").get("shown").asBoolean()).isTrue();
        assertThat(overrideRows(a, "gp")).isEqualTo(1);
        assertThat(overrideRows(b, "gp")).isEqualTo(1);
        assertThat(globalCount("select count(*) from opd.emr_template_override")).as("no clinic bound sees nothing").isZero();
        assertThat(count(b, "select count(*) from platform.audit_event where action like 'EMR\\_%' and actor_user_id = ?::uuid",
                a.ownerUserId())).isZero();
    }
}
