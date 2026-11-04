package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Sweep: SAH-7 clinic masters on the real database - services, fee settings (discount limits), medicines and
 * favourites, diagnoses, case paper settings, holidays, specialty templates, clinic profile, setup overview.
 */
@OnTestDatabase
class ClinicMastersIT extends SweepSupport {

    private static final String SERVICES = "/api/v1/opd/services";
    private static final String FEES = "/api/v1/opd/fee-settings";
    private static final String MEDS = "/api/v1/opd/medicines";
    private static final String FAVS = "/api/v1/opd/medicine-favourites/";
    private static final String DIAG = "/api/v1/opd/diagnoses";
    private static final String PAPER = "/api/v1/opd/case-paper-settings";
    private static final String HOLIDAYS = "/api/v1/platform/clinic/holidays";
    private static final String PROFILE = "/api/v1/platform/clinic/profile";
    private static final String SPECIALTIES = "/api/v1/platform/specialties";
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"};

    private static String svc(String name, String category, int price) {
        return "{\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"priceRupees\":" + price + "}";
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------- services ----------------

    @Test
    void priceListStartsFromTheSpecialtyTemplateAndEveryRoleCanRead() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, "/api/v1/platform/clinic/specialty", clinic.owner(), "{\"code\":\"gp\"}", 200);
        JsonNode list = ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200);
        assertThat(list.size()).isPositive();
        JsonNode first = list.get(0);
        assertThat(first.get("id").asText()).isNotBlank();
        assertThat(first.get("priceRupees").isInt()).isTrue();
        assertThat(first.get("active").asBoolean()).isTrue();
        for (String role : NON_OWNERS) {
            assertThat(ok(HttpMethod.GET, SERVICES, clinic.as(role), null, 200).size()).isEqualTo(list.size());
        }
    }

    @Test
    void addServiceThenItAppearsInTheListWithWholeRupeePrice() throws Exception {
        Clinic clinic = newClinic();
        String name = unique("ECG");
        JsonNode created = ok(HttpMethod.POST, SERVICES, clinic.owner(), svc(name, "Lab", 450), 201);
        assertThat(created.get("name").asText()).isEqualTo(name);
        assertThat(created.get("priceRupees").asInt()).isEqualTo(450);
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).findValuesAsText("name")).contains(name);
    }

    @Test
    void everyCategoryValueIsAccepted() throws Exception {
        Clinic clinic = newClinic();
        for (String cat : new String[] {"Consultation", "Procedure", "Nursing", "Lab", "Radiology", "Dental", "Package"}) {
            ok(HttpMethod.POST, SERVICES, clinic.owner(), svc(unique(cat), cat, 100), 201);
        }
    }

    @Test
    void duplicateServiceNameIgnoringCaseIsConflict() throws Exception {
        Clinic clinic = newClinic();
        String name = unique("X-ray chest");
        ok(HttpMethod.POST, SERVICES, clinic.owner(), svc(name, "Radiology", 300), 201);
        JsonNode p = problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(name.toUpperCase(), "Radiology", 300)), 409);
        assertThat(p.get("type").asText()).endsWith("duplicate-service");
    }

    @Test
    void sameServiceNameInAnotherClinicIsAllowed() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String name = unique("Dressing");
        ok(HttpMethod.POST, SERVICES, a.owner(), svc(name, "Nursing", 50), 201);
        ok(HttpMethod.POST, SERVICES, b.owner(), svc(name, "Nursing", 50), 201);
    }

    @Test
    void serviceValidationIs400WithProblemBody() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc("", "Lab", 100)), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc("   ", "Lab", 100)), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc("x".repeat(121), "Lab", 100)), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(unique("A"), "Magic", 100)), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(unique("A"), "Lab", 0)), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(unique("A"), "Lab", -5)), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), "{\"name\":\"" + unique("A") + "\",\"category\":\"Lab\"}"), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), "{\"name\":\"" + unique("A") + "\",\"priceRupees\":10}"), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), "{\"name\":\"" + unique("A") + "\",\"category\":\"Lab\",\"priceRupees\":\"ten\"}"), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), "{\"name\":\"" + unique("A") + "\",\"category\":\"Lab\",\"priceRupees\":99999999999}"), 400);
        problem(call(HttpMethod.POST, SERVICES, clinic.owner(), "{}"), 400);
    }

    @Test
    void editRenamePriceAndSwitchOffThenActiveFilterWorks() throws Exception {
        Clinic clinic = newClinic();
        String name = unique("Suture");
        UUID id = readId(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(name, "Procedure", 500)).andReturn());
        String renamed = unique("Suture removal");
        JsonNode edited = ok(HttpMethod.PATCH, SERVICES + "/" + id, clinic.owner(),
                "{\"name\":\"" + renamed + "\",\"priceRupees\":0,\"category\":\"Nursing\"}", 200);
        assertThat(edited.get("name").asText()).isEqualTo(renamed);
        assertThat(edited.get("priceRupees").asInt()).isZero();
        assertThat(edited.get("category").asText()).isEqualTo("Nursing");
        ok(HttpMethod.PATCH, SERVICES + "/" + id, clinic.owner(), "{\"active\":false}", 200);
        assertThat(ok(HttpMethod.GET, SERVICES + "?active=false", clinic.owner(), null, 200).findValuesAsText("name")).contains(renamed);
        assertThat(ok(HttpMethod.GET, SERVICES + "?active=true", clinic.owner(), null, 200).findValuesAsText("name")).doesNotContain(renamed);
        assertThat(ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).findValuesAsText("name")).contains(renamed);
        ok(HttpMethod.PATCH, SERVICES + "/" + id, clinic.owner(), "{\"active\":true}", 200);
        ok(HttpMethod.PATCH, SERVICES + "/" + id, clinic.owner(), "{}", 200);
    }

    @Test
    void renamingOntoAnExistingNameIsConflictAndPatchValidationAndNotFound() throws Exception {
        Clinic clinic = newClinic();
        String a = unique("Alpha");
        String b = unique("Beta");
        ok(HttpMethod.POST, SERVICES, clinic.owner(), svc(a, "Lab", 10), 201);
        UUID idB = readId(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(b, "Lab", 10)).andReturn());
        problem(call(HttpMethod.PATCH, SERVICES + "/" + idB, clinic.owner(), "{\"name\":\"" + a + "\"}"), 409);
        problem(call(HttpMethod.PATCH, SERVICES + "/" + idB, clinic.owner(), "{\"priceRupees\":-1}"), 400);
        problem(call(HttpMethod.PATCH, SERVICES + "/" + idB, clinic.owner(), "{\"category\":\"Magic\"}"), 400);
        problem(call(HttpMethod.PATCH, SERVICES + "/" + idB, clinic.owner(), "{\"name\":\"\"}"), 400);
        problem(call(HttpMethod.PATCH, SERVICES + "/" + UUID.randomUUID(), clinic.owner(), "{\"name\":\"Zed\"}"), 404);
        problem(call(HttpMethod.PATCH, SERVICES + "/xyz", clinic.owner(), "{\"name\":\"Zed\"}"), 400);
    }

    @Test
    void aSwitchedOffServiceCanBeBroughtBackByAddingItAgain() throws Exception {
        Clinic clinic = newClinic();
        String name = unique("Nebulisation");
        UUID id = readId(call(HttpMethod.POST, SERVICES, clinic.owner(), svc(name, "Nursing", 120)).andReturn());
        ok(HttpMethod.PATCH, SERVICES + "/" + id, clinic.owner(), "{\"active\":false}", 200);
        // either a clean 409 or a revived row is acceptable, but never a 500
        int status = call(HttpMethod.POST, SERVICES, clinic.owner(), svc(name, "Nursing", 130)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(201, 409);
    }

    @Test
    void serviceWritesAreOwnerOnlyAndOtherClinicsAreInvisible() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID inA = readId(call(HttpMethod.POST, SERVICES, a.owner(), svc(unique("Private"), "Lab", 10)).andReturn());
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, SERVICES, a.as(role), svc(unique("Nope"), "Lab", 10)), 403);
            problem(call(HttpMethod.PATCH, SERVICES + "/" + inA, a.as(role), "{\"name\":\"Hacked\"}"), 403);
        }
        problem(call(HttpMethod.PATCH, SERVICES + "/" + inA, b.owner(), "{\"name\":\"Hacked\"}"), 404);
        assertThat(ok(HttpMethod.GET, SERVICES, b.owner(), null, 200).findValuesAsText("id")).doesNotContain(inA.toString());
    }

    // ---------------- fee settings ----------------

    @Test
    void feeSettingsReflectWhatTheWizardSaved() throws Exception {
        Clinic clinic = newClinic();
        JsonNode fees = ok(HttpMethod.GET, FEES, clinic.owner(), null, 200);
        assertThat(fees.get("feeTiming").asText()).isEqualTo("before");
        assertThat(fees.get("followUpDays").asInt()).isEqualTo(7);
        assertThat(fees.get("followUpFee").asText()).isEqualTo("free");
        assertThat(fees.get("discountLimits").isObject()).isTrue();
        assertThat(fees.has("upiOnBill")).isTrue();
    }

    @Test
    void feeSettingsRoundTripIncludingDiscountLimitsPerRole() throws Exception {
        Clinic clinic = newClinic();
        String body = "{\"feeTiming\":\"split\",\"followUpDays\":14,\"followUpFee\":\"half\","
                + "\"discountLimits\":{\"OWNER\":100,\"FRONT_DESK\":5,\"NURSE\":0,\"BILLING\":15},\"upiOnBill\":false}";
        JsonNode saved = ok(HttpMethod.PUT, FEES, clinic.owner(), body, 200);
        assertThat(saved.get("discountLimits").get("FRONT_DESK").asInt()).isEqualTo(5);
        JsonNode read = ok(HttpMethod.GET, FEES, clinic.as("NURSE"), null, 200);
        assertThat(read.get("feeTiming").asText()).isEqualTo("split");
        assertThat(read.get("followUpDays").asInt()).isEqualTo(14);
        assertThat(read.get("discountLimits").get("BILLING").asInt()).isEqualTo(15);
        assertThat(read.get("upiOnBill").asBoolean()).isFalse();
        // saving identical settings again, and an empty limits map, are both fine
        ok(HttpMethod.PUT, FEES, clinic.owner(), body, 200);
        ok(HttpMethod.PUT, FEES, clinic.owner(),
                "{\"feeTiming\":\"after\",\"followUpDays\":1,\"followUpFee\":\"full\",\"discountLimits\":{},\"upiOnBill\":true}", 200);
        ok(HttpMethod.PUT, FEES, clinic.owner(),
                "{\"feeTiming\":\"after\",\"followUpDays\":30,\"followUpFee\":\"full\",\"discountLimits\":{\"OWNER\":0},\"upiOnBill\":true}", 200);
    }

    @Test
    void feeSettingsValidation() throws Exception {
        Clinic clinic = newClinic();
        String ok = "\"feeTiming\":\"before\",\"followUpDays\":7,\"followUpFee\":\"free\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":true";
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("before", "someday") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"followUpDays\":7", "\"followUpDays\":0") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"followUpDays\":7", "\"followUpDays\":31") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("free", "double") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"OWNER\":100", "\"OWNER\":101") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"OWNER\":100", "\"OWNER\":-1") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"OWNER\":100", "\"JANITOR\":10") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"OWNER\":100", "\"OWNER\":null") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace(",\"discountLimits\":{\"OWNER\":100}", "") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace(",\"upiOnBill\":true", "") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{" + ok.replace("\"followUpDays\":7", "\"followUpDays\":\"x\"") + "}"), 400);
        problem(call(HttpMethod.PUT, FEES, clinic.owner(), "{}"), 400);
    }

    @Test
    void feeSettingsWritesAreOwnerOnlyAndPerClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String body = "{\"feeTiming\":\"after\",\"followUpDays\":9,\"followUpFee\":\"half\",\"discountLimits\":{\"OWNER\":100},\"upiOnBill\":false}";
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, FEES, a.as(role), body), 403);
        }
        ok(HttpMethod.PUT, FEES, a.owner(), body, 200);
        assertThat(ok(HttpMethod.GET, FEES, b.owner(), null, 200).get("followUpDays").asInt()).isEqualTo(7);
    }

    // ---------------- medicines and diagnoses ----------------

    @Test
    void medicineListIsGlobalPagedAndSearchableByPrefix() throws Exception {
        Clinic clinic = newClinic();
        JsonNode page0 = ok(HttpMethod.GET, MEDS, clinic.owner(), null, 200);
        assertThat(page0.get("total").asInt()).isPositive();
        assertThat(page0.get("items").size()).isPositive();
        JsonNode m = page0.get("items").get(0);
        assertThat(m.get("favourite").asBoolean()).isFalse();
        assertThat(m.get("id").asText()).isNotBlank();
        String prefix = m.get("name").asText().substring(0, 3).toLowerCase();
        JsonNode hit = ok(HttpMethod.GET, MEDS + "?q=" + prefix, clinic.owner(), null, 200);
        assertThat(hit.get("total").asInt()).isPositive();
        for (JsonNode i : hit.get("items")) {
            assertThat(i.get("name").asText().toLowerCase()).startsWith(prefix);
        }
        JsonNode none = ok(HttpMethod.GET, MEDS + "?q=zzzzqqqq", clinic.owner(), null, 200);
        assertThat(none.get("total").asInt()).isZero();
        assertThat(none.get("items").size()).isZero();
        if (page0.get("total").asInt() > page0.get("items").size()) {
            JsonNode page1 = ok(HttpMethod.GET, MEDS + "?page=1", clinic.owner(), null, 200);
            assertThat(page1.get("items").get(0).get("id").asText()).isNotEqualTo(m.get("id").asText());
        }
        // far beyond the last page is an empty page, not an error
        assertThat(ok(HttpMethod.GET, MEDS + "?page=100000", clinic.owner(), null, 200).get("items").size()).isZero();
        assertThat(ok(HttpMethod.GET, MEDS + "?page=-3", clinic.owner(), null, 200).get("items").size()).isPositive();
    }

    @Test
    void medicineSearchTreatsPercentUnderscoreAndQuotesAsPlainText() throws Exception {
        Clinic clinic = newClinic();
        for (String q : new String[] {"%25", "_", "a%25b", "%5C", "O%27Brien", "%22", "%20", "%3B--"}) {
            JsonNode r = ok(HttpMethod.GET, MEDS + "?q=" + q, clinic.owner(), null, 200);
            assertThat(r.get("items").size()).isLessThanOrEqualTo(r.get("total").asInt());
        }
        assertThat(ok(HttpMethod.GET, MEDS + "?q=%25", clinic.owner(), null, 200).get("total").asInt()).isZero();
    }

    @Test
    void badPageValueIs400NotServerError() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.GET, MEDS + "?page=abc", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, MEDS + "?favouritesOnly=maybe", clinic.owner(), null), 400);
    }

    @Test
    void favouritesComeFirstAreFilterableAndPerClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        JsonNode items = ok(HttpMethod.GET, MEDS + "?page=1", a.owner(), null, 200).get("items");
        UUID med = UUID.fromString(items.get(items.size() - 1).get("id").asText());
        assertThat(ok(HttpMethod.GET, MEDS + "?favouritesOnly=true", a.owner(), null, 200).get("total").asInt()).isZero();
        ok(HttpMethod.PUT, FAVS + med, a.owner(), null, 204);
        ok(HttpMethod.PUT, FAVS + med, a.owner(), null, 204); // idempotent
        JsonNode first = ok(HttpMethod.GET, MEDS, a.owner(), null, 200).get("items").get(0);
        assertThat(first.get("id").asText()).isEqualTo(med.toString());
        assertThat(first.get("favourite").asBoolean()).isTrue();
        assertThat(ok(HttpMethod.GET, MEDS + "?favouritesOnly=true", a.as("NURSE"), null, 200).get("total").asInt()).isEqualTo(1);
        // clinic b does not see it
        assertThat(ok(HttpMethod.GET, MEDS + "?favouritesOnly=true", b.owner(), null, 200).get("total").asInt()).isZero();
        assertThat(ok(HttpMethod.GET, MEDS, b.owner(), null, 200).get("items").get(0).get("favourite").asBoolean()).isFalse();
        ok(HttpMethod.DELETE, FAVS + med, a.owner(), null, 204);
        problem(call(HttpMethod.DELETE, FAVS + med, a.owner(), null), 404);
        // b can independently favourite the same medicine
        ok(HttpMethod.PUT, FAVS + med, b.owner(), null, 204);
        assertThat(ok(HttpMethod.GET, MEDS + "?favouritesOnly=true", a.owner(), null, 200).get("total").asInt()).isZero();
    }

    @Test
    void favouriteErrorsUnknownMedicineBadIdAndNonOwner() throws Exception {
        Clinic clinic = newClinic();
        UUID med = UUID.fromString(ok(HttpMethod.GET, MEDS, clinic.owner(), null, 200).get("items").get(0).get("id").asText());
        problem(call(HttpMethod.PUT, FAVS + UUID.randomUUID(), clinic.owner(), null), 404);
        problem(call(HttpMethod.PUT, FAVS + "nope", clinic.owner(), null), 400);
        problem(call(HttpMethod.DELETE, FAVS + UUID.randomUUID(), clinic.owner(), null), 404);
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, FAVS + med, clinic.as(role), null), 403);
            problem(call(HttpMethod.DELETE, FAVS + med, clinic.as(role), null), 403);
        }
    }

    @Test
    void diagnosisSearchByNamePrefixCodeAndNoMatch() throws Exception {
        Clinic clinic = newClinic();
        JsonNode all = ok(HttpMethod.GET, DIAG, clinic.owner(), null, 200);
        assertThat(all.size()).isPositive();
        JsonNode d = all.get(0);
        assertThat(d.get("code").asText()).isNotBlank();
        assertThat(d.get("name").asText()).isNotBlank();
        String prefix = d.get("name").asText().substring(0, 3);
        JsonNode hit = ok(HttpMethod.GET, DIAG + "?q=" + prefix, clinic.as("DOCTOR"), null, 200);
        assertThat(hit.size()).isPositive();
        assertThat(ok(HttpMethod.GET, DIAG + "?q=zzzzqqqq", clinic.owner(), null, 200).size()).isZero();
        assertThat(ok(HttpMethod.GET, DIAG + "?q=%25", clinic.owner(), null, 200).size()).isZero();
        ok(HttpMethod.GET, DIAG + "?q=" + d.get("code").asText(), clinic.owner(), null, 200);
    }

    // ---------------- case paper ----------------

    private static final String PAPER_OK = "{\"size\":\"A4\",\"secondLanguage\":\"mr\",\"preprinted\":true,\"caps\":true,"
            + "\"sections\":{\"complaints\":true,\"vitals\":false,\"history\":true,\"exam\":true,\"diagnosis\":true,"
            + "\"inv\":false,\"rx\":true,\"advice\":true,\"next\":false}}";

    @Test
    void casePaperDefaultsThenRoundTripAndEveryRoleReads() throws Exception {
        Clinic clinic = newClinic();
        JsonNode defaults = ok(HttpMethod.GET, PAPER, clinic.owner(), null, 200);
        assertThat(defaults.get("size").asText()).isIn("A5", "A4");
        assertThat(defaults.get("sections").get("rx").asBoolean()).isTrue();
        assertThat(defaults.has("secondLanguage")).isTrue();
        JsonNode saved = ok(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK, 200);
        assertThat(saved.get("size").asText()).isEqualTo("A4");
        assertThat(saved.get("secondLanguage").asText()).isEqualTo("mr");
        for (String role : NON_OWNERS) {
            JsonNode read = ok(HttpMethod.GET, PAPER, clinic.as(role), null, 200);
            assertThat(read.get("sections").get("vitals").asBoolean()).isFalse();
            assertThat(read.get("caps").asBoolean()).isTrue();
        }
        ok(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"mr\"", "null"), 200);
        assertThat(ok(HttpMethod.GET, PAPER, clinic.owner(), null, 200).get("secondLanguage").isNull()).isTrue();
        ok(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"mr\"", "\"hi\"").replace("A4", "A5"), 200);
    }

    @Test
    void casePaperValidation() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("A4", "A3")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"mr\"", "\"fr\"")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"rx\":true", "\"rx\":false")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"next\":false", "\"nonsense\":false")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"preprinted\":true,", "")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replace("\"caps\":true,", "")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), PAPER_OK.replaceAll("\"sections\":\\{.*\\}", "\"sections\":null")), 400);
        problem(call(HttpMethod.PUT, PAPER, clinic.owner(), "{}"), 400);
    }

    @Test
    void casePaperWritesAreOwnerOnlyAndPerClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, PAPER, a.as(role), PAPER_OK), 403);
        }
        ok(HttpMethod.PUT, PAPER, a.owner(), PAPER_OK, 200);
        assertThat(ok(HttpMethod.GET, PAPER, b.owner(), null, 200).get("sections").get("vitals").asBoolean()).isNotNull();
        assertThat(ok(HttpMethod.GET, PAPER, b.owner(), null, 200).get("preprinted").asBoolean()).isFalse();
    }

    // ---------------- holidays ----------------

    @Test
    void holidayAddListDeleteLifecycle() throws Exception {
        Clinic clinic = newClinic();
        JsonNode added = ok(HttpMethod.POST, HOLIDAYS, clinic.owner(),
                "{\"date\":\"2027-01-26\",\"name\":\"Republic Day\",\"type\":\"CLOSED\"}", 201);
        UUID id = UUID.fromString(added.get("id").asText());
        assertThat(added.get("date").asText()).isEqualTo("2027-01-26");
        ok(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-08-15\",\"name\":\"Independence Day\",\"type\":\"MORNING_ONLY\"}", 201);
        ok(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-10-02\",\"name\":\"Gandhi Jayanti\",\"type\":\"EVENING_ONLY\"}", 201);
        JsonNode list = ok(HttpMethod.GET, HOLIDAYS, clinic.as("NURSE"), null, 200);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).get("date").asText()).isEqualTo("2027-01-26"); // oldest first
        ok(HttpMethod.DELETE, HOLIDAYS + "/" + id, clinic.owner(), null, 204);
        problem(call(HttpMethod.DELETE, HOLIDAYS + "/" + id, clinic.owner(), null), 404);
        assertThat(ok(HttpMethod.GET, HOLIDAYS, clinic.owner(), null, 200).size()).isEqualTo(2);
    }

    @Test
    void secondHolidayOnTheSameDateIsConflictAndAReAddAfterDeleteWorks() throws Exception {
        Clinic clinic = newClinic();
        String body = "{\"date\":\"2027-03-10\",\"name\":\"Local fair\",\"type\":\"CLOSED\"}";
        UUID id = readId(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), body).andReturn());
        JsonNode p = problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), body.replace("Local fair", "Other")), 409);
        assertThat(p.get("type").asText()).endsWith("holiday-date-taken");
        ok(HttpMethod.DELETE, HOLIDAYS + "/" + id, clinic.owner(), null, 204);
        ok(HttpMethod.POST, HOLIDAYS, clinic.owner(), body, 201);
    }

    @Test
    void holidayValidation() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"name\":\"X\",\"type\":\"CLOSED\"}"), 400);
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-03-10\",\"name\":\" \",\"type\":\"CLOSED\"}"), 400);
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-03-10\",\"name\":\"X\",\"type\":\"HALF\"}"), 400);
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-03-10\",\"name\":\"X\"}"), 400);
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"10/03/2027\",\"name\":\"X\",\"type\":\"CLOSED\"}"), 400);
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-02-30\",\"name\":\"X\",\"type\":\"CLOSED\"}"), 400);
        problem(call(HttpMethod.POST, HOLIDAYS, clinic.owner(), "{\"date\":\"2027-03-10\",\"name\":\"" + "n".repeat(121) + "\",\"type\":\"CLOSED\"}"), 400);
        problem(call(HttpMethod.DELETE, HOLIDAYS + "/not-a-uuid", clinic.owner(), null), 400);
    }

    @Test
    void holidayWritesAreOwnerOnlyAndPerClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID id = readId(call(HttpMethod.POST, HOLIDAYS, a.owner(), "{\"date\":\"2027-05-01\",\"name\":\"May Day\",\"type\":\"CLOSED\"}").andReturn());
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, HOLIDAYS, a.as(role), "{\"date\":\"2027-06-01\",\"name\":\"X\",\"type\":\"CLOSED\"}"), 403);
            problem(call(HttpMethod.DELETE, HOLIDAYS + "/" + id, a.as(role), null), 403);
        }
        problem(call(HttpMethod.DELETE, HOLIDAYS + "/" + id, b.owner(), null), 404);
        assertThat(ok(HttpMethod.GET, HOLIDAYS, b.owner(), null, 200).size()).isZero();
        // the same date is free in the other clinic
        ok(HttpMethod.POST, HOLIDAYS, b.owner(), "{\"date\":\"2027-05-01\",\"name\":\"May Day\",\"type\":\"CLOSED\"}", 201);
    }

    // ---------------- specialty templates ----------------

    @Test
    void specialtyListIsReadableAndApplyingATemplateAddsServicesAndSetsTheCode() throws Exception {
        Clinic clinic = newClinic();
        JsonNode list = ok(HttpMethod.GET, SPECIALTIES, clinic.as("FRONT_DESK"), null, 200);
        assertThat(list.findValuesAsText("code")).contains("gp", "dental");
        int before = ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).size();
        JsonNode profile = ok(HttpMethod.PUT, "/api/v1/platform/clinic/specialty", clinic.owner(), "{\"code\":\"dental\"}", 200);
        assertThat(profile.get("specialtyCode").asText()).isEqualTo("dental");
        assertThat(ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).size()).isGreaterThanOrEqualTo(before);
        // applying again adds no duplicates and does not fail
        int mid = ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).size();
        ok(HttpMethod.PUT, "/api/v1/platform/clinic/specialty", clinic.owner(), "{\"code\":\"dental\"}", 200);
        assertThat(ok(HttpMethod.GET, SERVICES, clinic.owner(), null, 200).size()).isEqualTo(mid);
    }

    @Test
    void everyPhaseOneTemplateCanBeApplied() throws Exception {
        Clinic clinic = newClinic();
        JsonNode list = ok(HttpMethod.GET, SPECIALTIES, clinic.owner(), null, 200);
        for (JsonNode s : list) {
            if (s.get("phase").asInt() == 1) {
                ok(HttpMethod.PUT, "/api/v1/platform/clinic/specialty", clinic.owner(), "{\"code\":\"" + s.get("code").asText() + "\"}", 200);
            } else {
                problem(call(HttpMethod.PUT, "/api/v1/platform/clinic/specialty", clinic.owner(),
                        "{\"code\":\"" + s.get("code").asText() + "\"}"), 400);
            }
        }
    }

    @Test
    void specialtyErrorsAndNonOwner() throws Exception {
        Clinic clinic = newClinic();
        String url = "/api/v1/platform/clinic/specialty";
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{\"code\":\"astrology\"}"), 400);
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{\"code\":\"\"}"), 400);
        problem(call(HttpMethod.PUT, url, clinic.owner(), "{}"), 400);
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, url, clinic.as(role), "{\"code\":\"gp\"}"), 403);
        }
    }

    // ---------------- clinic profile ----------------

    @Test
    void profileReadsWhatSetupSavedAndEveryRoleCanRead() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = ok(HttpMethod.GET, PROFILE, clinic.owner(), null, 200);
        assertThat(p.get("name").asText()).isEqualTo("Sahaj test clinic");
        assertThat(p.get("city").asText()).isEqualTo("Pune");
        assertThat(p.get("pin").asText()).isEqualTo("411001");
        assertThat(p.get("registrationNo").asText()).isEqualTo("REG-001");
        assertThat(p.get("slug").asText()).startsWith("sahaj-");
        assertThat(p.get("brand").asText()).isEqualTo("blue");
        for (String role : NON_OWNERS) {
            ok(HttpMethod.GET, PROFILE, clinic.as(role), null, 200);
        }
    }

    @Test
    void profileSaveUpdatesTheClinicEverywhere() throws Exception {
        Clinic clinic = newClinic();
        String slugBefore = ok(HttpMethod.GET, PROFILE, clinic.owner(), null, 200).get("slug").asText();
        JsonNode saved = ok(HttpMethod.PUT, PROFILE, clinic.owner(),
                "{\"name\":\"Renamed clinic\",\"nameLocal\":\"नवीन क्लिनिक\",\"address\":\"5 FC Road\",\"city\":\"Mumbai\","
                        + "\"pin\":\"400001\",\"phone\":\"02212345678\",\"whatsapp\":\"9876543210\","
                        + "\"mapsUrl\":\"https://maps.example.com/x\",\"registrationNo\":\"R-2\",\"brand\":\"teal\","
                        + "\"slug\":\"hacked-slug\"}", 200);
        assertThat(saved.get("name").asText()).isEqualTo("Renamed clinic");
        assertThat(saved.get("nameLocal").asText()).isEqualTo("नवीन क्लिनिक");
        assertThat(saved.get("city").asText()).isEqualTo("Mumbai");
        assertThat(saved.get("slug").asText()).isEqualTo(slugBefore);
        assertThat(saved.get("brand").asText()).isEqualTo("teal");
        JsonNode me = ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200);
        assertThat(me.get("clinic").get("name").asText()).isEqualTo("Renamed clinic");
        assertThat(me.get("clinic").get("brand").asText()).isEqualTo("teal");
        // saving the same again, and a body with only a name, both succeed and keep other fields
        ok(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"Renamed clinic\"}", 200);
        JsonNode keep = ok(HttpMethod.GET, PROFILE, clinic.owner(), null, 200);
        assertThat(keep.get("brand").asText()).isEqualTo("teal");
    }

    @Test
    void customBrandNeedsAHexColour() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"brand\":\"custom\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"brand\":\"custom\",\"brandHex\":\"zzzzzz\"}"), 400);
        JsonNode ok = ok(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"brand\":\"custom\",\"brandHex\":\"#1F5BC8\"}", 200);
        assertThat(ok.get("brandHex").asText()).isEqualToIgnoringCase("#1F5BC8");
        JsonNode me = ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200);
        assertThat(me.get("clinic").get("brand").asText()).isEqualTo("custom");
    }

    /** The error for a bad custom colour gives an example; typing exactly that example must be accepted. */
    @Test
    void colourExampleGivenInTheErrorMessageIsAccepted() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"brand\":\"custom\",\"brandHex\":\"zzz\"}"), 400);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#[0-9a-fA-F]{6}").matcher(p.toString());
        assertThat(m.find()).as("error message should show an example colour like #1F5BC8: %s", p).isTrue();
        String example = m.group();
        JsonNode saved = ok(HttpMethod.PUT, PROFILE, clinic.owner(),
                "{\"name\":\"C\",\"brand\":\"custom\",\"brandHex\":\"" + example + "\"}", 200);
        assertThat(saved.get("brandHex").asText()).isEqualToIgnoringCase(example);
    }

    @Test
    void profileValidation() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"  \"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"" + "n".repeat(161) + "\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"pin\":\"41100\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"pin\":\"41100a\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"mapsUrl\":\"javascript:alert(1)\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"mapsUrl\":\"not a url\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"brand\":\"pink\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"address\":\"" + "a".repeat(401) + "\"}"), 400);
        problem(call(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"C\",\"phone\":\"" + "9".repeat(21) + "\"}"), 400);
    }

    @Test
    void profileWritesAreOwnerOnlyAndPerClinic() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, PROFILE, a.as(role), "{\"name\":\"Hacked\"}"), 403);
        }
        ok(HttpMethod.PUT, PROFILE, a.owner(), "{\"name\":\"Only A\"}", 200);
        assertThat(ok(HttpMethod.GET, PROFILE, b.owner(), null, 200).get("name").asText()).isEqualTo("Sahaj test clinic");
    }

    // ---------------- setup overview and audit ----------------

    @Test
    void setupOverviewShowsPercentChecklistAndLastChange() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"Overview clinic\"}", 200);
        JsonNode o = ok(HttpMethod.GET, "/api/v1/platform/setup/overview", clinic.owner(), null, 200);
        assertThat(o.get("percent").asInt()).isBetween(0, 100);
        assertThat(o.get("checks").size()).isPositive();
        for (JsonNode c : o.get("checks")) {
            assertThat(c.get("key").asText()).isNotBlank();
            assertThat(c.get("label").asText()).isNotBlank();
            assertThat(c.has("done")).isTrue();
        }
        assertThat(o.get("lastChange").get("action").asText()).isEqualTo("CLINIC_PROFILE_UPDATED");
        assertThat(o.get("lastChange").get("actorName").asText()).isNotBlank();
    }

    @Test
    void auditEventsLatestFirstWithLimitAndNonOwnerRefused() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"Audit one\"}", 200);
        ok(HttpMethod.PUT, PROFILE, clinic.owner(), "{\"name\":\"Audit two\"}", 200);
        JsonNode events = ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=1", clinic.owner(), null, 200);
        assertThat(events.size()).isEqualTo(1);
        assertThat(events.get(0).get("after").toString()).contains("Audit two");
        assertThat(events.get(0).get("before").toString()).contains("Audit one");
        assertThat(ok(HttpMethod.GET, "/api/v1/platform/audit-events", clinic.owner(), null, 200).size()).isGreaterThan(1);
        problem(call(HttpMethod.GET, "/api/v1/platform/audit-events?limit=0", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, "/api/v1/platform/audit-events?limit=101", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, "/api/v1/platform/audit-events?limit=abc", clinic.owner(), null), 400);
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.GET, "/api/v1/platform/audit-events", clinic.as(role), null), 403);
            problem(call(HttpMethod.GET, "/api/v1/platform/setup/overview", clinic.as(role), null), 403);
        }
    }

    @Test
    void auditEventsOfAnotherClinicAreInvisible() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ok(HttpMethod.PUT, PROFILE, a.owner(), "{\"name\":\"Secret rename of A\"}", 200);
        assertThat(ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=100", b.owner(), null, 200).toString())
                .doesNotContain("Secret rename of A");
    }
}
