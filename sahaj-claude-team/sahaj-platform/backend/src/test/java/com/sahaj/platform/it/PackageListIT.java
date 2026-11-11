package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-29 AC 1 and AC 6: {@code GET /api/v1/ipd/packages} on the real {@code sahaj_test} database. Who may read, the
 * four module states, no session, the shape (four fields only), active rows only, the order (created_at, then id),
 * another clinic's packages never listed, the empty list, and no write verb. Packages are set as the table owner
 * ({@link PackageSupport#insertPackage}). Test plan: docs/test-plans/SAH-29.md.
 */
@OnTestDatabase
class PackageListIT extends PackageSupport {

    // ---------------- AC 1: the list ----------------

    @Test
    void theListHoldsTheClinicsActivePackagesInTheShapeOfTheContractAndNothingElse() throws Exception {
        Clinic clinic = hospital();
        insertPackages(clinic,
                new Object[] {"Appendicectomy", 45_000L, 3, true},
                new Object[] {"Cataract surgery", 25_000L, 0, true},
                new Object[] {"Normal delivery", 35_000L, 2, true});

        JsonNode list = packages(clinic);
        assertThat(list.fieldNames()).toIterable().containsExactly("items");
        assertThat(packageNames(list)).containsExactly("Appendicectomy", "Cataract surgery", "Normal delivery");
        JsonNode first = list.get("items").get(0);
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("no tenant id, active flag, timestamps or author on the wire").containsExactlyInAnyOrderElementsOf(PACKAGE_FIELDS);
        assertThat(first.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.get("pricePaise").asLong()).as("integer paise").isEqualTo(4_500_000L);
        assertThat(first.get("includedDays").asInt()).isEqualTo(3);
        assertThat(list.get("items").get(1).get("pricePaise").asLong()).isEqualTo(2_500_000L);
        assertThat(list.get("items").get(1).get("includedDays").asInt()).as("day-care").isZero();
        assertThat(list.get("items").get(2).get("pricePaise").asLong()).isEqualTo(3_500_000L);
    }

    @Test
    void theBoundaryValuesOfTheTableAreListedAsStored() throws Exception {
        Clinic clinic = hospital();
        String longest = "K".repeat(80);
        insertPackages(clinic,
                new Object[] {"Free camp", 0L, 0, true},
                new Object[] {longest, 1_000_000L, 60, true});

        JsonNode items = packages(clinic).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("pricePaise").asLong()).isZero();
        assertThat(items.get(1).get("name").asText()).hasSize(80).isEqualTo(longest);
        assertThat(items.get(1).get("pricePaise").asLong()).as("the largest price the table allows").isEqualTo(100_000_000L);
        assertThat(items.get(1).get("includedDays").asInt()).isEqualTo(60);
    }

    @Test
    void aClinicWithNoPackageGetsAnEmptyItemsArrayNotNullOrMissing() throws Exception {
        Clinic clinic = hospital();
        JsonNode list = packages(clinic);
        assertThat(list.get("items").isArray()).isTrue();
        assertThat(list.get("items")).isEmpty();
        assertThat(rowCount(clinic)).isZero();
    }

    @Test
    void onlyActivePackagesAreListedAndASwitchedOffOneComesBackWhenSwitchedOn() throws Exception {
        Clinic clinic = hospital();
        List<UUID> ids = insertPackages(clinic,
                new Object[] {"Hernia repair", 30_000L, 2, true},
                new Object[] {"Old package", 10_000L, 1, false},
                new Object[] {"Knee arthroscopy", 60_000L, 3, true});

        assertThat(packageNames(packages(clinic))).containsExactly("Hernia repair", "Knee arthroscopy");
        assertThat(rowCount(clinic)).as("the inactive row still exists, rows are never deleted").isEqualTo(3);

        editPackage(clinic, ids.get(1), 10_000, true);
        assertThat(packageNames(packages(clinic))).containsExactly("Hernia repair", "Old package", "Knee arthroscopy");
        editPackage(clinic, ids.get(0), 30_000, false);
        assertThat(packageNames(packages(clinic))).containsExactly("Old package", "Knee arthroscopy");
    }

    @Test
    void thePackagesComeInTheOrderTheyWereCreatedNotByNameOrPrice() throws Exception {
        Clinic clinic = hospital();
        // created in this order; alphabetical and by-price order would differ
        insertPackages(clinic,
                new Object[] {"Zonal block", 90_000L, 5, true},
                new Object[] {"Appendicectomy", 10_000L, 3, true},
                new Object[] {"Mastectomy", 50_000L, 4, true});
        assertThat(packageNames(packages(clinic))).containsExactly("Zonal block", "Appendicectomy", "Mastectomy");

        // a package created later in another transaction goes last, whatever its name
        insertPackage(clinic, "Angioplasty", 150_000, 2);
        assertThat(packageNames(packages(clinic))).containsExactly("Zonal block", "Appendicectomy", "Mastectomy",
                "Angioplasty");
    }

    @Test
    void packagesCreatedAtTheSameInstantAreOrderedById() throws Exception {
        Clinic clinic = hospital();
        Instant at = Instant.now().minus(1, ChronoUnit.DAYS);
        // ids are global (rows are never deleted), so a random head keeps each run unique; only the tail orders them
        String head = UUID.randomUUID().toString().substring(0, 24);
        UUID low = UUID.fromString(head + "000000000001");
        UUID mid = UUID.fromString(head + "000000000002");
        UUID high = UUID.fromString(head + "000000000003");
        // inserted high, low, mid: the list must show low, mid, high
        insertPackageAt(clinic, high, "Third by id", at);
        insertPackageAt(clinic, low, "First by id", at);
        insertPackageAt(clinic, mid, "Second by id", at);
        // an older row always comes before, whatever its id
        insertPackageAt(clinic, UUID.fromString(head + "ffffffffffff"), "Oldest", at.minusSeconds(60));

        JsonNode items = packages(clinic).get("items");
        assertThat(packageNames(packages(clinic))).containsExactly("Oldest", "First by id", "Second by id", "Third by id");
        assertThat(items.get(1).get("id").asText()).isEqualTo(low.toString());
    }

    @Test
    void everyReaderRoleSeesTheSameListWithoutAnAuditOrRecordAccessRow() throws Exception {
        Clinic clinic = hospital();
        insertPackage(clinic, "Appendicectomy", 45_000, 3);
        long audits = count(clinic, "select count(*) from platform.audit_event");
        long access = count(clinic, "select count(*) from patient.record_access");
        JsonNode owner = packages(clinic);

        for (String role : READERS) {
            JsonNode seen = ok(HttpMethod.GET, PACKAGES, clinic.as(role), null, 200);
            assertThat(seen).as(role).isEqualTo(owner);
            assertThat(packageNames(seen)).as(role).containsExactly("Appendicectomy");
        }
        assertThat(count(clinic, "select count(*) from platform.audit_event")).as("a read writes no audit row").isEqualTo(audits);
        assertThat(count(clinic, "select count(*) from patient.record_access")).as("a package is not patient data").isEqualTo(access);
    }

    // ---------------- AC 6: roles, 401, module states ----------------

    @Test
    void everyOtherRoleGets403ForbiddenWithAProblemBody() throws Exception {
        Clinic clinic = hospital();
        insertPackage(clinic, "Appendicectomy", 45_000, 3);
        for (String role : NON_READERS) {
            JsonNode p = problem(call(HttpMethod.GET, PACKAGES, clinic.as(role), null), 403);
            assertType(p, "forbidden");
            assertThat(p.toString()).as(role + " learns nothing of the list").doesNotContain("Appendicectomy");
        }
    }

    @Test
    void noSessionGets401() throws Exception {
        mvc.perform(get(PACKAGES)).andExpect(status().isUnauthorized());
        mvc.perform(get(PACKAGES).header("Authorization", "Bearer not-a-real-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void moduleOffAnswers403ModuleOffToEveryReaderAndForbiddenToTheOthers() throws Exception {
        Clinic clinic = newClinic();
        for (String role : READERS) {
            assertType(problem(call(HttpMethod.GET, PACKAGES, clinic.as(role), null), 403), "module-off");
        }
        for (String role : NON_READERS) {
            assertType(problem(call(HttpMethod.GET, PACKAGES, clinic.as(role), null), 403), "forbidden");
        }
    }

    @Test
    void trialOnReadOnlyAndAnExpiredTrialAllowTheRead() throws Exception {
        Clinic clinic = hospital();
        insertPackage(clinic, "Appendicectomy", 45_000, 3);
        assertThat(packageNames(packages(clinic))).as("trial").containsExactly("Appendicectomy");

        setIpdState(clinic, "on");
        for (String role : READERS) {
            assertThat(packageNames(ok(HttpMethod.GET, PACKAGES, clinic.as(role), null, 200))).as("on " + role)
                    .containsExactly("Appendicectomy");
        }
        setIpdState(clinic, "readonly");
        for (String role : READERS) {
            assertThat(packageNames(ok(HttpMethod.GET, PACKAGES, clinic.as(role), null, 200))).as("read-only " + role)
                    .containsExactly("Appendicectomy");
        }

        Clinic expired = hospital();
        insertPackage(expired, "Hernia repair", 30_000, 2);
        expireTrial(expired);
        assertThat(packageNames(packages(expired))).as("trial over").containsExactly("Hernia repair");

        setIpdState(clinic, "off");
        assertType(problem(call(HttpMethod.GET, PACKAGES, clinic.owner(), null), 403), "module-off");
    }

    @Test
    void switchingIpdOffHidesTheListAndSwitchingItOnShowsItAgain() throws Exception {
        Clinic clinic = hospital();
        insertPackage(clinic, "Appendicectomy", 45_000, 3);
        setIpdState(clinic, "off");
        assertType(problem(call(HttpMethod.GET, PACKAGES, clinic.owner(), null), 403), "module-off");
        setIpdState(clinic, "on");
        assertThat(packageNames(packages(clinic))).containsExactly("Appendicectomy");
    }

    // ---------------- AC 6: tenants ----------------

    @Test
    void anotherClinicsPackagesAreNeverListedAndTheClinicComesFromTheSessionNotTheRequest() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        insertPackage(a, "Clinic A package", 45_000, 3);
        insertPackage(b, "Clinic B package", 20_000, 1);
        assertThat(packageNames(packages(a))).containsExactly("Clinic A package");
        assertThat(packageNames(packages(b))).containsExactly("Clinic B package");

        // same name in two clinics is fine (the unique name is per clinic)
        insertPackage(a, "Shared name", 10_000, 1);
        insertPackage(b, "Shared name", 12_000, 2);
        assertThat(packageNames(packages(a))).containsExactly("Clinic A package", "Shared name");
        assertThat(packages(b).get("items").get(1).get("pricePaise").asLong()).isEqualTo(1_200_000L);

        // a tenant id in the query or a header changes nothing
        JsonNode spoofed = ok(HttpMethod.GET, PACKAGES + "?tenantId=" + a.tenantId(), b.owner(), null, 200);
        assertThat(packageNames(spoofed)).containsExactly("Clinic B package", "Shared name");
        JsonNode viaHeader = problem2(b, a.tenantId());
        assertThat(packageNames(viaHeader)).containsExactly("Clinic B package", "Shared name");

        // a clinic with none sees none while others have some
        Clinic c = hospital();
        assertThat(packages(c).get("items")).isEmpty();
    }

    @Test
    void thePackageRowsOfAnotherClinicAreInvisibleToSqlAsTheAppToo() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        insertPackage(a, "Clinic A package", 45_000, 3);
        assertThat(count(a, "select count(*) from ipd.package")).isOne();
        assertThat(count(b, "select count(*) from ipd.package")).isZero();
        assertThat(count(b, "select count(*) from ipd.package where tenant_id = ?", a.tenantId())).isZero();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.package"));
        assertThat(unbound).as("no tenant bound").isZero();
    }

    // ---------------- no write path ----------------

    @Test
    void thereIsNoEndpointToCreateChangeOrRemoveAPackageAndNothingIsWrittenByTryingToo() throws Exception {
        Clinic clinic = hospital();
        UUID id = insertPackage(clinic, "Appendicectomy", 45_000, 3);
        String admission = admit(clinic, "gen", "Self", 1_000_000).get("id").asText();
        long audits = count(clinic, "select count(*) from platform.audit_event");
        String body = "{\"name\":\"Hacked\",\"pricePaise\":100,\"includedDays\":1,\"active\":false}";

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "BILLING"}) {
            for (String url : new String[] {PACKAGES, PACKAGES + "/" + id}) {
                for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                        HttpMethod.DELETE}) {
                    int status = call(method, url, clinic.as(role), body).andReturn().getResponse().getStatus();
                    assertThat(status).as(role + " " + method + " " + url).isIn(403, 404, 405);
                }
            }
        }
        // the attach path does not exist either
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT}) {
            int status = call(method, ADMISSIONS + "/" + admission + "/package", clinic.owner(),
                    "{\"packageId\":\"" + id + "\"}").andReturn().getResponse().getStatus();
            assertThat(status).as("attach " + method).isIn(404, 405);
        }

        assertThat(packageNames(packages(clinic))).containsExactly("Appendicectomy");
        assertThat(packages(clinic).get("items").get(0).get("pricePaise").asLong()).isEqualTo(4_500_000L);
        assertThat(storedCopy(clinic, admission)).isEqualTo("none");
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like '%PACKAGE%'")).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event")).as("refused calls write no audit row")
                .isEqualTo(audits);
    }

    // ---------------- helpers ----------------

    private long rowCount(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from ipd.package");
    }

    /** The list as clinic {@code b}'s owner with a spoofed tenant header sent along. */
    private JsonNode problem2(Clinic b, UUID otherTenant) throws Exception {
        var result = mvc.perform(get(PACKAGES).with(b.owner()).header("X-Tenant-Id", otherTenant.toString())
                .header("X-Sahaj-Tenant", otherTenant.toString())).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
